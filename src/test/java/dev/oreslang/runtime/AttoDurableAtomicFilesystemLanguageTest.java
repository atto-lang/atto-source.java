package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles and executes actual linked .atto guest calls on the Java backend.
 * This is not a real power-loss, process SIGKILL or native-image AOT test.
 */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "ores.private.fs.guest.approved", matches = "true")
final class AttoDurableAtomicFilesystemLanguageTest {
    @TempDir Path temp;

    private Path privateRoot(String name) throws Exception {
        Path dir = Files.createDirectory(temp.resolve(name));
        Files.setPosixFilePermissions(dir, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        return dir.toRealPath();
    }

    private static String quoted(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String program(Path manifest, String instructions) {
        return "pub async routine main(): void {\n"
                + instructions.replace("__MANIFEST__", quoted(manifest))
                + "\n  return;\n}\n";
    }

    private static String guest(Path sourceFile, String source,
                                RuntimePermissions permissions,
                                IsolatePolicy policy) throws Exception {
        OresCompiler.parseAndTypeCheck(source);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                output, new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    private static RuntimePermissions rights(Path root) {
        return RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());
    }

    private static IsolatePolicy rwPolicy() {
        return IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE);
    }

    @Test
    void actualGuestDurablyReplacesManifestAndReplaysInSeparateRunner() throws Exception {
        Path root = privateRoot("durable");
        Path file = root.resolve("classic.meta");
        Path sourceFile = root.resolve("main.atto");
        String first = Files.readString(
                Path.of("tests", "fixtures", "durable_atomic_manifest_smoke.atto"),
                StandardCharsets.UTF_8).replace("__MANIFEST__", quoted(file));
        assertEquals("generation=1", guest(sourceFile, first, rights(root), rwPolicy()));
        assertEquals("generation=1", Files.readString(file));
        String second = program(file, """
                  await fs.write_text_durable_atomic_async("__MANIFEST__", "generation=2");
                  stdio.println(await fs.file_size_async("__MANIFEST__"));
                """);
        assertEquals("12", guest(sourceFile, second, rights(root), rwPolicy()));
        assertEquals("generation=2", Files.readString(file));
        String third = program(file, """
                  stdio.println(await fs.read_text_bounded_async("__MANIFEST__", 1024));
                """);
        assertEquals("generation=2", guest(sourceFile, third, rights(root), rwPolicy()));
        try (var files = Files.list(root)) {
            assertFalse(files.anyMatch(path ->
                    path.getFileName().toString().startsWith(".oreslang-durable-")),
                    "no temporary manifests after completed rename");
        }
    }

    @Test
    void destinationSymlinkCannotClobberOutsideData() throws Exception {
        Path allowed = privateRoot("allowed");
        Path outside = privateRoot("outside");
        Path secret = outside.resolve("secret");
        Files.writeString(secret, "preserved");
        Path symlink = allowed.resolve("classic.meta");
        Files.createSymbolicLink(symlink, secret);
        String source = program(symlink, """
                  await fs.write_text_durable_atomic_async("__MANIFEST__", "attack");
                """);
        assertThrows(RuntimeException.class, () ->
                guest(allowed.resolve("main.atto"), source, rights(allowed), rwPolicy()));
        assertEquals("preserved", Files.readString(secret));
        assertTrue(Files.isSymbolicLink(symlink));
    }

    @Test
    void outOfScopeDeniedBeforeFilesystemMetadataProbe() throws Exception {
        Path allowed = privateRoot("permitted");
        Path outside = privateRoot("forbidden");
        Path existing = outside.resolve("found");
        Files.writeString(existing, "untouched");
        Path missing = outside.resolve("missing-parent").resolve("classic.meta");
        for (Path forbidden : java.util.List.of(existing, missing)) {
            String source = program(forbidden, """
                      await fs.write_text_durable_atomic_async("__MANIFEST__", "attack");
                    """);
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    guest(allowed.resolve("main.atto"), source, rights(allowed), rwPolicy()));
            assertTrue(errorChain(failure).contains("permission denied"),
                    "WRITE denial must occur before metadata probe: " + errorChain(failure));
        }
        assertEquals("untouched", Files.readString(existing));
        assertFalse(Files.exists(missing.getParent()));
    }

    @Test
    void exactFileGrantDoesNotAuthorizeSiblingTempCreationOrDirectoryRename()
            throws Exception {
        Path root = privateRoot("file-only-grant");
        Path manifest = root.resolve("classic.meta");
        RuntimePermissions onlyFile = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, manifest.toString());
        String source = program(manifest, """
                  await fs.write_text_durable_atomic_async("__MANIFEST__", "forbidden");
                """);
        RuntimeException denied = assertThrows(RuntimeException.class, () ->
                guest(root.resolve("main.atto"), source, onlyFile, rwPolicy()));
        assertTrue(errorChain(denied).contains("permission denied"),
                "missing parent WRITE must fail before any filesystem probe: "
                        + errorChain(denied));
        assertFalse(Files.exists(manifest));
        try (var files = Files.list(root)) {
            assertFalse(files.anyMatch(path ->
                    path.getFileName().toString().startsWith(".oreslang-durable-")));
        }
    }

    @Test
    void untrustedGroupReadableDirectoryCannotClaimDurableCommit() throws Exception {
        Path root = privateRoot("world-readable");
        Files.setPosixFilePermissions(root, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE));
        Path manifest = root.resolve("classic.meta");
        String source = program(manifest, """
                  await fs.write_text_durable_atomic_async("__MANIFEST__", "should-not-write");
                """);
        assertThrows(RuntimeException.class, () ->
                guest(root.resolve("main.atto"), source, rights(root), rwPolicy()));
        assertFalse(Files.exists(manifest));
    }

    private static String errorChain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; failure != null && i < 12; i++, failure = failure.getCause()) {
            text.append(failure.getMessage()).append('\n');
        }
        return text.toString();
    }
}
