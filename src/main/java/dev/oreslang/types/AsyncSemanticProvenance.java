package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Exact-call semantic proof for async lowering. */
public final class AsyncSemanticProvenance {
    public record Admission(String target, Types.Type result) {
        public Admission {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(result, "result");
        }
    }

    private final IdentityHashMap<Ast.CallExpr, Admission> byCall =
            new IdentityHashMap<>();
    private boolean sealed;

    AsyncSemanticProvenance() { }

    void record(Ast.CallExpr call, String target, Types.Type result) {
        if (sealed) throw new IllegalStateException("async semantic provenance is sealed");
        Objects.requireNonNull(call, "call");
        Admission admission = new Admission(target, result);
        if (byCall.putIfAbsent(call, admission) != null) {
            throw new IllegalStateException("duplicate async semantic proof for one call");
        }
    }

    AsyncSemanticProvenance seal() {
        sealed = true;
        return this;
    }

    static AsyncSemanticProvenance empty() {
        return new AsyncSemanticProvenance().seal();
    }

    public Admission admissionFor(Ast.CallExpr call) {
        return byCall.get(call);
    }

    public boolean contains(Ast.CallExpr call) {
        return byCall.containsKey(call);
    }

    public int callCount() { return byCall.size(); }
    public boolean sealed() { return sealed; }

    public java.util.List<Ast.CallExpr> calls() {
        return java.util.List.copyOf(byCall.keySet());
    }

    /**
     * Require one exact-call semantic proof and one exact-call ownership proof
     * for every admitted async call. Keeping the join here lets every complete
     * front-end entrypoint share the same authority.
     */
    public static void requireExactOwnershipJoin(
            AsyncSemanticProvenance semantic,
            AsyncTransferProvenance transfers) {
        Objects.requireNonNull(semantic, "semantic");
        Objects.requireNonNull(transfers, "transfers");
        if (!semantic.sealed() || !transfers.sealed()) {
            throw new IllegalStateException(
                    "Oreslang compiler invariant violated: async proof "
                            + "ledgers must be sealed before admission");
        }
        if (semantic.callCount() != transfers.callCount()) {
            throw new IllegalArgumentException(
                    "async lowering admission is incomplete: semantic and "
                            + "ownership proof counts differ");
        }
        for (Ast.CallExpr call : semantic.calls()) {
            if (!transfers.contains(call)) {
                throw new IllegalArgumentException(
                        "async lowering admission is incomplete: a "
                                + "semantically admitted async call has no "
                                + "matching ownership-transfer proof");
            }
        }
        for (Ast.CallExpr call : transfers.calls()) {
            if (!semantic.contains(call)) {
                throw new IllegalStateException(
                        "Oreslang compiler invariant violated: async "
                                + "ownership proof has no matching semantic "
                                + "admission for the exact call");
            }
        }
    }
}
