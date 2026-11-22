package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.types.TypeChecker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * First verifier-issued, all-or-nothing SCC admission for the restricted
 * primitive, effect-free, explicitly named local-function import subset.
 *
 * This runs the REAL TypeChecker and OwnershipChecker for every unit, using
 * peer declarations as exact import signatures. It does not perform guest
 * initialization, runtime linking, import resolution of nominal types,
 * external package authorization or any native code-cache approval.
 *
 * Invariants:
 *   - Phase 1 collects declarations across the complete graph.
 *   - Phase 2 validates the import graph and resolves each named function to
 *     exactly one public provider declaration.
 *   - Every unit is type- and ownership-checked against linked signatures.
 *   - Only after ALL members pass may any VerifiedScc token be minted.
 */
public final class VerifiedSccAdmission {
    private VerifiedSccAdmission() { }

    public static final class VerifiedScc {
        private final List<String> units;
        private final Map<String, Ast.Program> checkedPrograms;
        private final String interfaceFingerprint;
        private VerifiedScc(List<String> units, Map<String, Ast.Program> checkedPrograms,
                            String interfaceFingerprint) {
            this.units = List.copyOf(units);
            this.checkedPrograms = Map.copyOf(checkedPrograms);
            this.interfaceFingerprint = interfaceFingerprint;
        }
        public List<String> units() { return units; }
        public Map<String, Ast.Program> checkedPrograms() { return checkedPrograms; }
        public String interfaceFingerprint() { return interfaceFingerprint; }
    }

    public static final class VerifiedGraph {
        private final List<VerifiedScc> components;
        private final InterfaceSccPlanner.Plan interfacePlan;
        private VerifiedGraph(List<VerifiedScc> components,
                              InterfaceSccPlanner.Plan interfacePlan) {
            this.components = List.copyOf(components);
            this.interfacePlan = interfacePlan;
        }
        public List<VerifiedScc> components() { return components; }
        public InterfaceSccPlanner.Plan interfacePlan() { return interfacePlan; }
    }

