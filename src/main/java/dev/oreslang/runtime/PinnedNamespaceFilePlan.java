package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.PinnedNamespaceInventory;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.parser.Parser;
import dev.oreslang.config.OresProjectConfig;
import org.tomlj.Toml;
import org.tomlj.TomlTable;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.IOException;
import java.util.*;

/** Explicit legacy v2 project profile. Host-controlled static build inputs,
 * not guest filesystem authority; no ambient search paths, registry fetches
 * or recursive imports. Like the existing source loader, the build root
 * must not be concurrently controlled by an adversarial local writer. */
final class PinnedNamespaceFilePlan {
    private PinnedNamespaceFilePlan(){}
    private static final String MANIFEST=".ores.pkg.toml";
    private static IllegalArgumentException deny(String s){return new IllegalArgumentException("E_NAMESPACE_FILE_PLAN: "+s);}
    private static String id(Path p){return p.toAbsolutePath().normalize().toString().replace('\\','/');}
    private static boolean exists(Path p)throws IOException{
        try{Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);return true;}
        catch(NoSuchFileException e){return false;}
    }
    private static String read(Path path)throws IOException{
        Path p=path.toAbsolutePath().normalize();
        for(Path c=p;c!=null;c=c.getParent())if(Files.isSymbolicLink(c))throw deny("symlinked snapshot path");
        var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.size()>65536)throw deny("regular bounded snapshot required");
        var result=BoundedSourceReader.readWithByteCount(p);
        if(result.byteCount()>65536)throw deny("snapshot byte budget");
        return result.text();
    }
    private static TomlTable table(TomlTable t,String key){
        var v=t.getTable(List.of(key));if(v==null)throw deny("missing "+key);return v;
    }
    private static TomlTable manifest(String text){
        var t=Toml.parse(text);
        if(t.hasErrors()||!"2".equals(t.getString("schema_version"))||!".".equals(t.getString("project.root")))throw deny("v2 manifest required");
        return t;
    }
    private static String name(String value){
        if(value==null||!value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")||value.contains("..")||value.endsWith("."))throw deny("package identity");
        return value;
    }
    private static String sourcePath(String p){
        if(p==null||p.length()>512||!p.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.(?:atto|ores)"))throw deny("inventory path");
        return p;
    }
    private static String sha(String s){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static boolean loadIfPresent(Path entry,Map<String,MixedSourceUnit> units,Map<String,Map<String,String>> resolutions)throws IOException{
        Path root=null;
        for(Path d=entry.getParent();d!=null;d=d.getParent()){
            boolean legacy=exists(d.resolve(MANIFEST));
            boolean ordinary=exists(d.resolve(OresProjectConfig.MANIFEST_NAME))||exists(d.resolve(OresProjectConfig.ATTO_MANIFEST_NAME));
            if(legacy&&ordinary)throw deny("ambiguous project manifests");
            if(ordinary)return false;
            if(legacy){root=d;break;}
        }
        if(root==null)return false;
        String cm=read(root.resolve(MANIFEST));
        var c=manifest(cm);
        if(!c.keySet().equals(Set.of("schema_version","project","packages","namespace_dependencies"))||
            !table(c,"project").keySet().equals(Set.of("root")))throw deny("unsupported consumer manifest fields");
        var packages=table(c,"packages");String consumerName=name(root.getFileName().toString());
        if(!packages.keySet().equals(Set.of(consumerName)))throw deny("consumer package identity");
        var cp=table(packages,consumerName);var cf=cp.getArray("files");
        if(!cp.keySet().equals(Set.of("files"))||cf==null||cf.size()!=1||!root.resolve(sourcePath(cf.getString(0))).equals(entry))throw deny("single listed consumer required");
        String source=read(entry);Ast.Program program=Parser.parse(source);
        if(program.imports().size()!=1)throw deny("one namespace import required");
        var selector=program.imports().getFirst();
        if(!selector.path().startsWith(ImportRules.NAMESPACE_PACKAGE_PREFIX))throw deny("namespace selector required");
        var parts=selector.path().substring(ImportRules.NAMESPACE_PACKAGE_PREFIX.length()).split(":",-1);
        if(parts.length!=2)throw deny("selector encoding");
        var deps=table(c,"namespace_dependencies");
        String alias=name(parts[0]);if(!deps.keySet().equals(Set.of(alias)))throw deny("one pinned dependency required");
        var dep=table(deps,alias);String providerName=name(dep.getString("package"));
        if(!dep.keySet().equals(Set.of("package","manifest","manifest_sha256","sources_sha256"))||
            !("../"+providerName+"/"+MANIFEST).equals(dep.getString("manifest"))||providerName.equals(consumerName))throw deny("sibling dependency required");
        Path provider=root.resolveSibling(providerName);
        // No competing project marker or symlink redirects in this profile.
        if(exists(provider.resolve(OresProjectConfig.MANIFEST_NAME))||exists(provider.resolve(OresProjectConfig.ATTO_MANIFEST_NAME)))throw deny("ambiguous provider manifests");
        String pm=read(provider.resolve(MANIFEST));
        if(!sha(pm).equals(dep.getString("manifest_sha256")))throw deny("manifest pin mismatch");
        var pv=manifest(pm);var pp=table(pv,"packages");
        if(!pv.keySet().equals(Set.of("schema_version","project","packages"))||!table(pv,"project").keySet().equals(Set.of("root"))||!pp.keySet().equals(Set.of(providerName)))throw deny("provider manifest fields");
        var pkg=table(pp,providerName);var inventory=pkg.getArray("files");
        if(!pkg.keySet().equals(Set.of("files"))||inventory==null||inventory.size()<1||inventory.size()>128)throw deny("provider inventory budget");
        Map<String,String> snapshot=new TreeMap<>();int total=0;
        for(int i=0;i<inventory.size();i++){
            String path=sourcePath(inventory.getString(i));if(snapshot.containsKey(path))throw deny("duplicate source path");
            String value=read(provider.resolve(path));total+=value.getBytes(StandardCharsets.UTF_8).length;
            if(total>4*1024*1024)throw deny("aggregate source budget");snapshot.put(path,value);
        }
        var selected=PinnedNamespaceInventory.admit(selector,cm,pm,snapshot);
        var plan=PinnedScalarNamespaceSession.lower(program,selected);
        var staged=new LinkedHashMap<String,MixedSourceUnit>();
        var mapped=new TreeMap<String,String>();
        for(var e:plan.sources().entrySet()){
            Path physical=e.getKey().equals(PinnedScalarNamespaceSession.ENTRY)?entry:provider.resolve(sourcePath(e.getKey().substring("provider/".length())));
            var mixed=MixedSourceUnit.parse(physical,e.getValue());
            if(mixed.hasJavaSource()||!mixed.oresSource().equals(e.getValue()))throw deny("mixed source not admitted");
            staged.put(id(physical),mixed);mapped.put(e.getKey(),id(physical));
        }
        var imports=new TreeMap<String,String>();
        plan.resolutions().forEach((key,target)->imports.put(key,Objects.requireNonNull(mapped.get(target))));
        units.putAll(staged);resolutions.put(id(entry),imports);return true;
    }
}
