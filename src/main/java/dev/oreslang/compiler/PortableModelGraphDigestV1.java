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

    private static void encodeConstant(Bytes out, Constant c) {
        int tag = ordinal(c.kind(), new String[]{"bool","i64","f64bits","string","symbol"}, "MODEL_INVALID_CONST");
        out.u8(tag);
        switch (c.kind()) {
            case "bool" -> {
                if (!c.value().equals("true") && !c.value().equals("false")) fail("MODEL_INVALID_CONST");
                out.u8(c.value().equals("true") ? 1 : 0);
            }
            case "i64" -> {
                if (!c.value().matches("-?(0|[1-9][0-9]*)")) fail("MODEL_INVALID_CONST");
                try { out.i64(Long.parseLong(c.value())); }
                catch (NumberFormatException e) { fail("MODEL_CONST_OVERFLOW"); }
            }
            case "f64bits" -> {
                if (!c.value().matches("0x[0-9a-f]{16}")) fail("MODEL_INVALID_CONST");
                long bits;
                try { bits = Long.parseUnsignedLong(c.value().substring(2), 16); }
                catch (NumberFormatException e) { fail("MODEL_INVALID_CONST"); return; }
                if (((bits >>> 52) & 0x7ffL) == 0x7ffL) fail("MODEL_NONFINITE_CONST");
                out.i64(bits);
            }
            case "string", "symbol" -> out.string(c.value());
            default -> fail("MODEL_INVALID_CONST");
        }
    }

    private static void encodePort(Bytes out, Port port) {
        if (port instanceof NominalPort n) {
            out.u8(1); out.string(n.typeAbi()); out.u64(n.bytes()); return;
        }
        if (!(port instanceof BufferPort b) || b.shape().size() > 8) fail("MODEL_INVALID_PORT");
        // Hashing cannot certify a model with unrepresentable physical storage.
        long elemBytes = switch (b.dtype()) {
            case "bool" -> 1L;
            case "i32", "f32" -> 4L;
            case "i64", "f64" -> 8L;
            default -> throw new IllegalArgumentException("MODEL_INVALID_PORT");
        };
        boolean zeroExtent = false;
        for (long extent : b.shape()) {
            if (extent < 0) fail("MODEL_INVALID_PORT");
            if (extent == 0) zeroExtent = true;
        }
        if (!zeroExtent) {
            try {
                for (long extent : b.shape()) elemBytes = Math.multiplyExact(elemBytes, extent);
            } catch (ArithmeticException overflow) {
                fail("MODEL_SIZE_OVERFLOW");
            }
        }
        if (b.unit() != null && (b.unit().length() > 40
                || !b.unit().matches("[A-Za-z0-9_/^-]*")))
            fail("MODEL_INVALID_PORT");
        out.u8(0);
        out.u8(ordinal(b.dtype(), new String[]{"bool","i32","i64","f32","f64"}, "MODEL_INVALID_PORT"));
        out.u8(b.shape().size());
        for (long extent : b.shape()) { if (extent < 0) fail("MODEL_INVALID_PORT"); out.u64(extent); }
        String layout = b.layout() == null ? (b.shape().isEmpty() ? "scalar" : "contiguous") : b.layout();
        if ((b.shape().isEmpty() && !layout.equals("scalar")) ||
                (!b.shape().isEmpty() && !layout.equals("contiguous"))) fail("MODEL_INVALID_PORT");
        out.u8(ordinal(layout, new String[]{"scalar","contiguous"}, "MODEL_INVALID_PORT"));
        if (b.unit() == null) out.u8(0); else { out.u8(1); out.string(b.unit()); }
    }

    private static boolean samePort(Port a, Port b) {
        if (a instanceof NominalPort x && b instanceof NominalPort y)
            return x.typeAbi().equals(y.typeAbi()) && x.bytes() == y.bytes();
        if (a instanceof BufferPort x && b instanceof BufferPort y) {
            String lx = x.layout() == null
                    ? (x.shape().isEmpty() ? "scalar" : "contiguous") : x.layout();
            String ly = y.layout() == null
                    ? (y.shape().isEmpty() ? "scalar" : "contiguous") : y.layout();
            return x.dtype().equals(y.dtype()) && x.shape().equals(y.shape())
                    && lx.equals(ly) && java.util.Objects.equals(x.unit(), y.unit());
        }
        return false;
    }

    public static byte[] canonicalBytes(Graph graph) {
        if (graph.version() != 1 || graph.nodes().isEmpty() || graph.outputs().isEmpty())
            fail("MODEL_INVALID_RESOLVED_GRAPH");
        if (graph.nodes().size() > 65536 || graph.outputs().size() > 65536)
            fail("MODEL_GRAPH_BUDGET");
        Bytes out = new Bytes();
        out.raw("ORESLANG-MODEL-GRAPH-V1\0".getBytes(StandardCharsets.US_ASCII));
        out.u32(1);
        out.string(graph.modelTypeId());

        Map<String,Integer> ordinals = new HashMap<>();
        for (int i = 0; i < graph.nodes().size(); i++) {
            String id = graph.nodes().get(i).id();
            if (id.isEmpty() || ordinals.putIfAbsent(id, i) != null) fail("MODEL_DUPLICATE_NODE");
        }

        out.u32(graph.nodes().size());
        for (int index = 0; index < graph.nodes().size(); index++) {
            Node node = graph.nodes().get(index);
            Signature sig = node.signature();
            if (node.constArgs().size() != sig.constKinds().size()) fail("MODEL_CONST_ARITY_MISMATCH");
            out.string(node.op());
            out.string(sig.abi());
            out.digest(sig.implDigest());
            out.u8(ordinal(sig.provenance(), new String[]{"verified-ores","trusted-native"}, "MODEL_INVALID_OP_SIGNATURE"));
            out.u8(ordinal(sig.effect(), new String[]{"pure","io","network","random","clock","actor","ffi","suspend"},
                    "MODEL_INVALID_OP_SIGNATURE"));
            out.u8(sig.vjp() ? 1 : 0);
            out.u32(sig.constKinds().size());
            for (String kind : sig.constKinds())
                out.u8(ordinal(kind, new String[]{"bool","i64","f64bits","string","symbol"}, "MODEL_INVALID_OP_SIGNATURE"));
            out.u32(node.constArgs().size());
            for (int i = 0; i < node.constArgs().size(); i++) {
                if (!node.constArgs().get(i).kind().equals(sig.constKinds().get(i)))
                    fail("MODEL_CONST_TYPE_MISMATCH");
                encodeConstant(out, node.constArgs().get(i));
            }
            // Ordered v1 permits exactly one unmatched signature input on
            // the first node: that slot is the model's external run(input)
            // boundary. Every later signature input must have a graph parent.
            boolean externalUnaryInput = index == 0
                    && node.inputs().isEmpty()
                    && sig.inputs().size() == 1;
            if (!externalUnaryInput && node.inputs().size() != sig.inputs().size())
                fail("MODEL_PORT_ARITY_MISMATCH");
            if (index == 0 && node.inputs().isEmpty() && sig.inputs().size() > 1)
                fail("MODEL_PORT_ARITY_MISMATCH");
            out.u32(node.inputs().size());
            for (int edge = 0; edge < node.inputs().size(); edge++) {
                String parent = node.inputs().get(edge);
                Integer p = ordinals.get(parent);
                if (p == null || p >= index) fail("MODEL_FORWARD_OR_CYCLIC_EDGE");
                Port upstream = graph.nodes().get(p).signature().output();
                if (!samePort(upstream, sig.inputs().get(edge)))
                    fail("MODEL_PORT_TYPE_MISMATCH");
                out.u32(p);
            }
            out.u32(sig.inputs().size());
            for (Port input : sig.inputs()) encodePort(out, input);
            encodePort(out, sig.output());
            out.u64(sig.scratchBytes());
            out.u8(sig.profile().freestanding() ? 1 : 0);
            out.u8(ordinal(sig.profile().allocation(), new String[]{"none","scratch","dynamic"}, "MODEL_INVALID_OP_PROFILE"));
            out.u8(sig.profile().deterministic() ? 1 : 0);
        }

        out.u32(graph.outputs().size());
        Map<String,Boolean> seen = new HashMap<>();
        for (String output : graph.outputs()) {
            Integer n = ordinals.get(output);
            if (n == null) fail("MODEL_UNKNOWN_OUTPUT");
            if (seen.putIfAbsent(output, Boolean.TRUE) != null) fail("MODEL_DUPLICATE_OUTPUT");
            out.u32(n);
        }
        return out.finish();
    }

    public static String digest(Graph graph) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(graph));
            char[] chars = new char[64];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                int b = hash[i] & 0xff;
                chars[i*2] = hex[b >>> 4];
                chars[i*2+1] = hex[b & 15];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
