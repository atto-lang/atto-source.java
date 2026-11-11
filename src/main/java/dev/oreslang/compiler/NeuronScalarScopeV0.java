package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, effect-free preflight for the first neuron float read/write scopes.
 *
 * This class inspects the REAL parser AST, not method text or method names
 * alone. Its results are descriptive proof requirements, NOT authorization
 * for guest execution. In particular there is no ActorCell execution identity,
 * physical heap/stack pin, scheduler continuation or cancellation cleanup.
 * TypeChecker MUST still throw NEURON_STATE_MIR_UNSUPPORTED after preflight.
 *
 * Only straight-line, scalar expressions with +, -, * are admitted. Anything
 * effectful/unknown (calls, await, nested lambdas, constructors, branch,
 * assignment inside a read, closure capture, direct state-view return, etc.)
 * fails closed instead of being accepted under an incomplete escape analysis.
 */
public final class NeuronScalarScopeV0 {
    private NeuronScalarScopeV0() { }

    public enum Mode { READ, WRITE }

    /** Metadata to inform a future checked MIR emitter, not a runtime loan. */
    public record Scope(Mode mode, List<String> reads, List<String> writes) {
        public Scope {
            Objects.requireNonNull(mode, "mode");
            reads = List.copyOf(reads);
            writes = List.copyOf(writes);
        }
    }

    public record Plan(String actorName, Scope initialization, Scope inference) {
        public Plan {
            Objects.requireNonNull(actorName, "actorName");
        }
        public boolean hasCriticalScopes() {
            return initialization != null || inference != null;
        }
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException("NEURON_STATE_SCOPE_UNSUPPORTED: " + reason);
    }

    /**
     * Preserve existing neuron parser/role experiments that contain no
     * protected scopes. If any scoped operation exists, require a complete
     * closed scalar init + read pair before reporting preflight success.
     */
    public static Plan preflight(Ast.ClassDecl neuron) {
        Objects.requireNonNull(neuron, "neuron");
        NeuronStateLayoutV0 layout = NeuronStateLayoutV0.fromActor(neuron);
        Ast.MethodDecl initializer = null;
        Ast.MethodDecl forward = null;
        for (Ast.MethodDecl method : neuron.methods()) {
            if (method.name().equals("on_start")) {
                if (initializer != null) throw unsupported("duplicate on_start");
                initializer = method;
            } else if (method.name().equals("forward")) {
                if (forward != null) throw unsupported("duplicate forward");
                forward = method;
            } else if (containsDirectStateScope(method)) {
                // No protected scope may be hidden inside receive(), an
                // arbitrary helper or a supervisor-invoked method in v0.
                throw unsupported("critical state scope in unsupported method: " + method.name());
            }
        }

        boolean hasOnStartScope = initializer != null && containsDirectStateScope(initializer);
        boolean hasForwardScope = forward != null && containsDirectStateScope(forward);
        if (!hasOnStartScope && !hasForwardScope)
            return new Plan(neuron.name(), null, null);
        if (!hasOnStartScope || !hasForwardScope)
            throw unsupported("scoped scalar inference needs both on_start write and forward read");
        Scope write = verifyInitialization(initializer, layout);
        Scope read = verifyForward(forward, layout, new HashSet<>(write.writes()));
        return new Plan(neuron.name(), write, read);
    }

    private static boolean isHandleState(Ast.Expr expression) {
        if (!(expression instanceof Ast.CallExpr call)) return false;
        return call.callee() instanceof Ast.MemberExpr member
                && member.member().equals("handle_state")
                && member.receiver() instanceof Ast.NameExpr owner
                && owner.name().equals("self");
    }

    private static boolean containsDirectStateScope(Ast.MethodDecl method) {
        for (Ast.Stmt stmt : method.body()) {
            if (stmt instanceof Ast.ExprStmt expression && isHandleState(expression.expression()))
                return true;
            if (stmt instanceof Ast.ReturnStmt result && isHandleState(result.value()))
                return true;
        }
        return false;
    }

