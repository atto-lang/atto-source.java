package dev.oreslang.types;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.HexFormat;

/**
 * Closed-world, source-exact Zorde linker manifest verifier for compiler
 * analysis. This is NOT a signature verifier or guest proof constructor.
 *
 * A trusted build driver must supply expected source/epoch/dependency pins.
 * Caller-supplied pins alone cannot authenticate source, compiler or policy.
 * Import AST resolution, ownership and runtime proof lowering remain disabled.
 */
final class CompilerZordeLinkManifest {
    static final int FORMAT_VERSION = 1;
    static final int MAX_UNITS = 128;
    private final Object linkSeal = new Object();
    private final String compilerBuild;
    private final String targetAbi;
    private final Map<String, Unit> units;
    private final Map<String, CompilerZordeSchemaRegistry> linkedRegistries;
    private final Set<String> roots;
    private final List<String> dependencyFirstOrder;
    private final String treeDigest;

    /** Build-policy input, never itself an authorization/proof token. */
    record Pin(String sourceDigest, long generation, Map<String, String> dependencies) {
        Pin {
            if (!isDigest(sourceDigest))
                throw new IllegalArgumentException("invalid pinned Zorde source digest");
            if (generation < 0)
                throw new IllegalArgumentException("negative pinned Zorde generation");
            dependencies = Collections.unmodifiableMap(new TreeMap<>(
                    Objects.requireNonNull(dependencies, "pinned dependencies")));
            for (var dep : dependencies.entrySet()) {
                if (!validUnitId(dep.getKey()) || !isDigest(dep.getValue()))
                    throw new IllegalArgumentException("invalid pinned Zorde dependency");
            }
        }
    }

    private record SchemaExport(int version, String fingerprint) {
        private SchemaExport {
            if (version != 1 || !isDigest(fingerprint))
                throw new IllegalArgumentException("unsupported Zorde exported schema version");
        }
    }

    private record Unit(String name, String sourceDigest, long generation,
                        Map<String, SchemaExport> exportedSchemas,
                        Map<String, String> dependencies) {
        private Unit {
            exportedSchemas = Collections.unmodifiableMap(new TreeMap<>(exportedSchemas));
            dependencies = Collections.unmodifiableMap(new TreeMap<>(dependencies));
        }
    }

    private CompilerZordeLinkManifest(
            String compilerBuild, String targetAbi, Map<String, Unit> units,
            Map<String, CompilerZordeSchemaRegistry> registries,
            Set<String> roots, List<String> dependencyFirstOrder) {
        this.compilerBuild = compilerBuild;
        this.targetAbi = targetAbi;
        this.units = Collections.unmodifiableMap(new TreeMap<>(units));
        this.linkedRegistries = Collections.unmodifiableMap(new TreeMap<>(registries));
        this.roots = Collections.unmodifiableSet(new TreeSet<>(roots));
        this.dependencyFirstOrder = List.copyOf(dependencyFirstOrder);
        this.treeDigest = computeDigest();
    }

    /**
     * Verify one entire build graph against externally supplied *expected*
     * immutable pins. The caller must derive these pins from approved build
     * configuration, not from the untrusted candidate being checked.
     *
     * No name/hash equality grants a Valid<T,S> constructor; the resulting
     * object is deliberately not consumed by Oreslang guest-code emission.
     */
    static CompilerZordeLinkManifest verifyForAnalysis(
            String compilerBuild, String targetAbi,
            Map<String, CompilerZordeSchemaRegistry> candidates,
            Map<String, Pin> expectedPins, Set<String> entryRoots) {
        // Legacy build-graph analysis accepts only units with NO source imports.
        // Imported source can never borrow this entrypoint to bypass symbol
        // resolution. Production guest proofs are still disabled everywhere.
        return verifyGraphForAnalysis(compilerBuild, targetAbi,
                candidates, expectedPins, entryRoots, false);
    }

    /**
     * Parse-derived dependency admission. Unlike the graph-only entrypoint,
     * the edges and imported type names here MUST match source import ASTs.
     * Pinned source digests/generations are independently provided build
     * policy inputs; never derive trust pins from the candidate source.
     */
    static CompilerZordeLinkManifest verifyParsedTypeImportsForAnalysis(
            String compilerBuild, String targetAbi,
            Map<String, CompilerZordeSchemaRegistry> candidates,
            Map<String, Pin> expectedPins, Set<String> entryRoots) {
        Objects.requireNonNull(candidates, "Zorde candidate source units");
        Objects.requireNonNull(expectedPins, "Zorde lockfile pins");
        if (!candidates.keySet().equals(expectedPins.keySet()))
            throw new IllegalArgumentException("missing or extra pinned Zorde import unit");
        for (var unit : candidates.entrySet()) {
            CompilerZordeSchemaRegistry registry = Objects.requireNonNull(
                    unit.getValue(), "Zorde source registry");
            Pin policy = Objects.requireNonNull(
                    expectedPins.get(unit.getKey()), "Zorde pinned source");
            Map<String, Set<String>> imported = registry.parsedTypeImports();
            if (!imported.keySet().equals(policy.dependencies().keySet()))
                throw new IllegalArgumentException(
                        "Zorde source import graph differs from independently pinned edges");
            for (var dep : imported.entrySet()) {
                CompilerZordeSchemaRegistry target = candidates.get(dep.getKey());
                if (target == null)
                    throw new IllegalArgumentException("unresolved Zorde parsed import path");
                if (!policy.dependencies().get(dep.getKey())
                        .equals(target.sourceDigest()))
                    throw new IllegalArgumentException("Zorde parsed import hash mismatch");
                for (String importedName : dep.getValue()) {
                    long matches = target.schemaEntriesForLinker().keySet().stream()
                            .filter(name -> name.endsWith("." + importedName))
                            .limit(2).count();
                    if (matches != 1) {
                        throw new IllegalArgumentException(
                                "ambiguous or missing imported Zorde schema "
                                        + importedName);
                    }
                }
            }
        }
        return verifyGraphForAnalysis(compilerBuild, targetAbi,
                candidates, expectedPins, entryRoots, true);
    }

