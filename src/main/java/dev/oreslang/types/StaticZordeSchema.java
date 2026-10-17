package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Staticaly evaluable Zorde scalar schema DSL for compiler lowering.
 *
 * Every operation is pure, immutable and reduces to the same Ast.RefinementBound
 * fragment as @Min/@Max/@Range and "with". This is a HOST COMPILER substrate,
 * not yet a guest source-level Zorde builder or a user-registered runtime
 * predicate. Runtime callbacks, transforms and coercion cannot infer types.
 */
public final class StaticZordeSchema {
    private final Ast.TypeRef base;
    private final List<Ast.RefinementBound> bounds;

    private StaticZordeSchema(Ast.TypeRef base, List<Ast.RefinementBound> bounds) {
        this.base = Objects.requireNonNull(base);
        this.bounds = List.copyOf(bounds);
        RefinementRules.normalize(base, this.bounds); // fail closed immediately
    }

    public static StaticZordeSchema int64() {
        return new StaticZordeSchema(Ast.TypeRef.simple("int"), List.of());
    }

    public static StaticZordeSchema string() {
        return new StaticZordeSchema(Ast.TypeRef.simple("string"), List.of());
    }

    public static StaticZordeSchema fromRefinement(Ast.TypeRef base, Ast.RefinementSpec refinement) {
        if (refinement == null)
            return new StaticZordeSchema(base, List.of());
        if (refinement.whereExpression() != null)
            throw new UnsupportedOperationException(
                    "runtime where predicates cannot infer a statically checked Zorde type");
        if (!base.arguments().isEmpty() || base.inferArguments())
            throw new IllegalArgumentException("static schema needs a concrete scalar base");
        return new StaticZordeSchema(base, refinement.bounds());
    }

    private StaticZordeSchema add(String expectedBase, String comparator, long bound) {
        String actual = base.name().equals("String") ? "string" : base.name();
        if (!actual.equals(expectedBase)) {
            throw new IllegalArgumentException(
                    "invalid Zorde scalar operation on " + base.name());
        }
        if (expectedBase.equals("string") && bound < 0)
            throw new IllegalArgumentException("string length bound cannot be negative");
        List<Ast.RefinementBound> clauses = new ArrayList<>(bounds);
        clauses.add(new Ast.RefinementBound(
                expectedBase.equals("int") ? "value" : "length", comparator, bound));
        return new StaticZordeSchema(base, clauses);
    }

    public StaticZordeSchema min(long bound) { return add("int", "gte", bound); }
    public StaticZordeSchema max(long bound) { return add("int", "lte", bound); }
    public StaticZordeSchema greaterThan(long bound) { return add("int", "gt", bound); }
    public StaticZordeSchema lessThan(long bound) { return add("int", "lt", bound); }
    public StaticZordeSchema exact(long bound) { return add("int", "eq", bound); }
    public StaticZordeSchema excluding(long bound) { return add("int", "ne", bound); }

    public StaticZordeSchema minLength(long bound) { return add("string", "gte", bound); }
    public StaticZordeSchema maxLength(long bound) { return add("string", "lte", bound); }
    public StaticZordeSchema exactLength(long bound) { return add("string", "eq", bound); }
    public StaticZordeSchema excludingLength(long bound) { return add("string", "ne", bound); }

    public Ast.TypeRef baseType() { return base; }
    public List<Ast.RefinementBound> constraints() { return bounds; }

    /**
     * Output type is inferred ONLY from static constraints. A plain unbounded
     * structural builder infers the primitive base without a refinement.
     * Nominal type identity must be stamped by the compiler declaration site.
     */
    public Ast.RefinementSpec refinement(boolean nominal) {
        if (bounds.isEmpty() && !nominal) return null;
        return new Ast.RefinementSpec(bounds, null, nominal);
    }

    public RefinementRules.Descriptor descriptor(String qualifiedIdentity, boolean nominal) {
        Ast.RefinementSpec spec = refinement(nominal);
        if (spec == null) {
            // An unbounded scalar still has a schema; it does not acquire
            // a stricter static type merely because it has a validator.
            spec = new Ast.RefinementSpec(List.of(
                    new Ast.RefinementBound(base.name().equals("int") ? "value" : "length",
                            "gte", base.name().equals("int") ? Long.MIN_VALUE : 0L)),
                    null, false);
        }
        return RefinementRules.descriptor(nominal ? qualifiedIdentity : null, base, spec);
    }

    /** Compile-time and runtime reference checks consume the SAME descriptor. */
    public boolean acceptsLiteral(Object raw) {
        return descriptor(null, false).accepts(raw);
    }
}
