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
    void omittableLambdaSyntaxFailsClosedInBothLambdaSpellings() {
        for (String source : java.util.List.of(
                """
                pub routine main(): void {
                    val f = (int value?) -> value;
                    return;
                }
                """,
                """
                pub routine main(): void {
                    val f = |int value?| -> value;
                    return;
                }
                """)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse(source));
            assertTrue(
                    failure.getMessage().contains("omittable lambda"),
                    failure.getMessage());
        }
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
    void implicitArgumentsTupleContainsMaterializedDefaultSlots()
            throws Exception {
        String code = """
                @DefaultArgVals(b: 2)
                fnc inspect(int a, int b?, int c): int {
                    return arguments[1];
                }

                pub routine main(): void {
                    stdio.stdout.write(inspect(10, c: 30));
                    return;
                }
                """;

        assertEquals("2", checkedExecution(code));
    }

    @Test
    void omittedOptionSlotMaterializesAsNoneInsideCallee()
            throws Exception {
        String code = """
                fnc missing(string? value?): bool {
                    return value.is_none();
                }

                pub routine main(): void {
                    stdio.stdout.write(missing());
                    return;
                }
                """;

        assertEquals("true", checkedExecution(code));
    }

    @Test
    void asyncDirectCallUsesSameOmissionPlanAndDefaults()
            throws Exception {
        String code = """
                @DefaultArgVals(b: 2)
                fnc calculate(int a, int b?, int c): int {
                    return a + b + c;
                }

                async fnc forwarded(): int {
                    return calculate(c: 30, a: 10);
                }

                pub routine main(): void {
                    stdio.stdout.write(await forwarded());
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
    void closedRecordPackCanSkipDefaultedNamedSlot() throws Exception {
        String code = """
                @DefaultArgVals(b: 2)
                fnc calculate(int a, int b?, int c): int {
                    return a + b + c;
                }

                pub routine main(): void {
                    stdio.stdout.write(
                            calculate(...record{c: 30, a: 10}));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void positionalArgumentsCannotSkipMiddleOptionalSlot()
            throws Exception {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(b: 2)
                        fnc calculate(int a, int b?, int c): int {
                            return a + b + c;
                        }

                        pub routine main(): void {
                            stdio.stdout.write(calculate(10, 30));
                            return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("required")
                        || failure.getMessage().contains("argument")
                        || failure.getMessage().contains("shape"),
                failure.getMessage());
    }

    @Test
    void tupleSpreadCannotJumpAcrossMiddleOptionalSlot()
            throws Exception {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        @DefaultArgVals(b: 2)
                        fnc calculate(int a, int b?, int c): int {
                            return a + b + c;
                        }

                        pub routine main(): void {
                            const prefix = (10, 30);
                            stdio.stdout.write(calculate(...prefix));
                            return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("required")
                        || failure.getMessage().contains("argument")
                        || failure.getMessage().contains("shape"),
                failure.getMessage());
    }

    @Test
    void tupleSpreadCanSupplyPrefixAndOmitTrailingDefaults()
            throws Exception {
        String code = """
                @DefaultArgVals(c: 3)
                fnc calculate(int a, int b, int c?): int {
                    return a + b + c;
                }

                pub routine main(): void {
                    const prefix = (19, 20);
                    stdio.stdout.write(calculate(...prefix));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void multipleTrailingDefaultsCanAllBeAbsent()
            throws Exception {
        String code = """
                @DefaultArgVals(b: 20, c: 21)
                fnc calculate(int a, int b?, int c?): int {
                    return a + b + c;
                }

                pub routine main(): void {
                    stdio.stdout.write(calculate(1));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void explicitOptionDefaultNoneMatchesImplicitNoneSemantics()
            throws Exception {
        String code = """
                @DefaultArgVals(value: None)
                fnc missing(string? value?): bool {
                    return value.is_none();
                }

                pub routine main(): void {
                    stdio.stdout.write(missing());
                    return;
                }
                """;

        assertEquals("true", checkedExecution(code));
    }

    @Test
    void exactArityLabelFailureDoesNotFallbackToLargerOptionalOverload() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc choose(int a, int b): int {
                            return 2;
                        }

                        @DefaultArgVals(c: 3)
                        fnc choose(int x, int y, int c?): int {
                            return 3;
                        }

                        pub routine main(): void {
                            stdio.stdout.write(choose(x: 1, y: 2));
                            return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("unknown")
                        || failure.getMessage().contains("argument"),
                failure.getMessage());
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
    void negativeIntegerBooleanAndStringDefaultsMaterializeExactly()
            throws Exception {
        String code = """
                @DefaultArgVals(delta: -2, enabled: true, label: "ok")
                fnc defaults(int delta?, bool enabled?, string label?): int {
                    if enabled; then
                        if label eq "ok"; then
                            return 44 + delta;
                        fi
                    fi
                    return 0;
                }

                pub routine main(): void {
                    stdio.stdout.write(defaults());
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
    }

    @Test
    void explicitDefaultDoesNotOverrideSuppliedValue()
            throws Exception {
        String code = """
                @DefaultArgVals(value: 2)
                fnc choose(int value?): int {
                    return value;
                }

                pub routine main(): void {
                    stdio.stdout.write(choose(value: 42));
                    return;
                }
                """;

        assertEquals("42", checkedExecution(code));
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
    void optionAliasOmissionRequiresExplicitCheckedNoneDefault() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        type MaybeInt = Option<int>;

                        fnc absent(MaybeInt value?): bool {
                            return value.is_none();
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("DefaultArgVals")
                        || failure.getMessage().contains("implicit None"),
                failure.getMessage());
    }

    @Test
    void optionAliasMayOmitWithExplicitNoneDefault() throws Exception {
        String code = """
                type MaybeInt = Option<int>;

                @DefaultArgVals(value: None)
                fnc absent(MaybeInt value?): bool {
                    return value.is_none();
                }

                pub routine main(): void {
                    stdio.stdout.write(absent());
                    return;
                }
                """;

        assertEquals("true", checkedExecution(code));
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

        IllegalArgumentException staticFunction = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          static fnc value(int x?): int {
                            return 1;
                          }
                        end
                        """)));
        assertTrue(
                staticFunction.getMessage().contains("fail-closed"),
                staticFunction.getMessage());

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
