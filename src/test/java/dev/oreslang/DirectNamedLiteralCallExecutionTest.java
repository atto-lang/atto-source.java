package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OptionalCallShapeBinder;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Staged direct local named calls: scalar primitive literals can be freely
 * permuted; closed zero-arg scalar calls may be reordered with lexical
 * evaluation before formal-slot assembly. Other nonliteral argument
 * permutations, omission, Option<T>, defaults, constructors, generic and
 * variadic calls remain disabled.
 */
final class DirectNamedLiteralCallExecutionTest {

    @Test
    void directNamedLiteralCallUsesOrdinaryRuntimeAndStaticChecks() throws Exception {
        String source = """
                pub fnc pick(int a, string b, bool c): int {
                    return a + (c ? 1 : 0);
                }
                pub routine main(): void {
                    stdio.stdout.write(pick(c: true, a: 3, b: "hi"));
                    stdio.stdout.write(":");
                    stdio.stdout.write(pick(8, c: false, b: "there"));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "named-literal-v1.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("4:8", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reorderedNamedIntLiteralPreservesAllSixtyFourBits() throws Exception {
        // A named-argument permutation must never route a signed int through
        // double (which would round 2^53 + 1 to 2^53), or change the selected
        // declaration's positional ABI.
        String source = """
                pub fnc keep(int marker, int value): int { return value; }
                pub routine main(): void {
                    stdio.stdout.write(keep(value: 9007199254740993, marker: 7));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "named-int64-permutation.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("9007199254740993", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void missingRequiredNamedSlotIsACompileTimeError() {
        String source = """
                pub fnc pick(int a, bool c): int { return a; }
                pub routine main(): void {
                    stdio.stdout.write(pick(a: 3));
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void unknownOrDuplicateLabelsAreRejected() {
        for (String call : java.util.List.of("pick(x: 3, c: true)",
                "pick(a: 3, a: 4, c: true)")) {
            String source = """
                    pub fnc pick(int a, bool c): int { return a; }
                    pub routine main(): void {
                        const int result = %s;
                        return;
                    }
                    """.formatted(call);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), call);
        }
    }

    @Test
    void sourceOrderedNamedFunctionCallsEvaluateOnceInLexicalOrder() throws Exception {
        // A direct CallExpr can preserve the exact order when every named
        // argument already binds to its declaration index. Nested effects
        // are not extracted, reordered, cloned or executed during checking.
        String source = """
                pub fnc first(): int {
                    stdio.stdout.write("A");
                    return 4;
                }
                pub fnc second(): int {
                    stdio.stdout.write("B");
                    return 3;
                }
                pub fnc combine(int left, int right): int {
                    return left * 10 + right;
                }
                pub routine main(): void {
                    stdio.stdout.write(combine(left: first(), right: second()));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "named-source-ordered-effects.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("AB43", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void sourceOrderedNamedScalarReadsAndExpressionsTypecheck() {
        String source = """
                pub fnc combine(int left, int right): int { return left * 10 + right; }
                pub routine main(): void {
                    const int value = 3;
                    const int result = combine(left: value, right: 1 + 2);
                    stdio.stdout.write(result);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void reorderedClosedScalarCallsExecuteExactlyOnceInLexicalSourceOrder() throws Exception {
        // The declared call ABI is (left, right), but the lexical order is
        // second() then first(): side effects must be BA, while result is 43.
        String source = """
                pub fnc first(): int {
                    stdio.stdout.write("A");
                    return 4;
                }
                pub fnc second(): int {
                    stdio.stdout.write("B");
                    return 3;
                }
                pub fnc combine(int left, int right): int { return left * 10 + right; }
                pub routine main(): void {
                    stdio.stdout.write(combine(right: second(), left: first()));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "named-reordered-lexical-effects.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("BA43", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void reorderedClosedScalarAndLiteralPreserveFullArityAndFormalSlots() throws Exception {
        String source = """
                pub fnc second(): int {
                    stdio.stdout.write("B");
                    return 3;
                }
                pub fnc combine(int left, int right): int { return left * 10 + right; }
                pub routine main(): void {
                    stdio.stdout.write(combine(right: second(), left: 4));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "named-reordered-literal.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("B43", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void threeArgumentPermutationRetainsLexicalEffectsAndDeclaredAbi() throws Exception {
        String source = """
                pub fnc first(): int {
                    stdio.stdout.write("A");
                    return 4;
                }
                pub fnc second(): int {
                    stdio.stdout.write("B");
                    return 2;
                }
                pub fnc third(): int {
                    stdio.stdout.write("C");
                    return 3;
                }
                pub fnc combine(int a, int b, int c): int { return a * 100 + b * 10 + c; }
                pub routine main(): void {
                    stdio.stdout.write(combine(b: second(), c: third(), a: first()));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "named-three-slot-lexical.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("BCA423", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void checkedThreeSlotPermutationMapsDenseSourceIndexesWithoutRunningGuestCode() {
        Ast.Program program = Parser.parse("""
                pub fnc combine(int a, int b, int c): int { return a * 100 + b * 10 + c; }
                """);
        Ast.FunctionDecl target = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.StagedCallExpr staged = new Ast.StagedCallExpr(
                new Ast.NameExpr("combine"), java.util.List.of(), false,
                java.util.List.of(
                        Ast.CallArgument.named("b",
                                new Ast.CallExpr(new Ast.NameExpr("second"), java.util.List.of())),
                        Ast.CallArgument.named("c",
                                new Ast.CallExpr(new Ast.NameExpr("third"), java.util.List.of())),
                        Ast.CallArgument.named("a",
                                new Ast.CallExpr(new Ast.NameExpr("first"), java.util.List.of()))));
        OptionalCallShapeBinder.Plan plan =
                OptionalCallShapeBinder.directScalarBindingPlan(staged, target);
        assertEquals(2, plan.sourceIndex(0));
        assertEquals(0, plan.sourceIndex(1));
        assertEquals(1, plan.sourceIndex(2));
        assertTrue(OptionalCallShapeBinder.isReorderedNamedCall(plan));
    }

    @Test
    void reorderedNamedCallsWithArgumentBearingNestedEffectsStayRejected() {
        String source = """
                pub fnc identity(int value): int { return value; }
                pub fnc combine(int left, int right): int { return left * 10 + right; }
                pub routine main(): void {
                    const int result = combine(right: identity(3), left: 4);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void reorderedNamedCallsCannotInvokeShadowedLocalZeroArgumentCallbacks() {
        String source = """
                pub fnc first(): int { return 4; }
                pub fnc combine(int left, int right): int { return left * 10 + right; }
                pub routine main(): void {
                    const int first = 9;
                    const int result = combine(right: first(), left: 4);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void reorderedNamedCallsStillRejectIncorrectScalarReturnType() {
        String source = """
                pub fnc words(): string { return "not-int"; }
                pub fnc combine(int left, int right): int { return left * 10 + right; }
                pub routine main(): void {
                    const int result = combine(right: words(), left: 4);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void suppliedNoneAndNonScalarValuesRemainDisabled() {
        String source = """
                pub fnc pick(int a): int { return a; }
                pub routine main(): void {
                    const int result = pick(a: None);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void localShadowingCannotBeMistakenForResolvedCallable() {
        String source = """
                pub fnc pick(int a): int { return a; }
                pub routine main(): void {
                    const int pick = 9;
                    const int value = pick(a: 3);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void astSourceOrderProofRejectsNonliteralPermutationWithoutExecuting() {
        Ast.Program program = Parser.parse("""
                pub fnc combine(int left, int right): int { return left * 10 + right; }
                """);
        Ast.FunctionDecl target = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.Expr left = new Ast.NameExpr("first_value");
        Ast.Expr right = new Ast.NameExpr("second_value");
        Ast.StagedCallExpr ordered = new Ast.StagedCallExpr(
                new Ast.NameExpr("combine"), java.util.List.of(), false,
                java.util.List.of(Ast.CallArgument.named("left", left),
                                  Ast.CallArgument.named("right", right)));
        Ast.CallExpr lowered =
                OptionalCallShapeBinder.lowerDirectScalarLiteralNamedCall(ordered, target);
        assertSame(left, lowered.arguments().get(0));
        assertSame(right, lowered.arguments().get(1));

        Ast.StagedCallExpr reordered = new Ast.StagedCallExpr(
                new Ast.NameExpr("combine"), java.util.List.of(), false,
                java.util.List.of(Ast.CallArgument.named("right", right),
                                  Ast.CallArgument.named("left", left)));
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.lowerDirectScalarLiteralNamedCall(reordered, target));
        assertTrue(rejected.getMessage().contains("closed scalar source-order lowering"));
    }

    @Test
    void directAstLoweringRejectsPrimitiveMismatchesWithoutTypeChecker() {
        Ast.Program source = Parser.parse("""
                pub fnc pick(int a, bool c): int { return a; }
                """);
        Ast.FunctionDecl target = (Ast.FunctionDecl)
                source.modules().getFirst().declarations().getFirst();
        Ast.StagedCallExpr forged = new Ast.StagedCallExpr(
                new Ast.NameExpr("pick"), java.util.List.of(), false,
                java.util.List.of(
                        Ast.CallArgument.named("c", new Ast.LiteralExpr("true")),
                        Ast.CallArgument.named("a", new Ast.LiteralExpr(3L))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.lowerDirectScalarLiteralNamedCall(forged, target));
    }

    @Test
    void directAstLoweringCannotPassStringIntoRequiredOption() {
        Ast.Program source = Parser.parse("""
                pub fnc pick(Option<string> b): int { return 1; }
                """);
        Ast.FunctionDecl target = (Ast.FunctionDecl)
                source.modules().getFirst().declarations().getFirst();
        Ast.StagedCallExpr forged = new Ast.StagedCallExpr(
                new Ast.NameExpr("pick"), java.util.List.of(), false,
                java.util.List.of(Ast.CallArgument.named("b", new Ast.LiteralExpr("fake"))));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.lowerDirectScalarLiteralNamedCall(forged, target));
    }

    @Test
    void scalarMismatchesRemainRejectedByTypechecker() {
        String source = """
                pub fnc pick(int a, bool c): int { return a; }
                pub routine main(): void {
                    val int result = pick(a: "three", c: true);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void optionalFormalsAndNamedConstructorsRemainFailClosed() {
        String optional = """
                pub fnc pick(int a, string? b, bool c): int { return a; }
                pub routine main(): void {
                    const int value = pick(a: 3, c: true);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(optional)));
        String constructor = """
                define class Box as
                    val int value;
                    constructor(int value) { self.value = value; }
                end
                pub routine main(): void {
                    val box = new Box(value: 3);
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(constructor)));
    }
}
