package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Async file I/O v1 (spec chapter 26): an append-oriented, positionally
 * readable binary file with fsync, used for logs and journals.
 */
final class AttoBinaryFileGuestTest {

    private static String run(String program, Path dir) throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        Path root = dir.toRealPath();
        Path sourceFile = root.resolve("main.atto");
        Files.writeString(sourceFile, program);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ, IsolatePolicy.Capability.FILESYSTEM_WRITE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(), out, new ByteArrayOutputStream());
        return out.toString(StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    private static String chain(Throwable failure) {
        StringBuilder rendered = new StringBuilder();
        for (int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause()) {
            rendered.append(failure.getMessage()).append('\n');
        }
        return rendered.toString();
    }

    private static String program(Path dir, String body) throws Exception {
        String path = dir.toRealPath().resolve("log.bin").toString();
        return body.replace("__PATH__", path);
    }

    @Test
    void appendReadSyncTruncateAndReopen(@TempDir Path dir) throws Exception {
        String source = program(dir, """
                pub async fnc writer(BinaryFile f): int {
                  val at = await f.append_async(Bytes.from_string("third"));
                  await f.sync_async();
                  await f.close_async();
                  return at;
                }
                pub async routine main(): void {
                  val f = await fs.open_binary_async("__PATH__");
                  val first = await f.append_async(Bytes.from_string("hello"));
                  val second = await f.append_async(Bytes.from_list([0, 255, 13, 10]));
                  await f.sync_async();
                  stdio.println(first);
                  stdio.println(second);
                  stdio.println(await f.size_async());
                  stdio.println((await f.read_at_async(0, 5)).to_utf8().unwrap());
                  stdio.println((await f.read_at_async(5, 100)).size());
                  stdio.println((await f.read_at_async(50, 10)).size());
                  await f.truncate_async(5);
                  stdio.println(await f.size_async());
                  stdio.println(await writer(f));
                  val again = await fs.open_binary_async("__PATH__");
                  stdio.println((await again.read_at_async(0, 64)).to_utf8().unwrap());
                  await again.close_async();
                  return;
                }
                """);
        assertEquals(String.join("\n", "0", "5", "9", "hello", "4", "0", "5", "5", "hellothird"),
                run(source, dir));
        assertArrayEquals("hellothird".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(dir.resolve("log.bin")));
    }

    @Test
    void failsClosedOnBadArgumentsAndUseAfterClose(@TempDir Path dir) throws Exception {
        String[][] cases = {
                {"pub async routine main(): void { val f = await fs.open_binary_async(\"__PATH__\"); await f.truncate_async(10); return; }",
                        "truncate_async size"},
                {"pub async routine main(): void { val f = await fs.open_binary_async(\"__PATH__\"); val b = await f.read_at_async(-1, 4); return; }",
                        "offset must be >= 0"},
                {"pub async routine main(): void { val f = await fs.open_binary_async(\"__PATH__\"); await f.close_async(); val n = await f.size_async(); return; }",
                        "closed BinaryFile"},
        };
        for (String[] c : cases) {
            String source = program(dir, c[0]);
            Throwable failure = assertThrows(Throwable.class, () -> run(source, dir), c[0]);
            assertTrue(chain(failure).contains(c[1]), c[0] + " -> " + chain(failure));
        }
    }

    @Test
    void openingOutsideTheGrantedRootIsDenied(@TempDir Path dir) throws Exception {
        Path granted = Files.createDirectory(dir.resolve("granted"));
        Path outside = dir.toRealPath().resolve("outside.bin");
        String source = "pub async routine main(): void { val f = await fs.open_binary_async(\"" + outside + "\"); return; }";
        Throwable denied = assertThrows(Throwable.class, () -> run(source, granted));
        assertTrue(chain(denied).contains("permission denied"), chain(denied));
        assertFalse(Files.exists(outside), "a denied open must not create the file");
    }

    @Test
    void aFileHandleIsMovedNotShared() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub async fnc keep(BinaryFile f): void { return; }
                        pub async routine main(): void {
                          val f = await fs.open_binary_async("/tmp/never-opened.bin");
                          await keep(f);
                          val n = await f.size_async();
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("use of moved value 'f'"), failure.getMessage());
    }
}
