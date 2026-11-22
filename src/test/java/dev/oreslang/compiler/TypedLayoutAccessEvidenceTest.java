package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class TypedLayoutAccessEvidenceTest {
    private static Ast.ForOfStmt loop(Ast.Expr value) {
        return new Ast.ForOfStmt(
                Ast.BindingKind.CONST, "row", new Ast.NameExpr("rows"),
                List.of(new Ast.BindingStmt(
                        Ast.BindingKind.CONST, Ast.TypeRef.simple("int"), "observed", value)));
    }

    private static TypedLayoutAccessEvidence.Report inspect(Ast.Stmt... statements) {
        var fn = new Ast.FunctionDecl("inspect", Ast.Visibility.PRIVATE, false,
                List.of(), List.of(), Ast.TypeRef.simple("void"), List.of(),
                List.of(statements));
        var program = new Ast.Program(List.of(new Ast.ModuleDecl("M", List.of(fn))));
        return TypedLayoutAccessEvidence.inspectAdmitted(program);
    }

    @Test void reportsDirectTypedMemberSyntaxNotInventedPhysicalFacts() {
        var result = inspect(loop(new Ast.MemberExpr(new Ast.NameExpr("row"), "position")));
        assertEquals(1, result.loops().size());
        var loop = result.loops().getFirst();
        assertEquals("M::inspect", loop.owner());
        assertEquals("rows", loop.iterable());
        assertEquals(List.of("position"), loop.directMemberSelectors());
        assertTrue(loop.supportedDirectBody());
        assertFalse(result.completeForLayoutRecommendations());
        assertEquals("typed_oreslang_member_syntax_only", result.assuranceScope());
    }

    @Test void aliasingAndComputedIterationNeverBecomeLayoutProofs() {
        var alias = inspect(loop(new Ast.NameExpr("row"))).loops().getFirst();
        assertFalse(alias.supportedDirectBody());
        assertTrue(alias.wholeBindingObserved());

        var computedLoop = new Ast.ForOfStmt(Ast.BindingKind.CONST, "row",
                new Ast.CallExpr(new Ast.NameExpr("load"), List.of(), false, List.of()),
                List.of(new Ast.ExprStmt(new Ast.MemberExpr(new Ast.NameExpr("row"), "x"))));
        var observed = inspect(computedLoop).loops().getFirst();
        assertEquals(List.of("x"), observed.directMemberSelectors());
        assertFalse(observed.supportedDirectBody());
    }

    @Test void actualSourceGoesThroughTypeAndOwnershipAdmission() {
        // Source and mutation are based on the checked for-of syntax tests.
        String source = """
                fnc sample(): void {
                  for val item of arr[struct{foo: string}{foo: "a"}] do
                    item.foo = "b";
                  done
                  return;
                }
                """;
        var report = OresCompiler.inspectLayoutAccesses(source);
        assertEquals(1, report.loops().size());
        assertFalse(report.completeForLayoutRecommendations());
        assertFalse(report.loops().getFirst().supportedDirectBody());
    }

    @Test void refusesToAnalyzeSourceThatFailsNormalTypeChecking() {
        assertThrows(RuntimeException.class, () ->
                OresCompiler.inspectLayoutAccesses(
                        "fnc f(): int { return unknown_variable; }"));
    }

    @Test void rejectsSourceOversizedBeforeParsing() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.inspectLayoutAccesses(" ".repeat(1_048_577)));
    }

    @Test void cannotMakeUnsupportedOrHugeInputLookComplete() {
        var result = inspect(loop(new Ast.CallExpr(new Ast.NameExpr("work"),
                List.of(), false, List.of(new Ast.NameExpr("row")))));
        assertFalse(result.loops().getFirst().supportedDirectBody());
        assertTrue(result.loops().getFirst().directMemberSelectors().isEmpty());
    }
}
