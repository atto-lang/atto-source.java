package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One immutable input snapshot for a restricted SCC semantic verification.
 *
 * Snapshotting *before* declaration staging keeps the import graph, checker,
 * effective target overrides and final ABI fingerprints aligned. This class
 * is NOT semantic admission and never mints a VerifiedScc certificate.
 *
 * The Ast.Program tree is composed of immutable AST records; this snapshots
 * their enclosing identity map plus nested, caller-owned override maps.
 */
public final class SccVerificationInputs {
    private static final int MAX_UNITS = 1024;
    private static final int MAX_IMPORTS = 4096;

    private final Map<String, Ast.Program> sources;
    private final Map<String, ExportInterfaceCodec.Header> environments;
    private final Map<String, Map<String, String>> overrides;

    private SccVerificationInputs(Map<String, Ast.Program> sources,
            Map<String, ExportInterfaceCodec.Header> environments,
            Map<String, Map<String, String>> overrides) {
        this.sources = Map.copyOf(sources);
        this.environments = Map.copyOf(environments);
        this.overrides = Map.copyOf(overrides);
    }

    public static SccVerificationInputs snapshot(
            Map<String, Ast.Program> sources,
            Map<String, ExportInterfaceCodec.Header> environments,
            Map<String, Map<String, String>> overrides) {
        Objects.requireNonNull(sources, "source programs");
        Objects.requireNonNull(environments, "compiler environments");
        Objects.requireNonNull(overrides, "resolved import overrides");
        if (sources.isEmpty() || sources.size() > MAX_UNITS) {
            throw new IllegalArgumentException(
                    "verified SCC requires 1..1024 actual source units");
        }
        if (!sources.keySet().equals(environments.keySet())
                || !sources.keySet().containsAll(overrides.keySet())) {
            throw new IllegalArgumentException(
                    "sources, compiler environments or override units disagree");
        }
        Map<String, Ast.Program> programSnapshot = new LinkedHashMap<>();
        Map<String, ExportInterfaceCodec.Header> environmentSnapshot =
                new LinkedHashMap<>();
        Map<String, Map<String, String>> overrideSnapshot = new LinkedHashMap<>();
        long totalImports = 0;
        for (Map.Entry<String, Ast.Program> entry : sources.entrySet()) {
            String id = entry.getKey();
            if (id == null || id.isBlank() || id.length() > 65536
                    || !id.equals(ImportGraph.normalizeUnitId(id))) {
                throw new IllegalArgumentException(
                        "invalid or noncanonical source-unit identity");
            }
            Ast.Program program = Objects.requireNonNull(entry.getValue(),
                    "source program");
            ExportInterfaceCodec.Header header =
                    Objects.requireNonNull(environments.get(id),
                            "missing compilation environment");
            totalImports += program.imports().size();
            if (totalImports > MAX_IMPORTS) {
                throw new IllegalArgumentException("verification import budget exceeded");
            }
            Map<String, String> original =
                    Objects.requireNonNull(overrides.getOrDefault(id, Map.of()),
                            "source import overrides");
            Map<String, String> localOverrides = new LinkedHashMap<>();
            Set<String> selectors = new java.util.HashSet<>();
            for (Ast.ImportDecl use : program.imports()) {
                if (use == null) throw new IllegalArgumentException("null import declaration");
                selectors.add(use.path());
            }
            if (!selectors.containsAll(original.keySet())) {
                throw new IllegalArgumentException(
                        "import override for a missing source selector");
            }
            for (Map.Entry<String, String> override : original.entrySet()) {
                String selector = override.getKey();
                String provider = override.getValue();
                if (selector == null || provider == null || provider.isBlank()
                        || !provider.equals(ImportGraph.normalizeUnitId(provider))) {
                    throw new IllegalArgumentException(
                            "invalid, noncanonical import resolution override");
                }
                if (!sources.containsKey(provider)) {
                    throw new IllegalArgumentException(
                            "import resolution override targets a missing source unit");
                }
                localOverrides.put(selector, provider);
            }
            programSnapshot.put(id, program);
            environmentSnapshot.put(id, header);
            overrideSnapshot.put(id, Map.copyOf(localOverrides));
        }
        return new SccVerificationInputs(
                programSnapshot, environmentSnapshot, overrideSnapshot);
    }

    public Map<String, Ast.Program> sources() { return sources; }
    public Map<String, ExportInterfaceCodec.Header> environments() {
        return environments;
    }
    public Map<String, Map<String, String>> overrides() { return overrides; }
}
