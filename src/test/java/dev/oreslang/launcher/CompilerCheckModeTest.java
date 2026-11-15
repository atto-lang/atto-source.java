package dev.oreslang.launcher;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.SourceUnitDiagnosticException;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void incrementalCompilerAttachesOriginalFileToRealParseFailure() {
        var failure = assertThrows(SourceUnitDiagnosticException.class,
                () -> new IncrementalCompiler().compile(
                        Map.of("src/malformed.ores", "this is not Oreslang source;")));
        assertTrue(failure.unitId().endsWith("src/malformed.ores"));
        assertTrue(failure.detail().contains("Oreslang parse error"));
    }

    @Test
    void incrementalCompilerAttachesModuleToRealSemanticFailure() {
        var failure = assertThrows(SourceUnitDiagnosticException.class,
                () -> new IncrementalCompiler().compile(
                        Map.of("src/broken.ores", """
                                define module payment as
                                  pub fnc amount(): int { return "wrong"; }
                                end
                                """)));
        assertEquals("payment", failure.moduleName());
        assertTrue(failure.unitId().endsWith("src/broken.ores"));
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
                new IllegalArgumentException("bad" + "\u001b[31m" + "\n"
                        + "\u202e" + "x".repeat(10000)));
        assertTrue(!diagnostic.contains("\u001b"));
        assertTrue(!diagnostic.contains("\u202e"));
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
