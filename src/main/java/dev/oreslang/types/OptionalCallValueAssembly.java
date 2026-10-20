package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The checked call plan's value-assembly boundary.
 *
 * <p>Values are ALREADY evaluated in lexical order before this helper is
 * called. It never evaluates an expression, performs ownership transfers,
 * resolves a callable, invokes guest code, or enables the staged-call AST.
 *
 * <p>The compiler must independently prove concrete generic/Option identity,
 * effect/loan safety, selected signature and cleanup before using these values
 * in a backend. That integration is deliberately gated.
 */
public final class OptionalCallValueAssembly {
    private OptionalCallValueAssembly() { }

    /**
     * Backend-provided creation of nominal Option values. A factory must not
     * turn None into Java null or emit a null Some. The compiler must ensure
     * it is the trusted Option implementation for its isolated allocation
     * domain, never an arbitrary user-provided callable.
     */
    public interface TrustedOptionFactory {
        Object some(Ast.TypeRef payloadType, Object value);
        Object none(Ast.TypeRef payloadType);
        /** Verify exact runtime Option identity for the checked payload type. */
        boolean isNominalOption(Ast.TypeRef payloadType, Object value);
    }

    /**
     * Materializes ABI values in FULL FORMAL order from dense source-order
     * temporaries. The returned list contains NO Java nulls.
     *
     * <p>Omitted slots do not consume a source value. A supplied Option.None
     * remains the supplied runtime value (PASS_OPTION), distinct from a
     * compiler-created None (INJECT_NONE) even though both may have the same
     * callee-visible Option representation.
     */
    public static List<Object> assemble(
            OptionalCallAbiPreview.Preview checkedPreview,
            List<?> evaluatedSourceValues,
            TrustedOptionFactory optionFactory) {
        Objects.requireNonNull(checkedPreview, "checked preview");
        Objects.requireNonNull(evaluatedSourceValues, "evaluated source values");
        Objects.requireNonNull(optionFactory, "trusted Option factory");

        // Snapshot caller-owned source temporaries before invoking the
        // trusted adapter. Otherwise a factory callback can replace a value
        // between nominal preflight and ABI materialization, making a
        // validated Option<T> slot receive a different (forged) object.
        // This is a shallow snapshot of already-evaluated guest values; the
        // owning compiler/runtime must separately enforce deep immutability
        // and borrowed-lifetime rules for mutable payloads.
        // Cheap preflight before making any O(n) snapshot of a caller-owned
        // vector. Check the immutable snapshot again afterward to detect a
        // size-changing caller or concurrent mutation before adapter effects.
        int expected = checkedPreview.sourceExpressions().size();
        if (evaluatedSourceValues.size() != expected)
            throw new IllegalArgumentException(
                    "source values do not match the verified evaluated-source count");
        List<?> sourceValues;
        try {
            sourceValues = List.copyOf(evaluatedSourceValues);
        } catch (NullPointerException nullTemporary) {
            throw new IllegalArgumentException(
                    "a source temporary is null; use a nominal Option.None value",
                    nullTemporary);
        }
        if (checkedPreview.sourceExpressions().size() != sourceValues.size()) {
            throw new IllegalArgumentException(
                    "source values do not match the verified evaluated-source count");
        }

        // Validate ALL supplied nominal Option values BEFORE creating any
        // synthetic Some/None. A required Option<T> is not omittable, but
        // passing a forged plain T to that slot is just as unsafe as forging
        // PASS_OPTION for an optional T? formal. Do not infer nominal identity
        // from a caller's type metadata alone.
        for (OptionalCallAbiPreview.Slot slot : checkedPreview.abiSlots()) {
            boolean requiredOption = slot.action() == OptionalCallAbiPreview.Action.PASS_REQUIRED
                    && slot.calleeType().name().equals("Option")
                    && slot.calleeType().arguments().size() == 1
                    && !slot.calleeType().inferArguments();
            if (slot.action() == OptionalCallAbiPreview.Action.PASS_OPTION || requiredOption) {
                int source = slot.evaluatedSourceIndex();
                if (source < 0 || source >= sourceValues.size()) {
                    throw new IllegalArgumentException("missing supplied nominal Option");
                }
                Ast.TypeRef payload = slot.calleeType().arguments().getFirst();
                if (!optionFactory.isNominalOption(payload, sourceValues.get(source))) {
                    throw new IllegalArgumentException(
                            "supplied Option value fails nominal identity validation");
                }
            }
        }

        List<Object> result = new ArrayList<>(checkedPreview.abiSlots().size());
        for (OptionalCallAbiPreview.Slot slot : checkedPreview.abiSlots()) {
            Object adapted;
            if (slot.action() == OptionalCallAbiPreview.Action.INJECT_NONE) {
                adapted = optionFactory.none(slot.calleeType().arguments().getFirst());
            } else {
                int source = slot.evaluatedSourceIndex();
                if (source < 0 || source >= sourceValues.size()) {
                    throw new IllegalArgumentException("missing checked source temporary");
                }
                Object supplied = sourceValues.get(source);
                adapted = slot.action() == OptionalCallAbiPreview.Action.WRAP_SOME
                        ? optionFactory.some(slot.calleeType().arguments().getFirst(), supplied)
                        : supplied;
            }
            if (adapted == null) {
                throw new IllegalArgumentException("trusted Option factory returned null");
            }
            if (slot.action() != OptionalCallAbiPreview.Action.PASS_REQUIRED
                    && !optionFactory.isNominalOption(
                            slot.calleeType().arguments().getFirst(), adapted)) {
                throw new IllegalArgumentException(
                        "an optional ABI slot does not contain a trusted nominal Option");
            }
            result.add(adapted);
        }
        return List.copyOf(result);
    }
}
