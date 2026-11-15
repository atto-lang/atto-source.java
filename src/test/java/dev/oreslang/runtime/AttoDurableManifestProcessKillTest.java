package dev.oreslang.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two fresh JVM processes run the actual linked Atto guest before and after
 * killing the first one. This is not power-cut or process-zero-GC evidence.
 */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "ores.private.fs.guest.approved", matches = "true")
@Timeout(120)
final class AttoDurableManifestProcessKillTest {
    @TempDir Path temp;

    private Process launch(String mode, Path root) throws IOException {
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) {
            classpath = System.getProperty("java.class.path");
        }
        assertNotNull(classpath, "JUnit process classpath");
        return new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath,
                "dev.oreslang.runtime.AttoDurableManifestGuestChild",
                mode, root.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static String waitForMarker(Process process, String marker) throws Exception {
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder prior = new StringBuilder();
                for (int i = 0; i < 256; i++) {
                    String line = reader.readLine();
                    if (line == null) break;
                    if (line.startsWith(marker)) return line;
                    if (prior.length() < 4096) prior.append(line).append('\n');
                }
                throw new IllegalStateException("missing guest receipt " + marker + ": " + prior);
            } catch (IOException failure) {
                throw new IllegalStateException("failed to read guest receipt", failure);
            }
        });
        return output.get(75, TimeUnit.SECONDS);
    }

    @Test
    void aCompletedAttoDurableManifestWriteSurvivesSigkillAndFreshGuestReplay()
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("private-wal-root")).toRealPath();
        Files.setPosixFilePermissions(root, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Process writer = launch("write-hold", root);
        try {
            assertEquals("ATTO_GUEST_FSYNC_AND_DIRECTORY_SYNC_COMPLETED",
                    waitForMarker(writer, "ATTO_GUEST_FSYNC_AND_DIRECTORY_SYNC_COMPLETED"));
            assertTrue(writer.isAlive(), "writer must still be live before termination");
        } finally {
            writer.destroyForcibly();
            assertTrue(writer.waitFor(15, TimeUnit.SECONDS), "SIGKILL writer must exit");
        }
        assertNotEquals(0, writer.exitValue(), "writer must terminate abnormally");
        assertEquals("generation=7", Files.readString(root.resolve("classic.meta")));

        Process replay = launch("replay", root);
        try {
            assertEquals("ATTO_GUEST_REPLAY=generation=7",
                    waitForMarker(replay, "ATTO_GUEST_REPLAY="));
            assertTrue(replay.waitFor(15, TimeUnit.SECONDS), "replay guest must exit");
            assertEquals(0, replay.exitValue(), "fresh Atto guest replay must exit cleanly");
        } finally {
            if (replay.isAlive()) {
                replay.destroyForcibly();
                replay.waitFor(15, TimeUnit.SECONDS);
            }
        }
        try (var files = Files.list(root)) {
            assertFalse(files.anyMatch(path ->
                    path.getFileName().toString().startsWith(".oreslang-durable-")));
        }
    }
}