    /**
     * No certificates are returned or exposed until every unit has passed
     * cross-file semantic and ownership checking. Unsupported syntax/ABI
     * forms fail closed; callers cannot substitute arbitrary hash strings as
     * linker or ownership proof.
     */
    public static VerifiedGraph verifyGraph(
            Map<String, Ast.Program> sourcePrograms,
            Map<String, ExportInterfaceCodec.Header> compilerEnvironments,
            Map<String, Map<String, String>> resolvedOverrides) {
        // Establish one consistent source+provider identity snapshot before
        // interface collection, semantic checking, and final hash generation.
        // Caller-owned maps must not be read again after this point.
        SccVerificationInputs snapshot = SccVerificationInputs.snapshot(
                sourcePrograms, compilerEnvironments, resolvedOverrides);
        sourcePrograms = snapshot.sources();
        compilerEnvironments = snapshot.environments();
        resolvedOverrides = snapshot.overrides();

        // v0 may certify only public signatures whose complete semantic
        // surface is represented by the primitive-only linked contract.
        // Otherwise a nominal/layout/alias/module contract could escape
        // without a canonical type or provider identity.
        for (Ast.Program source : sourcePrograms.values()) {
            SccPublicSurface.requirePrimitiveOnly(source);
        }

        TwoPhaseInterfaceLinker.Declarations staged =
                TwoPhaseInterfaceLinker.collectCheckedDeclarations(
                        sourcePrograms, compilerEnvironments);
        Map<String, Map<String, String>> noExternal = new LinkedHashMap<>();
        for (String unit : staged.unitIds()) noExternal.put(unit, Map.of());
        // Includes all import reachability and visibility checks. Cannot
        // create a valid token merely by passing a staged declaration map.
        TwoPhaseInterfaceLinker.resolveLinkedImports(
                staged, resolvedOverrides, noExternal);

        Map<String, Ast.Program> passed = new LinkedHashMap<>();
        Map<String, Map<String, Ast.FunctionDecl>> expectedImports = new LinkedHashMap<>();
        Set<String> available = sourcePrograms.keySet();
        for (Map.Entry<String, Ast.Program> importer : sourcePrograms.entrySet()) {
            String sourceId = importer.getKey();
            Map<String, Ast.FunctionDecl> signatures = new LinkedHashMap<>();
            for (Ast.ImportDecl imported : importer.getValue().imports()) {
                // Staged linker rejects other kinds, wildcards and external
                // providers. Keep an independent guard in the verifier.
                if (imported.wildcard() || imported.kind() != Ast.ImportKind.FUNCTION) {
                    throw new IllegalArgumentException("unsupported SCC semantic import");
                }
                String providerId = ImportGraph.resolveImportUnitId(
                        sourceId, imported, available, resolvedOverrides);
                if (providerId == null) {
                    throw new IllegalArgumentException("untrusted external dependency in SCC");
                }
                Ast.Program provider = Objects.requireNonNull(sourcePrograms.get(providerId));
                for (String sourceName : imported.names()) {
                    String local = ImportRules.localName(imported, sourceName);
                    Ast.FunctionDecl declaration = uniquelyExportedFunction(
                            provider, sourceName);
                    if (signatures.putIfAbsent(local, declaration) != null) {
                        throw new IllegalArgumentException(
                                "duplicate resolved imported callable: " + local);
                    }
                }
            }
            // This pass checks the complete original importer AST (without
            // stripping its imports), then checks ownership. Unlike the
            // legacy checker, linked values are NOT typed as Unknown.
            Ast.Program checked = TypeChecker.checkTypesWithLinkedFunctions(
                    importer.getValue(), signatures);
            passed.put(sourceId, checked);
            expectedImports.put(sourceId, Map.copyOf(signatures));
        }

        // Re-read provider signatures from the CHECKED programs. Otherwise
        // a checker annotation expansion could change a provider's public
        // signature after another module was type-checked against the old one.
        for (Map.Entry<String, Ast.Program> importer : passed.entrySet()) {
            String sourceId = importer.getKey();
            Map<String, Ast.FunctionDecl> checkedAgainst = expectedImports.get(sourceId);
            for (Ast.ImportDecl use : importer.getValue().imports()) {
                String providerId = ImportGraph.resolveImportUnitId(
                        sourceId, use, available, resolvedOverrides);
                if (providerId == null) {
                    throw new IllegalArgumentException("untrusted provider after semantic checking");
                }
                Ast.Program provider = Objects.requireNonNull(passed.get(providerId));
                for (String name : use.names()) {
                    String local = ImportRules.localName(use, name);
                    Ast.FunctionDecl formerlyResolved = checkedAgainst.get(local);
                    Ast.FunctionDecl verifiedProvider = uniquelyExportedFunction(provider, name);
                    // Recheck the exact provider ABI and its admitted effect
                    // subset after annotation expansion. Body differences
                    // are irrelevant here: this is an interface-only proof.
                    LinkedFunctionContract.requireStableSignature(
                            formerlyResolved, verifiedProvider);
                }
            }
        }

        // The post-expansion public ABI must independently remain within
        // the same certified subset as the pre-expansion declarations.
        for (Ast.Program checkedSource : passed.values()) {
            SccPublicSurface.requirePrimitiveOnly(checkedSource);
        }

        // Annotation expansion and checked signatures may change the public
        // ABI relative to phase-1 previews. Re-collect every checked program,
        // re-resolve all imports, and commit the authoritative final plan only
        // after the entire graph passes.
        TwoPhaseInterfaceLinker.Declarations checkedDeclarations =
                TwoPhaseInterfaceLinker.collectCheckedDeclarations(
                        passed, compilerEnvironments);
        InterfaceSccPlanner.Plan finalPlan =
                TwoPhaseInterfaceLinker.resolveLinkedImports(
                        checkedDeclarations, resolvedOverrides, noExternal).plan();

        List<VerifiedScc> admitted = new ArrayList<>();
        for (InterfaceSccPlanner.Group group : finalPlan.groups()) {
            Map<String, Ast.Program> members = new LinkedHashMap<>();
            for (String unit : group.units()) members.put(unit, passed.get(unit));
            admitted.add(new VerifiedScc(
                    group.units(), members, group.fingerprint()));
        }
        return new VerifiedGraph(admitted, finalPlan);
    }

    /** Reject disjoint DAGs when the caller requests exactly one verified SCC. */
    public static VerifiedScc verifySingleScc(
            Map<String, Ast.Program> sourcePrograms,
            Map<String, ExportInterfaceCodec.Header> compilerEnvironments,
            Map<String, Map<String, String>> resolvedOverrides) {
        VerifiedGraph graph = verifyGraph(sourcePrograms, compilerEnvironments, resolvedOverrides);
        if (graph.components().size() != 1) {
            throw new IllegalArgumentException("expected one complete strongly connected component");
        }
        return graph.components().getFirst();
    }

    private static Ast.FunctionDecl uniquelyExportedFunction(
            Ast.Program provider, String name) {
        Ast.FunctionDecl matched = null;
        for (Ast.ModuleDecl module : provider.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn
                        && fn.name().equals(name)
                        && fn.visibility() == Ast.Visibility.PUBLIC
                        && fn.kind() == Ast.CallableKind.FNC
                        && fn.actorKind() == Ast.ActorKind.NONE) {
                    if (matched != null) throw new IllegalArgumentException(
                            "ambiguous exported cross-module callable: " + name);
                    matched = fn;
                }
            }
        }
        if (matched == null) throw new IllegalArgumentException(
                "missing public provider callable: " + name);
        return matched;
    }
}
