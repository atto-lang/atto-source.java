package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;
import org.tomlj.Toml;
import org.tomlj.TomlTable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** In-memory admission of a pinned v2 namespace inventory. This is NOT a
 * filesystem resolver or linker. The host supplies the reviewed consumer
 * manifest and exact provider bytes; no ambient lookup or source execution.
 * Unnamespaced units never become namespace providers in this profile. */
public final class PinnedNamespaceInventory {
    private PinnedNamespaceInventory(){}
    public record Provider(String path,String source,Ast.Program program){}
    public record Selection(String packageAlias,String namespace,String localAlias,List<Provider> providers){
        public Selection{providers=List.copyOf(providers);}
    }
    private static IllegalArgumentException deny(String detail){return new IllegalArgumentException("E_NAMESPACE_INVENTORY: "+detail);}
    private static byte[] bytes(String text,int limit){
        if(text==null||text.length()>limit||!StandardCharsets.UTF_8.newEncoder().canEncode(text)||text.indexOf(0)>=0)throw deny("text budget");
        byte[] b=text.getBytes(StandardCharsets.UTF_8);if(b.length>limit)throw deny("byte budget");return b;
    }
    private static String digest(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static TomlTable manifest(String source){
        bytes(source,65536);var t=Toml.parse(source);
        if(t.hasErrors()||!"2".equals(t.getString("schema_version"))||!".".equals(t.getString("project.root")))throw deny("manifest schema");
        return t;
    }
    private static String name(String s){
        if(s==null||!s.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")||s.contains("..")||s.endsWith("."))throw deny("package alias");return s;
    }
    private static String path(String s){
        if(s==null||s.length()>512||!s.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.(?:atto|ores)"))throw deny("source path");return s;
    }
    private static TomlTable table(TomlTable t,String key){var v=t.getTable(List.of(key));if(v==null)throw deny("missing table "+key);return v;}
    // Opaque, single accepted inventory. Never trust a memo from caller-built
    // ASTs; only authenticated source parsed by this class enters it.
    public static final class Memo {
        private final String manifest;
        private final Map<String,Provider> entries;
        private Memo(String manifest,Map<String,Provider> entries){this.manifest=manifest;this.entries=Map.copyOf(entries);}
    }
    public record Prepared(Selection selection,Memo memo,int parsed,int reused){}
    public static Selection admit(Ast.ImportDecl selector,String consumerManifest,String providerManifest,Map<String,String> sourceSnapshot){
        return prepare(selector,consumerManifest,providerManifest,sourceSnapshot,null).selection();
    }
    public static Prepared prepare(Ast.ImportDecl selector,String consumerManifest,String providerManifest,Map<String,String> sourceSnapshot,Memo previous){
        if(selector==null||selector.kind()!=Ast.ImportKind.ALL||!selector.wildcard()||!selector.names().isEmpty()||
           selector.namespace()==null||!selector.namespace().matches("[A-Za-z_][A-Za-z0-9_]{0,127}")||selector.path()==null||!selector.path().startsWith(ImportRules.NAMESPACE_PACKAGE_PREFIX))throw deny("selector required");
        String[] parts=selector.path().substring(ImportRules.NAMESPACE_PACKAGE_PREFIX.length()).split(":",-1);
        if(parts.length!=2||!parts[1].matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))throw deny("namespace selector");
        String alias=name(parts[0]),ns=parts[1];var consumer=manifest(consumerManifest);
        var dep=table(table(consumer,"namespace_dependencies"),alias);
        if(!dep.keySet().equals(Set.of("manifest","package","manifest_sha256","sources_sha256")))throw deny("dependency fields");
        String packageName=name(dep.getString("package"));
        // Manifest locator is metadata, never opened by this API. Explicit
        // relative sibling path is the only admitted v2 fixture profile.
        if(!("../"+packageName+"/.ores.pkg.toml").equals(dep.getString("manifest")))throw deny("manifest locator");
        byte[] mb=bytes(providerManifest,65536);
        if(!digest(mb).equals(dep.getString("manifest_sha256")))throw deny("manifest pin mismatch");
        var provider=manifest(providerManifest);
        if(!provider.keySet().equals(Set.of("schema_version","project","packages"))||
           !table(provider,"project").keySet().equals(Set.of("root")))throw deny("provider manifest fields");
        var packages=table(provider,"packages");
        if(!packages.keySet().equals(Set.of(packageName)))throw deny("package identity");
        var pkg=table(packages,packageName);
        if(!pkg.keySet().equals(Set.of("files")))throw deny("provider fields");
        var inventory=pkg.getArray("files");
        if(inventory==null||inventory.size()<1||inventory.size()>128||sourceSnapshot==null||sourceSnapshot.size()!=inventory.size())throw deny("inventory size");
        var sorted=new TreeMap<String,String>();int total=0;
        for(int i=0;i<inventory.size();i++){
            String p=path(inventory.getString(i));String source=sourceSnapshot.get(p);byte[] b=bytes(source,65536);
            total+=b.length;if(total>4*1024*1024||sorted.putIfAbsent(p,source)!=null)throw deny("inventory duplicate or budget");
        }
        if(!sourceSnapshot.keySet().equals(sorted.keySet()))throw deny("inventory membership");
        var serialized=new StringBuilder();
        sorted.forEach((p,s)->serialized.append(p).append('\n').append(digest(bytes(s,65536))).append('\n'));
        if(!digest(bytes(serialized.toString(),128*1024)).equals(dep.getString("sources_sha256")))throw deny("source pin mismatch");
        var chosen=new ArrayList<Provider>();var symbols=new HashSet<String>();
        var next=new TreeMap<String,Provider>();int parsed=0,reused=0;
        for(var e:sorted.entrySet()){
            // Parse only after both pins and exact membership have been
            // validated. No inferred package/folder namespace.
            Provider old=previous!=null&&providerManifest.equals(previous.manifest)?previous.entries.get(e.getKey()):null;
            Provider item;
            if(old!=null&&old.source().equals(e.getValue())){item=old;reused++;}
            else{item=new Provider(e.getKey(),e.getValue(),Parser.parse(e.getValue()));parsed++;}
            next.put(e.getKey(),item);
            Ast.Program program=item.program();
            if(!ns.equals(program.namespace()))continue;
            for(var module:program.modules()){
                if(!Parser.ROOT_MODULE.equals(module.name()))continue;
                for(var d:module.declarations()){
                    if(d instanceof Ast.FunctionDecl fn&&!symbols.add(fn.name()))throw deny("duplicate function in selected namespace");
                }
            }
            chosen.add(item);
        }
        if(chosen.isEmpty())throw deny("namespace not present in pinned package");
        return new Prepared(new Selection(alias,ns,selector.namespace(),chosen),new Memo(providerManifest,next),parsed,reused);
    }
}
