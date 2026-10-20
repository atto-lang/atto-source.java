package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-minted ownership half-proof for statically resolved async calls.
 *
 * <p>This class proves no type-level task safety by itself. It is emitted only
 * after OwnershipChecker has completed the affine argument checks for the exact
 * call AST node. OresCompiler combines it with a successful TypeChecker result
 * before exposing async-call admission to later lowering.</p>
 */
public final class AsyncTransferProvenance {
    public enum TransferKind {
        COPY,
        UNIQUE_MOVE,
        OWNED_RVALUE
    }

    public static final class Transfer {
        private final int argumentIndex;
        private final Ast.TypeRef type;
        private final TransferKind kind;

        Transfer(int argumentIndex, Ast.TypeRef type, TransferKind kind) {
            if (argumentIndex < 0) {
                throw new IllegalArgumentException("argumentIndex must be non-negative");
            }
            this.argumentIndex = argumentIndex;
            this.type = Objects.requireNonNull(type, "type");
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        public int argumentIndex() { return argumentIndex; }
        public Ast.TypeRef type() { return type; }
        public TransferKind kind() { return kind; }
    }

    private final IdentityHashMap<Ast.CallExpr, List<Transfer>> byCall =
            new IdentityHashMap<>();
    private boolean sealed;

    AsyncTransferProvenance() { }

    void record(Ast.CallExpr call, List<Transfer> transfers) {
        if (sealed) {
            throw new IllegalStateException(
                    "async transfer provenance is already sealed");
        }
        Objects.requireNonNull(call, "call");
        List<Transfer> copy = List.copyOf(transfers);
        if (byCall.putIfAbsent(call, copy) != null) {
            throw new IllegalStateException(
                    "duplicate async transfer provenance for one call");
        }
    }

    AsyncTransferProvenance seal() {
        sealed = true;
        return this;
    }

    public List<Transfer> transfersFor(Ast.CallExpr call) {
        List<Transfer> transfers = byCall.get(call);
        return transfers == null ? List.of() : transfers;
    }

    public boolean contains(Ast.CallExpr call) {
        return byCall.containsKey(call);
    }

    public int callCount() {
        return byCall.size();
    }

    public int transferCount() {
        int count = 0;
        for (Map.Entry<Ast.CallExpr, List<Transfer>> entry : byCall.entrySet()) {
            count += entry.getValue().size();
        }
        return count;
    }

    public boolean sealed() {
        return sealed;
    }
}
