package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.LiteralOptionalCallCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Experimental literal-only T? lowering must be admitted by the real
 * TypeChecker and OwnershipChecker, not by a shape-only witness.
 */
final class LiteralOptionalCallCompilerTest {
    private static String source(String call) {
        return """
                define module app as
                    pub fnc greet(string? name): string {
                        return match name over
                            on Some(n) -> n
                            on None -> "anonymous"
                        end
                    }
                    pub fnc caller(): string {
                        return %s;
                    }
                end
                """.formatted(call);
    }

    @Test
    void suppliedNamedScalarBecomesCheckedOrdinarySomeCall() {
        var lowered = LiteralOptionalCallCompiler.lowerAndAnalyze(
                source("greet(name: \"Alice\")"), "app", "caller");
        assertEquals(1, lowered.loweredCall().arguments().size());
        Ast.CallExpr some = assertInstanceOf(
                Ast.CallExpr.class, lowered.loweredCall().arguments().getFirst());
        assertEquals("Some", ((Ast.NameExpr) some.callee()).name());
        assertEquals("Alice", ((Ast.LiteralExpr) some.arguments().getFirst()).value());
        assertEquals("Option", lowered.checkedTarget().parameters().getFirst().type().name());
        assertEquals(Ast.TypeRef.simple("string"),
                lowered.checkedTarget().parameters().getFirst().type().arguments().getFirst());
        assertSame(((Ast.ReturnStmt) lowered.checkedCaller().body().getFirst()).value(),
                lowered.loweredCall());
        assertSame(lowered.checkedCaller(),
                lowered.checkedProgram().modules().getFirst().declarations().get(1));
    }

    @Test
    void explicitOmissionBecomesCheckedTypedNoneValue() {
        var lowered = LiteralOptionalCallCompiler.lowerAndAnalyze(
                source("greet(name: _)"), "app", "caller");
        Ast.NameExpr none = assertInstanceOf(
                Ast.NameExpr.class, lowered.loweredCall().arguments().getFirst());
        assertEquals("None", none.name());
        assertEquals(Ast.TypeRef.simple("string"),
                lowered.checkedTarget().parameters().getFirst().type().arguments().getFirst());
    }

    @Test
    void ordinaryCompilerContinuesToRejectUnloweredOptionalSource() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(Parser.parse(source("greet(name: \"Alice\")"))));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(Parser.parse(source("greet(name: _)"))));
    }

    @Test
    void wrongScalarAndUnverifiedOptionValuesCannotBeLowered() {
        for (String call : List.of(
                "greet(name: true)",
                "greet(name: 8)",
                "greet(name: None)",
                "greet(name: unknown)",
                "greet(name: Some(\"Alice\"))")) {
            assertThrows(IllegalArgumentException.class,
                    () -> LiteralOptionalCallCompiler.lowerAndAnalyze(
                            source(call), "app", "caller"), call);
        }
    }

    @Test
    void effectfulOrOrdinaryUnprovedCallsCannotUseLiteralOnlyLowering() {
        for (String call : List.of("greet(name: sideEffect())", "greet()")) {
            assertThrows(IllegalArgumentException.class,
                    () -> LiteralOptionalCallCompiler.lowerAndAnalyze(
                            source(call), "app", "caller"), call);
        }
    }

    @Test
    void optionalConstructorShadowingIsNotAuthorized() {
        String bad = """
                define module app as
                    pub fnc Some(string x): string { return x; }
                    pub fnc greet(string? name): string { return "ok"; }
                    pub fnc caller(): string { return greet(name: "Alice"); }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> LiteralOptionalCallCompiler.lowerAndAnalyze(bad, "app", "caller"));
    }

    @Test
    void checkedResultCannotBeConstructedOutsideTheFullCompiler() {
        assertEquals(0, LiteralOptionalCallCompiler.Result.class.getConstructors().length);
    }
    @Test
    void reordersRequiredAndOptionalScalarArgumentsAfterFullAdmission() {
        String source = """
                define module app as
                    pub fnc pick(int a, string? label, bool enabled): int {
                        return a;
                    }
                    pub fnc caller(): int {
                        return pick(enabled: true, label: "Alice", a: 7);
                    }
                end
                """;
        var checked = LiteralOptionalCallCompiler.lowerAndAnalyze(source, "app", "caller");
        assertEquals(3, checked.loweredCall().arguments().size());
        assertEquals(7L, ((Ast.LiteralExpr) checked.loweredCall()
                .arguments().get(0)).value());
        var some = assertInstanceOf(Ast.CallExpr.class,
                checked.loweredCall().arguments().get(1));
        assertEquals("Some", ((Ast.NameExpr) some.callee()).name());
        assertEquals("Alice", ((Ast.LiteralExpr) some.arguments().getFirst()).value());
        assertEquals(true, ((Ast.LiteralExpr) checked.loweredCall()
                .arguments().get(2)).value());
    }

    @Test
    void omittedOptionalSlotDoesNotConsumeAnArgumentTemporary() {
        String source = """
                define module app as
                    pub fnc pick(int a, string? label, bool enabled): int {
                        return a;
                    }
                    pub fnc caller(): int {
                        return pick(enabled: true, label: _, a: 7);
                    }
                end
                """;
        var checked = LiteralOptionalCallCompiler.lowerAndAnalyze(source, "app", "caller");
        assertEquals(3, checked.loweredCall().arguments().size());
        assertEquals("None", ((Ast.NameExpr) checked.loweredCall()
                .arguments().get(1)).name());
        assertEquals(7L, ((Ast.LiteralExpr) checked.loweredCall()
                .arguments().get(0)).value());
    }

}
