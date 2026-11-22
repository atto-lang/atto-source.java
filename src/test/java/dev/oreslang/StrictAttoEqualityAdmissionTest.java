package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Candidate strict AttoLang equality *compiler front-end* admission.
 * These are source/AST tests, not generated guest native-AOT proof.
 */
final class StrictAttoEqualityAdmissionTest {
    private static final String VALID = """
            pub fnc main(): bool {
              val int a = 7;
              val int b = 8;
              return a eq a;
            }
            """;

    @Test
    void strictProfileAdmitsCanonicalOperatorsBeforeOwnershipAndTreeShaking() {
        Ast.Program checked = assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheckStrictAtto(VALID));
        assertNotNull(checked);
        assertDoesNotThrow(() -> OresCompiler.compileForBuildStrictAtto(
                VALID, BuildOptions.executable()));
        for (String op : new String[]{"neq", "!eq"}) {
            String src = "pub fnc main(): bool { return 7 " + op + " 8; }";
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(src), op);
        }
    }

    @Test
    void rejectsCAndErlangEqualityOperatorsWithoutBreakingLegacyParser() {
        for (String op : new String[]{"==", "!=", "=:=", "=/=", "/="}) {
            String src = "pub fnc main(): bool { return 7 " + op + " 7; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(src), op);
        }
        // The historical source grammar stays available until versioned migration.
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main(): bool { return 7 == 7; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main(): bool { return 7 != 8; }"));
    }

    @Test
    void strictEqualityRequiresExactlyCompatibleNumericTypes() {
        for (String op : new String[]{"eq", "neq", "!eq"}) {
            String expr = "pub fnc main(): bool { return 7 " + op + " 7.0; }";
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(expr), op);
            assertTrue(error.getMessage().contains("ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES"),
                    error::getMessage);
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto("""
                        pub fnc compare(int a, float b): bool {
                          return a eq b;
                        }
                        """));
    }

    @Test
    void commentsAndStringLiteralsAreNeverMistakenForOperators() {
        String src = """
                pub fnc main(): bool {
                  // forbidden == != =:= tokens in a comment
                  val string example = "== != =:= ! eq";
                  return 7 !eq 8;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(src));
    }

    @Test
    void splitBangEqAndPartialIdentifiersRemainInvalid() {
        for (String expr : new String[]{"7 ! eq 8", "7 !eqvalue 8", "7 neqvalue 8"}) {
            String src = "pub fnc main(): bool { return " + expr + "; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(src), expr);
        }
    }

    @Test
    void templateInterpolationFailsClosedUntilItPropagatesStrictProfile() {
        // A template may contain a reparsed expression. Reject even benign
        // templates rather than accepting a hidden legacy == or != path.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto("""
                        pub fnc main(): bool {
                          val label = `value: ${7 == 7}`;
                          return true;
                        }
                        """));
        assertTrue(error.getMessage().contains("ATTO_STRICT_TEMPLATE_UNSUPPORTED"),
                error::getMessage);
    }

    @Test
    void strictSingleUnitAdmissionRejectsUnverifiedImportedSourceGraph() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto("""
                        import fnc helper from "./legacy.ores";
                        pub fnc main(): bool { return 7 eq 7; }
                        """));
        assertTrue(error.getMessage().contains("ATTO_STRICT_IMPORT_GRAPH_UNVERIFIED"),
                error::getMessage);
    }

    @Test
    void legacyEqualityTypingRemainsSeparatelyCompatible() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): bool {
                  return 7 eq 7.0;
                }
                """));
    }
}
