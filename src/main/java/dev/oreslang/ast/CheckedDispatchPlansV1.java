package dev.oreslang.ast;

import dev.oreslang.compiler.AotDispatchPlannerV1;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable compiler-issued call plans indexed by exact Program and CallExpr
 * identity. There is deliberately no unscoped global per-call registry:
 * failed/partial type checks must not publish optimizable guest calls.
 *
 * <p>Plans do not authorize execution. The full compiler admission, ownership
 * checking, and evaluator-side declaration/slot and generation checks remain
 * independent prerequisites.
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
                    AotDispatchPlannerV1.Strategy required =
                            kind == AotDispatchPlannerV1.CallKind.STATIC_METHOD
                                    ? AotDispatchPlannerV1.Strategy.DIRECT_SYMBOL
                                    : AotDispatchPlannerV1.Strategy.METHOD_SLOT;
                    if (strategy != required || function != null || receiverClass == null || method == null
                            || !method.name().equals(name) || method.arity() != arity
                            || method.isStatic() != (kind == AotDispatchPlannerV1.CallKind.STATIC_METHOD)) {
                        throw new IllegalArgumentException("invalid checked class-method target");
                    }
                }
                default -> throw new IllegalArgumentException(
                        "only resolved local named calls have executable checked plans");
            }
        }

        /** Extra lexical check, never used in place of the type checker. */
        public void requireCallShape(Ast.CallExpr call) {
            Objects.requireNonNull(call, "checked call");
            if (call.arguments().size() != arity
                    || call.arguments().stream().anyMatch(a -> a instanceof Ast.SpreadExpr)) {
                throw new IllegalArgumentException("checked dispatch requires fixed source call arity");
            }
            if (kind == AotDispatchPlannerV1.CallKind.FREE_FUNCTION) {
                if (!(call.callee() instanceof Ast.NameExpr n) || !name.equals(n.name())) {
                    throw new IllegalArgumentException("checked free function must match named callee");
                }
            } else if (!(call.callee() instanceof Ast.MemberExpr m)
                    || !name.equals(m.member())) {
                throw new IllegalArgumentException("checked method must match named member");
            }
        }

        /** Targets must remain identical across repeated admission of this AST. */
        public boolean sameTargets(Plan other) {
            return other != null && kind == other.kind && strategy == other.strategy
                    && arity == other.arity && name.equals(other.name)
                    && function == other.function
                    && receiverClass == other.receiverClass && method == other.method;
        }
    }

    public static boolean fixedSourceArity(Ast.CallExpr call) {
        Objects.requireNonNull(call, "call");
        return call.arguments().stream().noneMatch(arg -> arg instanceof Ast.SpreadExpr);
    }

    private static final ReferenceQueue<Ast.Program> CLEARED_PROGRAMS = new ReferenceQueue<>();
    private static final Map<ProgramKey, Map<Ast.CallExpr, Plan>> BY_PROGRAM = new HashMap<>();

    private static final class ProgramKey extends WeakReference<Ast.Program> {
        private final int hash;

        private ProgramKey(Ast.Program program, ReferenceQueue<Ast.Program> queue) {
            super(Objects.requireNonNull(program, "checked program"), queue);
            hash = System.identityHashCode(program);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ProgramKey key)) return false;
            Ast.Program left = get();
            return left != null && left == key.get();
        }
    }

    private static void expunge() {
        Reference<? extends Ast.Program> ref;
        while ((ref = CLEARED_PROGRAMS.poll()) != null) BY_PROGRAM.remove(ref);
    }

    /**
     * Publish only after successful macro-expanded type validation. The caller
     * holds this full program and its identity-indexed checker-local targets.
     * All entries are copied and shape-validated before any publication.
     */
    public static synchronized void publish(Ast.Program program, Map<Ast.CallExpr, Plan> resolved) {
        Objects.requireNonNull(program, "checked program");
        Objects.requireNonNull(resolved, "checked call plans");
        IdentityHashMap<Ast.CallExpr, Plan> snapshot = new IdentityHashMap<>();
        for (Map.Entry<Ast.CallExpr, Plan> entry : resolved.entrySet()) {
            Ast.CallExpr call = Objects.requireNonNull(entry.getKey(), "checked call");
            Plan plan = Objects.requireNonNull(entry.getValue(), "checked call plan");
            plan.requireCallShape(call);
            snapshot.put(call, plan);
        }
        Map<Ast.CallExpr, Plan> immutable = Collections.unmodifiableMap(snapshot);
        expunge();
        ProgramKey key = new ProgramKey(program, CLEARED_PROGRAMS);
        Map<Ast.CallExpr, Plan> previous = BY_PROGRAM.get(key);
        if (previous != null) {
            if (previous.size() != immutable.size()) {
                throw new IllegalStateException("conflicting checked dispatch snapshot for program");
            }
            for (Map.Entry<Ast.CallExpr, Plan> entry : immutable.entrySet()) {
                Plan old = previous.get(entry.getKey());
                if (!entry.getValue().sameTargets(old)) {
                    throw new IllegalStateException(
                            "same program identity acquired changed call targets");
                }
            }
        } else {
            BY_PROGRAM.put(key, immutable);
        }
    }

    /**
     * Unpublished or rewritten programs get no shortcut. The evaluator makes
     * a single code-image-local immutable copy after verifying all link slots.
     */
    public static synchronized Map<Ast.CallExpr, Plan> forProgram(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        expunge();
        return BY_PROGRAM.getOrDefault(new ProgramKey(program, null), Map.of());
    }
}
