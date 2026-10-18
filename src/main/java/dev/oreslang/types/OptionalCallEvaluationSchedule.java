package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, non-executable evaluation/ABI ordering contract for staged calls.
 *
 * <p>Every ordinary call first evaluates its callee expression (including any
 * receiver), then each supplied argument exactly once in lexical source order.
 * The full ABI is materialized in declaration order only after those source
 * evaluations. An omitted T? parameter has an ABI slot but no argument
 * evaluation. A constructor or actor spawn has no guest callee expression.
 *
 * <p>This is a compiler preview, NOT permission to execute a staged call.
 * Selected declaration, types, generic substitutions, ownership, effects and
 * backend lowering still require independent compiler proofs.
 */
public final class OptionalCallEvaluationSchedule {
    private OptionalCallEvaluationSchedule() { }

    public enum Operation {
        EVALUATE_CALLEE,
        EVALUATE_ARGUMENT,
        MATERIALIZE_ABI_SLOT,
        INVOKE
    }

    /**
     * EVALUATE_ARGUMENT indexes dense lexical expressions;
     * MATERIALIZE_ABI_SLOT indexes full formal-order slots.
     * EVALUATE_CALLEE and INVOKE have no index (-1).
     */
    public record Step(Operation operation, int index) {
        public Step {
            Objects.requireNonNull(operation, "operation");
            if (operation == Operation.EVALUATE_ARGUMENT
                    || operation == Operation.MATERIALIZE_ABI_SLOT) {
                if (index < 0) {
                    throw new IllegalArgumentException("indexed schedule step requires index >= 0");
                }
            } else if (index != -1) {
                throw new IllegalArgumentException("callee/invoke schedule index must be -1");
            }
        }
    }

    /** Factory-only, immutable AST/ABI recipe; contains no guest values. */
    public static final class Schedule {
        private final OptionalCallAbiPreview.InvocationKind kind;
        private final List<Step> steps;
        private final List<Ast.Expr> evaluationOrder;
        private final List<OptionalCallAbiPreview.Slot> abiSlots;

        private Schedule(OptionalCallAbiPreview.InvocationKind kind, List<Step> steps,
                         List<Ast.Expr> evaluationOrder,
                         List<OptionalCallAbiPreview.Slot> abiSlots) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.steps = List.copyOf(steps);
            this.evaluationOrder = List.copyOf(evaluationOrder);
            this.abiSlots = List.copyOf(abiSlots);
        }

        public OptionalCallAbiPreview.InvocationKind kind() { return kind; }
        public List<Step> steps() { return steps; }
        /** Original expression identities, not computed values. */
        public List<Ast.Expr> evaluationOrder() { return evaluationOrder; }
        public List<OptionalCallAbiPreview.Slot> abiSlots() { return abiSlots; }
    }

    /**
     * Derive the only schedule from the factory-created InvocationPreview.
     * Reverify source expression identity and destination use without
     * mistaking a raw call-item index for a dense evaluated-expression index.
     */
    public static Schedule from(OptionalCallAbiPreview.InvocationPreview invocation) {
        Objects.requireNonNull(invocation, "invocation");
        OptionalCallAbiPreview.Preview preview =
                Objects.requireNonNull(invocation.arguments(), "argument preview");
        List<Ast.Expr> sources = preview.sourceExpressions();
        List<Ast.Expr> ordered = invocation.evaluationOrder();
        List<OptionalCallAbiPreview.Slot> slots = preview.abiSlots();
        boolean call = invocation.kind() == OptionalCallAbiPreview.InvocationKind.CALL;
        int offset = call ? 1 : 0;
        if (ordered.size() != sources.size() + offset) {
            throw new IllegalArgumentException("invalid evaluation-order length");
        }
        if (call) {
            if (invocation.calleeExpression() == null
                    || ordered.getFirst() != invocation.calleeExpression()
                    || invocation.constructedType() != null) {
                throw new IllegalArgumentException("ordinary call target must evaluate first");
            }
        } else if (invocation.calleeExpression() != null
                || invocation.constructedType() == null) {
            throw new IllegalArgumentException("construction must not evaluate a guest callee");
        }

        boolean[] consumed = new boolean[sources.size()];
        for (int i = 0; i < slots.size(); i++) {
            OptionalCallAbiPreview.Slot slot = slots.get(i);
            if (slot.formalIndex() != i) {
                throw new IllegalArgumentException("ABI slots must preserve formal order");
            }
            int source = slot.evaluatedSourceIndex();
            if (source >= 0) {
                if (source >= consumed.length || consumed[source]) {
                    throw new IllegalArgumentException("missing or duplicated argument temporary");
                }
                consumed[source] = true;
            }
        }
        for (boolean seen : consumed) {
            if (!seen) throw new IllegalArgumentException("source value has no ABI destination");
        }

        List<Step> steps = new ArrayList<>(offset + sources.size() + slots.size() + 1);
        if (call) steps.add(new Step(Operation.EVALUATE_CALLEE, -1));
        for (int i = 0; i < sources.size(); i++) {
            if (ordered.get(i + offset) != sources.get(i)) {
                throw new IllegalArgumentException("argument expression order is not lexical");
            }
            steps.add(new Step(Operation.EVALUATE_ARGUMENT, i));
        }
        for (int i = 0; i < slots.size(); i++) {
            steps.add(new Step(Operation.MATERIALIZE_ABI_SLOT, i));
        }
        steps.add(new Step(Operation.INVOKE, -1));
        return new Schedule(invocation.kind(), steps, ordered, slots);
    }
}
