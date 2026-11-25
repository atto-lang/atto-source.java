package dev.oreslang.imports;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

/** Frozen in-memory registry input. The expected lock hash MUST be independently trusted.
 * No filesystem or network reads. This narrow profile supports explicit .atto providers
 * and directory shorthand for namespace.atto, and only import-free root functions. */
public final class FrozenImportSnapshot {
    private static final int MAX_MANIFEST = 8192, MAX_LOCK = 32768, MAX_FILES = 64, MAX_SOURCE = 1048576;
    private final String name, version, lockDigest;
    private final Map<String,String> namespaces, exports, sources;
    public record Dependency(String version,String lockDigest) {}
    private final Map<String,Dependency> dependencies;
    private StaticImportGraph.View checkedView;
    private FrozenImportSnapshot(String name,String version,String lockDigest,Map<String,String> namespaces,
            Map<String,String> exports,Map<String,String> sources,Map<String,Dependency> dependencies) {
        this.name=name; this.version=version; this.lockDigest=lockDigest;
        this.namespaces=Map.copyOf(namespaces); this.exports=Map.copyOf(exports); this.sources=Map.copyOf(sources); this.dependencies=Map.copyOf(dependencies);
    }
    private static IllegalArgumentException fail(String code,String message) {
        return new IllegalArgumentException(code+": "+message);
    }
    private static boolean digest(String s) { return s!=null && s.matches("[a-f0-9]{64}"); }
    private static String sha(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String text(byte[] b,String code) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString(); }
        catch(CharacterCodingException e) { throw fail(code,"invalid UTF-8"); }
    }
    private static byte[] bounded(byte[] b,int max,String code) {
        if(b==null || b.length>max) throw fail(code,"input exceeds budget");
        return b.clone();
    }
    private static boolean segment(String s) {return s.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");}
    private static boolean subpath(String s) {
        return s.length()<=256 && Arrays.stream(s.split("/",-1)).allMatch(FrozenImportSnapshot::segment);
    }
    private static boolean path(String s) {
        return s.length()<=256 && s.endsWith(".atto") && subpath(s.substring(0,s.length()-5));
    }
    private static boolean ns(String s) {
        return s.length()<=519 && s.split("\\.",-1).length<=8 &&
            Arrays.stream(s.split("\\.",-1)).allMatch(ContextualImportPath::valid);
    }
    private static String quoted(String s,String code) {
        if(s.length()<3 || s.charAt(0)!='"' || s.charAt(s.length()-1)!='"')
            throw fail(code,"expected simple quoted ASCII string");
        String v=s.substring(1,s.length()-1);
        if(v.chars().anyMatch(c->c<32||c>126||c=='"'||c=='\\')) throw fail(code,"unsupported string");
        return v;
    }
    private static Map<String,Map<String,String>> parse(String text,Set<String> allowed,String code) {
        if(text.chars().anyMatch(c->c>126 || c<32 && c!='\r' && c!='\n' && c!='\t') ||
           text.replace("\r\n","").contains("\r")) throw fail(code,"invalid metadata bytes");
        var out=new LinkedHashMap<String,Map<String,String>>();
        Map<String,String> table=null;
        for(String raw:text.split("\n",-1)) {
            String line=raw.trim();
            if(line.isEmpty()||line.startsWith("#")) continue;
            if(line.startsWith("[")) {
                if(!allowed.contains(line)||out.containsKey(line)) throw fail(code,"unknown or duplicate table");
                table=new TreeMap<>(); out.put(line,table); continue;
            }
            int at=line.indexOf('=');
            if(table==null||at<1||table.size()>=MAX_FILES) throw fail(code,"invalid or excessive key");
            String key=line.substring(0,at).trim(), val=quoted(line.substring(at+1).trim(),code);
            if(key.startsWith("\"")) key=quoted(key,code);
            else if(!key.matches("[A-Za-z0-9_-]+")) throw fail(code,"dotted/slash keys must be quoted");
            if(table.putIfAbsent(key,val)!=null) throw fail(code,"duplicate key");
        }
        return out;
    }
    public static FrozenImportSnapshot verify(String expectedName,String expectedVersion,String expectedLock,
            byte[] manifestInput,byte[] lockInput,Map<String,byte[]> inputs) {
        if(!digest(expectedLock)) throw fail("E_LOCK","invalid trusted lock digest");
        byte[] manifest=bounded(manifestInput,MAX_MANIFEST,"E_MANIFEST"), lock=bounded(lockInput,MAX_LOCK,"E_LOCK");
        if(!sha(lock).equals(expectedLock)) throw fail("E_LOCK_HASH","lock digest mismatch");
        var tables=parse(text(lock,"E_LOCK"),Set.of("[lock]","[files]","[dependencies]"),"E_LOCK");
        var header=tables.getOrDefault("[lock]",Map.of()); var pins=tables.getOrDefault("[files]",Map.of());
        if(!(new ArrayList<>(tables.keySet()).equals(List.of("[lock]","[files]")) ||
            new ArrayList<>(tables.keySet()).equals(List.of("[lock]","[files]","[dependencies]"))) || header.size()!=1 ||
            !digest(header.get("manifest_sha256")) || pins.isEmpty()) throw fail("E_LOCK","invalid lock inventory");
        if(!sha(manifest).equals(header.get("manifest_sha256"))) throw fail("E_LOCK_HASH","manifest digest mismatch");
        if(inputs.size()!=pins.size()||inputs.size()>MAX_FILES) throw fail("E_LOCK_HASH","source inventory mismatch");
        var exact=new HashMap<String,byte[]>();
        for(var e:inputs.entrySet()) {
            if(exact.containsKey(e.getKey())) throw fail("E_LOCK_HASH","duplicate source");
            exact.put(e.getKey(),e.getValue());
        }
        var sources=new TreeMap<String,String>();
        for(var e:pins.entrySet()) {
            if(!path(e.getKey())||!digest(e.getValue())) throw fail("E_LOCK","invalid source pin");
            if(!exact.containsKey(e.getKey())) throw fail("E_LOCK_HASH","missing pinned source");
            byte[] data=bounded(exact.get(e.getKey()),MAX_SOURCE,"E_SOURCE_LIMIT");
            if(!sha(data).equals(e.getValue())) throw fail("E_LOCK_HASH","source digest mismatch");
            sources.put(e.getKey(),text(data,"E_LOCK"));
        }
        var m=parse(text(manifest,"E_MANIFEST"),Set.of("[package]","[namespaces]","[exports]","[dependencies]"),"E_MANIFEST");
        var pkg=m.getOrDefault("[package]",Map.of()); var ns=m.getOrDefault("[namespaces]",Map.of());
        var ex=m.getOrDefault("[exports]",Map.of());
        String name=pkg.get("name"), version=pkg.get("version");
        if(pkg.size()!=2||name==null||!name.matches("@[A-Za-z0-9][A-Za-z0-9_-]{0,63}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}")||
            version==null||!version.matches("[A-Za-z0-9.+-]{1,64}")||ns.isEmpty()) throw fail("E_MANIFEST","invalid package metadata");
        if(!name.equals(expectedName)||!version.equals(expectedVersion)) throw fail("E_LOCK_HASH","package identity mismatch");
        var providers=new TreeMap<String,String>(); var used=new HashSet<String>();
        for(var e:ns.entrySet()) {
            String provider=e.getValue().endsWith("/")?e.getValue()+"namespace.atto":e.getValue();
            if(!ns(e.getKey())||!path(provider)||!used.add(provider)||!sources.containsKey(provider))
                throw fail("E_MANIFEST","invalid, duplicate or unpinned provider");
            for(int dot=e.getKey().indexOf('.');dot>=0;dot=e.getKey().indexOf('.',dot+1))
                if(!ns.containsKey(e.getKey().substring(0,dot))) throw fail("E_MANIFEST","undeclared namespace parent");
            providers.put(e.getKey(),provider);
        }
        for(var e:ex.entrySet())
            if(!subpath(e.getKey())||!providers.containsKey(e.getValue())) throw fail("E_MANIFEST","dangling subpath");
        // All provider bytes remain owned by this manifest; other packages have separate snapshots.
        if(!used.equals(sources.keySet())) throw fail("E_UNSUPPORTED","additional dependency sources not admitted");
        var declared=m.getOrDefault("[dependencies]",Map.of());
        var locked=tables.getOrDefault("[dependencies]",Map.of());
        if(!declared.keySet().equals(locked.keySet())||declared.size()>16)
            throw fail("E_LOCK","dependency inventory mismatch");
        var deps=new TreeMap<String,Dependency>();
        for(var e:declared.entrySet()) {
            if(!e.getKey().matches("@[A-Za-z0-9][A-Za-z0-9_-]{0,63}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}/[A-Za-z0-9][A-Za-z0-9_-]{0,63}")||
                !e.getValue().matches("[A-Za-z0-9.+-]{1,64}")||!digest(locked.get(e.getKey())))
                throw fail("E_LOCK","invalid dependency identity, version or pin");
            deps.put(e.getKey(),new Dependency(e.getValue(),locked.get(e.getKey())));
        }
        return new FrozenImportSnapshot(name,version,expectedLock,providers,ex,sources,deps);
    }
    public String namespaceForSubpath(String subpath) {
        if(!subpath(subpath)||!exports.containsKey(subpath)) throw fail("E_UNBOUND","undeclared subpath");
        return exports.get(subpath);
    }
    public String providerSource(String namespace) {
        if(!namespaces.containsKey(namespace)) throw fail("E_UNBOUND","undeclared namespace");
        return sources.get(namespaces.get(namespace));
    }
    /** Only the actual parser determines source visibility. No caller-supplied export inventory. */
    public synchronized StaticImportGraph.View functionView() {
        if(checkedView!=null) return checkedView;
        var inventory=new ArrayList<StaticImportGraph.Export>();
        for(String namespace:new TreeSet<>(namespaces.keySet())) {
            var p=Parser.parse(providerSource(namespace));
            if(!namespace.equals(p.namespace())) throw fail("E_MANIFEST","provider namespace mismatch");
            if(!p.imports().isEmpty()||!p.exports().isEmpty())
                throw fail("E_UNSUPPORTED","provider imports or explicit export clauses require full linker");
            inventory.add(new StaticImportGraph.Export(namespace,StaticImportGraph.Kind.NAMESPACE,true));
            for(var module:p.modules()) {
                if(!Parser.ROOT_MODULE.equals(module.name())) throw fail("E_UNSUPPORTED","provider module requires full linker");
                for(var decl:module.declarations()) {
                    if(!(decl instanceof Ast.FunctionDecl f)||f.kind()!=Ast.CallableKind.FNC||f.name().equals("init"))
                        throw fail("E_UNSUPPORTED","only ordinary root functions admitted");
                    inventory.add(new StaticImportGraph.Export(namespace+"."+f.name(),StaticImportGraph.Kind.FNC,
                        f.visibility()==Ast.Visibility.PUBLIC));
                }
            }
        }
        checkedView = new StaticImportGraph.View(new StaticImportGraph.Origin(StaticImportGraph.Domain.PACKAGE,name,lockDigest),inventory);
        return checkedView;
    }
    /** Explicit root selector or pinned public subpath; no physical-path fallback. */
    public String selectNamespace(String source,String selector) {
        if(!ns(selector)) throw fail("E_IMPORT_PATH","invalid namespace selector");
        if(name.equals(source)) {
            if(namespaces.containsKey(selector)) return selector;
            int dot=selector.lastIndexOf('.');
            if(dot>=0) {
                String leaf=selector.substring(dot+1);
                var matches=namespaces.keySet().stream().filter(k->k.equals(leaf)||k.endsWith("."+leaf)).sorted().toList();
                if(matches.size()>1) throw fail("E_AMBIGUOUS","ambiguous namespace selector");
                if(matches.size()==1) return matches.getFirst();
            }
            throw fail("E_UNBOUND","namespace not declared");
        }
        if(source==null||!source.startsWith(name+"/")) throw fail("E_IMPORT_PATH","wrong pinned package");
        return namespaceForSubpath(source.substring(name.length()+1));
    }
    /** Explicit selected namespace view uses the parser-derived identity. */
    public void bindNamespace(StaticImportGraph scope,String source,String selector,String binding,boolean explicitAlias) {
        if(!explicitAlias&&!selector.equals(binding)) throw fail("E_IMPORT_PATH","rename requires explicit alias");
        String selected=selectNamespace(source,selector);
        var view=functionView();
        scope.bindGroup(view.origin(),view,List.of(new StaticImportGraph.Selection(selected,binding,StaticImportGraph.Kind.NAMESPACE,explicitAlias)));
    }
    public Map<String,Dependency> dependencies() {return dependencies;}
    public String name() {return name;}
    public String version() {return version;}
    public String lockDigest() {return lockDigest;}
}
