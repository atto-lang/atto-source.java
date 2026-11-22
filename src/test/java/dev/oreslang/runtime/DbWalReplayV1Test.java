package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Host-only read-only scanner tests; not a guest ACK or power-cut proof. */
final class DbWalReplayV1Test {
    @TempDir Path root;

    // The WAL requires a physical, non-aliased directory; on macOS the JUnit
    // temp root lives below the /var -> /private/var system alias.
    @org.junit.jupiter.api.BeforeEach
    void usePhysicalTempRoot() throws java.io.IOException {
        root = root.toRealPath();
    }

    private static void u16(byte[] b, int p, int v) {
        b[p] = (byte) v; b[p + 1] = (byte) (v >>> 8);
    }
    private static void u32(byte[] b, int p, int v) {
        u16(b, p, v); u16(b, p + 2, v >>> 16);
    }
    private static byte[] frame(int seq, int op) {
        byte[] b = new byte[31];
        b[0]=79; b[1]=68; b[2]=66; b[3]=49; b[4]=1; b[5]=1;
        u32(b, 6, 7); u32(b, 10, 4); u32(b, 14, seq); u32(b, 18, op);
        u16(b, 22, 1); u16(b, 24, 0); b[26]=(byte)op;
        int a=1,c=0;
        for(int i=0;i<27;++i){a=(a+(b[i]&255))%65521;c=(c+a)%65521;}
        u16(b,27,a); u16(b,29,c);
        return b;
    }
    // Shared deterministic ODB1 wire corpus with the LLVM host regression.
    // Checksums are fixed oracle values independently calculated for these
    // header/payload bytes, not merely rederived in an assertion.
    private static byte[] boundaryFrame(
            int kind, int seq, int operation, int keyLength, int valueLength) {
        byte[] wire = new byte[26 + keyLength + valueLength + 4];
        wire[0] = 79; wire[1] = 68; wire[2] = 66; wire[3] = 49;
        wire[4] = 1; wire[5] = (byte) kind;
        u32(wire, 6, 7); u32(wire, 10, 4);
        u32(wire, 14, seq); u32(wire, 18, operation);
        u16(wire, 22, keyLength); u16(wire, 24, valueLength);
        for (int i = 0; i < keyLength; i++)
            wire[26 + i] = (byte) (i * 17 + 3);
        for (int i = 0; i < valueLength; i++)
            wire[26 + keyLength + i] = (byte) (i * 29 + 11);
        int a = 1, b = 0;
        for (int i = 0; i < wire.length - 4; i++) {
            a = (a + (wire[i] & 255)) % 65521;
            b = (b + a) % 65521;
        }
        u16(wire, wire.length - 4, a);
        u16(wire, wire.length - 2, b);
        return wire;
    }

    @Test void maximumPutAndDeleteTornTailDifferentialCorpus() throws Exception {
        byte[] first = boundaryFrame(1, 1, 41, 1, 0);
        byte[] maximum = boundaryFrame(1, 2, 42, 64, 1024);
        byte[] deletion = boundaryFrame(2, 3, 43, 64, 0);
        assertEquals(31, first.length);
        assertEquals(1118, maximum.length);
        assertEquals(94, deletion.length);
        assertArrayEquals(new byte[]{0x42, 0x1d, 0x02, (byte) 0x86},
                java.util.Arrays.copyOfRange(maximum, 1114, 1118),
                "independent maximum-size checksum oracle");
        assertArrayEquals(new byte[]{0x23, 0x1f, (byte) 0xdf, 0x0a},
                java.util.Arrays.copyOfRange(deletion, 90, 94),
                "independent delete checksum oracle");
        Path path = root.resolve("boundary.odb");
        try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(first), 0);
            file.write(ByteBuffer.wrap(maximum), 31);
            file.write(ByteBuffer.wrap(deletion), 1149);
            var full = DbWalReplayV1.scan(file, 7, 4);
            assertEquals(3, full.records());
            assertEquals(1243, full.validBytes());
            assertEquals(3, full.lastSequence());
            assertEquals(43, full.lastOperation());
            assertFalse(full.incompleteTail());

