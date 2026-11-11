package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.CheckedDispatchPlansV1;
import dev.oreslang.compiler.AotDispatchPlannerV1;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These assertions operate on the *real checked and macro-expanded guest AST*.
 * They do not claim machine-code/native guest AOT or speedup evidence.
 */
final class CheckedDispatchPlanIntegrationV1Test {
    private static Ast.FunctionDecl function(Ast.Program program, String name) {
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.FunctionDecl fn && fn.name().equals(name))
                .map(decl -> (Ast.FunctionDecl) decl)
                .findFirst().orElseThrow();
    }

    private static Ast.CallExpr returnCall(Ast.FunctionDecl function) {
        Ast.ReturnStmt returned = (Ast.ReturnStmt) function.body().getFirst();
        return (Ast.CallExpr) returned.value();
    }

    @Test
    void typecheckerRecordsDirectAndVirtualCallPlansOnRealGuestSource() throws Exception {
        String source = Files.readString(
                Path.of("tests/fixtures/dispatch/known_and_virtual_v1.atto"),
                StandardCharsets.UTF_8);
        Ast.Program checked = OresCompiler.parseAndTypeCheck(source);

        Ast.CallExpr baseCall = returnCall(function(checked, "invoke_base"));
        CheckedDispatchPlansV1.Plan slot = CheckedDispatchPlansV1.lookup(baseCall);
        assertNotNull(slot, "base-typed call must have a checked slot plan");
        assertEquals(AotDispatchPlannerV1.CallKind.INSTANCE_METHOD, slot.kind());
        assertEquals(AotDispatchPlannerV1.Strategy.METHOD_SLOT, slot.strategy());
        assertEquals("Base", slot.receiverClass().name());
        assertEquals("value", slot.method().name());
        assertEquals(0, slot.arity());

        Ast.FunctionDecl main = function(checked, "main");
        List<Ast.Stmt> statements = main.body();
        // The last two writes contain the statically bound Class.fixed() calls.
        for (int i = 2; i < 4; i++) {
            Ast.CallExpr write = (Ast.CallExpr) ((Ast.ExprStmt) statements.get(i)).expression();
            Ast.CallExpr staticCall = (Ast.CallExpr) write.arguments().getFirst();
            CheckedDispatchPlansV1.Plan direct = CheckedDispatchPlansV1.lookup(staticCall);
            assertNotNull(direct, "static qualified method must be planned");
            assertEquals(AotDispatchPlannerV1.CallKind.STATIC_METHOD, direct.kind());
            assertEquals(AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL, direct.strategy());
            assertEquals("fixed", direct.method().name());
            assertEquals(0, direct.arity());
        }

        Ast.CallExpr firstWrite = (Ast.CallExpr) ((Ast.ExprStmt) statements.getFirst()).expression();
        Ast.CallExpr firstInvoke = (Ast.CallExpr) firstWrite.arguments().getFirst();
        CheckedDispatchPlansV1.Plan functionPlan = CheckedDispatchPlansV1.lookup(firstInvoke);
        assertNotNull(functionPlan, "direct user function call must be planned");
        assertEquals(AotDispatchPlannerV1.CallKind.FREE_FUNCTION, functionPlan.kind());
        assertEquals(AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL, functionPlan.strategy());
        assertSame(function(checked, "invoke_base"), functionPlan.function());

        Ast.CallExpr equalButDifferent = new Ast.CallExpr(
                firstInvoke.callee(),
                firstInvoke.typeArguments(),
                firstInvoke.typeArgumentsPresent(),
                firstInvoke.arguments(),
                firstInvoke.origin());
        assertEquals(firstInvoke, equalButDifferent);
        assertNull(CheckedDispatchPlansV1.lookup(equalButDifferent),
                "structurally equal but different AST nodes must not inherit compiler proofs");
    }

    @Test
    void checkedPlansRejectWrongSourceArityOrKind() {
        Ast.CallExpr call = new Ast.CallExpr(
                new Ast.NameExpr("target"), List.of(new Ast.LiteralExpr(1)));
        Ast.FunctionDecl target = function(OresCompiler.parseAndTypeCheck(
                "fnc target(int x): int { return x; }"), "target");

        CheckedDispatchPlansV1.Plan invalidArity = new CheckedDispatchPlansV1.Plan(
                AotDispatchPlannerV1.CallKind.FREE_FUNCTION,
                AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL,
                "target", 1, target, null, null);
        assertDoesNotThrow(() -> CheckedDispatchPlansV1.record(call, invalidArity));

        assertThrows(IllegalArgumentException.class, () -> new CheckedDispatchPlansV1.Plan(
                AotDispatchPlannerV1.CallKind.FREE_FUNCTION,
                AotDispatchPlannerV1.Strategy.METHOD_SLOT,
                "target", 1, target, null, null));
        assertThrows(IllegalArgumentException.class, () -> CheckedDispatchPlansV1.record(
                new Ast.CallExpr(new Ast.NameExpr("target"), List.of()),
                invalidArity));
        Ast.CallExpr spread = new Ast.CallExpr(
                new Ast.NameExpr("target"),
                List.of(new Ast.SpreadExpr(new Ast.NameExpr("args"))));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedDispatchPlansV1.record(spread, invalidArity));
    }
}
