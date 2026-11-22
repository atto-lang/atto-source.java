package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Host-only read-only ODB1 v1 recovery preflight. Caller must hold a trusted
 * exclusive file lease and not publish/replay state until scan succeeds.
 * This does NOT truncate a tail, authenticate a tenant or attest durability.
 * One bounded buffer is reused across all records (no per-frame allocation).
 */
final class DbWalReplayV1 {
    record Report(long validBytes, int records, int lastSequence,
                  int lastOperation, boolean incompleteTail) { }

    private DbWalReplayV1() { }

    static Report scan(FileChannel file, int tenant, int generation)
            throws IOException {
        Objects.requireNonNull(file);
        if (tenant <= 0 || generation <= 0)
            throw new SecurityException("WAL recovery owner invalid");
        long size = file.size();
        if (size < 0) throw new IOException("negative WAL length");
        byte[] bytes = new byte[DbDurableWalFile.MAX_FRAME];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long offset = 0;
        int records = 0, lastSeq = 0, lastOp = 0;
        while (offset < size) {
            long remaining = size - offset;
            if (remaining < 26) {
                // A short tail is not automatically a valid torn WAL frame.
                // Check all available magic, owner and sequence bytes.
                readAt(file, buffer, offset, 0, (int) remaining);
                validateHeaderPrefix(bytes, (int) remaining, tenant,
                        generation, lastSeq, lastOp);
                return new Report(offset, records, lastSeq, lastOp, true);
            }
            readAt(file, buffer, offset, 0, 26);
            validateHeaderPrefix(bytes, 26, tenant,
                    generation, lastSeq, lastOp);
            if ((bytes[0] & 255) != 79 || (bytes[1] & 255) != 68
                    || (bytes[2] & 255) != 66 || (bytes[3] & 255) != 49
                    || (bytes[4] & 255) != 1
                    || ((bytes[5] & 255) != 1 && (bytes[5] & 255) != 2))
                throw new IOException("corrupt ODB1 recovery header");
            int key = u16(bytes, 22), value = u16(bytes, 24);
            if (key < 1 || key > 64 || value > 1024
                    || ((bytes[5] & 255) == 2 && value != 0))
                throw new IOException("invalid WAL recovery header length");
            int length = 26 + key + value + 4;
            if (length > remaining)
                return new Report(offset, records, lastSeq, lastOp, true);
            readAt(file, buffer, offset, 26, length);
            int op = u32(bytes, 18);
            DbWalFrameV1.requireMatching(bytes, length, tenant, generation, op);
            int seq = u32(bytes, 14);
            if (lastSeq == 0 ? seq != 1 :
                    (lastSeq == Integer.MAX_VALUE || seq != lastSeq + 1))
                throw new IOException("WAL recovery sequence gap/reorder");
            if (op <= lastOp || records == Integer.MAX_VALUE)
                throw new IOException("WAL recovery duplicate op/overflow");
            offset += length;
            ++records;
            lastSeq = seq;
            lastOp = op;
        }
        return new Report(offset, records, lastSeq, lastOp, false);
    }

    /** Reject malformed prefixes before treating a torn final frame as repairable. */
    private static void validateHeaderPrefix(byte[] b, int length,
            int tenant, int generation, int previousSeq, int previousOp)
            throws IOException {
        int[] magic = {79, 68, 66, 49, 1};
        for (int i = 0; i < Math.min(length, magic.length); i++) {
            if ((b[i] & 255) != magic[i])
                throw new IOException("corrupt truncated WAL header");
        }
        if (length > 5 && (b[5] != 1 && b[5] != 2))
            throw new IOException("invalid truncated WAL kind");
        if (previousSeq == Integer.MAX_VALUE)
            throw new IOException("WAL sequence exhausted");
        requireFieldPrefix(b, length, 6, tenant);
        requireFieldPrefix(b, length, 10, generation);
        requireFieldPrefix(b, length, 14, previousSeq + 1);
        if (length >= 22
                && ((b[21] & 128) != 0 || u32(b, 18) <= previousOp))
            throw new IOException("WAL operation is not increasing");
        if (length >= 24) {
            int keyLength = u16(b, 22);
            if (keyLength < 1 || keyLength > 64)
                throw new IOException("invalid WAL key length in tail");
        }
        if (length >= 26) {
            int valueLength = u16(b, 24);
            if (valueLength > 1024 || (b[5] == 2 && valueLength != 0))
                throw new IOException("invalid WAL value length in tail");
        }
    }

    private static void requireFieldPrefix(
            byte[] b, int available, int offset, int expected)
            throws IOException {
        for (int i = 0; i < 4 && offset + i < available; i++) {
            if ((b[offset + i] & 255) != ((expected >>> (8 * i)) & 255))
                throw new IOException("WAL identity/sequence prefix mismatch");
        }
    }

    private static int u16(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8);
    }

    private static int u32(byte[] b, int p) {
        return u16(b, p) | (u16(b, p + 2) << 16);
    }

    private static void readAt(FileChannel file, ByteBuffer buffer,
            long base, int from, int until) throws IOException {
        buffer.position(from);
        buffer.limit(until);
        while (buffer.hasRemaining()) {
            int n = file.read(buffer, base + buffer.position());
            if (n <= 0)
                throw new IOException("WAL modified during recovery");
        }
    }
}
