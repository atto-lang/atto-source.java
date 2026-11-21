package dev.oreslang.nodes;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.GuestCallDepthExceededException;
import dev.oreslang.runtime.GuestInvocationFrame;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Pure, source-AST tests of M3's iterative scalar-frame interpreter. */
final class ScalarFrameMachineTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");

    private static Ast.FunctionDecl fn(String name, List<String> params, Ast.Stmt... body) {
        return new Ast.FunctionDecl(name, Ast.CallableKind.FNC,
                Ast.Visibility.PUBLIC, false, false, false, false, false, false,
                Ast.ActorKind.NONE, List.of(),
                params.stream().map(p -> new Ast.Param(INT, p)).toList(),
                INT, List.of(), List.of(body));
    }

    private static Ast.Expr n(long number) { return new Ast.LiteralExpr(number); }
    private static Ast.Expr v(String name) { return new Ast.NameExpr(name); }
    private static Ast.Expr b(String op, Ast.Expr left, Ast.Expr right) {
        return new Ast.BinaryExpr(op, left, right);
    }
    private static Ast.Expr call(String name, Ast.Expr... args) {
        return new Ast.CallExpr(new Ast.NameExpr(name), List.of(args));
    }
    private static Ast.ReturnStmt ret(Ast.Expr value) { return new Ast.ReturnStmt(value); }
    private static Ast.IfStmt cond(Ast.Expr condition, Ast.Expr whenTrue, Ast.Expr whenFalse) {
        return new Ast.IfStmt(List.of(new Ast.IfBranch(condition, List.of(ret(whenTrue)))),
                List.of(ret(whenFalse)));
    }

    private static final class Host implements ScalarFrameMachine.Host {
        final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
        final AtomicLong safepoints = new AtomicLong();
        final AtomicLong maxNativeCallDepth = new AtomicLong();
        Host(Ast.FunctionDecl... fncs) {
            for (var f : fncs) functions.put(f.name() + "/" + f.parameters().size(), f);
        }
        @Override public Ast.FunctionDecl resolve(String name, int arity) {
            return functions.get(name + "/" + arity);
        }
        @Override public Object binary(String op, Object left, Object right) {
            long x = ((Number) left).longValue();
            long y = ((Number) right).longValue();
            return switch (op) {
                case "+" -> x + y;
                case "-" -> x - y;
                case "*" -> x * y;
                case "eq", "==" -> x == y;
                case "neq", "!=" -> x != y;
                case "<" -> x < y;
                case "<=" -> x <= y;
                case ">" -> x > y;
                case ">=" -> x >= y;
                default -> throw new AssertionError("unsupported operator " + op);
            };
        }
        @Override public Object shapeReturn(Ast.FunctionDecl fn, Object result) {
            assertEquals("int", fn.returnType().name());
            return ((Number) result).longValue();
        }
        @Override public void safepoint() {
            safepoints.incrementAndGet();
            maxNativeCallDepth.accumulateAndGet(
                    Thread.currentThread().getStackTrace().length, Math::max);
        }
    }

    private static Ast.FunctionDecl sumFunction() {
        return fn("sum", List.of("n"), cond(b("==", v("n"), n(0)), n(0),
                b("+", v("n"), call("sum", b("-", v("n"), n(1))))));
    }

    private static Ast.FunctionDecl tailFunction() {
        return fn("tail", List.of("n", "acc"), cond(b("==", v("n"), n(0)),
                v("acc"), call("tail", b("-", v("n"), n(1)),
                        b("+", v("acc"), n(1)))));
    }

    @Test
    void nonTailRecursionUsesExplicitReturnProgramCounters() {
        var fn = sumFunction();
        var host = new Host(fn);
        var plan = ScalarFrameMachine.prepare(fn, host);
        assertNotNull(plan);
        assertEquals(1830L, ScalarFrameMachine.run(plan, List.of(60L), host));
        assertTrue(host.safepoints.get() > 0);
        assertTrue(host.maxNativeCallDepth.get() < 100,
                "60 non-tail guest calls must not consume 60 native Java call frames");
    }

    @Test
    void deepNonTailRecursionFailsWithTypedBudgetNotNativeOverflow() {
        var fn = sumFunction();
        var host = new Host(fn);
        var plan = ScalarFrameMachine.prepare(fn, host);
        assertThrows(GuestCallDepthExceededException.class,
                () -> ScalarFrameMachine.run(plan, List.of(100_000L), host));
        // All carrier scopes must unwind even after 64 explicit activations.
        try (GuestInvocationFrame another = GuestInvocationFrame.enter(0, 0)) {
            assertNotNull(another);
        }
    }

    @Test
    void hundredThousandTailTransfersReuseOneInvocationLease() {
        var fn = tailFunction();
        var host = new Host(fn);
        var plan = ScalarFrameMachine.prepare(fn, host);
        assertEquals(100_000L,
                ScalarFrameMachine.run(plan, List.of(100_000L, 0L), host));
        assertTrue(host.safepoints.get() >= 1_000L);
    }

    @Test
    void mutualTailRecursionHasBoundedStack() {
        var even = fn("even", List.of("n"),
                cond(b("==", v("n"), n(0)), n(1),
                        call("odd", b("-", v("n"), n(1)))));
        var odd = fn("odd", List.of("n"),
                cond(b("==", v("n"), n(0)), n(0),
                        call("even", b("-", v("n"), n(1)))));
        var host = new Host(even, odd);
        var plan = ScalarFrameMachine.prepare(even, host);
        assertNotNull(plan);
        assertEquals(1L, ScalarFrameMachine.run(plan, List.of(100_000L), host));
        assertEquals(0L, ScalarFrameMachine.run(plan, List.of(99_999L), host));
    }

    @Test
    void nestedSiblingCallsPreserveOperandEvaluationAndReturnOrder() {
        var fib = fn("fib", List.of("n"),
                cond(b("<=", v("n"), n(1)), v("n"),
                        b("+", call("fib", b("-", v("n"), n(1))),
                                call("fib", b("-", v("n"), n(2))))));
        var host = new Host(fib);
        assertEquals(21L, ScalarFrameMachine.run(
                ScalarFrameMachine.prepare(fib, host), List.of(8L), host));
    }

    @Test
    void unsupportedSideEffectFailsPreflightWithoutExecutingAnything() {
        var unsafe = fn("unsafe", List.of("n"),
                new Ast.ExprStmt(call("logger", v("n"))),
                ret(v("n")));
        var entry = fn("entry", List.of("n"), ret(call("unsafe", v("n"))));
        var host = new Host(entry, unsafe);
        assertNull(ScalarFrameMachine.prepare(entry, host),
                "closed graph must reject unsupported reachable side effects");
        assertEquals(0L, host.safepoints.get(),
                "preflight cannot execute partial guest instructions");
    }

    @Test
    void unsupportedGlobalReadAndUnknownFunctionFailClosedAtPreflight() {
        var global = fn("global", List.of("n"), ret(v("global_value")));
        var missing = fn("missing", List.of("n"), ret(call("not_here", v("n"))));
        assertNull(ScalarFrameMachine.prepare(global, new Host(global)));
        assertNull(ScalarFrameMachine.prepare(missing, new Host(missing)));
    }

    @Test
    void unsupportedMutableOrNonIntegerSignaturesRemainOnGuardedEvaluator() {
        var plain = sumFunction();
        var host = new Host(plain);
        var bad = new Ast.FunctionDecl("bad", Ast.CallableKind.FNC,
                Ast.Visibility.PUBLIC, false, false, false, false, false, false,
                Ast.ActorKind.NONE, List.of(),
                List.of(new Ast.Param(INT, "n", false, true)),
                INT, List.of(), List.of(ret(v("n"))));
        assertNull(ScalarFrameMachine.prepare(bad, host));
        var routine = new Ast.FunctionDecl("routine", Ast.CallableKind.ROUTINE,
                Ast.Visibility.PUBLIC, false, false, false, false, false, false,
                Ast.ActorKind.NONE, List.of(),
                List.of(new Ast.Param(INT, "n")), INT, List.of(), List.of(ret(v("n"))));
        assertNull(ScalarFrameMachine.prepare(routine, host));
    }

    @Test
    void missingElseReturnCannotBeAdmittedAsStacklessScalar() {
        var partial = fn("partial", List.of("n"),
                new Ast.IfStmt(
                        List.of(new Ast.IfBranch(b("==", v("n"), n(0)),
                                List.of(ret(n(1))))),
                        List.of()));
        assertNull(ScalarFrameMachine.prepare(partial, new Host(partial)),
                "unproven return paths stay on the existing checked evaluator");
    }

    @Test
    void invalidInitialArityDoesNotLeaveAStaleCarrierScope() {
        var fn = tailFunction();
        var host = new Host(fn);
        var plan = ScalarFrameMachine.prepare(fn, host);
        assertThrows(IllegalArgumentException.class,
                () -> ScalarFrameMachine.run(plan, List.of(1L), host));
        try (GuestInvocationFrame healthy = GuestInvocationFrame.enter(0, 0)) {
            assertNotNull(healthy);
        }
    }

    @Test
    void hugeAstExpressionFailsPreflightBeforeNativeRecursion() {
        Ast.Expr expr = n(1);
        for (int i = 0; i < 80; i++) expr = b("+", expr, n(1));
        var giant = fn("giant", List.of(), ret(expr));
        assertNull(ScalarFrameMachine.prepare(giant, new Host(giant)));
    }
}
