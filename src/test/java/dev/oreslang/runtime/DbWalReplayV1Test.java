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
}
