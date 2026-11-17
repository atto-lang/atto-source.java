package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.Objects;

/**
 * Immutable semantic description of one flattened scalar contributed by a raw
 * call item.
 *
 * <p>The {@code sourceExpression} is the expression evaluated once for the raw
 * call item. A direct positional/named argument has no projection. Tuple and
 * record spreads reuse the same source expression for every projected scalar,
 * distinguished by a monotonically increasing projection index. Consumers must
 * never evaluate {@code sourceExpression} once per projection.</p>
 */
public record CheckedCallItem(
        Ast.Expr sourceExpression,
        ProjectionKind projectionKind,
        String label,
        int callItemIndex,
        int projectionIndex) {

    public enum ProjectionKind {
        DIRECT,
        TUPLE,
        RECORD,
        OMISSION
    }

    public CheckedCallItem {
        sourceExpression =
                Objects.requireNonNull(
                        sourceExpression, "call item source expression");
        projectionKind =
                Objects.requireNonNull(
                        projectionKind, "call item projection kind");
        if (callItemIndex < 0) {
            throw new IllegalArgumentException(
                    "checked call item requires a non-negative source call-item index");
        }
        if (label != null && label.isBlank()) {
            throw new IllegalArgumentException(
                    "checked call item label cannot be blank");
        }

        switch (projectionKind) {
            case DIRECT -> {
                if (projectionIndex != -1) {
                    throw new IllegalArgumentException(
                            "direct call item cannot carry a projection index");
                }
            }
            case TUPLE -> {
                if (label != null || projectionIndex < 0) {
                    throw new IllegalArgumentException(
                            "tuple projection requires an unlabeled non-negative projection index");
                }
            }
            case RECORD -> {
                if (label == null || projectionIndex < 0) {
                    throw new IllegalArgumentException(
                            "record projection requires a label and non-negative projection index");
                }
            }
            case OMISSION -> {
                if (label != null || projectionIndex != -1
                        || !Ast.isExplicitOmissionMarker(sourceExpression)) {
                    throw new IllegalArgumentException(
                            "omission item requires a bare omit or '_' source marker");
                }
            }
        }
    }

    public static CheckedCallItem positional(
            Ast.Expr expression,
            int callItemIndex) {
        return new CheckedCallItem(
                expression,
                ProjectionKind.DIRECT,
                null,
                callItemIndex,
                -1);
    }

    public static CheckedCallItem named(
            Ast.Expr expression,
            String label,
            int callItemIndex) {
        return new CheckedCallItem(
                expression,
                ProjectionKind.DIRECT,
                Objects.requireNonNull(label, "label"),
                callItemIndex,
                -1);
    }

    public static CheckedCallItem tupleProjection(
            Ast.Expr spreadExpression,
            int callItemIndex,
            int projectionIndex) {
        return new CheckedCallItem(
                spreadExpression,
                ProjectionKind.TUPLE,
                null,
                callItemIndex,
                projectionIndex);
    }

    public static CheckedCallItem recordProjection(
            Ast.Expr spreadExpression,
            String label,
            int callItemIndex,
            int projectionIndex) {
        return new CheckedCallItem(
                spreadExpression,
                ProjectionKind.RECORD,
                Objects.requireNonNull(label, "label"),
                callItemIndex,
                projectionIndex);
    }

    public static CheckedCallItem omittedMarker(
            Ast.Expr markerExpression,
            int callItemIndex) {
        return new CheckedCallItem(
                markerExpression,
                ProjectionKind.OMISSION,
                null,
                callItemIndex,
                -1);
    }

    public boolean projected() {
        return projectionKind == ProjectionKind.TUPLE
                || projectionKind == ProjectionKind.RECORD;
    }

    public boolean omissionMarker() {
        return projectionKind == ProjectionKind.OMISSION;
    }

    public CheckedCallPlan.InputSlot inputSlot() {
        if (projectionKind == ProjectionKind.TUPLE) {
            return CheckedCallPlan.InputSlot.positionalProjection(
                    callItemIndex,
                    projectionIndex);
        }
        if (projectionKind == ProjectionKind.RECORD) {
            return CheckedCallPlan.InputSlot.namedProjection(
                    label,
                    callItemIndex,
                    projectionIndex);
        }
        if (projectionKind == ProjectionKind.OMISSION) {
            return CheckedCallPlan.InputSlot.explicitOmission(callItemIndex);
        }
        return label == null
                ? CheckedCallPlan.InputSlot.positional(callItemIndex)
                : CheckedCallPlan.InputSlot.named(label, callItemIndex);
    }
}
