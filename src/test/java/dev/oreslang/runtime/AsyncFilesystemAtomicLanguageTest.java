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

final class AsyncFilesystemAtomicLanguageTest {
    @TempDir Path temp;

    @Test
    void asyncAtomicReplaceAndFileSizeUseUtf8Bytes() throws Exception {
        Path root = Files.createDirectory(temp.resolve("atomic-utf8")).toRealPath();
        Path target = root.resolve("resource.txt");
        Path sourceFile = root.resolve("main.atto");

        String source = """
                pub async fnc main(): void {
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
                pub async fnc main(): void {
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
                    pub async fnc main(): void {
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

        try (var entries = Files.list(root)) {
            assertFalse(entries.anyMatch(path ->
                    path.getFileName().toString().startsWith(".oreslang-stack-")
                            && path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
