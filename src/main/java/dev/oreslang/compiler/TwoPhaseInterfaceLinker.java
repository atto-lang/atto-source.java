package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Two-phase public-interface graph admission, without guest execution.
 *
 * Phase 1: extract every unit's exported declaration signatures without
 * recursively chasing its imports (a cycle cannot have finished peer hashes).
 * Phase 2: validate the entire linked import graph against those exports and
 * compute dependency-first, SCC-atomic fingerprints.
 *
 * Inputs must already be fully checked by a trusted multi-unit type/ownership
 * driver. This is NOT yet that driver, an executable linker, or a cache grant.
 * In particular, symbolic aliases, re-exports, imported type ownership and
 * native private layouts need a complete cross-file semantic proof.
 */
public final class TwoPhaseInterfaceLinker {
    private static final int MAX_UNITS = 1024;
    private static final int MAX_IMPORTS = 4096;

    private TwoPhaseInterfaceLinker() { }

    /** Only the declaration collection method can mint this staging token. */
    public static final class Declarations {
        private final Map<String, Ast.Program> programs;
        private final Map<String, ExportInterfaceCodec.InterfaceFile> interfaces;

        private Declarations(Map<String, Ast.Program> programs,
                             Map<String, ExportInterfaceCodec.InterfaceFile> interfaces) {
            this.programs = Map.copyOf(programs);
            this.interfaces = Map.copyOf(interfaces);
        }

        public Map<String, ExportInterfaceCodec.InterfaceFile> interfaces() {
            return interfaces;
        }
        public Set<String> unitIds() { return interfaces.keySet(); }
    }

    public record Linked(Declarations declarations, InterfaceSccPlanner.Plan plan) {
        public Linked {
            Objects.requireNonNull(declarations);
            Objects.requireNonNull(plan);
        }
    }

    /**
     * Interface-only declaration collection. No dependency recursion, no
     * imported nominal guessing and no guest code execution.
     */
    public static Declarations collectCheckedDeclarations(
            Map<String, Ast.Program> checkedPrograms,
            Map<String, ExportInterfaceCodec.Header> trustedEnvironments) {
        Objects.requireNonNull(checkedPrograms);
        Objects.requireNonNull(trustedEnvironments);
        if (!checkedPrograms.keySet().equals(trustedEnvironments.keySet())
                || checkedPrograms.size() > MAX_UNITS) {
            throw new IllegalArgumentException("invalid source or compiler environment set");
        }
        Map<String, Ast.Program> copy = new LinkedHashMap<>();
        Map<String, ExportInterfaceCodec.InterfaceFile> exports = new LinkedHashMap<>();
        int totalImports = 0;
        for (Map.Entry<String, Ast.Program> unit : checkedPrograms.entrySet()) {
            String unitId = Objects.requireNonNull(unit.getKey());
            if (!unitId.equals(ImportGraph.normalizeUnitId(unitId))) {
                throw new IllegalArgumentException("noncanonical unit ID");
            }
            Ast.Program checked = Objects.requireNonNull(unit.getValue());
            totalImports += checked.imports().size();
            if (totalImports > MAX_IMPORTS) {
                throw new IllegalArgumentException("import count exceeds pre-link budget");
            }
            ExportInterfaceCodec.Header environment =
                    Objects.requireNonNull(trustedEnvironments.get(unitId));
            // The v1 collector correctly refuses unresolved imports. During
            // phase 1, only strip import declarations from this *temporary*
            // view; phase 2 must account for every original import.
            Ast.Program localView = new Ast.Program(
                    checked.namespace(), List.of(), checked.modules());
            ExportInterfaceCodec.InterfaceFile exported =
                    ExportInterfaceCollector.collect(localView, environment);
            copy.put(unitId, checked);
            exports.put(unitId, exported);
        }
        return new Declarations(copy, exports);
    }

    /**
     * Resolve all imports *after* all public declarations are available.
     * The public interface schema cannot yet prove wildcard and advanced
     * actor/trait/contract imports, so these deliberately fail closed.
     */
    public static Linked resolveLinkedImports(
            Declarations declarations,
            Map<String, Map<String, String>> resolvedOverrides,
            Map<String, Map<String, String>> trustedExternalPins) {
        Objects.requireNonNull(declarations);
        Objects.requireNonNull(resolvedOverrides);
        Objects.requireNonNull(trustedExternalPins);
        Set<String> available = declarations.programs.keySet();
        for (Map.Entry<String, Ast.Program> entry : declarations.programs.entrySet()) {
            String importer = entry.getKey();
            for (Ast.ImportDecl use : entry.getValue().imports()) {
                if (use.wildcard()) {
                    throw new IllegalArgumentException(
                            "wildcard imports need a verified resolved import-closure descriptor");
                }
                ExportInterfaceCodec.Kind kind = switch (use.kind()) {
                    case FUNCTION -> ExportInterfaceCodec.Kind.FUNCTION;
                    case CLASS -> ExportInterfaceCodec.Kind.CLASS;
                    case INTERFACE -> ExportInterfaceCodec.Kind.INTERFACE;
                    case TYPE -> ExportInterfaceCodec.Kind.TYPE;
                    case MODULE -> ExportInterfaceCodec.Kind.MODULE;
                    default -> throw new IllegalArgumentException(
                            "advanced import kind requires richer typed-interface contract");
                };
                String target = ImportGraph.resolveImportUnitId(importer, use,
                        available, resolvedOverrides);
                if (target == null) {
                    // External package proof is not yet enough to verify
                    // imported symbol membership or exported visibility.
                    throw new IllegalArgumentException(
                            "unlinked external provider requires signed typed export proof: "
                                    + use.path());
                }
                ExportInterfaceCodec.InterfaceFile provider = declarations.interfaces.get(target);
                if (provider == null) {
                    throw new IllegalArgumentException("missing staged provider declarations");
                }
                for (String symbol : use.names()) {
                    int matches = 0;
                    for (ExportInterfaceCodec.Entry declaration : provider.entries()) {
                        if (declaration.kind() == kind && declaration.name().equals(symbol)) {
                            matches++;
                        }
                    }
                    if (matches != 1) {
                        throw new IllegalArgumentException(
                                "import must have exactly one visible exported symbol: "
                                        + importer + " -> " + target + " : " + symbol);
                    }
                }
            }
        }
        Map<String, Map<String, String>> noExternal = new HashMap<>();
        for (String unit : available) {
            Map<String, String> pins = trustedExternalPins.get(unit);
            if (pins == null || !pins.isEmpty()) {
                throw new IllegalArgumentException(
                        "external provider pin cannot authorize source-only linking");
            }
            noExternal.put(unit, Map.of());
        }
        return new Linked(declarations,
                InterfaceSccPlanner.fromValidatedImportGraph(
                        declarations.programs, declarations.interfaces,
                        resolvedOverrides, noExternal));
    }
}
