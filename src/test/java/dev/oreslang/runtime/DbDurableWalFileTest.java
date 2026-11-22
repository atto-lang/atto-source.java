package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Host filesystem contract only: not an Oreslang guest or power-loss test. */
final class DbDurableWalFileTest {
    @TempDir Path root;

    // The WAL requires a physical, non-aliased directory; on macOS the JUnit
    // temp root lives below the /var -> /private/var system alias.
    @org.junit.jupiter.api.BeforeEach
    void usePhysicalTempRoot() throws java.io.IOException {
        root = root.toRealPath();
    }

    @Test void invalidOpenParametersFailBeforeAnyShardReservation() {
        assertThrows(IllegalArgumentException.class,
                () -> DbDurableWalFile.requireValidOpenArguments("../escape", 1));
        assertThrows(IllegalArgumentException.class,
                () -> DbDurableWalFile.requireValidOpenArguments("nested/wal", 1));
        assertThrows(IllegalArgumentException.class,
                () -> DbDurableWalFile.requireValidOpenArguments("wal.odb", 0));
        assertThrows(IllegalArgumentException.class,
                () -> DbDurableWalFile.requireValidOpenArguments("..", 1));
        assertDoesNotThrow(
                () -> DbDurableWalFile.requireValidOpenArguments("wal.odb", 1));
    }

    @Test void onlyCompletedForceReturnsAReceipt() throws Exception {
        byte[] frame = new byte[31];
        Arrays.fill(frame, (byte)0x5a);
        try (var wal = DbDurableWalFile.openHostOnly(root, "wal.odb", 5)) {
            assertEquals(0, wal.size());
            assertThrows(SecurityException.class, () -> wal.appendAndSync(4, 17, frame));
            assertThrows(SecurityException.class, () -> wal.appendAndSync(5, 0, frame));
            assertThrows(IllegalArgumentException.class,
                    () -> wal.appendAndSync(5, 17, new byte[30]));
            assertThrows(IllegalArgumentException.class,
                    () -> wal.appendAndSync(5, 17, new byte[1119]));
            assertThrows(IOException.class,
                    () -> DbDurableWalFile.openHostOnly(root, "wal.odb", 6));
            var receipt = wal.appendAndSync(5, 17, frame);
            assertEquals(5, receipt.generation());
            assertEquals(17, receipt.operationId());
            assertEquals(0, receipt.begin());
            assertEquals(31, receipt.end());
            assertEquals(31, wal.size());
            assertFalse(wal.uncertain());
        }
        try (var reopened = DbDurableWalFile.openHostOnly(root, "wal.odb", 6)) {
            var receipt = reopened.appendAndSync(6, 18, frame);
            assertEquals(31, receipt.begin());
            assertEquals(62, receipt.end());
        }
        assertEquals(62, Files.size(root.resolve("wal.odb")));
    }

    @Test void symlinkHardlinkAndTraversalFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> DbDurableWalFile.openHostOnly(root, "../out", 1));
        assertThrows(IllegalArgumentException.class,
                () -> DbDurableWalFile.openHostOnly(root, "a/b", 1));
        assertThrows(IllegalArgumentException.class,
                () -> DbDurableWalFile.openHostOnly(root, "a\\b", 1));
        Path target = Files.createFile(root.resolve("base"));
        Files.createSymbolicLink(root.resolve("link"), target);
        assertThrows(IOException.class,
                () -> DbDurableWalFile.openHostOnly(root, "link", 1));
        Files.createLink(root.resolve("hardlink"), target);
        assertThrows(SecurityException.class,
                () -> DbDurableWalFile.openHostOnly(root, "hardlink", 1));
    }

    @Test void rejectsSymlinkedParentBeforeCreatingWal() throws Exception {
        Path physical = Files.createDirectory(root.resolve("private"));
        Path alias = root.resolve("alias");
        Files.createSymbolicLink(alias, physical);
        assertThrows(SecurityException.class,
                () -> DbDurableWalFile.openHostOnly(alias, "wal.odb", 1));
        assertFalse(Files.exists(physical.resolve("wal.odb")),
                "untrusted directory alias must not create a WAL");
    }

    @Test void directoryForceIsAvailableOnThisTestHost() throws Exception {
        // The production open path now makes this a mandatory operation;
        // an unsupported target must reject open instead of claiming sync.
        try (var dir = java.nio.channels.FileChannel.open(root,
                java.util.Set.of(java.nio.file.StandardOpenOption.READ,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
            dir.force(true);
        }
        try (var wal = DbDurableWalFile.openHostOnly(root, "new.odb", 1)) {
            assertEquals(0, wal.size());
        }
        assertTrue(Files.isRegularFile(root.resolve("new.odb")));
    }

    @Test void cannotUseClosedHandle() throws Exception {
        var wal = DbDurableWalFile.openHostOnly(root, "wal.odb", 1);
        wal.close();
        wal.close();
        assertThrows(IllegalStateException.class,
                () -> wal.appendAndSync(1, 1, new byte[31]));
    }
}
