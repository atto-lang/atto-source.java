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

    @Test
    void nestedNoCopyFieldCannotBeLaunderedThroughProjection() {
        String source = """
                define class Inner as
                  @NoCopy
                  pub val int secret = 4;
                end
                define class Outer as
                  pub val Inner inner = new Inner();
                end
                fnc bad(Outer outer): int {
                  return rt copy outer.inner.secret;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Inner.secret"), ex.getMessage());
    }

    @Test
    void legacyShareCannotBypassNoCopyFieldPolicy() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt share vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void ordinaryFieldReadDoesNotTriggerExplicitCopyWarning() {
        String source = """
                define class Config as
                  @WarnOnCopy
                  pub val int version = 1;
                end
                fnc readVersion(Config config): int {
                  return config.version;
                }
                """;
        OresCompiler.AnalysisResult result =
                assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckWithDiagnostics(source));
        assertTrue(result.warnings().stream().noneMatch(
                warning -> warning.contains("W_COPY_DISCOURAGED")), result.warnings().toString());
    }

    @Test
    void copyPolicyOnModuleOrFunctionIsRejected() {
        for (String source : new String[] {
                "@NoCopy define module stuff as end",
                "@NoCopy fnc f(): void { return; }"
        }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source));
            assertTrue(ex.getMessage().contains("E_COPY_ANNOTATION"), ex.getMessage());
        }
    }

    @Test
    void noCopyClassCanTransferWholeOwnerWithRtTake() {
        String source = """
                @NoCopy
                define class Connection as
                  pub val int id = 7;
                end
                fnc transfer(Connection connection): Connection {
                  return rt take connection;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void rtTakeInvalidatesNoCopySourceOwner() {
        String source = """
                @NoCopy
                define class Connection as
                  pub val int id = 7;
                end
                fnc bad(Connection connection): int {
                  val Connection moved = rt take connection;
                  return connection.id;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("moved"), ex.getMessage());
    }

    @Test
    void partialMoveOfNoCopyFieldFailsWithMoveProofErrorNotCopyError() {
        String source = """
                define class Vault as
                  @NoCopy
                  pub let int token = 42;
                end
                fnc bad(Vault vault): int {
                  return rt take vault.token;
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_MOVE_UNPROVEN"), ex.getMessage());
        assertFalse(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void protectedFieldCannotEscapeThroughExplicitCastToOwnedValue() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt copy (vault.token as int);
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void dynamicIndexedAccessCannotSelectProtectedClassFieldForCopy() {
        String source = HEADER + """
                fnc bad(Vault vault, String key): int {
                  return rt copy vault[key];
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }

    @Test
    void protectedFieldCannotBeCopiedThroughConstantIndexedAccess() {
        String source = HEADER + """
                fnc bad(Vault vault): int {
                  return rt copy vault["token"];
                }
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(ex.getMessage().contains("E_COPY_FORBIDDEN"), ex.getMessage());
    }
}