            // A max-size WAL write can be interrupted after ANY byte.
            // Only its complete, checksummed form is accepted as a record.
            for (int n = 1; n < maximum.length; n++) {
                file.truncate(31);
                file.write(ByteBuffer.wrap(maximum, 0, n), 31);
                var torn = DbWalReplayV1.scan(file, 7, 4);
                assertEquals(1, torn.records(), "prefix " + n);
                assertEquals(31, torn.validBytes());
                assertTrue(torn.incompleteTail(), "prefix " + n);
                assertEquals(31 + n, file.size(),
                        "replay must never truncate an unknown tail");
            }
            file.truncate(31);
            byte[] corrupt = maximum.clone();
            corrupt[1117] ^= 1;
            file.write(ByteBuffer.wrap(corrupt), 31);
            assertThrows(IllegalArgumentException.class,
                    () -> DbWalReplayV1.scan(file, 7, 4),
                    "complete maximum frame with corrupt checksum");
            assertEquals(1149, file.size());

            file.truncate(31);
            byte[] illegalDelete = boundaryFrame(2, 2, 42, 64, 1);
            file.write(ByteBuffer.wrap(illegalDelete), 31);
            assertThrows(IOException.class,
                    () -> DbWalReplayV1.scan(file, 7, 4),
                    "DELETE record must not carry a value");
            assertEquals(31 + illegalDelete.length, file.size());

