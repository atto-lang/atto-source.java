package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class NativeCliIoTest {
    @Test
    void stringOperationsCompileAndRunWithoutHostInterop() throws Exception {
        String source = """
            pub routine main(): void {
              val fields = "GET /users/{id}".split_literal(" ");
              stdio.println(fields.get(0));
              stdio.println(fields.get(1));
              stdio.println("   hi  ".trim());
              stdio.println("route.ores".ends_with(".ores"));
              stdio.println("prefix".starts_with("pre"));
              stdio.println("abc".contains_literal("b"));
              stdio.println("route_name".is_identifier());
              stdio.println("[id]".is_route_segment());
              stdio.println("[bad/path]".is_route_segment());
              return;
            }
            """;
        OresCompiler.parseAndTypeCheck(source);
        Path file = Files.createTempFile("ores-cli-string-", ".ores");
        Files.writeString(file, source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(file, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), System.getenv(), out, new ByteArrayOutputStream());
        String logged = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(logged.contains("GET"));
        assertTrue(logged.contains("/users/{id}"));
        assertTrue(logged.contains("hi"));
        assertTrue(logged.contains("true"));
        assertTrue(logged.contains("false"));
    }

    @Test
    void directoryReadIsSortedAndSymlinksAreNotTraversed() throws Exception {
        Path root = Files.createTempDirectory("ores-cli-dir-").toRealPath();
        Files.writeString(root.resolve("b.ores"), "b");
        Files.writeString(root.resolve("a.ores"), "a");
        Path escape = Files.createTempDirectory("ores-cli-escape-").toRealPath();
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, escape);

        RuntimePermissions permissions = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString());
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.FILESYSTEM_READ);
        String source = """
            pub routine main(): void {
              val names = fs.list_dir("%s");
              stdio.println(names.get(0));
              stdio.println(names.get(1));
              stdio.println(fs.is_dir("%s"));
              stdio.println(fs.is_symlink("%s"));
              return;
            }
            """.formatted(root.toString().replace("\\", "\\\\"),
                root.toString().replace("\\", "\\\\"),
                link.toString().replace("\\", "\\\\"));
        Path sourceFile = root.resolve("main.ores");
        Files.writeString(sourceFile, source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                out, new ByteArrayOutputStream());
        String logged = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(logged.contains("a.ores"));
        assertTrue(logged.contains("b.ores"));
        assertTrue(logged.contains("true"));
        String rejected = """
            pub routine main(): void {
              val children = fs.list_dir("%s");
              return;
            }
            """.formatted(link.toString().replace("\\", "\\\\"));
        Files.writeString(sourceFile, rejected);
        assertThrows(RuntimeException.class, () ->
                LinkedProgramRunner.run(sourceFile, policy, permissions, PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(), Set.of(), System.getenv(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }
}
