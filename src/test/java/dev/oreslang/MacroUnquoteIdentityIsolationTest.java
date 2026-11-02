package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.MacroExpander;
import dev.oreslang.compiler.MacroSyntaxProvenance;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MacroUnquoteIdentityIsolationTest {
    private static Ast.Program parse(String value) {
        return Parser.parse("""
                define macro add_one(Syntax<Expr> value) -> Syntax<Expr> {
                    return quote(unquote(value) + 1);
                }

                fnc answer() -> int {
                    return add_one!(syntax expr %s);
                }
                """.formatted(value));
    }

    private static Ast.MacroSyntaxExprArgument input(Ast.Program program) {
        Ast.FunctionDecl function = program.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, function.body().getFirst());
        Ast.MacroInvocationExpr invocation =
                assertInstanceOf(Ast.MacroInvocationExpr.class, returned.value());
        return assertInstanceOf(
                Ast.MacroSyntaxExprArgument.class, invocation.arguments().getFirst());
    }

    private static Ast.Expr expandedValue(Ast.Program program) {
        Ast.FunctionDecl function = program.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .findFirst()
                .orElseThrow();
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, function.body().getFirst());
        return returned.value();
    }

    @Test
    void compositeCallerSyntaxFailsClosedBeforeProvenanceTouchesOriginalTree() {
        Ast.Program program = parse("40 + 2");
        Ast.Expr original = input(program).expression();
        Ast.BinaryExpr originalLeft = assertInstanceOf(Ast.BinaryExpr.class, original);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> MacroExpander.expand(program));

        assertTrue(failure.getMessage().contains("composite or compiler-internal syntax"),
                failure::toString);
        assertTrue(MacroSyntaxProvenance.lookup(original).isEmpty(),
                "failed expansion must not publish provenance onto caller-owned root syntax");
        assertTrue(MacroSyntaxProvenance.lookup(originalLeft.left()).isEmpty(),
                "failed expansion must not contaminate a nested caller-owned syntax node");
    }

    @Test
    void supportedScalarSpliceUsesFreshIdentityAndPreservesPriorProvenance() {
        Ast.Program program = parse("2");
        Ast.Expr original = input(program).expression();
        assertInstanceOf(Ast.LiteralExpr.class, original);
        MacroSyntaxProvenance.Origin prior = new MacroSyntaxProvenance.Origin(
                new MacroSyntaxProvenance.HygieneIdentity(
                        "test-unit", "Main", "Main", "prior",
                        new Ast.SourceSite(1, 1), 0),
                new Ast.SourceSite(1, 1),
                MacroSyntaxProvenance.EmissionKind.CONST_QUOTE);
        MacroSyntaxProvenance.record(original, prior);

        Ast.Program expanded = assertDoesNotThrow(() -> MacroExpander.expand(program));
        Ast.BinaryExpr result = assertInstanceOf(Ast.BinaryExpr.class, expandedValue(expanded));
        Ast.Expr emitted = result.left();

        assertNotSame(original, emitted,
                "each emitted splice occurrence must have a distinct AST identity");
        assertEquals(original, emitted);
        assertTrue(MacroSyntaxProvenance.lookup(emitted).contains(prior),
                "existing compiler-issued provenance must follow the copied syntax identity");
        assertTrue(MacroSyntaxProvenance.lookup(emitted).size() > 1,
                "the emitted leaf must also receive the current expansion provenance");
        assertEquals(java.util.List.of(prior), MacroSyntaxProvenance.lookup(original),
                "the original caller-owned leaf must retain only its preexisting provenance");
    }

    @Test
    void independentExpansionsDoNotShareCallerOrEmittedAstIdentity() {
        Ast.Program first = parse("7");
        Ast.Program second = parse("7");
        Ast.Expr firstInput = input(first).expression();
        Ast.Expr secondInput = input(second).expression();

        Ast.Program firstExpanded = MacroExpander.expand(first);
        Ast.Program secondExpanded = MacroExpander.expand(second);
        Ast.Expr firstOutput = assertInstanceOf(
                Ast.BinaryExpr.class, expandedValue(firstExpanded)).left();
        Ast.Expr secondOutput = assertInstanceOf(
                Ast.BinaryExpr.class, expandedValue(secondExpanded)).left();

        assertNotSame(firstInput, secondInput);
        assertNotSame(firstOutput, secondOutput);
        assertNotSame(firstInput, firstOutput);
        assertNotSame(secondInput, secondOutput);
        assertTrue(MacroSyntaxProvenance.lookup(firstInput).isEmpty());
        assertTrue(MacroSyntaxProvenance.lookup(secondInput).isEmpty());
        assertFalse(MacroSyntaxProvenance.lookup(firstOutput).isEmpty());
        assertFalse(MacroSyntaxProvenance.lookup(secondOutput).isEmpty());
    }
}
