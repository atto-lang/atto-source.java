package dev.oreslang.ast;

import dev.oreslang.compiler.AotDispatchPlannerV1;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Identity-bound decisions issued after successful callable type resolution.
 *
 * <p>No plan is inferred from runtime receiver names or from a JIT profile.
 * A method plan carries the checked class and declaration identity; the guest
 * evaluator may skip one slot lookup only when the actual class and code-owner
 * identities match. Other receivers use the pre-existing virtual lookup.
 * A failed or incomplete program is not executable through normal admission.
 */
public final class CheckedDispatchPlansV1 {
    private CheckedDispatchPlansV1() { }

    public record Plan(
            AotDispatchPlannerV1.CallKind kind,
            AotDispatchPlannerV1.Strategy strategy,
            String name,
            int arity,
            Ast.FunctionDecl function,
            Ast.ClassDecl receiverClass,
            Ast.MethodDecl method) {
        public Plan {
            Objects.requireNonNull(kind, "checked call kind");
            Objects.requireNonNull(strategy, "checked dispatch strategy");
            Objects.requireNonNull(name, "checked callable name");
            if (name.isBlank() || arity < 0) {
                throw new IllegalArgumentException("invalid checked callable selector");
            }
            switch (kind) {
                case FREE_FUNCTION -> {
                    if (strategy != AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL
                            || function == null || receiverClass != null || method != null
                            || !function.name().equals(name) || function.parameters().size() != arity) {
                        throw new IllegalArgumentException("invalid checked free-function target");
                    }
                }
                case STATIC_METHOD, INSTANCE_METHOD -> {
                    AotDispatchPlannerV1.Strategy required = kind == AotDispatchPlannerV1.CallKind.STATIC_METHOD
                            ? AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL
                            : AotDispatchPlannerV1.Strategy.METHOD_SLOT;
                    if (strategy != required || function != null || receiverClass == null || method == null
                            || !method.name().equals(name) || method.arity() != arity
                            || method.isStatic() != (kind == AotDispatchPlannerV1.CallKind.STATIC_METHOD)) {
                        throw new IllegalArgumentException("invalid checked class-method target");
                    }
                }
                default -> throw new IllegalArgumentException(
                        "only locally resolved named callables have executable checked plans");
            }
        }
    }

    /** Do not produce a partial plan for a spread whose runtime arity differs from syntax. */
    public static boolean fixedSourceArity(Ast.CallExpr call) {
        return call.arguments().stream().noneMatch(arg -> arg instanceof Ast.SpreadExpr);
    }

    private static final ReferenceQueue<Ast.CallExpr> CLEARED = new ReferenceQueue<>();
    private static final Map<IdentityKey, Plan> PLANS = new HashMap<>();

    private static final class IdentityKey extends WeakReference<Ast.CallExpr> {
        private final int hash;
        private IdentityKey(Ast.CallExpr call, ReferenceQueue<Ast.CallExpr> queue) {
            super(Objects.requireNonNull(call, "checked call"), queue);
            hash = System.identityHashCode(call);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey key)) return false;
            Ast.CallExpr left = get();
            return left != null && left == key.get();
        }
    }

    private static void expunge() {
        Reference<? extends Ast.CallExpr> ref;
        while ((ref = CLEARED.poll()) != null) PLANS.remove(ref);
    }

    /**
     * Compiler-only intent: callers outside TypeChecker must not present
     * arbitrary plans as admission evidence. A repeated check cannot change a
     * previously published target for the same immutable AST node.
     */
    public static synchronized void record(Ast.CallExpr call, Plan plan) {
        Objects.requireNonNull(call, "checked call");
        Objects.requireNonNull(plan, "checked plan");
        if (!fixedSourceArity(call) || plan.arity() != call.arguments().size()) {
            throw new IllegalArgumentException("checked dispatch cannot change source call arity");
        }
        expunge();
        IdentityKey key = new IdentityKey(call, CLEARED);
        Plan previous = PLANS.putIfAbsent(key, plan);
        if (previous != null
                && (previous.kind() != plan.kind() || previous.strategy() != plan.strategy()
                    || !previous.name().equals(plan.name()) || previous.arity() != plan.arity()
                    || previous.function() != plan.function()
                    || previous.receiverClass() != plan.receiverClass()
                    || previous.method() != plan.method())) {
            throw new IllegalStateException(
                    "same checked call expression acquired incompatible dispatch targets");
        }
    }

    public static synchronized Plan lookup(Ast.CallExpr call) {
        Objects.requireNonNull(call, "call");
        expunge();
        return PLANS.get(new IdentityKey(call, null));
    }
}
