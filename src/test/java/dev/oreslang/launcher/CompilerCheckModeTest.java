package dev.oreslang.launcher;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.SourceUnitDiagnosticException;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CompilerCheckModeTest {
    @Test
    void validationCompilesWithoutExecutingMain() throws Exception {
        Path source = Files.createTempFile("ores-check-", ".ores");
        Files.writeString(source, """
                pub fnc main(): void {
                  let denominator = 0;
                  let result = 1 / denominator;
                  return;
                }
                """);

        IncrementalCompiler.BuildResult result = LinkedProgramRunner.validate(source);

        assertEquals(1, result.units().size());
        assertTrue(result.units().containsKey(source.toAbsolutePath().normalize().toString().replace('\\', '/')));
    }

    @Test
    void conditionalDoDeprecationUsesEditorDiagnosticShape() {
        Path source = Path.of("demo.ores");
        var warnings = OresMain.formatSyntaxWarnings(source, """
                pub fnc main(): void {
                  if true; do
                    return;
                  fi
                }
                """);

        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().endsWith(
                "demo.ores:2:12: warning: conditional 'do' is deprecated; use 'then'"));
    }

    @Test
    void positionedParserErrorsBecomeEditorDiagnostics() {
        Path source = Path.of("demo.ores");
        String diagnostic = OresMain.formatCheckDiagnostic(
                source,
                new IllegalArgumentException("Oreslang parse error at 7:13: expected expression"));

        assertTrue(diagnostic.endsWith("demo.ores:7:13: error: expected expression"));
    }

    @Test
    void linkedParserErrorNamesTheImportedSourceUnit() {
        Path entry = Path.of("src/main.ores");
        String imported = Path.of("src/components/auth.ores").toAbsolutePath().normalize().toString();
        String diagnostic = OresMain.formatCheckDiagnostic(
                entry,
                new SourceUnitDiagnosticException(imported, "", "",
                        new IllegalArgumentException("Oreslang parse error at 12:7: expected expression")));

        assertEquals("src/components/auth.ores:12:7: error: expected expression", diagnostic);
    }

    @Test
    void linkedSemanticErrorPreservesModuleAndNamespaceContext() {
        Path entry = Path.of("src/main.ores");
        String imported = Path.of("src/components/auth.ores").toAbsolutePath().normalize().toString();
        String diagnostic = OresMain.formatCheckDiagnostic(
                entry,
                new SourceUnitDiagnosticException(imported, "auth", "identity",
                        new IllegalArgumentException("unknown binding 'token'")));

        assertEquals("src/components/auth.ores:1:1: error: [module: auth] [namespace: identity]"
                + " unknown binding 'token'", diagnostic);
    }

    @Test
    void forgedSourceUnitPrefixCannotRedirectUntrustedError() {
        String diagnostic = OresMain.formatCheckDiagnostic(
                Path.of("src/main.ores"),
                new IllegalArgumentException(
                        "Oreslang source unit '/tmp/secret.ores': Oreslang parse error at 7:3: failure"));
        assertTrue(diagnostic.startsWith("src/main.ores:1:1: error: "));
        assertTrue(!diagnostic.startsWith("/tmp/secret.ores:"));
    }

    @Test
    void terminalControlTextIsSanitizedAndBounded() {
        String diagnostic = OresMain.formatCheckDiagnostic(
                Path.of("src/main.ores"),
                new IllegalArgumentException("bad" + "\u001b[31m" + "\n" + "x".repeat(10000)));
        assertTrue(!diagnostic.contains("\u001b"));
        assertTrue(!diagnostic.contains("\n"));
        assertTrue(diagnostic.length() < 2200);
    }

    @Test
    void unpositionedErrorsFallBackToFileStart() {
        Path source = Path.of("demo.ores");
        String diagnostic = OresMain.formatCheckDiagnostic(
                source,
                new IllegalArgumentException("unknown binding 'value'"));

        assertTrue(diagnostic.endsWith("demo.ores:1:1: error: unknown binding 'value'"));
    }
}
