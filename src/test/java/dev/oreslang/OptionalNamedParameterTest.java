package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OptionalNamedParameterTest {
    @Test
    void typeQuestionAndNameQuestionAreIndependentSyntax() {
        Ast.Program program = Parser.parse("""
                fnc sample(string? required, int count?): int {
                    return 1;
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();

        Ast.Param required = fn.parameters().get(0);
        assertEquals("Option", required.type().name());
        assertTrue(required.optionTypeSugar());
        assertFalse(required.omittable());

        Ast.Param omittable = fn.parameters().get(1);
        assertEquals("int", omittable.type().name());
        assertFalse(omittable.optionTypeSugar());
        assertTrue(omittable.omittable());
    }

    @Test
    void scalarDefaultCanFillNonTrailingNamedHole() throws Exception {
        String code = """
                @DefaultArgVals(b: 2)
                fnc calculate(int a, int b?, int c): int {
                    return a + b + c;
                }

                pub routine main(): void {
                    stdio.stdout.write(calculate(10, c: 30));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void omittedOptionParameterDefaultsToNoneWithoutHiddenExpression()
            throws Exception {
        String code = """
                fnc constant(string? value?): int {
                    return 42;
                }

                pub routine main(): void {
                    stdio.stdout.write(constant());
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void sourceEffectsStayLexicalWhileDefaultFillsAbiHole() throws Exception {
        String code = """
                fnc first(): int {
                    stdio.stdout.write("A");
                    return 10;
                }

                fnc last(): int {
                    stdio.stdout.write("C");
                    return 30;
                }

                @DefaultArgVals(b: 2)
                fnc calculate(int a, int b?, int c): int {
                    return a + b + c;
                }

                pub routine main(): void {
                    stdio.stdout.write(
                            calculate(c: last(), a: first()));
                    return;
                }
                """;

        assertEquals("CA42", checkedExecution(code));
    }

    @Test
    void exactDeclaredArityPrecedesOptionalFallback() throws Exception {
        String code = """
                fnc choose(int a): int {
                    return 1;
                }

                @DefaultArgVals(b: 9)
                fnc choose(int a, int b?): int {
                    return 2;
                }

                pub routine main(): void {
                    stdio.stdout.write(choose(5));
                    return;
                }
                """;

        assertEquals("1", checkedExecution(code));
    }

    @Test
    void multipleOptionalFallbackAritiesAreAmbiguous() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(b: 2)
                        fnc choose(int a, int b?): int {
                            return 2;
                        }

                        @DefaultArgVals(b: 2, c: 3)
                        fnc choose(int a, int b?, int c?): int {
                            return 3;
                        }

                        pub routine main(): void {
                            stdio.stdout.write(choose(1));
                            return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("ambiguous"), failure.getMessage());
    }

    @Test
    void scalarOmissionRequiresClosedLiteralDefault() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc missing(int value?): int {
                            return value;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("DefaultArgVals"),
                failure.getMessage());
    }

    @Test
    void defaultMetadataMustNameAnOmittableExistingParameter() {
        IllegalArgumentException nonOmittable = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(value: 2)
                        fnc bad(int value): int {
                            return value;
                        }
                        """)));
        assertTrue(
                nonOmittable.getMessage().contains("omittable"),
                nonOmittable.getMessage());

        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(nope: 2)
                        fnc bad(int value): int {
                            return value;
                        }
                        """)));
        assertTrue(
                unknown.getMessage().contains("unknown parameter"),
                unknown.getMessage());
    }

    @Test
    void defaultLiteralTypeMustMatchFormalType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(value: "wrong")
                        fnc bad(int value?): int {
                            return value;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("default")
                        || failure.getMessage().contains("expected"),
                failure.getMessage());
    }

    @Test
    void overflowingDefaultRejectsBeforeRuntime() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(value: 9223372036854775808)
                        fnc bad(int value?): int {
                            return value;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("overflows"),
                failure.getMessage());
    }

    @Test
    void noneCannotDefaultANonOptionFormal() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(value: None)
                        fnc bad(int value?): int {
                            return value;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("None")
                        || failure.getMessage().contains("Option"),
                failure.getMessage());
    }

    @Test
    void duplicateDefaultAnnotationsReject() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(value: 1)
                        @DefaultArgVals(value: 2)
                        fnc bad(int value?): int {
                            return value;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("more than once")
                        || failure.getMessage().contains("duplicate"),
                failure.getMessage());
    }

    @Test
    void defaultMetadataOnUnsupportedDeclarationRejects() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(value: 1)
                        val int value = 1;
                        """)));
        assertTrue(
                failure.getMessage().contains("DefaultArgVals"),
                failure.getMessage());
    }

    @Test
    void requiredOptionSugarDoesNotBecomeOmittable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc required(string? value): int {
                            return 1;
                        }

                        pub routine main(): void {
                            stdio.stdout.write(required());
                            return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("shape")
                        || failure.getMessage().contains("overload")
                        || failure.getMessage().contains("argument"),
                failure.getMessage());
    }

    @Test
    void omittableMethodAndConstructorSurfacesRemainFailClosed() {
        IllegalArgumentException method = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          value(int x?): int {
                            return 1;
                          }
                        end
                        """)));
        assertTrue(method.getMessage().contains("fail-closed"), method.getMessage());

        IllegalArgumentException constructor = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          constructor(int x?) {
                            return;
                          }
                        end
                        """)));
        assertTrue(
                constructor.getMessage().contains("fail-closed"),
                constructor.getMessage());
    }

    private static String checkedExecution(String code) throws Exception {
        Ast.Program parsed = Parser.parse(code);
        var typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID,
                            code,
                            "optional-named-parameter.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
