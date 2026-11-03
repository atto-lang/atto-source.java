package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

final class AotV2ManifestPolicyTest {
    @SafeVarargs
    private static Map<String,Object> obj(Object... pairs) {
        var m=new LinkedHashMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);
        return m;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> child(Map<String,Object> m,String k){return (Map<String,Object>)m.get(k);}
    private static Map<String,Object> base(){
        return obj(
            "schema_version","atto.aot-artifact.v2",
            "compiler",obj("backend","llvm","version","0.1.0-fixture","git_commit","a".repeat(40),"mode","per-guest-aot"),
            "source",obj("sha256","b".repeat(64),"expanded_sha256","c".repeat(64),"ast_sha256","d".repeat(64)),
            "artifact",obj("sha256","cfd83f63b6dcbaf49bbf80732cd4086a9971d0f32cfc31b6f22396740629f157","size_bytes",new BigDecimal("128"),"kind","native-executable","format","elf","os","linux","arch","x86_64","abi","gnu","target_triple","x86_64-unknown-linux-gnu"),
            "runtime",obj("guest_jit",false,"interpreter_fallback",false,"runtime_guest_compilation",false),
            "provenance",obj("builder_id","test-only-not-a-release-builder"));
    }
    private static void reject(Runnable f){try{f.run();throw new AssertionError("unexpected V2 admission");}catch(IllegalArgumentException expected){}}
    @Test void acceptsValidTypedManifestAndAllProfileProducts(){
        String[][] targets={
            {"x86_64-unknown-linux-gnu","linux","x86_64","gnu","elf"},
            {"aarch64-unknown-linux-gnu","linux","aarch64","gnu","elf"},
            {"x86_64-unknown-linux-musl","linux","x86_64","musl","elf"},
            {"aarch64-unknown-linux-musl","linux","aarch64","musl","elf"},
            {"x86_64-pc-windows-msvc","windows","x86_64","msvc","pe"},
            {"aarch64-pc-windows-msvc","windows","aarch64","msvc","pe"},
            {"x86_64-apple-darwin","macos","x86_64","darwin","macho"},
            {"aarch64-apple-darwin","macos","aarch64","darwin","macho"},
            {"wasm32-wasi","wasi","wasm32","wasi","wasm"}};
        int accepted=0,rejected=0;
        for(String kind:List.of("native-executable","portable-wasm","precompiled-wasmtime","native-wasm-host")){
            for(String backend:List.of("java-graalvm","llvm","dotnet-nativeaot","wasm-wasmtime")){
                for(String[] t:targets){
                    boolean expected=switch(kind){
                        case "native-executable"-> !backend.equals("wasm-wasmtime")&&!t[1].equals("wasi");
                        case "portable-wasm" -> backend.equals("wasm-wasmtime")&&t[1].equals("wasi");
                        default -> backend.equals("wasm-wasmtime")&&!t[1].equals("wasi");
                    };
                    Map<String,Object> m=base();var c=child(m,"compiler");var a=child(m,"artifact");
                    c.put("backend",backend);
                    c.put("mode",kind.equals("portable-wasm")?"portable-wasm":kind.equals("precompiled-wasmtime")?"wasmtime-precompiled":"per-guest-aot");
                    a.put("kind",kind);a.put("target_triple",t[0]);a.put("os",t[1]);a.put("arch",t[2]);a.put("abi",t[3]);
                    a.put("format",kind.equals("precompiled-wasmtime")?"wasmtime-serialized":t[4]);
                    if(expected){
                        var receipt=AotV2ManifestPolicy.validateTree(m);
                        if(receipt.guestExecuted()||receipt.loaderVerified()||receipt.authenticated())throw new AssertionError("tier elevated");
                        accepted++;
                    }else{reject(()->AotV2ManifestPolicy.validateTree(m));rejected++;}
                }
            }
        }
        if(accepted!=41||rejected!=103)throw new AssertionError("wrong admission counts "+accepted+"/"+rejected);
    }
    @Test void rejectsUnsafeNumbersUnknownFieldsAndUnsafeRuntimePolicy(){
        for(Object size:List.of(new BigDecimal("128.0000000000000001"),new BigDecimal("9007199254740992"),new BigDecimal("0"),
                             new BigDecimal("1e100000000"),new BigDecimal("1e-100000000"),"128",128)){
            var m=base();child(m,"artifact").put("size_bytes",size);reject(()->AotV2ManifestPolicy.validateTree(m));
        }
        var precise=base();child(precise,"artifact").put("size_bytes",new BigDecimal("128.000000000000000000000000000"));
        if(AotV2ManifestPolicy.validateTree(precise).artifactSize()!=128)throw new AssertionError("integer spelling changed value");
        for(String f:List.of("guest_jit","interpreter_fallback","runtime_guest_compilation")){
            var m=base();child(m,"runtime").put(f,true);reject(()->AotV2ManifestPolicy.validateTree(m));
        }
        var m=base();m.put("extra",true);reject(()->AotV2ManifestPolicy.validateTree(m));
        var n=base();child(n,"provenance").put("signature","inline-bypass");reject(()->AotV2ManifestPolicy.validateTree(n));
        var p=base();child(p,"artifact").put("target_triple","aarch64-x86_64-unknown-linux-gnu");reject(()->AotV2ManifestPolicy.validateTree(p));
    }
    public static void main(String[] args){
        var t=new AotV2ManifestPolicyTest();t.acceptsValidTypedManifestAndAllProfileProducts();t.rejectsUnsafeNumbersUnknownFieldsAndUnsafeRuntimePolicy();
        System.out.println("JAVA_V2_POLICY_41_POSITIVE_103_NEGATIVE_PLUS_ADVERSARIAL_OK");
    }
}
