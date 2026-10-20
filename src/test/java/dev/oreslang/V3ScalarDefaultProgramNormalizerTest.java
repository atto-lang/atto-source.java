package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.V3ScalarDefaultProgramNormalizer;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class V3ScalarDefaultProgramNormalizerTest {
    @Test
    void defaultedMiddleSlotIsExecutableAfterRealCompilerChecks() throws Exception {
        String source = """
                @DefaultArgVals(b: 10)
                pub fnc calculate(int a, int b?, int c): int {
                    return a + b + c;
                }
                pub fnc main(): int {
                    return calculate(c: 3, a: 5);
                }
                """;
        Ast.Program normalized =
                V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze(source);
        assertDoesNotThrow(() -> TypeChecker.check(normalized));
        assertDoesNotThrow(() -> OwnershipChecker.check(normalized));
        var functions = normalized.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast).toList();
        assertEquals(2, functions.size());
        assertTrue(functions.stream().flatMap(fn -> fn.parameters().stream())
                .noneMatch(p -> p.hasDefault() || p.argumentOmittable()));
        assertTrue(functions.stream().allMatch(fn -> fn.annotations().isEmpty()));

        Source polyglot = Source.newBuilder(OresLanguage.ID, source, "v3-default.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build();
             OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram("v3-default.ores", source, normalized)) {
            assertEquals(18L, context.eval(polyglot).asLong());
        }
    }

    @Test
    void parameterLocalDefaultIsAlsoRunnableThroughTheCheckedPolyglotHost() throws Exception {
        String source = """
                pub fnc add(int a, @Default(10) int b, int c): int {
                    return a + b + c;
                }
                pub fnc main(): int {
                    return add(a: 5, c: 3);
                }
                """;
        var normalized = V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze(source);
        assertEquals(2, normalized.modules().getFirst().declarations().size());
        Source polyglot = Source.newBuilder(OresLanguage.ID, source, "v3-formal.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build();
             OresLanguage.PrecheckedProgramScope ignored =
                     OresLanguage.openPrecheckedProgram("v3-formal.ores", source, normalized)) {
            assertEquals(18L, context.eval(polyglot).asLong());
        }
    }

    @Test
    void explicitOmitUsesTheDefaultInFullArityNormalizedCall() {
        var program = V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                @DefaultArgVals(b: 10)
                pub fnc calculate(int a, int b?, int c): int {
                    return a + b + c;
                }
                pub fnc main(): int {
                    return calculate(a: 5, b: _, c: 3);
                }
                """);
        var fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        var called = (Ast.CallExpr) ((Ast.ReturnStmt) fn.body().getFirst()).value();
        assertEquals(3, called.arguments().size());
        assertEquals(10L, ((Ast.LiteralExpr) called.arguments().get(1)).value());
    }

    @Test
    void originalStagedSourceDoesNotBecomeEnabledThroughNormalCompiler() {
        String source = """
                @DefaultArgVals(b: 10)
                pub fnc add(int a, int b?): int { return a + b; }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void rejectsUnprovenEffectsAndGlobalBindingsBeforeGuestExecution() {
        for (String source : List.of(
                """
                @DefaultArgVals(b: 10)
                pub fnc add(int a, int b?): int { return a + b; }
                pub fnc main(): int { return add(a: compute()); }
                """,
                """
                pub fnc main(): int { return globalUnknown; }
                """,
                """
                pub fnc main(): int { return myfunc(4); }
                """,
                """
                pub fnc main(): int {
                    val int x = 2;
                    return x;
                }
                """)) {
            assertThrows(IllegalArgumentException.class,
                    () -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze(source),
                    source);
        }
    }

    @Test
    void rejectsOptionSemanticsUntilNominalRuntimeAdapterIsVerified() {
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                        pub fnc f(Option<int> b?): int { return 1; }
                        pub fnc main(): int { return f(); }
                        """));
    }

    @Test
    void rejectsOverloadsClassesModulesImportsAndUnknownAnnotations() {
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                        pub fnc f(int x): int { return x; }
                        pub fnc f(int x, int y): int { return x + y; }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                        define class Box as
                          val int a;
                        end
                        """));
    }

    @Test
    void rejectsShadowedCalleeEvenWithIdenticalNameAndFullArity() {
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultProgramNormalizer.parseNormalizeAndAnalyze("""
                        @DefaultArgVals(a: 3)
                        pub fnc f(int a?): int { return a; }
                        pub fnc main(int f): int { return f(a: 5); }
                        """));
    }
}
