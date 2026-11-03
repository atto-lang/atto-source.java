package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Package-private V2 structural admission after a strict decoded-key UTF-8
 * JSON parser. This does not parse raw JSON, authenticate signatures, load
 * binaries or establish AOT guest execution. It may only be called by future
 * trusted ingress that has eliminated duplicate keys and unsafe Unicode.
 */
final class AotV2ManifestPolicy {
    private static final long MAX_SIZE=9_007_199_254_740_991L;
    private static final Pattern SHA=Pattern.compile("[0-9a-f]{64}");
    private static final Pattern COMMIT=Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");
    private static final Pattern VERSION=Pattern.compile("[A-Za-z0-9_.+\\-]{1,100}");
    private static final Pattern BUILDER=Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:\\-]{0,127}");
    // Exact V2 triples shared with conformance. Never infer ABI from substrings.
    private static final Map<String, Target> TARGETS=Map.of(
            "x86_64-unknown-linux-gnu", new Target("linux","x86_64","gnu"),
            "aarch64-unknown-linux-gnu", new Target("linux","aarch64","gnu"),
            "x86_64-unknown-linux-musl",new Target("linux","x86_64","musl"),
            "aarch64-unknown-linux-musl",new Target("linux","aarch64","musl"),
            "x86_64-pc-windows-msvc",new Target("windows","x86_64","msvc"),
            "aarch64-pc-windows-msvc",new Target("windows","aarch64","msvc"),
            "x86_64-apple-darwin",new Target("macos","x86_64","darwin"),
            "aarch64-apple-darwin",new Target("macos","aarch64","darwin"),
            "wasm32-wasi",new Target("wasi","wasm32","wasi"));
    record Target(String os,String arch,String abi) { }
    record Typed(String backend,String mode,String artifactKind,String artifactFormat,
                 String targetTriple,Target target,String artifactSha256,long artifactSize,
                 String sourceSha256,String compilerCommit,String builderId,
                 boolean authenticated,boolean loaderVerified,boolean guestExecuted) { }
    private AotV2ManifestPolicy() { }

    static Typed validateTree(Object decoded) {
        Map<String,Object> m=obj(decoded,"manifest");
        keys(m,"manifest","schema_version","compiler","source","artifact","runtime","provenance");
        if(!"atto.aot-artifact.v2".equals(str(m,"schema_version","manifest")))fail("version");
        Map<String,Object> comp=obj(m.get("compiler"),"compiler");
        keys(comp,"compiler","backend","version","git_commit","mode");
        String backend=str(comp,"backend","compiler"),mode=str(comp,"mode","compiler");
        if(!Set.of("java-graalvm","llvm","wasm-wasmtime","dotnet-nativeaot").contains(backend))fail("backend");
        match(str(comp,"version","compiler"),VERSION,"compiler.version");
        String compilerCommit=str(comp,"git_commit","compiler");match(compilerCommit,COMMIT,"compiler.git_commit");
        if(!Set.of("per-guest-aot","portable-wasm","wasmtime-precompiled").contains(mode))fail("mode");
        Map<String,Object> source=obj(m.get("source"),"source");
        keys(source,"source","sha256","expanded_sha256","ast_sha256");
        for(String s:Set.of("sha256","expanded_sha256","ast_sha256"))match(str(source,s,"source"),SHA,"source."+s);
        Map<String,Object> art=obj(m.get("artifact"),"artifact");
        keys(art,"artifact","sha256","size_bytes","kind","format","os","arch","abi","target_triple");
        String artifactHash=str(art,"sha256","artifact");match(artifactHash,SHA,"artifact sha256");
        long artifactSize=safeSize(art.get("size_bytes"));
        String kind=str(art,"kind","artifact"),format=str(art,"format","artifact");
        String triple=str(art,"target_triple","artifact");
        Target expected=TARGETS.get(triple);
        if(expected==null)fail("unsupported target triple");
        String os=str(art,"os","artifact"),arch=str(art,"arch","artifact"),abi=str(art,"abi","artifact");
        if(!expected.equals(new Target(os,arch,abi)))fail("target triple/ABI mismatch");
        boolean nativeTarget=!"wasi".equals(os);
        String nativeFormat=switch(os){case "linux"->"elf";case "windows"->"pe";case "macos"->"macho";default->"";};
        switch(kind){
            case "native-executable" -> {
                if(!nativeTarget||!Set.of("java-graalvm","llvm","dotnet-nativeaot").contains(backend)
                   ||!"per-guest-aot".equals(mode)||!nativeFormat.equals(format))fail("native executable combination");
            }
            case "native-wasm-host" -> {
                if(!nativeTarget||!"wasm-wasmtime".equals(backend)||!"per-guest-aot".equals(mode)
                   ||!nativeFormat.equals(format))fail("native WASM host combination");
            }
            case "portable-wasm" -> {
                if(nativeTarget||!"wasm-wasmtime".equals(backend)||!"portable-wasm".equals(mode)
                   ||!"wasm".equals(format))fail("portable WASM combination");
            }
            case "precompiled-wasmtime" -> {
                if(!nativeTarget||!"wasm-wasmtime".equals(backend)||!"wasmtime-precompiled".equals(mode)
                   ||!"wasmtime-serialized".equals(format))fail("precompiled WASM combination");
            }
            default -> fail("artifact kind");
        }
        Map<String,Object> runtime=obj(m.get("runtime"),"runtime");
        keys(runtime,"runtime","guest_jit","interpreter_fallback","runtime_guest_compilation");
        for(String field:Set.of("guest_jit","interpreter_fallback","runtime_guest_compilation"))
            if(!Boolean.FALSE.equals(runtime.get(field)))fail("runtime policy "+field);
        Map<String,Object> provenance=obj(m.get("provenance"),"provenance");
        keys(provenance,"provenance","builder_id");
        String builder=str(provenance,"builder_id","provenance");match(builder,BUILDER,"builder id");
        return new Typed(backend,mode,kind,format,triple,expected,artifactHash,artifactSize,
                         str(source,"sha256","source"),compilerCommit,builder,false,false,false);
    }

    private static Map<String,Object> obj(Object raw,String field){
        if(!(raw instanceof Map<?,?> source))throw new IllegalArgumentException(field+" must be object");
        for(Map.Entry<?,?> e:source.entrySet())if(!(e.getKey() instanceof String))fail(field+" object key type");
        @SuppressWarnings("unchecked") Map<String,Object> cast=(Map<String,Object>)source;
        return cast;
    }
    private static void keys(Map<String,Object> m,String label,String...names){
        if(!m.keySet().equals(Set.of(names)))fail(label+" fields");
    }
    private static String str(Map<String,Object> m,String key,String label){
        Object value=m.get(key);
        if(!(value instanceof String s))throw new IllegalArgumentException("V2 invalid "+label+"."+key);
        return s;
    }
    private static void match(String value,Pattern pattern,String label){
        if(!pattern.matcher(value).matches())fail(label);
    }
    private static long safeSize(Object value){
        if(!(value instanceof BigDecimal n))throw new IllegalArgumentException("size_bytes must be exact JSON number");
        if(n.signum()<=0)fail("unsafe size_bytes");
        // Reject huge exponents before creating a potentially enormous BigInteger.
        // Strip trailing zeros so exact 128.000... remains a valid integer.
        BigDecimal reduced=n.stripTrailingZeros();
        if(reduced.scale()>0 || (long)reduced.precision()-reduced.scale()>16L)
            fail("noninteger or oversized size_bytes");
        final BigInteger i;
        try{i=reduced.toBigIntegerExact();}catch(ArithmeticException e){throw new IllegalArgumentException("V2 fractional size_bytes",e);}
        if(i.compareTo(BigInteger.valueOf(MAX_SIZE))>0)fail("unsafe size_bytes");
        return i.longValueExact();
    }
    private static void fail(String issue){throw new IllegalArgumentException("V2 reject: "+issue);}
}
