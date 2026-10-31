package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Copy policy v1: source compiler checks, not host-language object copying. */
final class CopyPolicyCompilerTest {

    private static final String HEADER = """
            define class Vault as
              @NoCopy
              pub val int token = 42;
            end
            """;

    @Test
    void classPolicySurvivesParsingAndAnnotationExpansion() {
        String source = """
                @NoCopy
                define class Connection as
                  pub val int id = 7;
                end
                """;
        Ast.Program parsed = Parser.parse(source);
        Ast.ClassDecl initial = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        assertEquals("NoCopy", initial.annotations().getFirst().name());
        Ast.Program checked = TypeChecker.checkTypes(parsed);
        Ast.ClassDecl expanded = (Ast.ClassDecl) checked.modules().getFirst().declarations().getFirst();
        assertEquals(initial.annotations(), expanded.annotations());
    }

    @Test
    void noCopyClassCannotBeExplicitlyCopied() {
        String source = """
                @NoCopy
                define class Connection as
                  pub val int id = 7;
                end
                fnc bad(Connection connection): Connection {
                  return rt copy connection;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void noCopyFieldCannotBeExplicitlyCopied() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt copy vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Vault.token"), ex.getMessage());
    }

    @Test
    void noCopyFieldCannotBeExtractedIntoNewOwnedValue() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void borrowOfNoCopyFieldIsPermitted() {
        String source = HEADER + """
                fnc good(Vault vault): void {
                  val tokenView = rt borrow vault.token;
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void warnOnCopyIsAvailableInTheCompilerDiagnosticsApi() {
        String source = """
                define class Config as
                  @WarnOnCopy
                  pub val int version = 1;
                end
                fnc copyVersion(Config config): int {
                  return rt copy config.version;
                }
                """;
        OresCompiler.AnalysisResult result =
                assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckWithDiagnostics(source));
        assertEquals(1, result.warnings().stream()
                .filter(warning -> warning.contains("W_COPY_DISCOURAGED")).count());
        assertTrue(result.warnings().stream()
                .anyMatch(warning -> warning.contains("Config.version")));
    }

    @Test
    void copyableClassFailsClosedUntilVerifiedCopyLoweringExists() {
        String source = """
                @Copyable
                define class Config as
                  pub val int version = 1;
                end
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_UNPROVEN"), ex.getMessage());
    }

    @Test
    void conflictingCopyFieldAnnotationsAreRejected() {
        String source = """
                define class Config as
                  @NoCopy
                  @WarnOnCopy
                  pub val int version = 1;
                end
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_ANNOTATION"), ex.getMessage());
    }

    @Test
    void unknownClassAnnotationsStillFailClosed() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("@Audited define class Config as end"));
        assertTrue(ex.getMessage().contains("silently discarded"), ex.getMessage());
    }

    @Test
    void unsupportedCopyPolicyArgumentsAreRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("@NoCopy(level=2) define class Config as end"));
        assertTrue(ex.getMessage().contains("E_COPY_ANNOTATION"), ex.getMessage());
    }
}
