package dev.oreslang.compiler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic dependency-first SCC fingerprints for trusted public interfaces.
 *
 * A cycle is hashed as a *group*, never by inserting unfinished member hashes
 * into one another. Any exported change in a cycle invalidates the whole cycle
 * and its downstream importers. The checker/linker must separately establish
 * import legality and visibility, and must supply trusted interface files.
 *
 * This component is an incremental *plan*, not a compiler, linker, or ABI
 * authorization mechanism. It executes no Oreslang code.
 */
public final class InterfaceSccPlanner {
    private static final int MAX_UNITS = 1024;
    private static final int MAX_EDGES = 16384;
    private static final int MAX_LABEL_BYTES = 65536;
    private static final byte[] MAGIC = {'O','S','C','C',1};
    private InterfaceSccPlanner() { }

    public record Group(List<String> units, String fingerprint) {
        public Group {
            units = List.copyOf(units);
            if (units.isEmpty() || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid interface SCC group");
        }
    }

    public record Plan(List<Group> groups, Map<String, String> unitFingerprint,
                       Map<String, String> unitToGroupFingerprint) {
        public Plan {
            groups = List.copyOf(groups);
            unitFingerprint = Map.copyOf(unitFingerprint);
            unitToGroupFingerprint = Map.copyOf(unitToGroupFingerprint);
        }
    }

    /**
     * Integrates the plan with Oreslang's real import resolution and visibility
     * checks, including user-supplied effective import target overrides.
     *
     * The caller must provide compiler-admitted (type/ownership-checked)
     * programs and true exported interfaces; this method does not perform
     * type checking or authenticate imported package fingerprints.
     *
     * External dependencies are keyed by the import's exact source path,
     * not guessed from a module name. Missing external pins are rejected.
     */
    public static Plan fromValidatedImportGraph(
            Map<String, dev.oreslang.ast.Ast.Program> admittedPrograms,
            Map<String, ExportInterfaceCodec.InterfaceFile> publicExports,
            Map<String, Map<String, String>> resolvedOverrides,
            Map<String, Map<String, String>> externalImportPins) {
        Objects.requireNonNull(admittedPrograms, "admittedPrograms");
        Objects.requireNonNull(resolvedOverrides, "resolvedOverrides");
        Objects.requireNonNull(externalImportPins, "externalImportPins");
        if (!admittedPrograms.keySet().equals(externalImportPins.keySet())) {
            throw new IllegalArgumentException("external pins must be specified per source unit");
        }
        ImportGraph.validateLinkedImports(admittedPrograms, resolvedOverrides);
        Map<String, Set<String>> edges = ImportGraph.resolveDependencies(
                admittedPrograms, admittedPrograms.keySet(), resolvedOverrides);
        Map<String, Map<String, String>> completeBindings = new LinkedHashMap<>();
        for (Map.Entry<String, dev.oreslang.ast.Ast.Program> entry : admittedPrograms.entrySet()) {
            String unit = entry.getKey();
            Map<String, String> pins = externalImportPins.get(unit);
            if (pins == null) throw new IllegalArgumentException("null external pins");
            Set<String> required = new HashSet<>();
            Map<String, String> fingerprints = new LinkedHashMap<>(pins);
            for (dev.oreslang.ast.Ast.ImportDecl use : entry.getValue().imports()) {
                String target = ImportGraph.resolveImportUnitId(unit, use,
                        admittedPrograms.keySet(), resolvedOverrides);
                if (target == null) {
                    required.add(use.path());
                } else {
                    // A dependency *set* loses import-path retargeting when the
                    // same targets remain present. Commit each actual import
                    // selector -> resolved provider binding to the group hash.
                    // This reserved virtual dependency is NOT a provider and
                    // never participates in link-time import admission.
                    String selector = importSelectorDigest(use);
                    String virtualId = "@ores-resolved-import/" + selector;
                    String digest = hashImportTarget(target);
                    if (fingerprints.putIfAbsent(virtualId, digest) != null) {
                        throw new IllegalArgumentException(
                                "duplicate or conflicting resolved import selector");
                    }
                }
            }
            if (!required.equals(pins.keySet())) {
                throw new IllegalArgumentException(
                        "external import fingerprints must match every unresolved import path");
            }
            completeBindings.put(unit, Map.copyOf(fingerprints));
        }
        return compute(edges, publicExports, completeBindings);
    }

