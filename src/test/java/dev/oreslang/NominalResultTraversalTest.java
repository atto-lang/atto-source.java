package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.NominalResultErrorAudit;
import dev.oreslang.types.TaggedMessageContracts;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct AST coverage for compiler-only nominal Result preflight.
 * No positive runtime/guest semantics are asserted.
 */
final class NominalResultTraversalTest {
    private static final Ast.TypeRef RESULT = new Ast.TypeRef("Result",
            List.of(Ast.TypeRef.simple("int"),
                    Ast.TypeRef.simple("TrainingError")), false);

    private static Ast.CallExpr rejected() {
        return new Ast.CallExpr(new Ast.NameExpr("Err"),
                List.of(new Ast.LiteralExpr("BAD")));
    }

    private static Ast.CallExpr allowed() {
        return new Ast.CallExpr(new Ast.NameExpr("Err"),
                List.of(new Ast.CallExpr(
                        new Ast.MemberExpr(new Ast.NameExpr("TrainingError"), "BAD"),
                        List.of())));
    }

    private static Ast.Program program(Ast.Stmt statement) {
        Ast.EnumDecl errors = new Ast.EnumDecl("TrainingError",
                List.of(new Ast.EnumCase("BAD", null, 1)));
        Ast.FunctionDecl fn = new Ast.FunctionDecl(
                "get_error", Ast.CallableKind.FNC, Ast.Visibility.PRIVATE,
                false, false, false, false, false, Ast.ActorKind.NONE,
                List.of(), List.of(), RESULT, List.of(), List.of(statement));
        return new Ast.Program(List.of(new Ast.ModuleDecl(
                Parser.ROOT_MODULE, List.of(errors, fn))));
    }

    private static void audit(Ast.Program program) {
        NominalResultErrorAudit.verify(program,
                TaggedMessageContracts.forTypes(program));
    }

    private static void rejects(Ast.Stmt statement) {
        assertThrows(IllegalArgumentException.class, () -> audit(program(statement)));
    }

    @Test void traversalFindsErrorsInTrySwitchAndAllLoopForms() {
        Ast.ReturnStmt bad = new Ast.ReturnStmt(rejected());
        rejects(new Ast.TryStmt(List.of(bad), "error",
                List.of(), List.of()));
        rejects(new Ast.TryStmt(List.of(), "error",
                List.of(bad), List.of()));
        rejects(new Ast.TryStmt(List.of(), "error",
                List.of(), List.of(bad)));
        rejects(new Ast.SwitchStmt(new Ast.LiteralExpr(1L),
                List.of(new Ast.SwitchCase(List.of(new Ast.LiteralExpr(1L)),
                        List.of(bad))), List.of()));
        rejects(new Ast.SwitchStmt(new Ast.LiteralExpr(1L),
                List.of(), List.of(bad)));
        rejects(new Ast.ForStmt(null, null, null, List.of(bad)));
        rejects(new Ast.ForStmt(new Ast.BindingStmt(
                Ast.BindingKind.VAL, RESULT, "error", rejected()),
                null, null, List.of()));
        rejects(new Ast.ForOfStmt(Ast.BindingKind.VAL, "item",
                new Ast.LiteralExpr(1L), false, List.of(bad)));
        rejects(new Ast.ForOfDestructureStmt(
                List.of(new Ast.DestructureBinding(Ast.BindingKind.VAL, "item")),
                new Ast.LiteralExpr(1L), false, List.of(bad)));
        rejects(new Ast.LoopStmt(List.of(bad)));
        Ast.SelectArm defaultArm = new Ast.SelectArm(
                Ast.ChannelOperation.DEFAULT, null, null, null, null,
                List.of(bad), false);
        rejects(new Ast.SelectStmt(Ast.WaitMode.BLOCKING,
                Ast.SelectPolicy.FAIR, List.of(defaultArm)));
    }

    @Test void constructorAndClassFieldCannotHideIncorrectNominalResult() {
        Ast.EnumDecl errors = new Ast.EnumDecl("TrainingError",
                List.of(new Ast.EnumCase("BAD", null, 1)));
        Ast.FieldDecl field = new Ast.FieldDecl("error", Ast.Visibility.PRIVATE,
                Ast.BindingKind.VAL, RESULT, List.of(), rejected());
        Ast.ConstructorDecl constructor = new Ast.ConstructorDecl(
                Ast.Visibility.PRIVATE, List.of(), List.of(),
                List.of(new Ast.BindingStmt(
                        Ast.BindingKind.VAL, RESULT, "local", rejected())));
        Ast.ClassDecl holder = new Ast.ClassDecl("Holder", Ast.Visibility.PRIVATE,
                false, Ast.ActorKind.NONE, List.of(), List.of(),
                List.of(), List.of(field), constructor, List.of());
        Ast.Program p = new Ast.Program(List.of(new Ast.ModuleDecl(
                Parser.ROOT_MODULE, List.of(errors, holder))));
        assertThrows(IllegalArgumentException.class, () -> audit(p));
    }

    @Test void deepAdversarialStatementTreeIsBoundedWithoutRecursion() {
        Ast.Stmt nested = new Ast.ReturnStmt(allowed());
        for (int i = 0; i < 8193; i++) {
            nested = new Ast.BlockStmt(List.of(nested));
        }
        IllegalArgumentException denial = assertThrows(
                IllegalArgumentException.class, () -> audit(program(nested)));
        assertTrue(denial.getMessage().contains("statement work budget"));
    }

    @Test void deeplyNestedButBoundedNominalErrorRemainsTypeOnlyAccepted() {
        Ast.Stmt nested = new Ast.ReturnStmt(allowed());
        for (int i = 0; i < 250; i++) {
            nested = new Ast.BlockStmt(List.of(nested));
        }
        assertDoesNotThrow(() -> audit(program(nested)));
    }
}
