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
