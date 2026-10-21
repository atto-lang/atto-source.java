package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

final class DurableAtomicFileIoTest {
    @TempDir Path root;

    @Test
    void helperForcesFileBeforeRenameAndDirectoryAfterRename() throws Exception {
        Path target = root.resolve("state.wal");
        Files.writeString(target, "old", StandardCharsets.UTF_8);
        List<String> order = new ArrayList<>();

        NativeIo.replaceTextDurably(
                root,
                target,
                "new",
                new NativeIo.DurableWriteSync() {
                    @Override
                    public void forceFile(Path staged) throws IOException {
                        assertEquals("old", Files.readString(target));
                        assertEquals("new", Files.readString(staged));
                        order.add("file");
                    }

                    @Override
                    public void forceDirectory(Path directory) throws IOException {
                        assertEquals(root, directory);
                        assertEquals("new", Files.readString(target));
                        order.add("directory");
                    }
                });

        assertEquals(List.of("file", "directory"), order);
        assertEquals("new", Files.readString(target));
    }

    @Test
    void fileForceFailureNeverReplacesAcknowledgedTarget() throws Exception {
        Path target = root.resolve("state-before.wal");
        Files.writeString(target, "old", StandardCharsets.UTF_8);

        IOException failure = assertThrows(
                IOException.class,
                () -> NativeIo.replaceTextDurably(
                        root,
                        target,
                        "new",
                        new NativeIo.DurableWriteSync() {
                            @Override
                            public void forceFile(Path staged) throws IOException {
                                throw new IOException("file-force-failed");
                            }

                            @Override
                            public void forceDirectory(Path directory) {
                                fail("directory force cannot run after file-force failure");
                            }
                        }));

        assertEquals("file-force-failed", failure.getMessage());
        assertEquals("old", Files.readString(target));
    }

    @Test
    void directoryForceFailureIsExplicitlyAnUncertainPostReplaceOutcome()
            throws Exception {
        Path target = root.resolve("state-after.wal");
        Files.writeString(target, "old", StandardCharsets.UTF_8);

        IOException failure = assertThrows(
                IOException.class,
                () -> NativeIo.replaceTextDurably(
                        root,
                        target,
                        "new",
                        new NativeIo.DurableWriteSync() {
                            @Override
                            public void forceFile(Path staged) {
                                // Test hook: successful file durability proof.
                            }

                            @Override
                            public void forceDirectory(Path directory)
                                    throws IOException {
                                assertEquals("new", Files.readString(target));
                                throw new IOException("directory-force-failed");
                            }
                        }));

        assertEquals("directory-force-failed", failure.getMessage());
        assertEquals(
                "new",
                Files.readString(target),
                "rename may already be visible; caller must treat failure as uncertain");
    }

    @Test
    void guestDurableAtomicWriteUsesScopedFilesystemAuthority() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"),
                "directory fsync is intentionally fail-closed on unsupported hosts");

        Path target = root.resolve("guest-state.wal");
        Path source = root.resolve("program.ores");
        String escaped = target.toString().replace("\\", "\\\\");
        Files.writeString(source, """
                pub routine main(): void {
                  fs.write_text_durable_atomic("%s", "first");
                  fs.write_text_durable_atomic("%s", "second");
                  return;
                }
                """.formatted(escaped, escaped));

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString())
                .withAllowed(RuntimePermissions.Permission.READ, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(
                        IsolatePolicy.Capability.FILESYSTEM_WRITE,
                        IsolatePolicy.Capability.FILESYSTEM_READ);

        LinkedProgramRunner.run(
                source,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                System.getenv(),
                new ByteArrayOutputStream(),
                new ByteArrayOutputStream());

        assertEquals("second", Files.readString(target));
    }
}
