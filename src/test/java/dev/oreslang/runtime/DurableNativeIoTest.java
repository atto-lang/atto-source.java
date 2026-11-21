package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class DurableNativeIoTest {
    @TempDir Path root;

    private void requireUnixDurabilityBackend() throws Exception {
        Assumptions.assumeTrue(
                Files.getFileStore(root).supportsFileAttributeView("unix"),
                "M1 durable filesystem contract requires Unix link metadata");
    }

    private String run(String program) throws Exception {
        Path source = root.resolve("durable-test.ores");
        Files.writeString(source, program, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);
        LinkedProgramRunner.run(
                source,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                System.getenv(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String q(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    @Test
    void durableAppendAndAtomicReplaceExecuteRealForceBoundaries()
            throws Exception {
        requireUnixDurabilityBackend();
        Path wal = root.resolve("state.wal");
        Path checkpoint = root.resolve("checkpoint.json");

        String program = """
                pub routine main(): void {
                  fs.append_text_durable("%s", "frame-1\\n");
                  fs.append_text_durable("%s", "frame-2\\n");
                  fs.write_text_atomic_durable("%s", "checkpoint-1");
                  fs.write_text_atomic_durable("%s", "checkpoint-2");
                  return;
                }
                """.formatted(q(wal), q(wal), q(checkpoint), q(checkpoint));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        run(program);

        assertEquals("frame-1\nframe-2\n", Files.readString(wal));
        assertEquals("checkpoint-2", Files.readString(checkpoint));
        try (var children = Files.list(root)) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString()
                            .startsWith(".oreslang-durable-")),
                    "durable atomic replace must not leak temp files");
        }
    }

    @Test
    void actorStateMutationCanBeSequencedAfterDurableWalAppend()
            throws Exception {
        requireUnixDurabilityBackend();
        Path wal = root.resolve("actor-state.wal");
        String program = """
                define actor DurableState as
                  state {
                    revision: int = 0;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "commit"; then
                      self.handle_state(|state| -> {
                        fs.append_text_durable("%s", "revision=1\\n");
                        state.revision = 1;
                      });
                    elif mail.value == "verify"; then
                      self.handle_state(|state| -> {
                        if state.revision == 1; do
                          stdio.stdout.write("durable-state-ok");
                        fi
                      });
                    elif mail.value == "shutdown"; then
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val authority = spawn DurableState();
                  await authority.get_ready();
                  authority.send("commit");
                  authority.send("verify");
                  authority.send("shutdown");
                  await authority.get_done();
                  return;
                }
                """.formatted(q(wal));

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("durable-state-ok", run(program));
        assertEquals("revision=1\n", Files.readString(wal));
    }

    @Test
    void durableAppendRejectsHardLinkedWalAuthority()
            throws Exception {
        requireUnixDurabilityBackend();
        Path wal = root.resolve("state.wal");
        Path alias = root.resolve("alias.wal");
        Files.writeString(wal, "base\n");
        Files.createLink(alias, wal);

        String program = """
                pub routine main(): void {
                  fs.append_text_durable("%s", "forged\\n");
                  return;
                }
                """.formatted(q(alias));

        RuntimeException failure = assertThrows(
                RuntimeException.class, () -> run(program));
        assertTrue(
                String.valueOf(failure.getMessage()).contains("hard-linked")
                        || String.valueOf(failure.getCause()).contains("hard-linked"),
                String.valueOf(failure));
        assertEquals("base\n", Files.readString(wal));
        assertEquals("base\n", Files.readString(alias));
    }

    @Test
    void checkedDurableWritesExposeUncertainOutcomeWithoutPublishingSuccess()
            throws Exception {
        requireUnixDurabilityBackend();
        Path wal = root.resolve("uncertain.wal");
        String append = """
                pub routine main(): void {
                  const ok = fs.append_text_durable_checked("%s", "frame-1\\n");
                  if !ok; do
                    stdio.stdout.write("append-uncertain");
                  fi
                  return;
                }
                """.formatted(q(wal));

        NativeIo.injectDurabilityFaultForTest("append-after-force");
        try {
            assertEquals("append-uncertain", run(append));
        } finally {
            NativeIo.clearDurabilityFaultForTest();
        }
        assertEquals("frame-1\n", Files.readString(wal),
                "uncertain means durable bytes may already be visible");

        Path checkpoint = root.resolve("uncertain-checkpoint");
        String atomic = """
                pub routine main(): void {
                  const ok = fs.write_text_atomic_durable_checked("%s", "cp-1");
                  if !ok; do
                    stdio.stdout.write("atomic-uncertain");
                  fi
                  return;
                }
                """.formatted(q(checkpoint));

        NativeIo.injectDurabilityFaultForTest("atomic-after-move");
        try {
            assertEquals("atomic-uncertain", run(atomic));
        } finally {
            NativeIo.clearDurabilityFaultForTest();
        }
        assertEquals("cp-1", Files.readString(checkpoint),
                "atomic uncertainty after move may leave replacement visible");
    }

    @Test
    void exclusiveLeaseIsContextLifetimeAndBlocksCompetingOwner()
            throws Exception {
        requireUnixDurabilityBackend();
        Path lease = root.resolve("state.wal.lock");

        String program = """
                pub routine main(): void {
                  const first = fs.acquire_exclusive_lease("%s");
                  const second = fs.acquire_exclusive_lease("%s");
                  if first && !second; do
                    stdio.stdout.write("lease-held");
                  fi
                  return;
                }
                """.formatted(q(lease), q(lease));

        assertEquals("lease-held", run(program));
        assertEquals("lease-held", run(program),
                "context close must release the kernel lease for restart");

        try (FileChannel channel = FileChannel.open(
                lease,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            String contended = """
                    pub routine main(): void {
                      if !fs.acquire_exclusive_lease("%s"); do
                        stdio.stdout.write("lease-busy");
                      fi
                      return;
                    }
                    """.formatted(q(lease));
            assertEquals("lease-busy", run(contended));
        }

        assertEquals("lease-held", run(program),
                "lease must be acquirable after competing owner releases it");
    }

    @Test
    void durableWritesRejectTerminalSymlinkDestinations()
            throws Exception {
        requireUnixDurabilityBackend();
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path outsideWal = outside.resolve("outside.wal");
        Files.writeString(outsideWal, "outside\n");
        Path link = root.resolve("state-link.wal");
        Files.createSymbolicLink(link, outsideWal);

        String appendProgram = """
                pub routine main(): void {
                  fs.append_text_durable("%s", "forged\\n");
                  return;
                }
                """.formatted(q(link));
        assertThrows(RuntimeException.class, () -> run(appendProgram));
        assertEquals("outside\n", Files.readString(outsideWal));

        String replaceProgram = """
                pub routine main(): void {
                  fs.write_text_atomic_durable("%s", "forged");
                  return;
                }
                """.formatted(q(link));
        assertThrows(RuntimeException.class, () -> run(replaceProgram));
        assertEquals("outside\n", Files.readString(outsideWal));
        assertTrue(Files.isSymbolicLink(link));
    }
}
