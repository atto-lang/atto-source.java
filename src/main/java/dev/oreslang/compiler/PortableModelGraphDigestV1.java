package dev.oreslang.compiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Portable resolved ModelGraph identity contract matching common-source MODEL_GRAPH_DIGEST_V1. */
public final class PortableModelGraphDigestV1 {
    private PortableModelGraphDigestV1() { }

    public sealed interface Port permits BufferPort, NominalPort { }
    public record BufferPort(String dtype, List<Long> shape, String layout, String unit) implements Port {
        public BufferPort { Objects.requireNonNull(dtype); shape = List.copyOf(shape); }
    }
    public record NominalPort(String typeAbi, long bytes) implements Port {
        public NominalPort { Objects.requireNonNull(typeAbi); if (bytes < 0) fail("MODEL_INVALID_PORT"); }
    }
    public record Constant(String kind, String value) {
        public Constant { Objects.requireNonNull(kind); Objects.requireNonNull(value); }
    }
    public record Profile(boolean freestanding, String allocation, boolean deterministic) {
        public Profile { Objects.requireNonNull(allocation); }
    }
    public record Signature(List<Port> inputs, Port output, String effect, boolean vjp,
            long scratchBytes, String abi, String implDigest, String provenance,
            List<String> constKinds, Profile profile) {
        public Signature {
            inputs = List.copyOf(inputs); Objects.requireNonNull(output); Objects.requireNonNull(effect);
            if (scratchBytes < 0) fail("MODEL_INVALID_SCRATCH");
            Objects.requireNonNull(abi); Objects.requireNonNull(implDigest); Objects.requireNonNull(provenance);
            constKinds = List.copyOf(constKinds); Objects.requireNonNull(profile);
        }
    }
    public record Node(String id, String op, List<String> inputs,
            List<Constant> constArgs, Signature signature) {
        public Node {
            Objects.requireNonNull(id); Objects.requireNonNull(op);
            inputs = List.copyOf(inputs); constArgs = List.copyOf(constArgs); Objects.requireNonNull(signature);
        }
    }
    public record Graph(int version, String modelTypeId, List<Node> nodes, List<String> outputs) {
        public Graph {
            Objects.requireNonNull(modelTypeId); nodes = List.copyOf(nodes); outputs = List.copyOf(outputs);
        }
    }

    private static void fail(String code) { throw new IllegalArgumentException(code); }
    private static int ordinal(String value, String[] values, String code) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        fail(code); return -1;
    }
    private static void wellFormed(String value) {
        Objects.requireNonNull(value);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) fail("MODEL_DIGEST_STRING");
            } else if (Character.isLowSurrogate(c)) fail("MODEL_DIGEST_STRING");
        }
    }

    private static final class Bytes {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void raw(byte[] b) { out.writeBytes(b); }
        void u8(int n) { if (n < 0 || n > 255) fail("MODEL_DIGEST_ENCODING"); out.write(n); }
        void u32(long n) {
            if (n < 0 || n > 0xffff_ffffL) fail("MODEL_DIGEST_ENCODING");
            for (int shift = 24; shift >= 0; shift -= 8) out.write((int)(n >>> shift) & 0xff);
        }
        void u64(long n) {
            if (n < 0) fail("MODEL_DIGEST_ENCODING");
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int)(n >>> shift) & 0xff);
        }
        void i64(long n) {
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int)(n >>> shift) & 0xff);
        }
        void string(String value) {
            wellFormed(value); byte[] b = value.getBytes(StandardCharsets.UTF_8); u32(b.length); raw(b);
        }
        void digest(String hex) {
            if (hex == null || !hex.matches("[0-9a-f]{64}")) fail("MODEL_INVALID_IMPLEMENTATION_DIGEST");
            byte[] b = new byte[32];
            for (int i = 0; i < b.length; i++) b[i] = (byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
            raw(b);
        }
        byte[] finish() { return out.toByteArray(); }
    }
