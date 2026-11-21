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

    /**
     * An immutable diagnostic handle, not a guest-language Valid<T> or a
     * transferrable authorization token. Only this bridge creates handles.
     * The authoritative ledger additionally requires original AST/catalog
     * identity, so copying this reference never transfers compiler evidence.
     */
    public static final class Evidence {
        private final String schemaId;
        private final String qualifiedTypeName;
        private final String fingerprint;
        private final long value;
        private final TypeConstraintDescriptors.Catalog originCatalog;

        private Evidence(
                String schemaId,
                String qualifiedTypeName,
                String fingerprint,
                long value,
                TypeConstraintDescriptors.Catalog originCatalog) {
            this.schemaId = Objects.requireNonNull(schemaId, "constraint schema id");
            this.qualifiedTypeName = Objects.requireNonNull(
                    qualifiedTypeName, "constraint qualified type");
            this.fingerprint = Objects.requireNonNull(
                    fingerprint, "constraint fingerprint");
            this.value = value;
            this.originCatalog = Objects.requireNonNull(
                    originCatalog, "origin compiler catalog");
        }

        public String schemaId() { return schemaId; }
        public String qualifiedTypeName() { return qualifiedTypeName; }
        public String fingerprint() { return fingerprint; }
        public long value() { return value; }

        private boolean sameEvidence(Evidence other) {
            return originCatalog == other.originCatalog
                    && value == other.value
                    && schemaId.equals(other.schemaId)
                    && qualifiedTypeName.equals(other.qualifiedTypeName)
                    && fingerprint.equals(other.fingerprint);
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
     * Static v1 bridge only. Never claim that a caller-supplied long is the
     * evaluated result of an arbitrary AST node. Runtime checked conversion
     * will require a separate compiler-private, evaluated-value gateway.
     */
    private static long literalSignedI64(Ast.Expr expression) {
        if (!(expression instanceof Ast.LiteralExpr literal)) {
            throw new IllegalArgumentException(
                    "CONSTRAINT_STATIC_LITERAL_REQUIRED: dynamic values need "
                    + "a trusted compiler-generated checked-conversion path");
        }
        Object raw = literal.value();
        if (raw instanceof Long integer) return integer;
        if (raw instanceof Integer integer) return integer.longValue();
        if (raw instanceof Short integer) return integer.longValue();
        if (raw instanceof Byte integer) return integer.longValue();
        // Float, Double, BigInteger, strings, booleans and user-supplied
        // boxed numeric objects are not canonical signed-i64 AST literals.
        throw new IllegalArgumentException(
                "CONSTRAINT_I64_LITERAL_REQUIRED: exact signed-i64 literal expected");
    }

    /**
     * Validate a signed-i64 literal against one descriptor and record
     * evidence bound to the exact AST node + catalog identity. This method
     * does not authorize guest constructors or bind a runtime variable.
     */
    public static synchronized Evidence validateAndRecord(
            Ast.Expr expression,
            TypeConstraintDescriptors.Catalog catalog,
            String qualifiedTypeName,
            long value) {
        Objects.requireNonNull(expression, "constrained alias expression");
        Objects.requireNonNull(catalog, "constraint descriptor catalog");
        Objects.requireNonNull(qualifiedTypeName, "qualified constrained alias");

        // First bind the proposed value to the actual immutable expression.
        // Previously an AST literal of 100 could be "proved" using value=3.
        long actual = literalSignedI64(expression);
        if (actual != value) {
            throw new IllegalArgumentException(
                    "CONSTRAINT_VALUE_EXPRESSION_MISMATCH: supplied value "
                    + "is not the expression's exact signed-i64 literal");
        }

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
                // Never retain rejected input values in diagnostics. The
                // stable predicate ID is enough for callers and CI.
                throw new Violation(
                        predicate.errorId(),
                        "constrained i64 alias predicate was not satisfied");
            }
        }

        Evidence evidence = new Evidence(
                descriptor.schemaId(),
                descriptor.qualifiedTypeName(),
                descriptor.fingerprint(),
                actual,
                catalog);
        expunge();
        IdentityKey key = new IdentityKey(expression, CLEARED);
        Evidence previous = EVIDENCE.putIfAbsent(key, evidence);
        if (previous != null && !previous.sameEvidence(evidence)) {
            throw new IllegalStateException(
                    "CONSTRAINT_PROVENANCE_REBIND: checked expression changed alias evidence");
        }
        return previous == null ? evidence : previous;
    }

    private static synchronized Evidence lookup(Ast.Expr expression) {
        Objects.requireNonNull(expression, "constrained alias expression");
        expunge();
        return EVIDENCE.get(new IdentityKey(expression, null));
    }

    /**
     * Exact AST identity is required. Optimizer rewrites must call the normal
     * checker/validator again; there is intentionally no metadata-copy API.
     */
    private static synchronized Evidence requireIdentity(Ast.Expr expression) {
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

        Evidence evidence = requireIdentity(expression);
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
        // Identical bytes in a different compilation catalog do not transfer
        // AST evidence across import/recompile/generation boundaries.
        if (evidence.originCatalog != catalog) {
            throw new IllegalStateException(
                    "CONSTRAINT_PROVENANCE_CATALOG_CONTEXT_MISMATCH");
        }
        // Recheck the source expression, not just a stale ledger entry.
        if (literalSignedI64(expression) != evidence.value()) {
            throw new IllegalStateException(
                    "CONSTRAINT_PROVENANCE_EXPRESSION_CHANGED");
        }
        return evidence;
    }
}
