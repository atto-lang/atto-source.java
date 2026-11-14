package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.CheckedCallPlan;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Main-based parameter-presence shape tests. No runtime admission is implied.
 */
final class CheckedOptionalNamedBinderMainTest {
    private static List<Ast.Param> formals(String sourceParameters) {
        Ast.Program parsed = Parser.parse(
                "fnc staged(" + sourceParameters + "): int { return 42; }");
        Ast.FunctionDecl function = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().getFirst();
        return function.parameters();
    }

    @Test
    void requiredOptionIsNotAnOmittableSlot() {
        List<Ast.Param> formals = formals(
                "int? required, int fallback? = 2, int? maybe?");
        var error = assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.shapeForDeclaredParameters(formals, List.of()));
        assertTrue(error.getMessage().contains("missing required argument"));
        CheckedCallPlan plan = CheckedCallPlan.shapeForDeclaredParameters(formals,
                List.of(CheckedCallPlan.InputSlot.named("required", 0)));
        assertEquals(List.of(0, -1, -1), plan.abiToSource());
        assertFalse(plan.isOmitted(0));
        assertTrue(plan.isOmitted(1));
        assertTrue(plan.isOmitted(2));
    }

    @Test
    void nonTrailingNamedHoleDoesNotShiftPositionalValues() {
        CheckedCallPlan plan = CheckedCallPlan.shapeForDeclaredParameters(
                formals("int x, int y? = 10, int z"),
                List.of(CheckedCallPlan.InputSlot.positional(0),
                        CheckedCallPlan.InputSlot.named("z", 1)));
        assertEquals(List.of(0, -1, 1), plan.abiToSource());
        assertEquals(List.of(0, 2), plan.sourceOrder().stream()
                .map(CheckedCallPlan.BoundSlot::parameterIndex).toList());
    }

    @Test
    void invalidLabelDuplicateAndPositionalReorderingAllFail() {
        var p = formals("int x, int y? = 10");
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.shapeForDeclaredParameters(p,
                        List.of(CheckedCallPlan.InputSlot.positional(0),
                                CheckedCallPlan.InputSlot.named("x", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.shapeForDeclaredParameters(p,
                        List.of(CheckedCallPlan.InputSlot.named("y", 0),
                                CheckedCallPlan.InputSlot.named("y", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.shapeForDeclaredParameters(p,
                        List.of(CheckedCallPlan.InputSlot.named("missing", 0))));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.shapeForDeclaredParameters(p,
                        List.of(CheckedCallPlan.InputSlot.named("y", 0),
                                CheckedCallPlan.InputSlot.positional(1))));
    }

    @Test
    void rawSourceProvenanceIndicesMustStartAtZeroAndStayDense() {
        List<String> p = List.of("x", "y");
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.required(p,
                        List.of(CheckedCallPlan.InputSlot.positional(1),
                                CheckedCallPlan.InputSlot.positional(2))));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.required(p,
                        List.of(CheckedCallPlan.InputSlot.positional(0),
                                CheckedCallPlan.InputSlot.positional(2))));
        CheckedCallPlan grouped = CheckedCallPlan.required(p,
                List.of(CheckedCallPlan.InputSlot.positionalProjection(0, 0),
                        CheckedCallPlan.InputSlot.positionalProjection(0, 1)));
        assertEquals(List.of(0, 1), grouped.abiToSource());
    }

    @Test
    void checkedPlanIsRevokedOnRecheckAndFailedValidation() {
        Ast.CallExpr call = new Ast.CallExpr(new Ast.NameExpr("fnc"),
                List.of(new Ast.LiteralExpr(1L)));
        CheckedCallPlan plan = CheckedCallPlan.required(List.of("x"),
                List.of(CheckedCallPlan.InputSlot.positional(0)));
        CheckedCallPlan.record(call, plan);
        assertEquals(plan, CheckedCallPlan.require(call));
        CheckedCallPlan.beginRecheck(call);
        assertNull(CheckedCallPlan.lookup(call));
        try (CheckedCallPlan.ValidationScope scope =
                     CheckedCallPlan.beginValidation()) {
            CheckedCallPlan.trackValidationSite(call);
            CheckedCallPlan.record(call, plan);
            // An exception or failed semantic pass deliberately never commits.
        }
        assertNull(CheckedCallPlan.lookup(call));
    }

    @Test
    void typeCheckingPublishesVerifiedPositionalFunctionShapes() {
        Ast.Program parsed = Parser.parse("""
                fnc add(int x, int y): int { return x + y; }
                pub fnc main(): int { return add(40, 2); }
                """);
        Ast.FunctionDecl main = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(1);
        Ast.ReturnStmt returned = (Ast.ReturnStmt) main.body().getFirst();
        Ast.CallExpr call = (Ast.CallExpr) returned.value();

        assertNull(CheckedCallPlan.lookup(call));
        dev.oreslang.types.TypeChecker.checkTypes(parsed);
        CheckedCallPlan verified = CheckedCallPlan.require(call);
        assertTrue(verified.isTriviallyPositional());
        assertEquals(List.of(0, 1), verified.abiToSource());
    }

    @Test
    void failingTypeCheckRevokesCallPlansCreatedEarlierInPass() {
        Ast.Program parsed = Parser.parse("""
                fnc add(int x, int y): int { return x + y; }
                pub fnc main(): int { return add(40, 2) + "invalid"; }
                """);
        Ast.FunctionDecl main = (Ast.FunctionDecl)
                parsed.modules().getFirst().declarations().get(1);
        Ast.ReturnStmt returned = (Ast.ReturnStmt) main.body().getFirst();
        Ast.BinaryExpr binary = (Ast.BinaryExpr) returned.value();
        Ast.CallExpr call = (Ast.CallExpr) binary.left();

        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(parsed));
        assertNull(CheckedCallPlan.lookup(call),
                "failed checker pass must revoke plan for already visited call");
    }

    @Test
    void ASTTransferRequiresExactCalleeAndArgumentIdentity() {
        Ast.NameExpr f = new Ast.NameExpr("fnc");
        Ast.LiteralExpr n = new Ast.LiteralExpr(1L);
        Ast.CallExpr original = new Ast.CallExpr(f, List.of(n));
        Ast.CallExpr identicalChildren = new Ast.CallExpr(f, List.of(n));
        Ast.CallExpr differentArgument = new Ast.CallExpr(f,
                List.of(new Ast.LiteralExpr(1L)));
        CheckedCallPlan plan = CheckedCallPlan.required(List.of("x"),
                List.of(CheckedCallPlan.InputSlot.positional(0)));
        CheckedCallPlan.record(original, plan);
        Ast.copyCheckedMetadata(original, identicalChildren);
        assertEquals(plan, CheckedCallPlan.require(identicalChildren));
        Ast.copyCheckedMetadata(original, differentArgument);
        assertNull(CheckedCallPlan.lookup(differentArgument));
    }
}
