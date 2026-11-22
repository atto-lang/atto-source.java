package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Linked .atto guest evidence for read_text_bounded_async; not host-only mock tests. */
final class BoundedFilesystemReadLanguageTest {
    @TempDir Path temp;

    private static void guest(
            Path sourceFile, Path resource, long maxBytes,
            RuntimePermissions permissions, ByteArrayOutputStream output) throws Exception {
        String escaped = resource.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String source = """
                pub routine main(): void {
                  stdio.println(await fs.read_text_bounded_async("%s", %d));
                  return;
                }
                """.formatted(escaped, maxBytes);
        OresCompiler.parseAndTypeCheck(source);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_READ);
        LinkedProgramRunner.run(
                sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                output, new ByteArrayOutputStream());
    }

    @Test
    void exactUtf8ByteBoundaryAndEmptyFile() throws Exception {
        Path root = Files.createDirectory(temp.resolve("utf8")).toRealPath();
        Path target = root.resolve("text.txt");
        Path sourceFile = root.resolve("main.atto");
        Files.writeString(target, "é🙂", StandardCharsets.UTF_8);
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        guest(sourceFile, target, 6, permissions, output);
        assertEquals("é🙂", output.toString(StandardCharsets.UTF_8).trim());

        ByteArrayOutputStream denied = new ByteArrayOutputStream();
        assertThrows(RuntimeException.class, () -> guest(sourceFile, target, 5, permissions, denied));
        assertEquals("", denied.toString(StandardCharsets.UTF_8),
                "over-budget reads must never publish a partial text result");

        Files.writeString(target, "", StandardCharsets.UTF_8);
        guest(sourceFile, target, 0, permissions, new ByteArrayOutputStream());
        assertEquals(0L, Files.size(target));
    }

    @Test
    void canonicalInScopePathWorksButSymlinkAliasIsDeniedBeforeResolution() throws Exception {
        // A symlink that points *inside* the granted scope is still refused
        // under this strict no-symlink-components policy. Callers may pass
        // the canonical path instead. This matters on hosts with /var -> /private/var.
        Path scope = Files.createDirectory(temp.resolve("alias-policy")).toRealPath();
        Path actual = Files.createDirectory(scope.resolve("actual")).toRealPath();
        Path resource = actual.resolve("resource.txt");
        Files.writeString(resource, "canonical-ok", StandardCharsets.UTF_8);
        Path alias = scope.resolve("alias");
        Files.createSymbolicLink(alias, actual);
        Path sourceFile = scope.resolve("main.atto");
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, scope.toString());

        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        guest(sourceFile, resource, 8192, permissions, accepted);
        assertEquals("canonical-ok", accepted.toString(StandardCharsets.UTF_8).trim());

        for (Path aliasTarget : java.util.List.of(
                alias.resolve("resource.txt"), alias.resolve("missing.txt"))) {
            RuntimeException denied = assertThrows(RuntimeException.class, () ->
                    guest(sourceFile, aliasTarget, 8192, permissions,
                            new ByteArrayOutputStream()));
            StringBuilder errors = new StringBuilder();
            for (Throwable cause = denied; cause != null; cause = cause.getCause()) {
                errors.append(cause.getMessage()).append(' ');
            }
            assertTrue(errors.toString().contains("refuses symlink component: " + alias),
                    "even an in-scope ancestor alias must be rejected before resolution: " + errors);
        }
        assertEquals("canonical-ok", Files.readString(resource, StandardCharsets.UTF_8));
        assertFalse(Files.exists(actual.resolve("missing.txt")));
    }

    @Test
    void existingMissingAndSymlinkParentCannotEscapeReadScope() throws Exception {
        Path allowed = Files.createDirectory(temp.resolve("allowed")).toRealPath();
        Path outside = Files.createDirectory(temp.resolve("outside")).toRealPath();
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "private", StandardCharsets.UTF_8);
        Path missing = outside.resolve("missing.txt");
        Path link = allowed.resolve("redirect");
        Files.createSymbolicLink(link, outside);
        Path sourceFile = allowed.resolve("main.atto");
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, allowed.toString());

        for (Path forbidden : java.util.List.of(
                secret, missing, link.resolve("secret.txt"), link.resolve("missing.txt"))) {
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    guest(sourceFile, forbidden, 8192, permissions, new ByteArrayOutputStream()));
            StringBuilder message = new StringBuilder();
            Throwable cause = thrown;
            for (int i = 0; cause != null && i < 12; i++, cause = cause.getCause()) {
                message.append(cause.getMessage()).append(' ');
            }
            if (forbidden.startsWith(link)) {
                // The denial must occur at the symlink ancestor, even for a
                // nonexistent restricted target, before canonical resolution.
                assertTrue(message.toString().contains("refuses symlink component: " + link),
                        "READ must not probe behind an out-of-scope parent symlink: " + message);
            } else {
                assertTrue(message.toString().contains("Oreslang permission denied"),
                        "out-of-scope lexical READ must deny before probing: " + message);
            }
        }
        assertEquals("private", Files.readString(secret, StandardCharsets.UTF_8));
        assertFalse(Files.exists(missing));
    }

    @Test
    void malformedUtf8AndExcessiveMaximumFailClosed() throws Exception {
        Path root = Files.createDirectory(temp.resolve("invalid")).toRealPath();
        Path resource = root.resolve("invalid.txt");
        Files.write(resource, new byte[] {(byte) 0xc3, (byte) 0x28});
        Path sourceFile = root.resolve("main.atto");
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString());

        assertThrows(RuntimeException.class, () -> guest(
                sourceFile, resource, 2, permissions, new ByteArrayOutputStream()));
        assertThrows(RuntimeException.class, () -> guest(
                sourceFile, resource, -1, permissions, new ByteArrayOutputStream()));
        assertThrows(RuntimeException.class, () -> guest(
                sourceFile, resource, 16777217, permissions, new ByteArrayOutputStream()));
    }
}
