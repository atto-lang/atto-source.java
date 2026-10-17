package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nested ternary false-arm inheritance: strictly bounded syntactic desugaring.
 * This suite must execute on the exact PR tree before feature admission.
 */
final class NestedTernaryInheritanceTest {
    private static void accept(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void reject(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void ordinaryNestedConditionalUsesOuterIntegerNarrowing() {
        accept("""
                pub fnc foo(any v): int {
                    return Integer.is_int(v) ? (v % 7 == 0 ? 3 : 5) : 0;
                }
                """);
    }

    @Test
    void explicitNestedConditionalSelectsExactlyOneBranch() throws Exception {
        assertEquals("350", run("""
                fnc foo(any v): int {
                    return Integer.is_int(v) ? (v % 7 == 0 ? 3 : 5) : 0;
                }
                pub fnc main(): void {
                    stdio.stdout.write(foo(7));
                    stdio.stdout.write(foo(8));
                    stdio.stdout.write(foo("not an int"));
                    return;
                }
                """));
    }

    @Test
    void inheritedElseReturnsOuterFallback() throws Exception {
        String src = """
                fnc foo(any v): int {
                    return Integer.is_int(v) ? (v % 7 == 0 ? 3) : 0;
                }
                pub fnc main(): void {
                    stdio.stdout.write(foo(7));
                    stdio.stdout.write(foo(8));
                    stdio.stdout.write(foo("x"));
                    return;
                }
                """;
        accept(src);
        assertEquals("300", run(src));
    }

    @Test
    void deeperNestedOmissionsUseNearestCompleteEnclosingFalseArm() throws Exception {
        assertEquals("3000", run("""
                fnc choose(bool a, bool b, bool c): int {
                    return a ? (b ? (c ? 3)) : 0;
                }
                pub fnc main(): void {
                    stdio.stdout.write(choose(true, true, true));
                    stdio.stdout.write(choose(true, true, false));
                    stdio.stdout.write(choose(true, false, true));
                    stdio.stdout.write(choose(false, true, true));
                    return;
                }
                """));
    }

    @Test
    void inheritedFallbackExecutesOnceEvenWithSideEffects() throws Exception {
        assertEquals("3F0F0", run("""
                fnc fallback(): int {
                    stdio.stdout.write("F");
                    return 0;
                }
                fnc choose(bool a, bool b): int {
                    return a ? (b ? 3) : fallback();
                }
                pub fnc main(): void {
                    stdio.stdout.write(choose(true, true));
                    stdio.stdout.write(choose(true, false));
                    stdio.stdout.write(choose(false, true));
                    return;
                }
                """));
    }

    @Test
    void guardOnOuterElseIsAvailableAsNestedFallback() throws Exception {
        assertEquals("3121", run("""
                fnc foo(any v): int {
                    return Integer.is_int(v)
                        ? (v % 7 == 0 ? 3)
                        : (Double.is_double(v) ? 2 : 1);
                }
                pub fnc main(): void {
                    stdio.stdout.write(foo(7));
                    stdio.stdout.write(foo(8));
                    stdio.stdout.write(foo(7.5));
                    stdio.stdout.write(foo("x"));
                    return;
                }
                """));
    }

    @Test
    void noImplicitFloatingPointToIntConversion() {
        reject("""
                pub fnc bad(any v): int {
                    return Integer.is_int(v) ? (v % 7 == 0 ? 3 : 5)
                        : (Double.is_double(v) ? v : 1);
                }
                """);
        reject("""
                pub fnc bad(any v): int {
                    return Integer.is_int(v) ? (v % 7 == 0 ? 3)
                        : (Double.is_double(v) ? v : 1);
                }
                """);
    }

    @Test
    void doublePredicateRecognizesOnlyFloatingPointRuntimeValues() throws Exception {
        assertEquals("falsetruetruefalse", run("""
                pub fnc main(): void {
                    stdio.stdout.write(Double.is_double(7));
                    stdio.stdout.write(Double.is_double(7.5));
                    stdio.stdout.write(Double.is_double(-0.0));
                    stdio.stdout.write(Double.is_double("7.5"));
                    return;
                }
                """));
    }

    @Test
    void doubleGuardNarrowsForFloatingPointOperations() throws Exception {
        String program = """
                fnc foo(any v): float {
                    return Double.is_double(v) ? v * 2.0 : 0.0;
                }
                pub fnc main(): void {
                    stdio.stdout.write(foo(2.5));
                    stdio.stdout.write(foo("x"));
                    return;
                }
                """;
        accept(program);
        assertEquals("5.00.0", run(program));
    }

    @Test
    void userDefinedDoubleNamespaceCannotForgeRefinement() {
        reject("""
                define module Double
                  pub fnc is_double(any value): bool { return true; }
                end
                pub fnc bad(any v): float {
                  return Double.is_double(v) ? v : 0.0;
                }
                """);
    }

    @Test
    void missingFalseBranchCannotEscapeThroughChainedTrueExpression() {
        reject("""
                pub fnc bad(): int {
                    return true ? ((false ? 3) + 5) : 0;
                }
                """);
        reject("""
                pub fnc bad(): int {
                    return true ? (false ? 3) : (false ? 4);
                }
                """);
    }

    @Test
    void inheritedFallbackTypechecksBothPaths() {
        reject("""
                pub fnc bad(any v): int {
                    return Integer.is_int(v) ? (v % 7 == 0 ? 3) : v;
                }
                """);
    }

    @Test
    void nestedFallbackChoosesNearestEnclosingElse() throws Exception {
        assertEquals("310", run("""
                fnc choose(bool a, bool b, bool c): int {
                    return a ? (b ? (c ? 3) : 1) : 0;
                }
                pub fnc main(): void {
                    stdio.stdout.write(choose(true, true, true));
                    stdio.stdout.write(choose(true, true, false));
                    stdio.stdout.write(choose(false, true, true));
                    return;
                }
                """));
    }

    @Test
    void importedTypeNameDoesNotCountAsBuiltinGuard() {
        reject("""
                import type Other as Double from "types.ores";
                pub fnc bad(any v): float {
                    return Double.is_double(v) ? v : 0.0;
                }
                """);
        reject("""
                import type Other as Integer from "types.ores";
                pub fnc bad(any v): int {
                    return Integer.is_int(v) ? v : 0;
                }
                """);
    }

    @Test
    void scalarGuardsDoNotRemoveBorrowContracts() {
        reject("""
                pub fnc bad(borrow any v): int {
                    return Integer.is_int(v) ? v : 0;
                }
                """);
        reject("""
                pub fnc bad(borrow unknown v): float {
                    return Double.is_double(v) ? v : 0.0;
                }
                """);
    }

    @Test
    void scalarGuardsCanAcceptAlreadyTypedImmutableValues() {
        accept("""
                pub fnc foo(int v): int {
                    return Integer.is_int(v) ? v : 0;
                }
                pub fnc bar(float v): float {
                    return Double.is_double(v) ? v : 0.0;
                }
                """);
    }

    @Test
    void inheritedMoveOnlyFallbackIsCheckedAsMutuallyExclusiveBranches() {
        var program = Parser.parse("""
                pub fnc choose(bool outer, bool inner, any fallback): any {
                    return outer ? (inner ? 3) : fallback;
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
        assertDoesNotThrow(() -> dev.oreslang.types.OwnershipChecker.check(program));
    }

    @Test
    void noMissingTopLevelElseOrInvalidInheritancePosition() {
        reject("pub fnc bad(): int { return true ? 1; }");
        reject("pub fnc bad(): int { return true ? 1 : (false ? 2); }");
        reject("pub fnc bad(): int { return true ? ((false ? 2) + 1) : 0; }");
        reject("""
                fnc id(int v): int { return v; }
                pub fnc bad(): int { return true ? id(false ? 2) : 0; }
                """);
    }

    @Test
    void conditionsRemainStaticallyBooleanAndNestedAssociativityIsPreserved() {
        reject("pub fnc bad(): int { return 1 ? (false ? 3) : 0; }");
        accept("""
                pub fnc right(bool a, bool b): int {
                    return a ? 1 : b ? 2 : 3;
                }
                """);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "nested-ternary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
