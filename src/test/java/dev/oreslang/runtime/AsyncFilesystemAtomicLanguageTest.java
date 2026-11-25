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

final class AsyncFilesystemAtomicLanguageTest {
    @TempDir Path temp;

    @Test
    void asyncAtomicReplaceAndFileSizeUseUtf8Bytes() throws Exception {
        Path root = Files.createDirectory(temp.resolve("atomic-utf8")).toRealPath();
        Path target = root.resolve("resource.txt");
        Path sourceFile = root.resolve("main.atto");

        String source = """
                pub routine main(): void {
                  await fs.write_text_atomic_async("%s", "é🙂");
                  stdio.println(await fs.file_size_async("%s"));
                  await fs.write_text_atomic_async("%s", "replacement");
                  stdio.println(await fs.file_size_async("%s"));
                  return;
                }
                """.formatted(target, target, target, target);

        OresCompiler.parseAndTypeCheck(source);
        Files.writeString(sourceFile, source);

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(
                        IsolatePolicy.Capability.FILESYSTEM_READ,
                        IsolatePolicy.Capability.FILESYSTEM_WRITE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                sourceFile,
                policy,
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                System.getenv(),
                out,
                new ByteArrayOutputStream());

        assertEquals("6\n11", out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
        assertEquals("replacement", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(11L, Files.size(target));
    }

    @Test
    void asyncAtomicReplaceRejectsDestinationSymlinkWithoutEscape() throws Exception {
        Path root = Files.createDirectory(temp.resolve("atomic-root")).toRealPath();
        Path outside = Files.createDirectory(temp.resolve("atomic-outside")).toRealPath();
        Path outsideTarget = outside.resolve("secret.txt");
        Files.writeString(outsideTarget, "unchanged");
        Path link = root.resolve("resource.txt");
        Files.createSymbolicLink(link, outsideTarget);
        Path sourceFile = root.resolve("main.atto");

        String source = """
                pub routine main(): void {
                  await fs.write_text_atomic_async("%s", "forbidden");
                  return;
                }
                """.formatted(link);
        OresCompiler.parseAndTypeCheck(source);
        Files.writeString(sourceFile, source);

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(
                        IsolatePolicy.Capability.FILESYSTEM_READ,
                        IsolatePolicy.Capability.FILESYSTEM_WRITE);

        assertThrows(RuntimeException.class, () ->
                LinkedProgramRunner.run(
                        sourceFile,
                        policy,
                        permissions,
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        System.getenv(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertEquals("unchanged", Files.readString(outsideTarget));
    }

    @Test
    void outOfScopeFileSizeDeniesBeforeCheckingWhetherFileExists() throws Exception {
        Path allowed = Files.createDirectory(temp.resolve("read-allowed")).toRealPath();
        Path outside = Files.createDirectory(temp.resolve("read-denied")).toRealPath();
        Path existing = outside.resolve("present.txt");
        Path missing = outside.resolve("missing.txt");
        Files.writeString(existing, "secret bytes", StandardCharsets.UTF_8);
        Path sourceFile = allowed.resolve("main.atto");

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, allowed.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_READ);

        // The same READ denial is required whether an unauthorized path
        // exists or not. Checking existence/canonicalization first would
        // disclose the distinction through a filesystem exception.
        for (Path forbidden : java.util.List.of(existing, missing)) {
            String source = """
                    pub routine main(): void {
                      stdio.println(await fs.file_size_async("%s"));
                      return;
                    }
                    """.formatted(forbidden);
            OresCompiler.parseAndTypeCheck(source);
            Files.writeString(sourceFile, source);
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    LinkedProgramRunner.run(
                            sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                            ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                            new ByteArrayOutputStream(), new ByteArrayOutputStream()));

            StringBuilder diagnostics = new StringBuilder();
            Throwable cursor = thrown;
            int depth = 0;
            while (cursor != null && depth++ < 12) {
                diagnostics.append(cursor.getClass().getName()).append(": ")
                        .append(cursor.getMessage()).append('\n');
                cursor = cursor.getCause();
            }
            assertTrue(diagnostics.toString().contains("Oreslang permission denied"),
                    "out-of-scope file-size operation must fail on READ admission: " + diagnostics);
            assertTrue(diagnostics.toString().contains("fs.file_size"),
                    "READ denial must identify the file-size API: " + diagnostics);
        }
        assertEquals("secret bytes", Files.readString(existing, StandardCharsets.UTF_8));
        assertFalse(Files.exists(missing));
    }

    @Test
    void outOfScopeAtomicWriteDeniesBeforeProbingExistingOrMissingParent() throws Exception {
        Path permitted = Files.createDirectory(temp.resolve("write-permitted")).toRealPath();
        Path outside = Files.createDirectory(temp.resolve("write-denied")).toRealPath();
        Path existing = outside.resolve("existing.txt");
        Files.writeString(existing, "untouched", StandardCharsets.UTF_8);
        Path missingParent = outside.resolve("missing-dir").resolve("target.txt");
        Path sourceFile = permitted.resolve("main.atto");

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, permitted.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_WRITE);

        for (Path forbidden : java.util.List.of(existing, missingParent)) {
            String source = """
                    pub routine main(): void {
                      await fs.write_text_atomic_async("%s", "forbidden");
                      return;
                    }
                    """.formatted(forbidden);
            OresCompiler.parseAndTypeCheck(source);
            Files.writeString(sourceFile, source);
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    LinkedProgramRunner.run(
                            sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                            ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                            new ByteArrayOutputStream(), new ByteArrayOutputStream()));
            assertTrue(denialChain(thrown).contains("Oreslang permission denied"),
                    "atomic write must deny out-of-scope path before filesystem lookup: " + thrown);
        }
        assertEquals("untouched", Files.readString(existing, StandardCharsets.UTF_8));
        assertFalse(Files.exists(missingParent.getParent()));
    }

    @Test
    void atomicWriteCannotEscapeThroughSymlinkedParent() throws Exception {
        Path permitted = Files.createDirectory(temp.resolve("write-link-allowed")).toRealPath();
        Path outside = Files.createDirectory(temp.resolve("write-link-outside")).toRealPath();
        Path sentinel = outside.resolve("resource.txt");
        Files.writeString(sentinel, "preserved", StandardCharsets.UTF_8);
        Path link = permitted.resolve("redirect");
        Files.createSymbolicLink(link, outside);
        Path sourceFile = permitted.resolve("main.atto");
        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.WRITE, permitted.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_WRITE);

        // Both existing and missing targets must deny at the symlink itself,
        // before realPath(parent) can disclose target existence outside scope.
        for (Path target : java.util.List.of(
                link.resolve("resource.txt"), link.resolve("absent.txt"))) {
            String source = """
                    pub routine main(): void {
                      await fs.write_text_atomic_async("%s", "forbidden");
                      return;
                    }
                    """.formatted(target);
            OresCompiler.parseAndTypeCheck(source);
            Files.writeString(sourceFile, source);
            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    LinkedProgramRunner.run(
                            sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                            ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                            new ByteArrayOutputStream(), new ByteArrayOutputStream()));
            assertTrue(denialChain(thrown).contains("refuses symlink component: " + link),
                    "atomic write must deny before resolving a symlinked parent: " + thrown);
        }
        assertEquals("preserved", Files.readString(sentinel, StandardCharsets.UTF_8));
        assertFalse(Files.exists(outside.resolve("absent.txt")));
    }

    private static String denialChain(Throwable failure) {
        StringBuilder rendered = new StringBuilder();
        for (int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause()) {
            rendered.append(failure.getClass().getName()).append(": ")
                    .append(failure.getMessage()).append('\n');
        }
        return rendered.toString();
    }

    @Test
    void asyncFileSizeCannotEscapeReadScopeOrSizeDirectory() throws Exception {
        Path root = Files.createDirectory(temp.resolve("size-root")).toRealPath();
        Path outside = Files.createDirectory(temp.resolve("size-outside")).toRealPath();
        Path outsideTarget = outside.resolve("secret.txt");
        Files.writeString(outsideTarget, "hidden");
        Path link = root.resolve("secret-link.txt");
        Files.createSymbolicLink(link, outsideTarget);
        Path sourceFile = root.resolve("main.atto");

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_READ);

        for (Path forbidden : java.util.List.of(link, root)) {
            String source = """
                    pub routine main(): void {
                      stdio.println(await fs.file_size_async("%s"));
                      return;
                    }
                    """.formatted(forbidden);
            OresCompiler.parseAndTypeCheck(source);
            Files.writeString(sourceFile, source);

            assertThrows(RuntimeException.class, () ->
                    LinkedProgramRunner.run(
                            sourceFile,
                            policy,
                            permissions,
                            PermissionCheckMode.RUNTIME,
                            ExecutionProfile.serverJit(),
                            Set.of(),
                            System.getenv(),
                            new ByteArrayOutputStream(),
                            new ByteArrayOutputStream()));
        }

        // Parent-component symlinks cannot be resolved before the READ
        // capability check, regardless of the restricted target's existence.
        Path parentLink = root.resolve("redirect-outside");
        Files.createSymbolicLink(parentLink, outside);
        for (Path forbidden : java.util.List.of(
                parentLink.resolve("secret.txt"), parentLink.resolve("absent.txt"))) {
            String source = """
                    pub routine main(): void {
                      stdio.println(await fs.file_size_async("%s"));
                      return;
                    }
                    """.formatted(forbidden);
            OresCompiler.parseAndTypeCheck(source);
            Files.writeString(sourceFile, source);
            RuntimeException denied = assertThrows(RuntimeException.class, () ->
                    LinkedProgramRunner.run(
                            sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                            ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                            new ByteArrayOutputStream(), new ByteArrayOutputStream()));
            assertTrue(denialChain(denied).contains(
                    "refuses symlink component: " + parentLink),
                    "file size must reject before probing a parent symlink: " + denied);
        }
        assertEquals("hidden", Files.readString(outsideTarget));
        assertFalse(Files.exists(outside.resolve("absent.txt")));

        try (var entries = Files.list(root)) {
            assertFalse(entries.anyMatch(path ->
                    path.getFileName().toString().startsWith(".oreslang-stack-")
                            && path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
