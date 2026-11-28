package dev.oreslang.compiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Canonical atto-spec cache-key envelope v1. A key is not an artifact receipt.
 *  Stage-specific digest construction and authentication are caller obligations. */
public final class CompilerCacheKey {
    private CompilerCacheKey() {}
    public record Dependency(String identity, String digest) {}
    public record Input(String stage,String owner,String unitKind,String unit,
            String compiler,String toolchain,String target,String configuration,
            String sources,String bindings,String generated,List<Dependency> dependencies) {
        public Input {
            if(dependencies==null||dependencies.size()>128) throw invalid("dependency budget");
            dependencies=Collections.unmodifiableList(new ArrayList<>(dependencies));
            if(dependencies.size()>128) throw invalid("dependency budget");
        }
    }
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("E_CACHE_KEY: "+reason);
    }
    private static void u32(ByteArrayOutputStream out,int n) {
        for(int shift=24;shift>=0;shift-=8) out.write(n>>>shift);
    }
    private static void identity(ByteArrayOutputStream out,String value) {
        if(value==null||value.isEmpty()||value.length()>512||
                value.chars().anyMatch(c->c<33||c>126)) throw invalid("identity");
        byte[] bytes=value.getBytes(StandardCharsets.US_ASCII);
        u32(out,bytes.length);out.writeBytes(bytes);
    }
    private static void digest(ByteArrayOutputStream out,String value) {
        if(value==null||value.length()!=64||value.chars().anyMatch(c->
            !(c>='0'&&c<='9'||c>='a'&&c<='f'))) throw invalid("digest");
        out.writeBytes(HexFormat.of().parseHex(value));
    }
    public static String encode(Input input) {
        if(input==null||!("namespace".equals(input.unitKind())||"source-unit".equals(input.unitKind())))
            throw invalid("unit kind");
        var out=new ByteArrayOutputStream();
        out.writeBytes("atto.compiler.cache-key.v1".getBytes(StandardCharsets.US_ASCII));out.write(0);
        identity(out,input.stage());identity(out,input.owner());identity(out,input.unitKind());identity(out,input.unit());
        digest(out,input.compiler());digest(out,input.toolchain());digest(out,input.target());
        digest(out,input.configuration());digest(out,input.sources());digest(out,input.bindings());
        out.write(input.generated()==null?0:1);
        if(input.generated()!=null)digest(out,input.generated());
        var ordered=new TreeMap<String,String>();
        for(var dep:input.dependencies()) {
            if(dep==null)throw invalid("null dependency");
            // Validate before inserting: no null keys, silent replacements or non-ASCII ordering.
            identity(new ByteArrayOutputStream(),dep.identity());
            digest(new ByteArrayOutputStream(),dep.digest());
            if(ordered.putIfAbsent(dep.identity(),dep.digest())!=null)throw invalid("duplicate dependency");
        }
        u32(out,ordered.size());
        ordered.forEach((id,hash)->{identity(out,id);digest(out,hash);});
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
