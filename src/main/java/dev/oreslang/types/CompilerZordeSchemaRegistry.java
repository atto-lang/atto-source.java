package dev.oreslang.types;

import dev.oreslang.compiler.ZordeSchemaGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-local, closed registry of statically derived Zorde scalar schemas.
 *
 * This is an admission *staging* phase, not production proof authority:
 * the source has no linked/imported contracts or checked owned graph lowering.
 * A private per-instance seal prevents an analysis Type issued by one registry
 * from impersonating another registry, even when schema names/hashes match.
 *
 * No public registry constructor, schema registration or guest-facing minting
 * API exists. In particular, a caller-provided fingerprint is never accepted.
 */
final class CompilerZordeSchemaRegistry {
    private final Object compilationSeal = new Object();
    private final String unitId;
    private final long generation;
    // Reproducibility/debugging only; this digest does NOT grant authority.
    private final String sourceDigest;
    private final Map<String, RefinementRules.Descriptor> schemas;

    private CompilerZordeSchemaRegistry(
            String unitId, long generation, String sourceDigest,
            Map<String, RefinementRules.Descriptor> schemas) {
        this.unitId = unitId;
        this.generation = generation;
        this.sourceDigest = sourceDigest;
        this.schemas = Collections.unmodifiableMap(new LinkedHashMap<>(schemas));
    }

    /**
     * Restricted parser/compiler analysis entry: no external linked schemas,
     * runtime schema creation, arbitrary descriptor map or user-provided digest.
     * Only statically parsed and normalized scalar declarations may enter.
     *
     * A future compiler stage must also validate module imports, ABI, actor
     * domains and closed-world generation before granting executable proof
     * construction. Until then guest Valid<T,S> remains rejected.
     */
    static CompilerZordeSchemaRegistry stageSourceForAnalysis(
            String source, String compilationUnitId, long generation) {
        Objects.requireNonNull(source, "source");
        if (compilationUnitId == null || compilationUnitId.isBlank()
                || compilationUnitId.length() > 256
                || !compilationUnitId.matches("[A-Za-z_][A-Za-z_0-9./-]*")) {
            throw new IllegalArgumentException("invalid Zorde compilation unit identity");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("negative Zorde compilation generation");
        }
        // planSource parses the source and rejects unresolved imports,
        // arbitrary schema calls, duplicate names and dynamic constraints.
        ZordeSchemaGenerator.Plan plan = ZordeSchemaGenerator.planSource(source);
        if (plan.schemas().isEmpty()) {
            throw new IllegalArgumentException("no statically admitted Zorde schemas");
        }
        Map<String, RefinementRules.Descriptor> entries = new java.util.TreeMap<>();
        for (var entry : plan.schemas().entrySet()) {
            String name = entry.getKey();
            RefinementRules.Descriptor descriptor = entry.getValue();
            if (name == null || !name.matches(
                    "[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)+")) {
                throw new IllegalArgumentException("Zorde schema is not fully qualified");
            }
            // Source-planned nominal declarations are precisely identified;
            // structural scalar schemas also get the scope-unique seal.
            if (!descriptor.nominalIdentity().isEmpty()
                    && !name.equals(descriptor.nominalIdentity())) {
                throw new IllegalArgumentException("Zorde nominal schema mismatch");
            }
            entries.put(name, descriptor);
        }
        return new CompilerZordeSchemaRegistry(
                compilationUnitId, generation, sha256(source), entries);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("required SHA-256 unavailable", unavailable);
        }
    }

    // Only code in the trusted compiler package can see the opaque seal.
    // Neither the compilation identifier nor the SHA-256 descriptor is it.
    Object compilationSeal() { return compilationSeal; }
    String unitId() { return unitId; }
    String sourceDigest() { return sourceDigest; }
    long generation() { return generation; }
    int size() { return schemas.size(); }

    RefinementRules.Descriptor descriptor(String qualifiedName) {
        RefinementRules.Descriptor descriptor = schemas.get(qualifiedName);
        if (descriptor == null)
            throw new IllegalArgumentException("unadmitted Zorde schema: " + qualifiedName);
        return descriptor;
    }

    /**
     * Type-system evidence ONLY, not a runtime Valid<T,S> value.
     * This method cannot accept a fingerprint, foreign descriptor, or a
     * schema from another registry. It validates the exact source scalar.
     */
    Types.Type checkedTypeForAnalysis(String qualifiedName, Object raw) {
        RefinementRules.Descriptor descriptor = descriptor(qualifiedName);
        Types.Primitive scalar = descriptor.base().equals("int")
                ? Types.Primitive.INT : Types.Primitive.STRING;
        if (!descriptor.accepts(raw)) {
            throw new IllegalArgumentException(
                    "scalar failed statically staged Zorde schema " + qualifiedName);
        }
        return Types.checkedScalarTypeFromRegistry(
                scalar, qualifiedName, descriptor, this);
    }
}
