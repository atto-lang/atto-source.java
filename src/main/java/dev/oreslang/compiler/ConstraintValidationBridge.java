package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-owned checked provenance for constrained aliases.
 *
 * <p>This is deliberately an identity side table, not a guest-visible bit,
 * wrapper field, string tag, or runtime nominal. The only minting API performs
 * descriptor validation first. AST replacement therefore loses authority
 * unless the replacement is independently revalidated.
 */
public final class ConstraintValidationBridge {
    private ConstraintValidationBridge() { }

    public record Evidence(
            String schemaId,
            String qualifiedTypeName,
            String fingerprint,
            long value) {
        public Evidence {
            Objects.requireNonNull(schemaId, "constraint schema id");
            Objects.requireNonNull(qualifiedTypeName, "constraint qualified type");
            Objects.requireNonNull(fingerprint, "constraint fingerprint");
        }
    }

    public static final class Violation extends IllegalArgumentException {
        private final String errorId;

        private Violation(String errorId, String message) {
            super(errorId + ": " + message);
            this.errorId = errorId;
        }

        public String errorId() {
            return errorId;
        }
    }

    private static final ReferenceQueue<Ast.Expr> CLEARED = new ReferenceQueue<>();
    private static final Map<IdentityKey, Evidence> EVIDENCE = new HashMap<>();

    private static final class IdentityKey extends WeakReference<Ast.Expr> {
        private final int hash;

        private IdentityKey(Ast.Expr expression, ReferenceQueue<Ast.Expr> queue) {
            super(expression, queue);
            hash = System.identityHashCode(expression);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey key)) return false;
            Ast.Expr value = get();
            return value != null && value == key.get();
        }
    }

    private static void expunge() {
        Reference<? extends Ast.Expr> collected;
        while ((collected = CLEARED.poll()) != null) {
            EVIDENCE.remove(collected);
        }
    }

    /**
     * Validate a signed-i64 value against one compiler-owned descriptor and
     * mint evidence bound to the exact expression node only after every
     * predicate succeeds.
     */
    public static synchronized Evidence validateAndRecord(
            Ast.Expr expression,
            TypeConstraintDescriptors.Catalog catalog,
            String qualifiedTypeName,
            long value) {
        Objects.requireNonNull(expression, "constrained alias expression");
        Objects.requireNonNull(catalog, "constraint descriptor catalog");
        Objects.requireNonNull(qualifiedTypeName, "qualified constrained alias");

        TypeConstraintDescriptors.Descriptor descriptor = catalog.find(qualifiedTypeName);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "CONSTRAINT_DESCRIPTOR_NOT_FOUND: " + qualifiedTypeName);
        }

        for (TypeConstraintDescriptors.Predicate predicate : descriptor.predicates()) {
            boolean accepted = switch (predicate.id()) {
                case "min_inclusive_i64" -> value >= predicate.operand();
                case "max_inclusive_i64" -> value <= predicate.operand();
                default -> throw new IllegalArgumentException(
                        "CONSTRAINT_PREDICATE_UNSUPPORTED: " + predicate.id());
            };
            if (!accepted) {
                throw new Violation(
                        predicate.errorId(),
                        "value " + value + " violates " + predicate.operator()
                                + " " + predicate.operand() + " for "
                                + descriptor.qualifiedTypeName());
            }
        }

        Evidence evidence = new Evidence(
                descriptor.schemaId(),
                descriptor.qualifiedTypeName(),
                descriptor.fingerprint(),
                value);
        expunge();
        IdentityKey key = new IdentityKey(expression, CLEARED);
        Evidence previous = EVIDENCE.putIfAbsent(key, evidence);
        if (previous != null && !previous.equals(evidence)) {
            throw new IllegalStateException(
                    "CONSTRAINT_PROVENANCE_REBIND: checked expression changed alias evidence");
        }
        return previous == null ? evidence : previous;
    }

    public static synchronized Evidence lookup(Ast.Expr expression) {
        Objects.requireNonNull(expression, "constrained alias expression");
        expunge();
        return EVIDENCE.get(new IdentityKey(expression, null));
    }

    /**
     * Exact AST identity is required. Optimizer rewrites must call the normal
     * checker/validator again; there is intentionally no metadata-copy API.
     */
    public static synchronized Evidence require(Ast.Expr expression) {
        Evidence evidence = lookup(expression);
        if (evidence == null) {
            throw new IllegalStateException(
                    "CONSTRAINT_VALIDATION_PROVENANCE_REQUIRED");
        }
        return evidence;
    }

    /**
     * Consume evidence only against the compiler catalog that is authoritative
     * at the use site. Exact AST identity alone is insufficient: recompilation,
     * imports or package metadata may carry a newer descriptor fingerprint.
     */
    public static synchronized Evidence requireCompatible(
            Ast.Expr expression,
            TypeConstraintDescriptors.Catalog catalog,
            String qualifiedTypeName) {
        Objects.requireNonNull(catalog, "constraint descriptor catalog");
        Objects.requireNonNull(qualifiedTypeName, "qualified constrained alias");

        Evidence evidence = require(expression);
        if (!evidence.qualifiedTypeName().equals(qualifiedTypeName)) {
            throw new IllegalStateException(
                    "CONSTRAINT_PROVENANCE_TYPE_MISMATCH: expected "
                            + qualifiedTypeName + ", got " + evidence.qualifiedTypeName());
        }

        TypeConstraintDescriptors.Descriptor descriptor =
                catalog.requireCompatible(
                        qualifiedTypeName,
                        evidence.schemaId(),
                        evidence.fingerprint());
        if (!descriptor.validationRequired()) {
            throw new IllegalStateException("CONSTRAINT_PROVENANCE_DESCRIPTOR_INVALID");
        }
        return evidence;
    }
}