    private static CompilerZordeLinkManifest verifyGraphForAnalysis(
            String compilerBuild, String targetAbi,
            Map<String, CompilerZordeSchemaRegistry> candidates,
            Map<String, Pin> expectedPins, Set<String> entryRoots,
            boolean parsedImportsVerified) {
        if (!validVersion(compilerBuild) || !validVersion(targetAbi))
            throw new IllegalArgumentException("invalid pinned compiler/target ABI");
        Objects.requireNonNull(candidates, "candidate source units");
        Objects.requireNonNull(expectedPins, "expected build pins");
        Objects.requireNonNull(entryRoots, "entry roots");
        if (candidates.isEmpty() || candidates.size() > MAX_UNITS)
            throw new IllegalArgumentException("invalid Zorde linker unit count");
        if (!candidates.keySet().equals(expectedPins.keySet()))
            throw new IllegalArgumentException("missing or extra Zorde build-policy pin");
        if (entryRoots.isEmpty() || !candidates.keySet().containsAll(entryRoots))
            throw new IllegalArgumentException("Zorde link roots are missing");

        Map<String, Unit> built = new TreeMap<>();
        Map<String, String> exportedBy = new HashMap<>();
        for (String name : new TreeSet<>(candidates.keySet())) {
            if (!validUnitId(name))
                throw new IllegalArgumentException("invalid Zorde unit key");
            CompilerZordeSchemaRegistry registry = Objects.requireNonNull(
                    candidates.get(name), "Zorde compilation unit");
            Pin pin = Objects.requireNonNull(expectedPins.get(name), "Zorde policy pin");
            if (!parsedImportsVerified && !registry.parsedTypeImports().isEmpty())
                throw new IllegalArgumentException(
                        "Zorde parsed imports require strict import-link verifier");
            if (!name.equals(registry.unitId()))
                throw new IllegalArgumentException("candidate Zorde unit identity mismatch");
            if (!pin.sourceDigest().equals(registry.sourceDigest())
                    || pin.generation() != registry.generation())
                throw new IllegalArgumentException("Zorde pinned source or generation mismatch");

            Map<String, SchemaExport> exports = new TreeMap<>();
            for (var schema : registry.schemaEntriesForLinker().entrySet()) {
                if (exportedBy.putIfAbsent(schema.getKey(), name) != null) {
                    throw new IllegalArgumentException(
                            "ambiguous Zorde exported schema " + schema.getKey());
                }
                exports.put(schema.getKey(), new SchemaExport(
                        schema.getValue().version(), schema.getValue().fingerprint()));
            }
            if (exports.isEmpty())
                throw new IllegalArgumentException("Zorde unit exports no schema");
            built.put(name, new Unit(name, registry.sourceDigest(),
                    registry.generation(), exports, pin.dependencies()));
        }

        for (var unit : built.values()) {
            for (var dependency : unit.dependencies().entrySet()) {
                String depName = dependency.getKey();
                if (unit.name().equals(depName))
                    throw new IllegalArgumentException("Zorde unit cannot depend on itself");
                Unit target = built.get(depName);
                if (target == null || !target.sourceDigest().equals(dependency.getValue()))
                    throw new IllegalArgumentException(
                            "unresolved or mismatched Zorde dependency pin " + depName);
            }
        }

        List<String> order = new ArrayList<>();
        Map<String, Integer> visited = new HashMap<>();
        for (String root : new TreeSet<>(entryRoots)) {
            visit(root, built, visited, order);
        }
        if (order.size() != built.size())
            throw new IllegalArgumentException("unreachable source unit in exact-tree Zorde link");
        return new CompilerZordeLinkManifest(
                compilerBuild, targetAbi, built, candidates, entryRoots, order);
    }

    private static void visit(
            String name, Map<String, Unit> units,
            Map<String, Integer> visited, List<String> ordered) {
        Integer state = visited.get(name);
        if (Integer.valueOf(1).equals(state))
            throw new IllegalArgumentException("cyclic Zorde build dependency");
        if (Integer.valueOf(2).equals(state)) return;
        if (ordered.size() > MAX_UNITS || visited.size() > MAX_UNITS)
            throw new IllegalArgumentException("Zorde build graph budget exceeded");
        visited.put(name, 1);
        for (String dependency : units.get(name).dependencies().keySet())
            visit(dependency, units, visited, ordered);
        visited.put(name, 2);
        ordered.add(name);
    }

