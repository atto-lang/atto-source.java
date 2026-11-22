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
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            return context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "std-process-namespace-guest.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build()).asInt();
        }
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
