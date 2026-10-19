package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-minted ownership/provenance half-proof for scheduled work.
 *
 * <p>This object deliberately proves no type-level Send/Sync property.
 * {@link ThreadSafetyTraits} remains the sole resolved semantic authority for
 * that decision. These entries are minted only by {@link OwnershipChecker}
 * after the corresponding affine move/copy checks have succeeded.</p>
 *
 * <p>The map is keyed by AST node identity, not structural equality. A later
 * lowering phase must consume proofs for the exact checked Task boundary and
 * must also require the resolved ThreadSafetyTraits proof plus TaskScope
 * lifetime/quiescence admission.</p>
 */
public final class TaskTransferProvenance {
    private static final int MAX_BOUNDARIES = 4_096;
    private static final int MAX_TRANSFERS_PER_BOUNDARY = 4_096;
    private static final int MAX_TOTAL_TRANSFERS = 65_536;
    private static final int MAX_NAME_CHARS = 256;

    public enum TransferKind {
        /** Copy value; the original binding remains usable. */
        COPY,
        /** Existing affine owner was consumed exactly once at this boundary. */
        UNIQUE_MOVE,
        /** Fresh/returned owned rvalue with no source binding retained here. */
        OWNED_RVALUE
    }

    public static final class Transfer {
        private final String name;
        private final Ast.TypeRef type;
        private final TransferKind kind;

        Transfer(String name, Ast.TypeRef type, TransferKind kind) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank() || name.length() > MAX_NAME_CHARS) {
                throw new IllegalArgumentException(
                        "invalid Task transfer provenance name");
            }
            this.type = Objects.requireNonNull(type, "type");
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        public String name() { return name; }
        public Ast.TypeRef type() { return type; }
        public TransferKind kind() { return kind; }
    }

    private final IdentityHashMap<Object, List<Transfer>> byBoundary =
            new IdentityHashMap<>();
    private int totalTransfers;
    private boolean sealed;

    TaskTransferProvenance() { }

    void record(Object boundary, List<Transfer> transfers) {
        if (sealed) {
            throw new IllegalStateException(
                    "Task transfer provenance is already sealed");
        }
        Objects.requireNonNull(boundary, "boundary");
        List<Transfer> copy =
                List.copyOf(Objects.requireNonNull(transfers, "transfers"));
        if (copy.size() > MAX_TRANSFERS_PER_BOUNDARY
                || byBoundary.size() >= MAX_BOUNDARIES
                || copy.size() > MAX_TOTAL_TRANSFERS - totalTransfers) {
            throw new IllegalStateException(
                    "Task transfer provenance capacity exceeded");
        }
        if (byBoundary.containsKey(boundary)) {
            throw new IllegalStateException(
                    "duplicate Task transfer provenance for one AST boundary");
        }
        // Commit only after every bound/duplicate check so rejection is atomic.
        byBoundary.put(boundary, copy);
        totalTransfers += copy.size();
    }

    TaskTransferProvenance seal() {
        sealed = true;
        return this;
    }

    public List<Transfer> transfersFor(Ast.Stmt boundary) {
        return transfersForIdentity(boundary);
    }

    public List<Transfer> transfersFor(Ast.Expr boundary) {
        return transfersForIdentity(boundary);
    }

    private List<Transfer> transfersForIdentity(Object boundary) {
        List<Transfer> transfers = byBoundary.get(boundary);
        return transfers == null ? List.of() : transfers;
    }

    public int boundaryCount() {
        return byBoundary.size();
    }

    public int transferCount() {
        return totalTransfers;
    }

    public boolean sealed() {
        return sealed;
    }
}
