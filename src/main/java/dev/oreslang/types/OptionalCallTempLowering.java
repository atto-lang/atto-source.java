package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A factory-derived, NON-EXECUTABLE temporary/ABI materialization recipe.
 *
 * <p>The parsed AST and selected owner come from BoundInvocation, which
 * reconstructs the exact call-item plan and checks the immutable schedule.
 * These records describe future compiler-owned temporaries, not guest values
 * or permissions to invoke anything. Neither a supplied TypeRef claim nor a
 * successful recipe can authorize runtime lowering.
 *
 * <p>The target is deliberately distinguished from evaluated arguments:
 * a module function is a statically selected declaration (no guest target
 * temp); an instance method captures its receiver expression exactly once;
 * a constructor has no callee or receiver expression to evaluate.
 */
public final class OptionalCallTempLowering {
    private OptionalCallTempLowering() { }

    public enum TargetKind {
        STATIC_MODULE_FUNCTION, INSTANCE_METHOD_RECEIVER, CLASS_CONSTRUCTOR
    }

    /** A separate lexical call-item occurrence receives one distinct temp. */
    public record ArgumentTemp(int evaluatedIndex, int rawCallItemIndex,
                               int tempIndex, Ast.Expr expression) {
        public ArgumentTemp {
            if (evaluatedIndex < 0 || rawCallItemIndex < 0 || tempIndex < 0) {
                throw new IllegalArgumentException("argument temp indexes must be nonnegative");
            }
            Objects.requireNonNull(expression, "argument source expression");
        }
    }

    /** An ABI slot materializes after all source expressions are captured. */
    public record AbiSlot(int formalIndex, String name, Ast.TypeRef calleeType,
                          OptionalCallAbiPreview.Action action,
                          int tempIndex, int rawCallItemIndex) {
        public AbiSlot {
            if (formalIndex < 0 || name == null || name.isBlank()) {
                throw new IllegalArgumentException("invalid ABI formal");
            }
            Objects.requireNonNull(calleeType, "ABI callee type");
            Objects.requireNonNull(action, "ABI slot action");
            if ((tempIndex == -1) != (action == OptionalCallAbiPreview.Action.INJECT_NONE)) {
                throw new IllegalArgumentException(
                        "only injected None can lack a supplied argument temp");
            }
            if (tempIndex < -1 || rawCallItemIndex < -1
                    || (tempIndex >= 0 && rawCallItemIndex < 0)) {
                throw new IllegalArgumentException("invalid ABI temp/origin");
            }
        }
    }

    /**
     * Target receiver, source temporaries, and full formal-order materialization.
     * A method receiver consumes temp #0; args follow in lexical source order.
     * Static module targets and constructors have no guest callee temp.
     */
    public static final class Recipe {
        private final BoundInvocation bound;
        private final TargetKind targetKind;
        private final Ast.Expr receiverExpression;
        private final int receiverTempIndex;
        private final List<ArgumentTemp> argumentTemps;
        private final List<AbiSlot> abiSlots;

        private Recipe(BoundInvocation bound, TargetKind targetKind,
                       Ast.Expr receiverExpression, int receiverTempIndex,
                       List<ArgumentTemp> arguments, List<AbiSlot> slots) {
            this.bound = Objects.requireNonNull(bound);
            this.targetKind = Objects.requireNonNull(targetKind);
            this.receiverExpression = receiverExpression;
            this.receiverTempIndex = receiverTempIndex;
            this.argumentTemps = List.copyOf(arguments);
            this.abiSlots = List.copyOf(slots);
            if ((targetKind == TargetKind.INSTANCE_METHOD_RECEIVER)
                    != (receiverExpression != null && receiverTempIndex == 0)) {
                throw new IllegalArgumentException("method receiver temp invariant violated");
            }
            if (targetKind != TargetKind.INSTANCE_METHOD_RECEIVER
                    && (receiverExpression != null || receiverTempIndex != -1)) {
                throw new IllegalArgumentException(
                        "static module or constructor cannot evaluate a guest receiver");
            }
            if (argumentTemps.size() != bound.schedule().sourceBindings().size()
                    || abiSlots.size() != bound.schedule().abiSlots().size()) {
                throw new IllegalArgumentException(
                        "temp plan must cover all source items and all ABI slots");
            }
            boolean[] seen = new boolean[argumentTemps.size()];
            for (ArgumentTemp argument : argumentTemps) {
                int dense = argument.evaluatedIndex();
                if (dense >= seen.length || seen[dense]) {
                    throw new IllegalArgumentException("argument evaluated twice or missing");
                }
                seen[dense] = true;
                int expectedTemp = dense + (receiverTempIndex >= 0 ? 1 : 0);
                if (argument.tempIndex() != expectedTemp) {
                    throw new IllegalArgumentException("argument temps not ordered lexically");
                }
            }
            for (boolean covered : seen) {
                if (!covered) throw new IllegalArgumentException("dropped argument temp");
            }
        }