    /**
     * @param localEdges unit -> in-repository, resolved provider IDs
     * @param publicExports exactly one trusted, checked canonical v1 envelope per unit
     * @param externalDependencies unit -> external provider ID -> verified ABI sha256
     */
    public static Plan compute(
            Map<String, Set<String>> localEdges,
            Map<String, ExportInterfaceCodec.InterfaceFile> publicExports,
            Map<String, Map<String, String>> externalDependencies) {
        Objects.requireNonNull(localEdges, "localEdges");
        Objects.requireNonNull(publicExports, "publicExports");
        Objects.requireNonNull(externalDependencies, "externalDependencies");
        if (localEdges.size() > MAX_UNITS) throw new IllegalArgumentException("too many source units");
        if (!localEdges.keySet().equals(publicExports.keySet())
                || !localEdges.keySet().equals(externalDependencies.keySet())) {
            throw new IllegalArgumentException("all units must have edges, exports and external dependency maps");
        }
        int edgeCount = 0;
        for (String unit : localEdges.keySet()) {
            validateUnit(unit);
            if (publicExports.get(unit) == null || localEdges.get(unit) == null
                    || externalDependencies.get(unit) == null) {
                throw new IllegalArgumentException("null interface/edge set/dependency map");
            }
            for (String dependency : localEdges.get(unit)) {
                validateUnit(dependency);
                if (!localEdges.containsKey(dependency)) {
                    throw new IllegalArgumentException("unresolved local dependency: " + dependency);
                }
                if (unit.equals(dependency)) {
                    throw new IllegalArgumentException("self-import must not be represented as a valid edge");
                }
                if (++edgeCount > MAX_EDGES) throw new IllegalArgumentException("too many dependency edges");
            }
            for (Map.Entry<String, String> external : externalDependencies.get(unit).entrySet()) {
                validateUnit(external.getKey());
                String digest = external.getValue();
                if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("unverified external interface fingerprint");
                }
                if (localEdges.containsKey(external.getKey()) || external.getKey().equals(unit)) {
                    throw new IllegalArgumentException("external dependency conflicts with local unit");
                }
                if (++edgeCount > MAX_EDGES) throw new IllegalArgumentException("too many dependency edges");
            }
        }

        // ImportGraph already has deterministic dependency-first Tarjan SCCs.
        // Reuse its implementation, so run time initialization and interface
        // planning agree on the same cycle boundaries and ordering.
        List<List<String>> components = ImportGraph.initializationGroups(localEdges);
        Map<String, String> groupOf = new HashMap<>();
        Map<String, String> memberHashes = new LinkedHashMap<>();
        List<Group> output = new ArrayList<>(components.size());
        for (List<String> component : components) {
            List<String> ordered = new ArrayList<>(component);
            ordered.sort(InterfaceSccPlanner::compareUtf8);
            Set<String> inside = Set.copyOf(ordered);
            String groupDigest;
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream data = new DataOutputStream(buffer);
                data.write(MAGIC);
                data.writeInt(ordered.size());
                for (String member : ordered) {
                    put(data, member);
                    // Each member's local public ABI is hashed only once,
                    // independent of cyclic imports and source bodies.
                    put(data, publicExports.get(member).sha256());
                    List<String> dependencies = new ArrayList<>(localEdges.get(member));
                    dependencies.sort(InterfaceSccPlanner::compareUtf8);
                    data.writeInt(dependencies.size());
                    for (String dependency : dependencies) {
                        put(data, dependency); // internal edge identity matters
                        if (!inside.contains(dependency)) {
                            String finished = groupOf.get(dependency);
                            if (finished == null) {
                                throw new IllegalStateException(
                                        "component order is not dependency-first");
                            }
                            put(data, finished); // downstream invalidation
                        }
                    }
                    Map<String, String> externals = externalDependencies.get(member);
                    List<String> providerIds = new ArrayList<>(externals.keySet());
                    providerIds.sort(InterfaceSccPlanner::compareUtf8);
                    data.writeInt(providerIds.size());
                    for (String providerId : providerIds) {
                        put(data, providerId);
                        put(data, externals.get(providerId));
                    }
                }
                data.flush();
                groupDigest = sha256(buffer.toByteArray());
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            output.add(new Group(ordered, groupDigest));
            for (String member : ordered) {
                if (groupOf.put(member, groupDigest) != null) {
                    throw new IllegalStateException("source unit assigned to multiple components");
                }
                memberHashes.put(member, sha256((member + "\u0000" + groupDigest)
                        .getBytes(StandardCharsets.UTF_8)));
            }
        }
        if (groupOf.size() != localEdges.size())
            throw new IllegalStateException("incomplete SCC decomposition");
        return new Plan(output, memberHashes, groupOf);
    }

    private static String importSelectorDigest(dev.oreslang.ast.Ast.ImportDecl use) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            put(data, use.kind().name());
            data.writeBoolean(use.wildcard());
            put(data, use.namespace() == null ? "" : use.namespace());
            put(data, use.path());
            List<String> names = new ArrayList<>(use.names());
            names.sort(InterfaceSccPlanner::compareUtf8);
            data.writeInt(names.size());
            for (String name : names) put(data, name);
            data.flush();
            return sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hashImportTarget(String provider) {
        // Domain-separate a target identity from actual provider ABI hashes.
        byte[] identity = utf8("ores-internal-target-v1:" + provider);
        return sha256(identity);
    }

    private static void validateUnit(String value) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("invalid source/provider unit ID");
        }
        byte[] text = utf8(value);
        if (text.length > MAX_LABEL_BYTES) throw new IllegalArgumentException("unit ID too long");
    }

    private static void put(DataOutputStream data, String value) throws IOException {
        byte[] utf8 = utf8(value);
        if (utf8.length > MAX_LABEL_BYTES) throw new IllegalArgumentException("SCC field too long");
        data.writeInt(utf8.length);
        data.write(utf8);
    }

    private static byte[] utf8(String value) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            return data;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("malformed interface label", e);
        }
    }

    private static int compareUtf8(String a, String b) {
        return Arrays.compareUnsigned(utf8(a), utf8(b));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
