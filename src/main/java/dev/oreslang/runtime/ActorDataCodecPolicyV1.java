package dev.oreslang.runtime;

import java.util.Arrays;

/** Host-only policy primitive. Trusted registration supplies the masks/schema.
 * Not an authentication, decoder, actor admission or guest-language API. */
public final class ActorDataCodecPolicyV1 {
    private ActorDataCodecPolicyV1() {}
    public enum Reject { UnknownCodec, InvalidMask, Denied, Unsupported, InvalidBinding, Mismatch }
    public static final class PolicyException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final Reject reason;
        private PolicyException(Reject reason) { super(reason.name()); this.reason = reason; }
    }
    private static PolicyException reject(Reject reason) { return new PolicyException(reason); }
    private enum Codec {
        MESSAGEPACK(1), PROTOBUF(2), CBOR(4), FLATBUFFERS(8);
        final int bit;
        Codec(int bit) { this.bit = bit; }
    }
    private static Codec parse(String label) {
        if (label == null || label.length() > 11) return null;
        return switch (label) {
            case "messagepack" -> Codec.MESSAGEPACK;
            case "protobuf" -> Codec.PROTOBUF;
            case "cbor" -> Codec.CBOR;
            case "flatbuffers" -> Codec.FLATBUFFERS;
            default -> null;
        };
    }
    private static boolean schemaValid(String schema) {
        if (schema == null || schema.isEmpty() || schema.length() > 256) return false;
        for (int i = 0; i < schema.length(); i++) {
            char c = schema.charAt(i);
            if (c < 33 || c > 126) return false;
        }
        return true;
    }
    public static final class Binding {
        private final Codec codec;
        private final long version;
        private final String schema;
        private final byte[] shape;
        private Binding(Codec codec, long version, String schema, byte[] shape) {
            this.codec = codec; this.version = version; this.schema = schema;
            this.shape = shape.clone();
        }
        /** Match already authenticated metadata only; never use incoming codec to dispatch. */
        public void requireMatch(String label, long version, String schema, byte[] shape) {
            if (parse(label) != codec || version != this.version || !schemaValid(schema)
                    || !this.schema.equals(schema) || shape == null || shape.length != 32
                    || !Arrays.equals(this.shape, shape)) throw reject(Reject.Mismatch);
        }
    }
    /** Null requested means the default; empty or unknown explicit selection is rejected. */
    public static Binding select(String requested, int allowed, int supported,
                                 long version, String schema, byte[] shape) {
        if (((allowed | supported) & ~15) != 0) throw reject(Reject.InvalidMask);
        Codec codec = parse(requested == null ? "messagepack" : requested);
        if (codec == null) throw reject(Reject.UnknownCodec);
        if ((allowed & codec.bit) == 0) throw reject(Reject.Denied);
        if ((supported & codec.bit) == 0) throw reject(Reject.Unsupported);
        if (version <= 0 || version > 0xffff_ffffL || !schemaValid(schema)
                || shape == null || shape.length != 32) throw reject(Reject.InvalidBinding);
        return new Binding(codec, version, schema, shape);
    }
}