    private static Ast.LambdaExpr scopeLambda(
            Ast.Expr candidate, Mode mode, String methodName) {
        if (!(candidate instanceof Ast.CallExpr call) || !isHandleState(call)
                || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.RuntimeCallExpr intrinsic)
                || !intrinsic.operation().equals(
                        mode == Mode.READ ? "$neuron.critical.read" : "$neuron.critical.write")
                || intrinsic.arguments().size() != 1
                || !(intrinsic.arguments().getFirst() instanceof Ast.LambdaExpr lambda)
                || lambda.nonLexical() || lambda.declaredReturnType() != null
                || lambda.parameters().size() != 1) {
            throw unsupported(methodName + " requires one compiler-marked critical-"
                    + mode.name().toLowerCase() + " state scope");
        }
        Ast.Param view = lambda.parameters().getFirst();
        if (view.structural() || view.mutable() || view.name().isBlank()
                || view.name().equals("_")
                || !view.type().equals(Ast.TypeRef.inferred())) {
            throw unsupported("critical state view requires a single inferred immutable alias");
        }
        return lambda;
    }

    private static Scope verifyInitialization(
            Ast.MethodDecl method, NeuronStateLayoutV0 layout) {
        if (!method.parameters().isEmpty() || method.async() || method.suspends()
                || method.isStatic() || !Ast.TypeRef.simple("void").equals(method.returnType())
                || method.body().isEmpty() || method.body().size() > 2) {
            throw unsupported("on_start must be synchronous and have only a critical write and return");
        }
        Ast.Stmt first = method.body().getFirst();
        if (!(first instanceof Ast.ExprStmt expr)) {
            throw unsupported("on_start must write under a critical scope");
        }
        if (method.body().size() == 2
                && !(method.body().get(1) instanceof Ast.ReturnStmt ret && ret.value() == null)) {
            throw unsupported("on_start may only return after its write scope");
        }
        Ast.LambdaExpr lambda = scopeLambda(expr.expression(), Mode.WRITE, "on_start");
        if (lambda.blockBody() == null || lambda.expressionBody() != null
                || lambda.blockBody().isEmpty()
                || lambda.blockBody().size() > layout.floatFields().size()) {
            throw unsupported("critical-write needs a bounded straight-line assignment block");
        }

        String alias = lambda.parameters().getFirst().name();
        Set<String> initialized = new HashSet<>();
        List<String> writes = new ArrayList<>();
        List<String> reads = new ArrayList<>();
        for (Ast.Stmt statement : lambda.blockBody()) {
            if (!(statement instanceof Ast.ExprStmt exprStmt)
                    || !(exprStmt.expression() instanceof Ast.AssignExpr assignment)
                    || !(assignment.target() instanceof Ast.MemberExpr field)
                    || !(field.receiver() instanceof Ast.NameExpr owner)
                    || !owner.name().equals(alias)
                    || !layout.floatFields().contains(field.member())
                    || initialized.contains(field.member())) {
                throw unsupported("write scope requires one assignment per declared scalar field");
            }
            // Validate the RHS BEFORE declaring this slot initialized.
            // Otherwise 's.weight = s.weight + 1.0' reads uninitialized
            // memory on its first assignment even though the pass "saw"
            // a write to that slot.
            verifyScalarExpr(assignment.value(), alias, null, layout,
                    initialized, reads, 0);
            initialized.add(field.member());
            writes.add(field.member());
        }
        if (initialized.size() != layout.floatFields().size()) {
            throw unsupported("on_start must initialize every scalar field before inference");
        }
        return new Scope(Mode.WRITE, reads, writes);
    }

    private static Scope verifyForward(
            Ast.MethodDecl method, NeuronStateLayoutV0 layout, Set<String> initialized) {
        if (method.body().size() != 1
                || !(method.body().getFirst() instanceof Ast.ReturnStmt result)
                || method.parameters().size() != 1) {
            throw unsupported("forward must return exactly one protected scalar expression");
        }
        Ast.LambdaExpr lambda = scopeLambda(result.value(), Mode.READ, "forward");
        if (lambda.blockBody() != null || lambda.expressionBody() == null) {
            throw unsupported("scalar v0 read scope requires an expression body");
        }
        String alias = lambda.parameters().getFirst().name();
        String input = method.parameters().getFirst().name();
        if (alias.equals(input)) {
            throw unsupported("state view alias cannot shadow forward parameter");
        }
        List<String> reads = new ArrayList<>();
        verifyScalarExpr(lambda.expressionBody(), alias, input, layout,
                initialized, reads, 0);
        return new Scope(Mode.READ, reads, List.of());
    }

    /**
     * Closed expression walk: every AST kind not enumerated is forbidden.
     * This is intentionally stricter than a general-purpose borrow checker,
     * since supporting arbitrary calls would require an effect/capture proof.
     */
    private static void verifyScalarExpr(
            Ast.Expr expression, String alias, String parameter,
            NeuronStateLayoutV0 layout, Set<String> initialized,
            List<String> reads, int depth) {
        if (depth > 64) throw unsupported("critical scope expression depth exceeds 64");
        if (expression instanceof Ast.LiteralExpr literal
                && literal.value() instanceof Double) {
            return;
        }
        if (expression instanceof Ast.NameExpr name) {
            if (parameter != null && name.name().equals(parameter)) return;
            throw unsupported("state view alias or unknown name cannot escape as a value: "
                    + name.name());
        }
        if (expression instanceof Ast.MemberExpr member) {
            if (!(member.receiver() instanceof Ast.NameExpr owner)
                    || !owner.name().equals(alias)
                    || !layout.floatFields().contains(member.member())
                    || !initialized.contains(member.member())) {
                throw unsupported("read requires an initialized private state scalar");
            }
            reads.add(member.member());
            return;
        }
        if (expression instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("-") || unary.operator().equals("+"))) {
            verifyScalarExpr(unary.operand(), alias, parameter, layout,
                    initialized, reads, depth + 1);
            return;
        }
        if (expression instanceof Ast.BinaryExpr binary
                && (binary.operator().equals("+") || binary.operator().equals("-")
                    || binary.operator().equals("*"))) {
            verifyScalarExpr(binary.left(), alias, parameter, layout,
                    initialized, reads, depth + 1);
            verifyScalarExpr(binary.right(), alias, parameter, layout,
                    initialized, reads, depth + 1);
            return;
        }
        throw unsupported("only scalar float literals, loads and +,-,* arithmetic are admitted");
    }
}