    private static boolean isDigest(String digest) {
        return digest != null && digest.matches("[0-9a-f]{64}");
    }

    private static boolean validUnitId(String id) {
        return id != null && id.length() <= 256
                && id.matches("[A-Za-z_][A-Za-z_0-9./-]*")
                && !id.contains("..") && !id.contains("//") && !id.endsWith("/");
    }

    private static boolean validVersion(String value) {
        return value != null && value.length() <= 128
                && value.matches("[A-Za-z0-9_][A-Za-z0-9_.+-]*");
    }

    /**
     * After a successful whole-build verification, derive a checked analysis
     * type only from the *same staged registry instance* admitted to this
     * build graph. This does not produce a runtime value or a guest proof.
     */
    Types.Type checkedTypeForLinkedAnalysis(
            String unitId, String qualifiedSchema, Object candidate) {
        CompilerZordeSchemaRegistry registry = linkedRegistries.get(unitId);
        Unit unit = units.get(unitId);
        if (registry == null || unit == null
                || !unit.exportedSchemas().containsKey(qualifiedSchema))
            throw new IllegalArgumentException("unlinked Zorde schema or source unit");
        // Re-check exact registry/manifest bindings before issuing any type
        // marker. Link digest equality is not sufficient as admission.
        if (!registry.sourceDigest().equals(unit.sourceDigest())
                || registry.generation() != unit.generation()
                || registry.descriptor(qualifiedSchema).version()
                        != unit.exportedSchemas().get(qualifiedSchema).version()
                || !registry.descriptor(qualifiedSchema).fingerprint().equals(
                        unit.exportedSchemas().get(qualifiedSchema).fingerprint()))
            throw new IllegalArgumentException("Zorde linker registry identity changed");
        return Types.scopeCheckedTypeToVerifiedLink(
                registry.checkedTypeForAnalysis(qualifiedSchema, candidate), this);
    }

    int schemaVersion(String unitId, String qualifiedName) {
        Unit unit = units.get(unitId);
        SchemaExport export = unit == null ? null : unit.exportedSchemas().get(qualifiedName);
        if (export == null)
            throw new IllegalArgumentException("unknown exact-tree Zorde schema");
        return export.version();
    }

    String digest() { return treeDigest; }
    String compilerBuild() { return compilerBuild; }
    String targetAbi() { return targetAbi; }
    int unitCount() { return units.size(); }
    Set<String> roots() { return roots; }
    List<String> dependencyFirstOrder() { return dependencyFirstOrder; }

    // Package-only private authority. Never serialized in the deterministic
    // manifest digest or exposed to Oreslang runtime/userland.
    Object linkSeal() { return linkSeal; }

    boolean sameLinkInstance(CompilerZordeLinkManifest other) {
        return other != null && linkSeal == other.linkSeal;
    }

    /** Integrity equality only. An externally copied digest proves no authority. */
    void requireMatchingBuild(String expectedCompiler, String expectedAbi,
                              String expectedTreeDigest) {
        if (!compilerBuild.equals(expectedCompiler) || !targetAbi.equals(expectedAbi)
                || !treeDigest.equals(expectedTreeDigest))
            throw new IllegalArgumentException("Zorde linked manifest does not match pinned build");
    }

    private String computeDigest() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            putText(md, "oreslang.zorde.link-manifest.v1");
            putInt(md, FORMAT_VERSION);
            putText(md, compilerBuild);
            putText(md, targetAbi);
            putInt(md, roots.size());
            for (String root : roots) putText(md, root);
            putInt(md, units.size());
            for (Unit unit : units.values()) {
                putText(md, unit.name());
                putText(md, unit.sourceDigest());
                putLong(md, unit.generation());
                putInt(md, unit.exportedSchemas().size());
                for (var schema : unit.exportedSchemas().entrySet()) {
                    putText(md, schema.getKey());
                    putInt(md, schema.getValue().version());
                    putText(md, schema.getValue().fingerprint());
                }
                putInt(md, unit.dependencies().size());
                for (var dependency : unit.dependencies().entrySet()) {
                    putText(md, dependency.getKey());
                    putText(md, dependency.getValue());
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 required for Zorde linking", unavailable);
        }
    }

    // Binary length-prefixing prevents ambiguous concatenation of graph edges
    // or module/schema identifiers; lexicographic order makes it reproducible.
    private static void putText(MessageDigest md, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        putInt(md, bytes.length);
        md.update(bytes);
    }

    private static void putInt(MessageDigest md, int number) {
        for (int shift = 24; shift >= 0; shift -= 8)
            md.update((byte) (number >>> shift));
    }

    private static void putLong(MessageDigest md, long number) {
        for (int shift = 56; shift >= 0; shift -= 8)
            md.update((byte) (number >>> shift));
    }
}
