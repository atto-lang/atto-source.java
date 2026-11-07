package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct AST/compiler tests for the admitted scalar bytecode graph.
 * These are real Java compiler/interpreter unit tests when executed by CI,
 * but are NOT actor source guest receipts or cross-backend native proof.
 */
final class ScalarFrameMachineTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static Ast.Expr n(String name) { return new Ast.NameExpr(name); }
    private static Ast.Expr v(long value) { return new Ast.LiteralExpr(value); }
    private static Ast.Expr bin(String op, Ast.Expr x, Ast.Expr y) {
        return new Ast.BinaryExpr(op, x, y);
    }
    private static Ast.Expr call(String name, Ast.Expr... arguments) {
        return new Ast.CallExpr(n(name), List.of(arguments));
    }
    private static Ast.ReturnStmt ret(Ast.Expr value) {
        return new Ast.ReturnStmt(value);
    }
    private static Ast.IfStmt cond(Ast.Expr check, Ast.Stmt left, Ast.Stmt right) {
        return new Ast.IfStmt(
                List.of(new Ast.IfBranch(check, List.of(left))), List.of(right));
    }
    private static Ast.FunctionDecl function(
            String name, Ast.CallableKind kind, List<Ast.Param> params,
            List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(name, kind, Ast.Visibility.PUBLIC,
                false, false, false, false, false, false, Ast.ActorKind.NONE,
                List.of(), params, INT, List.of(), body, List.of(), Ast.EffectSpec.none());
    }
    private static Ast.FunctionDecl function(
            String name, List<Ast.Param> params, List<Ast.Stmt> body) {
        return function(name, Ast.CallableKind.FNC, params, body);
    }
    private static Ast.Param p(String name) { return new Ast.Param(INT, name); }
    private static ScalarFrameMachine.BinaryOp math() {
        return (op, a, b) -> {
            if (a instanceof Long x && b instanceof Long y) {
                return switch (op) {
                    case "+" -> x + y;
                    case "-" -> x - y;
                    case "*" -> x * y;
                    case "<" -> x < y;
                    case "<=" -> x <= y;
                    case ">" -> x > y;
                    case ">=" -> x >= y;
                    case "==", "eq" -> x.equals(y);
                    case "!=", "neq" -> !x.equals(y);
                    default -> throw new AssertionError(op);
                };
            }
            throw new AssertionError("unadmitted scalar operation");
        };
    }

    @Test
    void thousandTwentyFourNonTailCallsUseIterativeHeapFrames() {
        Ast.FunctionDecl sum = function("sum", List.of(p("n")), List.of(
                cond(bin("==", n("n"), v(0)), ret(v(0)),
                        ret(bin("+", v(1),
                                call("sum", bin("-", n("n"), v(1))))))));
        ScalarFrameMachine.Plan plan = ScalarFrameMachine.compile(sum,
                (name, arity) -> name.equals("sum") && arity == 1 ? sum : null);
        assertNotNull(plan);
        assertEquals(1, plan.reachableFunctions());
        assertEquals(1_024L,
                ScalarFrameMachine.execute(plan, List.of(1_024L), math(), () -> { }));
    }

    @Test
    void hundredThousandTailCallsKeepOnlyOneActiveFrame() {
        Ast.FunctionDecl tail = function("tail", List.of(p("n"), p("acc")), List.of(
                cond(bin("==", n("n"), v(0)), ret(n("acc")),
                        ret(call("tail",
                                bin("-", n("n"), v(1)),
                                bin("+", n("acc"), v(1)))))));
        AtomicInteger safepoints = new AtomicInteger();
        ScalarFrameMachine.Plan plan = ScalarFrameMachine.compile(tail,
                (name, arity) -> name.equals("tail") && arity == 2 ? tail : null);
        assertNotNull(plan);
        assertEquals(100_000L, ScalarFrameMachine.execute(plan,
                List.of(100_000L, 0L), math(), safepoints::incrementAndGet));
        assertTrue(safepoints.get() >= 100_000 / 64,
                "scalar machine must keep scheduler safepoints on long tail loops");
    }

    @Test
    void mutualTailCallsHaveNoCarrierRecursion() {
        Ast.FunctionDecl even = function("even", List.of(p("n")), List.of(
                cond(bin("==", n("n"), v(0)), ret(v(0)),
                        ret(call("odd", bin("-", n("n"), v(1)))))));
        Ast.FunctionDecl odd = function("odd", List.of(p("n")), List.of(
                cond(bin("==", n("n"), v(0)), ret(v(1)),
                        ret(call("even", bin("-", n("n"), v(1)))))));
        ScalarFrameMachine.Plan plan = ScalarFrameMachine.compile(even,
                (name, arity) -> arity != 1 ? null
                        : name.equals("even") ? even : name.equals("odd") ? odd : null);
        assertNotNull(plan);
        assertEquals(2, plan.reachableFunctions());
        assertEquals(0L, ScalarFrameMachine.execute(plan,
                List.of(100_000L), math(), () -> { }));
    }

    @Test
    void immutableScalarLocalAndBranchAreStoredInTypedSlots() {
        Ast.FunctionDecl add = function("local", List.of(p("n")), List.of(
                new Ast.BindingStmt(Ast.BindingKind.CONST, INT, "one", v(1)),
                cond(bin(">", n("n"), v(10)),
                        ret(bin("+", n("n"), n("one"))),
                        ret(n("one")))));
        ScalarFrameMachine.Plan plan = ScalarFrameMachine.compile(add,
                (name, arity) -> null);
        assertNotNull(plan);
        assertEquals(12L, ScalarFrameMachine.execute(plan,
                List.of(11L), math(), () -> { }));
        assertEquals(1L, ScalarFrameMachine.execute(plan,
                List.of(2L), math(), () -> { }));
    }

    @Test
    void nonActorDepthBudgetFailsBeforeNativeStackOverflow() {
        Ast.FunctionDecl sum = function("sum", List.of(p("n")), List.of(
                cond(bin("==", n("n"), v(0)), ret(v(0)),
                        ret(bin("+", v(1),
                                call("sum", bin("-", n("n"), v(1))))))));
        ScalarFrameMachine.Plan plan = ScalarFrameMachine.compile(sum,
                (name, arity) -> name.equals("sum") ? sum : null);
        assertNotNull(plan);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ScalarFrameMachine.execute(plan,
                        List.of(4_096L), math(), () -> { }));
        assertTrue(failure.getMessage().contains("bounded non-actor scalar call depth"));
        assertEquals(4L, ScalarFrameMachine.execute(plan,
                List.of(4L), math(), () -> { }),
                "a previous root call's frames must not contaminate a fresh call");
    }

    @Test
    void unsupportedRoutineDeferAndImpureCallFailClosedBeforeExecution() {
        Ast.FunctionDecl routine = function("routine", Ast.CallableKind.ROUTINE,
                List.of(), List.of(ret(v(3))));
        assertNull(ScalarFrameMachine.compile(routine, (name, arity) -> null),
                "routine cannot gain recursive-fnc semantics");
        Ast.FunctionDecl defer = function("defer", List.of(), List.of(
                new Ast.DeferStmt(call("cleanup")), ret(v(3))));
        assertNull(ScalarFrameMachine.compile(defer, (name, arity) -> null),
                "cleanup obligations are not yet lowered");
        Ast.FunctionDecl dynamic = function("dynamic", List.of(), List.of(
                ret(call("unknown"))));
        assertNull(ScalarFrameMachine.compile(dynamic, (name, arity) -> null),
                "an unresolved dynamic call must fall back before any guest instruction");
        Ast.FunctionDecl invalid = function("invalid", List.of(), List.of(
                ret(new Ast.LiteralExpr("not-int"))));
        assertNull(ScalarFrameMachine.compile(invalid, (name, arity) -> null),
                "typed-frame admission must reject non-primitive guest values");
        Ast.FunctionDecl mutated = function("mutated", List.of(p("n")), List.of(
                ret(new Ast.UnaryExpr("&", n("n")))));
        assertNull(ScalarFrameMachine.compile(mutated, (name, arity) -> null),
                "unsupported ownership syntax must not be reinterpreted as a scalar op");
    }

    @Test
    void mutableAndShadowedBindingsCannotEnterImmutableScalarIR() {
        Ast.FunctionDecl mutable = function("mut", List.of(), List.of(
                new Ast.BindingStmt(Ast.BindingKind.LET, INT, "n", v(1)),
                ret(n("n"))));
        assertNull(ScalarFrameMachine.compile(mutable, (name, arity) -> null));
        Ast.FunctionDecl shadow = function("shadow", List.of(p("n")), List.of(
                new Ast.BindingStmt(Ast.BindingKind.CONST, INT, "n", v(1)),
                ret(n("n"))));
        assertNull(ScalarFrameMachine.compile(shadow, (name, arity) -> null));
    }
}
