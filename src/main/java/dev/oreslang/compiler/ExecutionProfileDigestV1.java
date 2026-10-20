package dev.oreslang.compiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

public final class ExecutionProfileDigestV1 {
    private ExecutionProfileDigestV1() { }

    public record Profile(
            int version,
            String backendId,
            String backendSemanticVersion,
            String compilerSemanticVersion,
            String targetTriple,
            String dataLayout,
            String runtimeAbi,
            String numericMode,
            boolean deterministic,
            String allocation,
            boolean freestanding,
            String deviceAbi,
            List<String> numericFeatures) {
        public Profile {
            Objects.requireNonNull(backendId);
            Objects.requireNonNull(backendSemanticVersion);
            Objects.requireNonNull(compilerSemanticVersion);
            Objects.requireNonNull(targetTriple);
            Objects.requireNonNull(dataLayout);
            Objects.requireNonNull(runtimeAbi);
            Objects.requireNonNull(numericMode);
            Objects.requireNonNull(allocation);
            numericFeatures = List.copyOf(numericFeatures);
        }
    }

    private static void fail(String code) { throw new IllegalArgumentException(code); }

    private static void validString(String s) {
        if (s == null) fail("MODEL_INVALID_EXECUTION_PROFILE");
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=s.length() || !Character.isLowSurrogate(s.charAt(i))) fail("MODEL_DIGEST_STRING");
            } else if (Character.isLowSurrogate(c)) fail("MODEL_DIGEST_STRING");
        }
        if (s.getBytes(StandardCharsets.UTF_8).length>256) fail("MODEL_INVALID_EXECUTION_PROFILE");
    }

    private static int ordinal(String v, String[] values) {
        for (int i=0;i<values.length;i++) if (values[i].equals(v)) return i;
        fail("MODEL_INVALID_EXECUTION_PROFILE");
        return -1;
    }

    private static final class Bytes {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        void raw(byte[] b){out.writeBytes(b);}
        void u8(int n){out.write(n);}
        void u32(int n){for(int shift=24;shift>=0;shift-=8)out.write((n>>>shift)&255);}
        void string(String s){validString(s);byte[] b=s.getBytes(StandardCharsets.UTF_8);u32(b.length);raw(b);}
        byte[] finish(){return out.toByteArray();}
    }

    public static byte[] canonicalBytes(Profile p) {
        if (p.version()!=1) fail("MODEL_INVALID_EXECUTION_PROFILE");
        for (int i=0;i<p.numericFeatures().size();i++) {
            String f=p.numericFeatures().get(i);
            if (!f.matches("[A-Za-z0-9_.+\\-]{1,64}")) fail("MODEL_INVALID_EXECUTION_PROFILE");
            if (i>0 && p.numericFeatures().get(i-1).compareTo(f)>=0)
                fail("MODEL_NONCANONICAL_EXECUTION_PROFILE");
        }
        Bytes out=new Bytes();
        out.raw("ORESLANG-MODEL-PROFILE-V1\0".getBytes(StandardCharsets.US_ASCII));
        out.u32(1);
        out.string(p.backendId());
        out.string(p.backendSemanticVersion());
        out.string(p.compilerSemanticVersion());
        out.string(p.targetTriple());
        out.string(p.dataLayout());
        out.string(p.runtimeAbi());
        out.u8(ordinal(p.numericMode(),new String[]{"strict","ieee","fast"}));
        out.u8(p.deterministic()?1:0);
        out.u8(ordinal(p.allocation(),new String[]{"preallocated","bounded","dynamic"}));
        out.u8(p.freestanding()?1:0);
        out.u8(p.deviceAbi()==null?0:1);
        if(p.deviceAbi()!=null)out.string(p.deviceAbi());
        out.u32(p.numericFeatures().size());
        for(String f:p.numericFeatures())out.string(f);
        return out.finish();
    }

    public static String digest(Profile p) {
        try {
            byte[] d=MessageDigest.getInstance("SHA-256").digest(canonicalBytes(p));
            char[] result=new char[64];
            char[] hex="0123456789abcdef".toCharArray();
            for(int i=0;i<d.length;i++){
                int b=d[i]&255;
                result[i*2]=hex[b>>>4];
                result[i*2+1]=hex[b&15];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
