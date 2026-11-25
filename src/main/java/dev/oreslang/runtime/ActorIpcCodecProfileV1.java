package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Proposed opt-in codec descriptor commitment for the trusted build attestation.
 * Not a replacement for legacy wire codec digests and not a codec implementation.
 * Labels are configuration values only. All metadata is trusted registration input.
 */
record ActorIpcCodecProfileV1(String codec, long version, String schema,
        String shapeSha256, String messageAbiSha256) {
    ActorIpcCodecProfileV1 {
        if (!"messagepack".equals(codec) && !"protobuf".equals(codec)
                && !"cbor".equals(codec) && !"flatbuffers".equals(codec))
            throw new IllegalArgumentException("unknown codec");
        if (version <= 0 || version > 0xffff_ffffL || schema == null
                || schema.isEmpty() || schema.length() > 256)
            throw new IllegalArgumentException("invalid codec profile");
        for (int i = 0; i < schema.length(); i++)
            if (schema.charAt(i) < 33 || schema.charAt(i) > 126)
                throw new IllegalArgumentException("invalid schema identifier");
        if (!digestValid(shapeSha256) || !digestValid(messageAbiSha256))
            throw new IllegalArgumentException("invalid profile digest");
    }

    private static boolean digestValid(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        }
        return true;
    }

    // Newline separators cannot occur in any admitted field. Domain-separated
    // ASCII preimage; stable decimal version, no trailing newline ambiguity.
    String commitment() {
        String descriptor = "atto.ipc.codec-profile.v1\n" + codec + "\n" + version
                + "\n" + schema + "\n" + shapeSha256 + "\n" + messageAbiSha256 + "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(descriptor.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    // Only a trusted registry may supply supported/allowed masks. A mask
    // is not evidence of an implemented codec, and cannot be derived from DATA.
    void requireAdmitted(int allowed, int supported, String attestedCodec, String attestedAbi) {
        if (((allowed | supported) & ~15) != 0)
            throw new IllegalArgumentException("invalid codec policy mask");
        int bit = switch (codec) {
            case "messagepack" -> 1;
            case "protobuf" -> 2;
            case "cbor" -> 4;
            case "flatbuffers" -> 8;
            default -> throw new IllegalStateException("invalid codec");
        };
        if ((allowed & supported & bit) == 0)
            throw new SecurityException("codec not allowed and supported");
        if (!digestValid(attestedCodec) || !digestValid(attestedAbi)
                || !commitment().equals(attestedCodec) || !messageAbiSha256.equals(attestedAbi))
            throw new SecurityException("artifact codec or ABI commitment mismatch");
    }
}
