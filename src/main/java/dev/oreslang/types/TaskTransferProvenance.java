package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashSet;
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

    enum SelfMode {
        NONE,
        CLASS_NOMINAL,
        RECEIVER_POLYMORPHIC
    }

    /**
     * Compiler-minted lexical type-resolution context for one exact Task
     * boundary. This is not a Send proof and is never source-authorable.
     */
    record LexicalTypeContext(
            List<String> genericParameters,
            Ast.ClassDecl ownerClass,
            SelfMode selfMode) {
        LexicalTypeContext {
            genericParameters = List.copyOf(
                    Objects.requireNonNull(
                            genericParameters, "genericParameters"));
            selfMode = Objects.requireNonNull(selfMode, "selfMode");
            if (new HashSet<>(genericParameters).size()
                    != genericParameters.size()) {
                throw new IllegalArgumentException(
                        "duplicate Task lexical generic parameter");
            }
            if ((selfMode == SelfMode.NONE) != (ownerClass == null)) {
                throw new IllegalArgumentException(
                        "Task lexical self mode/class owner mismatch");
            }
        }

        static LexicalTypeContext none() {
            return new LexicalTypeContext(
                    List.of(), null, SelfMode.NONE);
        }
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

    private final Ast.Program programIdentity;
    private final IdentityHashMap<Object, List<Transfer>> byBoundary =
            new IdentityHashMap<>();
    private final IdentityHashMap<Object, LexicalTypeContext>
            lexicalContextByBoundary = new IdentityHashMap<>();
    private int totalTransfers;
    private boolean sealed;

    TaskTransferProvenance() { this(null); }

    TaskTransferProvenance(Ast.Program programIdentity) {
        this.programIdentity = programIdentity;
    }

    Ast.Program programIdentity() { return programIdentity; }

    void record(Object boundary, List<Transfer> transfers) {
        record(boundary, transfers, LexicalTypeContext.none());
    }

    void record(
            Object boundary,
            List<Transfer> transfers,
            LexicalTypeContext lexicalContext) {
        if (sealed) {
            throw new IllegalStateException(
                    "Task transfer provenance is already sealed");
        }
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(lexicalContext, "lexicalContext");
        List<Transfer> copy =
                List.copyOf(Objects.requireNonNull(transfers, "transfers"));
        if (copy.size() > MAX_TRANSFERS_PER_BOUNDARY
                || byBoundary.size() >= MAX_BOUNDARIES
                || copy.size() > MAX_TOTAL_TRANSFERS - totalTransfers) {
            throw new IllegalStateException(
                    "Task transfer provenance capacity exceeded");
        }
        if (byBoundary.containsKey(boundary)
                || lexicalContextByBoundary.containsKey(boundary)) {
            throw new IllegalStateException(
                    "duplicate Task transfer provenance for one AST boundary");
        }
        // Commit only after every bound/duplicate/context check so rejection
        // cannot leave the transfer and lexical-context ledgers misaligned.
        byBoundary.put(boundary, copy);
        lexicalContextByBoundary.put(boundary, lexicalContext);
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
        Objects.requireNonNull(boundary, "boundary");
        if (!sealed) {
            throw new IllegalStateException(
                    "Task transfer provenance must be sealed before lookup");
        }
        List<Transfer> transfers = byBoundary.get(boundary);
        if (transfers == null) {
            throw new IllegalArgumentException(
                    "no Task transfer provenance for exact AST boundary");
        }
        return transfers;
    }

    LexicalTypeContext lexicalContextForIdentity(Object boundary) {
        Objects.requireNonNull(boundary, "boundary");
        if (!sealed) {
            throw new IllegalStateException(
                    "Task transfer provenance must be sealed before lookup");
        }
        if (!byBoundary.containsKey(boundary)) {
            throw new IllegalArgumentException(
                    "no Task transfer provenance for exact AST boundary");
        }
        LexicalTypeContext context = lexicalContextByBoundary.get(boundary);
        if (context == null) {
            throw new IllegalStateException(
                    "Task transfer provenance lost lexical context");
        }
        return context;
    }

    void forEachBoundary(
            java.util.function.BiConsumer<Object, List<Transfer>> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (!sealed) {
            throw new IllegalStateException(
                    "Task transfer provenance must be sealed before iteration");
        }
        byBoundary.forEach(consumer);
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
