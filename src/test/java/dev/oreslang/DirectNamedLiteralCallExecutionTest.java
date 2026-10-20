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
 * First executable named-value-argument subset. Only direct non-generic local
 * functions/routines with a full set of primitive scalar literal arguments
 * are enabled. Nothing here enables omission, Option<T>, defaults, dynamic
 * argument evaluation, constructors or varargs.
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
    void effectfulNamedValueAndSuppliedNoneRemainDisabled() {
        for (String argument : java.util.List.of("effect()", "None", "value")) {
            String source = """
                    pub fnc pick(int a): int { return a; }
                    pub fnc effect(): int { return 7; }
                    pub routine main(): void {
                        const int value = 9;
                        const int result = pick(a: %s);
                        return;
                    }
                    """.formatted(argument);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), argument);
        }
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