        public BoundInvocation bound() { return bound; }
        public TargetKind targetKind() { return targetKind; }
        public Ast.Expr receiverExpression() { return receiverExpression; }
        public int receiverTempIndex() { return receiverTempIndex; }
        public List<ArgumentTemp> argumentTemps() { return argumentTemps; }
        public List<AbiSlot> abiSlots() { return abiSlots; }
        public BoundInvocation.Readiness readiness() {
            return BoundInvocation.Readiness.SHAPE_ONLY;
        }
        /** Deliberately cannot execute until lexical, type, ownership and ABI proof. */
        public void requireExecutable() { bound.requireExecutable(); }
    }

    /** Derive the only temp mapping from already-bound, checked-source-order IR. */
    public static Recipe from(BoundInvocation bound) {
        Objects.requireNonNull(bound, "bound invocation");
        if (bound.readiness() != BoundInvocation.Readiness.SHAPE_ONLY) {
            throw new IllegalArgumentException("unreviewed bound invocation readiness");
        }
        OptionalCallEvaluationSchedule.Schedule schedule = bound.schedule();
        TargetKind targetKind;
        Ast.Expr receiver = null;
        if (bound.kind() == BoundInvocation.Kind.MODULE_FUNCTION) {
            targetKind = TargetKind.STATIC_MODULE_FUNCTION;
            if (!(bound.owner() instanceof Ast.ModuleDecl module)
                    || !(bound.source() instanceof Ast.StagedCallExpr call)) {
                throw new IllegalArgumentException("invalid statically selected module target");
            }
            Ast.Expr callee = call.callee();
            if (!(callee instanceof Ast.NameExpr)
                    && !(callee instanceof Ast.MemberExpr member
                         && member.receiver() instanceof Ast.NameExpr qualifier
                         && qualifier.name().equals(module.name()))) {
                throw new IllegalArgumentException("module target is not statically resolved");
            }
        } else if (bound.kind() == BoundInvocation.Kind.CLASS_METHOD) {
            targetKind = TargetKind.INSTANCE_METHOD_RECEIVER;
            if (!(bound.source() instanceof Ast.StagedCallExpr call)
                    || !(call.callee() instanceof Ast.MemberExpr member)) {
                throw new IllegalArgumentException("instance target has no receiver");
            }
            receiver = member.receiver();
        } else if (bound.kind() == BoundInvocation.Kind.CLASS_CONSTRUCTOR) {
            targetKind = TargetKind.CLASS_CONSTRUCTOR;
            if (!(bound.source() instanceof Ast.StagedNewExpr)) {
                throw new IllegalArgumentException("constructor has no corresponding source");
            }
        } else {
            throw new IllegalArgumentException("unsupported bound invocation kind");
        }
        int receiverTemp = receiver == null ? -1 : 0;
        int argOffset = receiver == null ? 0 : 1;
        List<Ast.Expr> sources = bound.preview().arguments().sourceExpressions();
        List<OptionalCallEvaluationSchedule.SourceBinding> sourceBindings =
                schedule.sourceBindings();
        if (sources.size() != sourceBindings.size()) {
            throw new IllegalArgumentException("source count does not match ABI schedule");
        }
        List<ArgumentTemp> arguments = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            var binding = sourceBindings.get(i);
            if (binding.evaluatedIndex() != i) {
                throw new IllegalArgumentException("out-of-order evaluated source binding");
            }
            arguments.add(new ArgumentTemp(
                    i, binding.originalCallItemIndex(), i + argOffset, sources.get(i)));
        }
        List<AbiSlot> slots = new ArrayList<>(schedule.abiSlots().size());
        for (var sourceSlot : schedule.abiSlots()) {
            int index = sourceSlot.evaluatedSourceIndex();
            int temp = index == -1 ? -1 : arguments.get(index).tempIndex();
            if (index >= 0 && arguments.get(index).rawCallItemIndex()
                    != sourceSlot.originalCallItemIndex()) {
                throw new IllegalArgumentException("ABI slot and raw source origin diverged");
            }
            slots.add(new AbiSlot(sourceSlot.formalIndex(), sourceSlot.formalName(),
                    sourceSlot.calleeType(), sourceSlot.action(),
                    temp, sourceSlot.originalCallItemIndex()));
        }
        return new Recipe(bound, targetKind, receiver, receiverTemp, arguments, slots);
    }
}
