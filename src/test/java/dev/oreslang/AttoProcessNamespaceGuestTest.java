package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real guest import/link/execute acceptance. No host subprocess is launched.
 * Distinct from source-shape checks in StdProcessNamespaceSmoke.
 */
final class AttoProcessNamespaceGuestTest {
    private static int run(String importPath) throws Exception {
        String source = """
                import module process from "%s";
                pub fnc main(): int {
                    if process.succeeded(process.exit_status(0, false)) == false then return 1; fi
                    if process.succeeded(process.exit_status(0, true)) then return 2; fi
                    if process.valid_stdio(process.command("/example/tool")) == false then return 3; fi
                    return 42;
                }
                """.formatted(importPath);
        // std/process is a compiler-owned unit: the linked program runner
        // binds it (a bare Context.eval has no library loader by design).
        java.nio.file.Path entry = java.nio.file.Files.createTempDirectory("atto-process-namespace")
                .resolve("main.atto");
        java.nio.file.Files.writeString(entry, source);
        return dev.oreslang.runtime.LinkedProgramRunner.runWithExitStatus(
                entry, dev.oreslang.runtime.IsolatePolicy.developer(),
                dev.oreslang.runtime.ExecutionProfile.serverJit(),
                new java.io.ByteArrayOutputStream(), new java.io.ByteArrayOutputStream()).exitStatus();
    }

    @Test void defaultStdProcessImportExecutesLowercaseModule() throws Exception {
        assertEquals(42, run("std/process"));
    }

    @Test void qualifiedAttoStdProcessImportExecutesSameContract() throws Exception {
        assertEquals(42, run("atto.std/process"));
    }

    @Test void legacyOresStdProcessImportMustNotCompileOrExecute() {
        assertThrows(RuntimeException.class, () -> run("ores.std/process"));
    }

    @Test void qualifiedNamespaceRejectsTraversal() {
        assertThrows(RuntimeException.class, () -> run("atto.std/process/../secrets"));
    }
}