            file.truncate(31);
            byte[] oversized = boundaryFrame(1, 2, 42, 64, 1025);
            file.write(ByteBuffer.wrap(oversized), 31);
            assertThrows(IOException.class,
                    () -> DbWalReplayV1.scan(file, 7, 4),
                    "1025-byte values exceed the ODB1 maximum");
            assertEquals(31 + oversized.length, file.size());
        }
    }

    @Test void incompleteTailRequiresValidHeaderPrefixAndOwner() throws Exception {
        byte[] a = frame(1, 41), b = frame(2, 42), c = frame(3, 43);
        Path p = root.resolve("partial.odb");
        try (var file = FileChannel.open(p, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(a), 0);
            file.write(ByteBuffer.wrap(b), 31);
            for (int prefix = 1; prefix < c.length; prefix++) {
                file.truncate(62);
                file.write(ByteBuffer.wrap(c, 0, prefix), 62);
                var tail = DbWalReplayV1.scan(file, 7, 4);
                assertEquals(2, tail.records());
                assertEquals(62, tail.validBytes());
                assertTrue(tail.incompleteTail(), "valid prefix " + prefix);
                assertEquals(62 + prefix, file.size(),
                        "scanner never repairs/truncates in place");
            }
            file.truncate(62);
            file.write(ByteBuffer.wrap(new byte[]{0}), 62);
            assertThrows(IOException.class, () -> DbWalReplayV1.scan(file, 7, 4),
                    "one corrupt magic byte is not a repairable torn tail");

            for (int field : new int[]{0, 4, 5, 6, 10, 14, 18, 22, 24}) {
                byte[] damaged = c.clone();
                switch (field) {
                    case 0 -> damaged[0] = 0;
                    case 4 -> damaged[4] = 0;
                    case 5 -> damaged[5] = 3;
                    case 6 -> u32(damaged, 6, 8);
                    case 10 -> u32(damaged, 10, 5);
                    case 14 -> u32(damaged, 14, 4);
                    case 18 -> u32(damaged, 18, 42);
                    case 22 -> u16(damaged, 22, 0);
                    case 24 -> u16(damaged, 24, 65535);
                    default -> throw new AssertionError(field);
                }
                file.truncate(62);
                file.write(ByteBuffer.wrap(damaged, 0, 26), 62);
                assertThrows(IOException.class,
                        () -> DbWalReplayV1.scan(file, 7, 4),
                        "a malformed incomplete header must fail: " + field);
                assertEquals(88, file.size(), "replay is always read-only");
            }
            // Each partially present u32 identity/sequence byte MUST be
            // validated as soon as its prefix appears. Full-header-only
            // negatives would otherwise miss this early tail admission bug.
            for (int position = 6; position <= 17; position++) {
                byte[] damaged = c.clone();
                damaged[position] ^= 1;
                int prefix = position + 1;
                file.truncate(62);
                file.write(ByteBuffer.wrap(damaged, 0, prefix), 62);
                assertThrows(IOException.class,
                        () -> DbWalReplayV1.scan(file, 7, 4),
                        "partially present identity/sequence byte " + position);
                assertEquals(62 + prefix, file.size());
            }
        }
    }

    @Test void reopenTailCorruptionAndDuplicateDetection() throws Exception {
        Path p = root.resolve("wal.odb");
        byte[] a=frame(1,41), b=frame(2,42), c=frame(3,43);
        try(var file=FileChannel.open(p,StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)){
            file.write(ByteBuffer.wrap(a)); file.write(ByteBuffer.wrap(b));
            file.force(true);
        }
        try(var file=FileChannel.open(p,StandardOpenOption.READ,
                StandardOpenOption.WRITE)){
            var ok=DbWalReplayV1.scan(file,7,4);
            assertEquals(2,ok.records()); assertEquals(62,ok.validBytes());
            assertFalse(ok.incompleteTail());
            file.write(ByteBuffer.wrap(c,0,15),62);
            var tail=DbWalReplayV1.scan(file,7,4);
            assertEquals(2,tail.records()); assertEquals(62,tail.validBytes());
            assertTrue(tail.incompleteTail());
            assertEquals(77,file.size(), "scan must not auto-truncate");
            file.truncate(62); // test-owned exclusive repair; never scanner action
            byte[] broken=b.clone();broken[26]^=1;
            file.write(ByteBuffer.wrap(broken),31);
            assertThrows(IllegalArgumentException.class,
                    ()->DbWalReplayV1.scan(file,7,4));
            file.write(ByteBuffer.wrap(frame(2,41)),31);
            assertThrows(IOException.class,()->DbWalReplayV1.scan(file,7,4));
            file.write(ByteBuffer.wrap(frame(4,42)),31);
            assertThrows(IOException.class,()->DbWalReplayV1.scan(file,7,4));
            file.write(ByteBuffer.wrap(b),31);
            assertThrows(SecurityException.class,()->DbWalReplayV1.scan(file,8,4));
            assertThrows(SecurityException.class,()->DbWalReplayV1.scan(file,7,5));
            assertEquals(2,DbWalReplayV1.scan(file,7,4).records());
        }
        // Recovery is exposed only through a locked trusted file owner, not
        // a user-created guest handle. The new owner has generation 5 but
        // validates the previous generation-4 single-segment WAL.
        try (var owner = DbDurableWalFile.openHostOnly(root, "wal.odb", 5)) {
            assertEquals(2, owner.scanRecovery(7, 4).records());
            assertThrows(SecurityException.class, () -> owner.scanRecovery(8, 4));
            assertThrows(SecurityException.class, () -> owner.scanRecovery(7, 5));
        }
    }

    // Seven malformed complete ODB1 frames with freshly computed checksums.
    // Structural validation MUST reject even when Adler32 is correct.
    @Test void rejectsRechecksummedMalformedCompleteHeadersWithoutMutation() throws Exception {
        byte[] first = boundaryFrame(1, 1, 41, 1, 0);
        byte[] valid = boundaryFrame(1, 2, 42, 1, 1);
        Path path = root.resolve("forged-header.odb");
        try (var file = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.wrap(first), 0);
            for (int attempt = 0; attempt < 7; attempt++) {
                byte[] changed = valid.clone();
                switch (attempt) {
                    case 0 -> changed[4] = 2;                // wrong version
                    case 1 -> changed[5] = 2;                // DELETE cannot carry value
                    case 2 -> changed[21] = (byte) 128;      // signed-u32 high bit
                    case 3 -> changed[22] = 0;               // zero key length
                    case 4 -> changed[22] = 65;              // key length > 64
                    case 5 -> { changed[24] = 1; changed[25] = 4; } // value=1025
                    case 6 -> changed[0] = 80;               // wrong magic
                    default -> throw new AssertionError(attempt);
                }
                int a = 1, b = 0;
                for (int i = 0; i < changed.length - 4; i++) {
                    a = (a + (changed[i] & 255)) % 65521;
                    b = (b + a) % 65521;
                }
                u16(changed, changed.length - 4, a);
                u16(changed, changed.length - 2, b);
                file.truncate(first.length);
                ByteBuffer complete = ByteBuffer.wrap(changed);
                long physicalOffset = first.length;
                while (complete.hasRemaining()) {
                    int written = file.write(complete, physicalOffset);
                    if (written <= 0)
                        throw new IOException("forged test fixture write stalled");
                    physicalOffset += written;
                }
                // A partially written frame could be (correctly) reported
                // as a torn tail. Never confuse that with structural rejection.
                assertEquals(first.length + changed.length, file.size(),
                        "test setup must write a FULL malformed frame");
                assertThrows(Exception.class, () -> DbWalReplayV1.scan(file, 7, 4),
                        "a checksummed but malformed header must fail: " + attempt);
                assertEquals(first.length + changed.length, file.size(),
                        "scanner must never truncate a malformed complete frame");
            }
        }
    }
}
