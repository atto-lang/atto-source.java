package dev.oreslang.runtime;

/**
 * Host-only ODB1 WAL v1 frame validation. This is a checksum/structure
 * validator, NOT a signature, permission, durable receipt or guest ABI.
 * The caller must validate an already detached immutable copy of the
 * submitted frame and still authenticate its actor/shard capability.
 */
final class DbWalFrameV1 {
    private DbWalFrameV1() {}

    static void requireMatching(byte[] frame, int tenant, int generation,
            int operationId) {
        requireMatching(frame, frame == null ? 0 : frame.length,
                tenant, generation, operationId);
    }

    // Buffer-prefix variant for recovery scanners: callers may reuse a
    // bounded array and avoid per-record heap allocations. The VALID prefix,
    // not the array capacity, is the complete ODB1 frame.
    static void requireMatching(byte[] frame, int length, int tenant,
            int generation, int operationId) {
        if (frame == null || length < DbDurableWalFile.MIN_FRAME
                || length > DbDurableWalFile.MAX_FRAME || length > frame.length)
            throw new IllegalArgumentException("invalid WAL frame length");
        if (tenant <= 0 || generation <= 0 || operationId <= 0)
            throw new SecurityException("WAL caller identity invalid");
        if ((frame[0] & 255) != 79 || (frame[1] & 255) != 68
                || (frame[2] & 255) != 66 || (frame[3] & 255) != 49
                || (frame[4] & 255) != 1)
            throw new IllegalArgumentException("WAL magic/version mismatch");
        int kind = frame[5] & 255;
        if (kind != 1 && kind != 2)
            throw new IllegalArgumentException("WAL record kind invalid");
        // Reject the top bit before decoding little-endian signed u32 IDs.
        // This avoids accepting negative IDs through two's-complement wrap.
        if ((frame[9] & 128) != 0 || (frame[13] & 128) != 0
                || (frame[17] & 128) != 0 || (frame[21] & 128) != 0)
            throw new IllegalArgumentException("WAL high-bit ID rejected");
        int storedTenant = u32(frame, 6);
        int storedGeneration = u32(frame, 10);
        int sequence = u32(frame, 14);
        int storedOperation = u32(frame, 18);
        if (storedTenant <= 0 || storedGeneration <= 0
                || sequence <= 0 || storedOperation <= 0)
            throw new IllegalArgumentException("WAL zero ID or sequence");
        int keyLen = u16(frame, 22);
        int valLen = u16(frame, 24);
        if (keyLen < 1 || keyLen > 64 || valLen > 1024
                || (kind == 2 && valLen != 0)
                || length != 26 + keyLen + valLen + 4)
            throw new IllegalArgumentException("WAL payload length mismatch");
        int a = 1, b = 0;
        for (int i = 0; i < length - 4; ++i) {
            a = (a + (frame[i] & 255)) % 65521;
            b = (b + a) % 65521;
        }
        if (u16(frame, length - 4) != a
                || u16(frame, length - 2) != b)
            throw new IllegalArgumentException("WAL checksum mismatch");
        if (storedTenant != tenant || storedGeneration != generation
                || storedOperation != operationId)
            throw new SecurityException("WAL frame does not match shard grant");
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static int u32(byte[] bytes, int offset) {
        return u16(bytes, offset) | (u16(bytes, offset + 2) << 16);
    }
}
