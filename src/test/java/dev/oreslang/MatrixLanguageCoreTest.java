package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MatrixLanguageCoreTest {
    @Test
    void finiteMatrixLiteralMatmulAndCoreMembersExecute() {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val Matrix<float> a = matrix[[1.0, 2.0], [3.0, 4.0]];
                      val Matrix<float> b = matrix[[5.0, 6.0], [7.0, 8.0]];
                      val Matrix<float> c = a @ b;
                      return c.rows eq 2
                          && c.cols eq 2
                          && c.get(0, 0) eq 19.0
                          && c.get(0, 1) eq 22.0
                          && c.get(1, 0) eq 43.0
                          && c.get(1, 1) eq 50.0
                          && c.transpose().get(0, 1) eq 43.0
                          && c.trace() eq 69.0;
                    }
                    """).asBoolean());
        }
    }

    @Test
    void complexMatrixMatmulUsesHardenedWholeDotReferenceCore() {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val Matrix<complex> left =
                          matrix[[1.0 + 2.0i, 3.0 - 1.0i]];
                      val Matrix<complex> right =
                          matrix[[2.0 - 1.0i], [4.0 + 3.0i]];
                      val Matrix<complex> result = left @ right;
                      return result.get(0, 0) eq (19.0 + 8.0i)
                          && result.hermitian().get(0, 0) eq (19.0 - 8.0i);
                    }
                    """).asBoolean());
        }
    }

    @Test
    void scalarScaleAndDivisionStayDistinctFromMatrixMatmul() {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val Matrix<float> a = matrix[[2.0, 4.0]];
                      val Matrix<float> left = 3.0 * a;
                      val Matrix<float> right = a * 3.0;
                      val Matrix<float> divided = right / 2.0;
                      return left.get(0, 1) eq 12.0
                          && right.get(0, 0) eq 6.0
                          && divided.get(0, 1) eq 6.0;
                    }
                    """).asBoolean());
        }

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> context.eval(OresLanguage.ID, """
                            pub routine main(): Matrix<float> {
                              val Matrix<float> a = matrix[[1.0]];
                              return a * a;
                            }
                            """));
            assertTrue(failure.getMessage().contains("uses '@'"), failure::toString);
        }
    }

    @Test
    void matrixLiteralRejectsRaggedAndExactDecimalStorage() {
        for (String source : new String[] {
                """
                pub routine main(): Matrix<float> {
                  return matrix[[1.0, 2.0], [3.0]];
                }
                """,
                """
                pub routine main(Matrix<decimal> value): void {
                  return;
                }
                """
        }) {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).build()) {
                PolyglotException failure = assertThrows(
                        PolyglotException.class,
                        () -> context.eval(OresLanguage.ID, source));
                assertTrue(failure.getMessage().contains("rectangular")
                                || failure.getMessage().contains("Decimal")
                                || failure.getMessage().contains("binary64"),
                        failure::toString);
            }
        }
    }

    @Test
    void existingVectorTypeRemainsGenericSequenceUntilMathVectorMigrationIsDesigned() {
        Ast.Program program = Parser.parse("""
                pub fnc first(Vector<float> values): float {
                  return values[0];
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }
    @Test
    void complexTypedZeroImaginaryScaleAndDivisionRemainComplexInGuestCode() {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertTrue(context.eval(OresLanguage.ID, """
                    pub routine main(): bool {
                      val Matrix<float> a = matrix[[2.0]];
                      val Matrix<complex> scaled = a * (2.0 + 0.0i);
                      val Matrix<complex> divided = a / (2.0 + 0.0i);
                      return scaled.get(0, 0) eq (4.0 + 0.0i)
                          && scaled.trace() eq (4.0 + 0.0i)
                          && divided.get(0, 0) eq (1.0 + 0.0i);
                    }
                    """).asBoolean());
        }
    }

}
