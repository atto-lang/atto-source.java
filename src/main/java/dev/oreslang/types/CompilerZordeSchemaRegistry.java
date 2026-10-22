package dev.oreslang.types;

import dev.oreslang.compiler.ZordeSchemaGenerator;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    // Bound source-controlled import declarations independently of the
    // linker's unit count, so even one source cannot induce unbounded work.
    static final int MAX_NAMED_TYPE_IMPORTS = 128;
    static final int MAX_TYPES_PER_IMPORTED_UNIT = 32;
    private final Object compilationSeal = new Object();
    private final String unitId;
    private final long generation;
    // Reproducibility/debugging only; this digest does NOT grant authority.
    private final String sourceDigest;
    private final Map<String, RefinementRules.Descriptor> schemas;
    // Only parsed and checked named type imports, never caller graph edges.
    private final Map<String, Set<String>> parsedTypeImports;
    private final Set<String> publicSchemaNames;

    private CompilerZordeSchemaRegistry(
            String unitId, long generation, String sourceDigest,
            Map<String, RefinementRules.Descriptor> schemas,
            Map<String, Set<String>> imports,
            Set<String> publiclyVisibleSchemas) {
        this.unitId = unitId;
        this.generation = generation;
        this.sourceDigest = sourceDigest;
        this.schemas = Collections.unmodifiableMap(new LinkedHashMap<>(schemas));
        Map<String, Set<String>> copy = new TreeMap<>();
        imports.forEach((unit, names) ->
                copy.put(unit, Collections.unmodifiableSet(new TreeSet<>(names))));
        this.parsedTypeImports = Collections.unmodifiableMap(copy);
        this.publicSchemaNames = Collections.unmodifiableSet(
                new TreeSet<>(publiclyVisibleSchemas));
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
        return stage(source, compilationUnitId, generation, false);
    }

    /**
     * Import-aware source staging for a separate strict linker entrypoint.
     * Imports are parsed from the original AST, not interpolated by a build
     * driver. Only explicit named `import type` is currently understood.
     *
     * Dependencies must still be verified against pinned external sources
     * and exported schemas before any linked analysis type may be issued.
     */
    static CompilerZordeSchemaRegistry stageSourceWithParsedImportsForAnalysis(
            String source, String compilationUnitId, long generation) {
        return stage(source, compilationUnitId, generation, true);
    }

    private static CompilerZordeSchemaRegistry stage(
            String source, String compilationUnitId,
            long generation, boolean allowTypeImports) {
        Objects.requireNonNull(source, "source");
        if (!CompilerZordeLinkManifest.validUnitId(compilationUnitId)) {
            throw new IllegalArgumentException("invalid Zorde compilation unit identity");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("negative Zorde compilation generation");
        }
        Ast.Program parsed = Parser.parse(source);
        if (!allowTypeImports && !parsed.imports().isEmpty())
            throw new IllegalArgumentException(
                    "unresolved Zorde imports require strict parsed-import linking");

        Map<String, Set<String>> imported = new TreeMap<>();
        Set<String> importedSimpleNames = new HashSet<>();
        if (allowTypeImports) {
            if (parsed.imports().size() > MAX_NAMED_TYPE_IMPORTS)
                throw new IllegalArgumentException(
                        "Zorde source type import budget exceeded");
            for (Ast.ImportDecl item : parsed.imports()) {
                if (item.kind() != Ast.ImportKind.TYPE
                        || item.wildcard() || item.namespace() != null
                        || item.names().size() != 1) {
                    throw new IllegalArgumentException(
                            "only explicit single-name import type is admitted");
                }
                String module = item.path();
                String simple = item.names().getFirst();
                if (!CompilerZordeLinkManifest.validUnitId(module)
                        // The restricted import-type dialect addresses
                        // dotted unit identifiers; no filesystem resolution.
                        || module.indexOf('/') >= 0
                        || !simple.matches("[A-Za-z_][A-Za-z_0-9]*")
                        || !importedSimpleNames.add(simple)
                        || module.equals(compilationUnitId)) {
                    throw new IllegalArgumentException(
                            "invalid, duplicate, ambiguous or self Zorde import type");
                }
                Set<String> unitNames = imported.computeIfAbsent(
                        module, ignored -> new TreeSet<>());
                if (unitNames.size() >= MAX_TYPES_PER_IMPORTED_UNIT)
                    throw new IllegalArgumentException(
                            "Zorde imported type budget exceeded for " + module);
                unitNames.add(simple);
            }
        }
        // Source-to-schema inference remains strictly local. We remove
        // imports only AFTER retaining them for linker symbol verification.
        // This does not make imported types available to the schema builder.
        ZordeSchemaGenerator.Plan plan = ZordeSchemaGenerator.plan(
                new Ast.Program(parsed.namespace(), java.util.List.of(), parsed.modules()));
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
        for (String name : entries.keySet()) {
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (importedSimpleNames.contains(simple))
                throw new IllegalArgumentException(
                        "imported Zorde type shadows a local schema");
        }
        // An inferred descriptor is not automatically an importable export.
        // Only explicit `pub` declarations may cross a source unit.
        Set<String> publicNames = new TreeSet<>();
        for (Ast.ModuleDecl module : parsed.modules()) {
            String prefix = (parsed.namespace() == null ? ""
                    : parsed.namespace() + ".")
                    + (module.name().equals(Parser.ROOT_MODULE) ? ""
                    : module.name() + ".");
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.TypeAliasDecl alias
                        && alias.visibility() == Ast.Visibility.PUBLIC) {
                    String qualifiedName = prefix + alias.name();
                    if (entries.containsKey(qualifiedName))
                        publicNames.add(qualifiedName);
                }
            }
        }
        return new CompilerZordeSchemaRegistry(
                compilationUnitId, generation, sha256(source),
                entries, imported, publicNames);
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
    Map<String, Set<String>> parsedTypeImports() { return parsedTypeImports; }
    Set<String> publicSchemaNames() { return publicSchemaNames; }
    long generation() { return generation; }
    int size() { return schemas.size(); }

    /** Closed-world export view only; the link stage must not re-register it. */
    Map<String, RefinementRules.Descriptor> schemaEntriesForLinker() {
        return schemas;
    }

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
