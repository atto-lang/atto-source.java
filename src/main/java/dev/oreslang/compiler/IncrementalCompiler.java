package dev.oreslang.compiler;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File-granular incremental compiler.
 *
 * Each source file is an independently versioned compilation/code unit.
 * Source edits always rebuild their own unit. Importers rebuild only when the
 * dependency's exported ABI digest changes, so implementation-only edits stay
 * local while public contract changes invalidate the necessary dependents.
 */
public final class IncrementalCompiler {
    private final Map<String, CompiledUnit> cache = new LinkedHashMap<>();
    // Never reuse a legacy-checked unit as strict-AOT source admission evidence.
    private final Map<String, CompiledUnit> strictAttoCache = new LinkedHashMap<>();
    // Parsing is separate from type/borrow admission. Cached expanded syntax
    // may be reused, but checked units must still honor current import ABI.
    // Strict-Atto and legacy parsing must never share cache entries.
    private final Map<String, ParsedUnit> parsedCache = new LinkedHashMap<>();
    private final Map<String, ParsedUnit> strictAttoParsedCache = new LinkedHashMap<>();
    // Include the imported declaration identity, not only resolved source IDs:
    // swapping {left} for {right} within the same provider also invalidates
    // re-exports and all downstream typed assumptions.
    private final Map<String, List<ImportBinding>> importBindingCache = new LinkedHashMap<>();
    private final Map<String, List<ImportBinding>> strictAttoImportBindingCache = new LinkedHashMap<>();
    private record ImportBinding(Ast.ImportDecl declaration, String resolvedTarget) { }

    // Raw AST is the input to semantic analysis (which expands annotations
    // itself). The expanded AST is used for graph linking and ABI indexing.
    // Both are immutable source-derived facts, never checked/borrow-authority.
    private record ParsedUnit(
            String sourceDigest, String abiDigest,
            Ast.Program rawProgram, Ast.Program program) { }

    public synchronized BuildResult compile(Map<String, String> sources) {
        return compile(sources, Map.of());
    }

    public synchronized BuildResult compile(
            Map<String, String> sources,
            Map<String, Map<String, String>> importResolutions) {
        return compileWithProfile(sources, importResolutions, false);
    }

    /**
     * Full-graph strict AttoLang front-end admission, not machine-code emission.
     * Every supplied unit is parsed under the same profile. Imported external
     * units with no supplied strict source are rejected, not trusted by name.
     */
    public synchronized BuildResult compileStrictAtto(Map<String, String> sources) {
        return compileStrictAtto(sources, Map.of());
    }

    public synchronized BuildResult compileStrictAtto(
            Map<String, String> sources,
            Map<String, Map<String, String>> importResolutions) {
        return compileWithProfile(sources, importResolutions, true);
    }

    private BuildResult compileWithProfile(
            Map<String, String> sources,
            Map<String, Map<String, String>> importResolutions,
            boolean strictAtto) {
        if (sources == null) throw new IllegalArgumentException("sources cannot be null");
        if (importResolutions == null) throw new IllegalArgumentException("import resolutions cannot be null");
        Map<String, CompiledUnit> selectedCache = strictAtto ? strictAttoCache : cache;
        Map<String, ParsedUnit> selectedParsedCache =
                strictAtto ? strictAttoParsedCache : parsedCache;
        Map<String, List<ImportBinding>> selectedBindingCache =
                strictAtto ? strictAttoImportBindingCache : importBindingCache;
        if (sources.isEmpty()) {
            // The input map is a complete source snapshot, including removals.
            // A later re-add must not reuse a checked result for deleted files.
            selectedCache.clear();
            selectedParsedCache.clear();
            selectedBindingCache.clear();
            return new BuildResult(Map.of(), Set.of(), Set.of(), List.of(), Set.of());
        }

        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String id = normalizeUnitId(entry.getKey());
            if (normalized.putIfAbsent(id, entry.getValue()) != null) {
                throw new IllegalArgumentException("duplicate source unit '" + id + "'");
            }
        }

        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, String> abiHashes = new LinkedHashMap<>();
        Map<String, Ast.Program> parsed = new LinkedHashMap<>();
        Map<String, ParsedUnit> stagedParsed = new LinkedHashMap<>();
        LinkedHashSet<String> reparsed = new LinkedHashSet<>();

        // Parse all *changed* inputs before resolving the complete graph.
        // This is in-memory only: AST keys include strict-vs-legacy parser
        // mode via separate maps. Never treat parse reuse as type validation.
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            String id = entry.getKey();
            String sourceDigest = digest(entry.getValue());
            hashes.put(id, sourceDigest);
            ParsedUnit previous = selectedParsedCache.get(id);
            ParsedUnit unit;
            if (previous != null && previous.sourceDigest().equals(sourceDigest)) {
                unit = previous;
            } else {
                final Ast.Program rawProgram;
                final Ast.Program expandedProgram;
                try {
                    rawProgram = strictAtto
                            ? Parser.parseStrictAtto(entry.getValue())
                            : Parser.parse(entry.getValue());
                    expandedProgram = AnnotationExpander.expand(rawProgram);
                } catch (IllegalArgumentException invalid) {
                    throw new SourceUnitDiagnosticException(id, "", "", invalid);
                }
                unit = new ParsedUnit(
                        sourceDigest, abiDigest(expandedProgram), rawProgram, expandedProgram);
                reparsed.add(id);
            }
            stagedParsed.put(id, unit);
            parsed.put(id, unit.program());
            abiHashes.put(id, unit.abiDigest());
        }

        Map<String, Map<String, String>> normalizedImportResolutions =
                normalizeImportResolutions(importResolutions);
        if (strictAtto) {
            for (Map.Entry<String, Ast.Program> entry : parsed.entrySet()) {
                String unitId = entry.getKey();
                for (Ast.ImportDecl imported : entry.getValue().imports()) {
                    final String target;
                    try {
                        target = ImportGraph.resolveImportUnitId(
                                unitId, imported, parsed.keySet(), normalizedImportResolutions);
                    } catch (IllegalArgumentException invalid) {
                        throw new SourceUnitDiagnosticException(unitId, "", "", invalid);
                    }
                    if (target == null) {
                        throw new SourceUnitDiagnosticException(unitId, "", "",
                                new IllegalArgumentException(
                                        "ATTO_STRICT_IMPORT_GRAPH_UNVERIFIED: unresolved import '"
                                        + imported.path() + "' needs a supplied strict-profile source unit"));
                    }
                }
            }
        }
        ImportGraph.validateLinkedImports(parsed, normalizedImportResolutions);
        Map<String, Set<String>> dependencies =
                ImportGraph.resolveDependencies(parsed, normalized.keySet(), normalizedImportResolutions);
        List<List<String>> initializationGroups = ImportGraph.initializationGroups(dependencies);

        // A dependency SET is not sufficient: swapping the binding of two
        // imports can retain exactly the same set of target source IDs.
        // Resolve every declaration again against this complete snapshot.
        Map<String, List<ImportBinding>> resolvedBindings = new LinkedHashMap<>();
        LinkedHashSet<String> unversioned = new LinkedHashSet<>();
        for (Map.Entry<String, Ast.Program> entry : parsed.entrySet()) {
            String id = entry.getKey();
            ArrayList<ImportBinding> targets = new ArrayList<>();
            for (Ast.ImportDecl imported : entry.getValue().imports()) {
                String target = ImportGraph.resolveImportUnitId(
                        id, imported, parsed.keySet(), normalizedImportResolutions);
                if (target == null) {
                    // Unknown host/package interface: never keep an unchecked
                    // cached type result across builds without version proof.
                    unversioned.add(id);
                    targets.add(new ImportBinding(imported, null));
                } else {
                    targets.add(new ImportBinding(imported, target));
                }
            }
            resolvedBindings.put(id, List.copyOf(targets));
        }

        LinkedHashSet<String> dirty = new LinkedHashSet<>();
        LinkedHashSet<String> abiChanged = new LinkedHashSet<>();
        for (String id : normalized.keySet()) {
            CompiledUnit previous = selectedCache.get(id);
            boolean sourceChanged = previous == null || !previous.sourceDigest().equals(hashes.get(id));
            boolean depsChanged = previous == null || !previous.dependencies().equals(dependencies.get(id));
            boolean bindingsChanged = previous == null
                    || !resolvedBindings.get(id).equals(selectedBindingCache.get(id));
            if (sourceChanged || depsChanged || bindingsChanged || unversioned.contains(id)) {
                dirty.add(id);
            }
            if (previous == null || !previous.abiDigest().equals(abiHashes.get(id))
                    || bindingsChanged || unversioned.contains(id)) {
                // Binding changes may alter exposed identities even when the
                // raw public declaration text is unchanged.
                abiChanged.add(id);
            }
        }

        /*
         * ABI changes invalidate the full reverse-import closure. This remains
         * conservative until the cross-unit linker tracks exactly which public
         * imported symbols are re-exported by each dependent. Crucially,
         * implementation-only source changes do not enter this queue.
         */
        Map<String, Set<String>> reverse = reverseDependencies(dependencies);
        ArrayDeque<String> abiQueue = new ArrayDeque<>(abiChanged);
        LinkedHashSet<String> abiAffected = new LinkedHashSet<>(abiChanged);
        while (!abiQueue.isEmpty()) {
            String changed = abiQueue.removeFirst();
            for (String dependent : reverse.getOrDefault(changed, Set.of())) {
                dirty.add(dependent);
                if (abiAffected.add(dependent)) abiQueue.addLast(dependent);
            }
        }

        LinkedHashMap<String, CompiledUnit> next = new LinkedHashMap<>();
        LinkedHashSet<String> rebuilt = new LinkedHashSet<>();
        LinkedHashSet<String> reused = new LinkedHashSet<>();

        for (String id : normalized.keySet()) {
            if (!dirty.contains(id) && selectedCache.containsKey(id)) {
                next.put(id, selectedCache.get(id));
                reused.add(id);
                continue;
            }

            Ast.Program checked;
            LinkedClasses linked = linkedPublicClasses(
                    id, parsed.get(id), parsed, normalizedImportResolutions);
            LinkedHashMap<String, Ast.InterfaceDecl> contracts = new LinkedHashMap<>(
                    linkedImportedContracts(id, parsed.get(id), parsed, normalizedImportResolutions));
            for (Map.Entry<String, Ast.InterfaceDecl> trait : linkedImportedTraits(
                    id, parsed.get(id), parsed, normalizedImportResolutions).entrySet()) {
                Ast.InterfaceDecl previous = contracts.putIfAbsent(
                        trait.getKey(), trait.getValue());
                if (previous != null && previous != trait.getValue()) {
                    throw new IllegalArgumentException(
                            "ambiguous imported contract/trait nominal name '" + trait.getKey() + "'");
                }
            }
            try {
                TypeChecker.TypeCheckResult analysis =
                        TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                                stagedParsed.get(id).rawProgram(),
                                linked.classes(), linked.aliases(), contracts, strictAtto);
                checked = analysis.program();
                OwnershipChecker.checkWithImportedClasses(
                        checked, linked.ownershipClasses(), analysis.shortInferredAliases());
            } catch (IllegalArgumentException invalid) {
                Ast.Program sourceProgram = parsed.get(id);
                String moduleName = sourceProgram.modules().size() == 1
                        ? sourceProgram.modules().get(0).name() : "";
                throw new SourceUnitDiagnosticException(
                        id, moduleName, sourceProgram.namespace(), invalid);
            }
            CompiledUnit unit = new CompiledUnit(
                    id,
                    packageId(id),
                    checked.namespace(),
                    hashes.get(id),
                    abiHashes.get(id),
                    dependencies.get(id),
                    normalized.get(id),
                    checked);
            next.put(id, unit);
            rebuilt.add(id);
        }

        // Publish syntax, imported bindings and checked units together ONLY
        // after all graph validation, type/ownership checks have succeeded.
        selectedCache.clear();
        selectedCache.putAll(next);
        selectedParsedCache.clear();
        selectedParsedCache.putAll(stagedParsed);
        selectedBindingCache.clear();
        selectedBindingCache.putAll(resolvedBindings);
        return new BuildResult(
                Map.copyOf(next),
                Set.copyOf(rebuilt),
                Set.copyOf(reused),
                initializationGroups,
                Set.copyOf(reparsed));
    }

    private record LinkedClasses(
            Map<String, Ast.ClassDecl> classes,
            Map<String, Map<String, Ast.TypeAliasDecl>> aliases,
            Map<String, Ast.ClassDecl> ownershipClasses) { }

    private static LinkedClasses linkedPublicClasses(
            String unitId,
            Ast.Program importer,
            Map<String, Ast.Program> parsed,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Ast.ClassDecl> linked = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, Ast.TypeAliasDecl>> aliases = new LinkedHashMap<>();
        LinkedHashMap<String, Ast.ClassDecl> ownershipClasses = new LinkedHashMap<>();
        for (Ast.ImportDecl imported : importer.imports()) {
            // Both classes and actors have nominal method/protocol metadata.
            // ImportGraph already proves the requested public declaration kind;
            // dropping ACTOR here previously let Worker.receive() fall through
            // to the unknown-member path without checking mailbox ingress.
            if (imported.wildcard() || (imported.kind() != Ast.ImportKind.CLASS
                    && imported.kind() != Ast.ImportKind.ACTOR
                    && imported.kind() != Ast.ImportKind.MODULE)) continue;
            String sourceId = ImportGraph.resolveImportUnitId(
                    unitId, imported, parsed.keySet(), importResolutions);
            if (sourceId == null) continue; // external resolution remains opaque
            Ast.Program source = parsed.get(sourceId);
            if (source == null) {
                throw new IllegalArgumentException("missing linked class source '" + sourceId + "'");
            }
            if (imported.kind() == Ast.ImportKind.MODULE) {
                // The validated import graph establishes source-unit identity.
                // Link public non-actor layouts only under the explicit module
                // qualifier; never expose a short class name or a sibling module.
                for (String moduleName : imported.names()) {
                    Ast.ModuleDecl selected = null;
                    for (Ast.ModuleDecl module : source.modules()) {
                        if (!module.name().equals(moduleName)) continue;
                        if (selected != null) {
                            throw new IllegalArgumentException(
                                    "ambiguous linked module '" + moduleName + "'");
                        }
                        selected = module;
                    }
                    if (selected == null) {
                        throw new IllegalArgumentException(
                                "missing linked module '" + moduleName + "'");
                    }
                    String prefix = ImportRules.localName(imported, moduleName) + ".";
                    for (Ast.Decl declaration : selected.declarations()) {
                        if (declaration instanceof Ast.ClassDecl klass
                                && klass.visibility() == Ast.Visibility.PUBLIC
                                && klass.actorKind() == Ast.ActorKind.NONE) {
                            String qualified = prefix + klass.name();
                            if (ownershipClasses.putIfAbsent(qualified, klass) != null) {
                                throw new IllegalArgumentException(
                                        "ambiguous linked public module class '" + qualified + "'");
                            }
                        }
                    }
                }
                continue;
            }
            for (String name : imported.names()) {
                Ast.ClassDecl resolved = null;
                Map<String, Ast.TypeAliasDecl> classAliases = Map.of();
                for (Ast.ModuleDecl module : source.modules()) {
                    for (Ast.Decl declaration : module.declarations()) {
                        if (declaration instanceof Ast.ClassDecl klass
                                && klass.name().equals(name)
                                && klass.visibility() == Ast.Visibility.PUBLIC) {
                            if (resolved != null) {
                                throw new IllegalArgumentException(
                                        "ambiguous linked public class '" + name + "'");
                            }
                            resolved = klass;
                            LinkedHashMap<String, Ast.TypeAliasDecl> moduleAliases = new LinkedHashMap<>();
                            for (Ast.Decl companion : module.declarations()) {
                                if (companion instanceof Ast.TypeAliasDecl alias
                                        && moduleAliases.putIfAbsent(alias.name(), alias) != null) {
                                    throw new IllegalArgumentException(
                                            "duplicate type alias in linked class module: " + alias.name());
                                }
                            }
                            classAliases = Map.copyOf(moduleAliases);
                        }
                    }
                }
                if (resolved == null || linked.putIfAbsent(name, resolved) != null) {
                    throw new IllegalArgumentException(
                            "missing or duplicate public linked class '" + name
                                    + "' in source unit '" + unitId + "'");
                }
                aliases.put(name, classAliases);
                if (ownershipClasses.putIfAbsent(name, resolved) != null) {
                    throw new IllegalArgumentException(
                            "ambiguous linked ownership class '" + name + "'");
                }
            }
        }
        return new LinkedClasses(
                Map.copyOf(linked), Map.copyOf(aliases), Map.copyOf(ownershipClasses));
    }

    public synchronized void clear() {
        cache.clear();
        strictAttoCache.clear();
        parsedCache.clear();
        strictAttoParsedCache.clear();
        importBindingCache.clear();
        strictAttoImportBindingCache.clear();
    }

    /**
     * Resolves named cross-file contract imports into type-only linker metadata.
     *
     * This deliberately does not splice foreign declarations into the local AST.
     * The first linked slice supports direct exported contracts. Inherited
     * cross-file contracts fail closed until parent-type provenance/substitution
     * is represented explicitly by the linker rather than guessed by name.
     */
    private static Map<String, Ast.InterfaceDecl> linkedImportedContracts(
            String unitId,
            Ast.Program program,
            Map<String, Ast.Program> programs,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Ast.InterfaceDecl> result = new LinkedHashMap<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.kind() != Ast.ImportKind.CONTRACT) continue;
            if (imported.wildcard()) {
                // Namespace-qualified contract references need a first-class
                // qualified nominal metadata model; do not manufacture one by
                // string concatenation.
                continue;
            }

            String targetId = ImportGraph.resolveImportUnitId(
                    unitId, imported, programs.keySet(), importResolutions);
            if (targetId == null) continue; // external/package resolver owns it
            Ast.Program target = programs.get(targetId);

            for (String sourceName : imported.names()) {
                Ast.InterfaceDecl match = null;
                for (Ast.ModuleDecl module : target.modules()) {
                    for (Ast.Decl declaration : module.declarations()) {
                        if (declaration instanceof Ast.InterfaceDecl iface
                                && iface.moduleContract()
                                && iface.visibility() == Ast.Visibility.PUBLIC
                                && iface.name().equals(sourceName)) {
                            if (match != null) {
                                throw new IllegalArgumentException(
                                        "import contract '" + sourceName + "' from '" + imported.path()
                                                + "' is ambiguous in '" + targetId + "'");
                            }
                            match = iface;
                        }
                    }
                }
                if (match == null) {
                    // ImportGraph.validateLinkedImports should already diagnose
                    // this, but keep this boundary independently fail-closed.
                    throw new IllegalArgumentException(
                            "import contract '" + sourceName + "' from '" + imported.path()
                                    + "' does not resolve to an exported module contract");
                }
                if (!match.parents().isEmpty()) {
                    throw new IllegalArgumentException(
                            "imported module contract '" + sourceName
                                    + "' extends another contract; cross-file inherited contract metadata "
                                    + "is not yet linkable without explicit parent provenance");
                }
                validatePortableImportedContract(match, targetId);

                String localName = ImportRules.localName(imported, sourceName);
                Ast.InterfaceDecl previous = result.putIfAbsent(localName, match);
                if (previous != null && previous != match) {
                    throw new IllegalArgumentException(
                            "duplicate imported contract metadata for local name '" + localName + "'");
                }
            }
        }
        return Map.copyOf(result);
    }


    /**
     * Imports are checked first by ImportGraph. This step carries their
     * public nominal declarations into the type checker, including same-unit
     * ancestor traits needed for inherited generic method obligations.
     *
     * Never synthesize a trait from a structural shape, an ambient filesystem
     * path or a private/module-contract declaration. An ancestor's name is
     * type-checker metadata, not a new runtime export.
     */
    private static Map<String, Ast.InterfaceDecl> linkedImportedTraits(
            String unitId, Ast.Program program, Map<String, Ast.Program> programs,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Ast.InterfaceDecl> linked = new LinkedHashMap<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.kind() != Ast.ImportKind.TRAIT
                    && imported.kind() != Ast.ImportKind.INTERFACE) continue;
            if (imported.wildcard()) continue; // no unchecked wildcard name grants
            String targetId = ImportGraph.resolveImportUnitId(
                    unitId, imported, programs.keySet(), importResolutions);
            if (targetId == null) continue;
            Ast.Program target = programs.get(targetId);
            if (target == null) {
                throw new IllegalArgumentException(
                        "missing imported trait source unit '" + targetId + "'");
            }
            for (String sourceName : imported.names()) {
                collectImportedTraitAndAncestors(target, targetId, sourceName,
                        ImportRules.localName(imported, sourceName),
                        imported.kind(), linked, new LinkedHashSet<>(), 0);
            }
        }
        return Map.copyOf(linked);
    }

    private static void collectImportedTraitAndAncestors(
            Ast.Program target, String targetId, String sourceName,
            String localName, Ast.ImportKind expectedKind,
            Map<String, Ast.InterfaceDecl> linked,
            Set<String> visiting, int depth) {
        if (depth > 32 || !visiting.add(sourceName)) {
            throw new IllegalArgumentException(
                    "imported trait inheritance cycle/depth limit: " + sourceName);
        }
        try {
            Ast.InterfaceDecl match = null;
            for (Ast.ModuleDecl module : target.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.InterfaceDecl iface
                            && !iface.moduleContract()
                            && iface.visibility() == Ast.Visibility.PUBLIC
                            && iface.name().equals(sourceName)) {
                        if (match != null) {
                            throw new IllegalArgumentException(
                                    "ambiguous imported trait '" + sourceName
                                            + "' in '" + targetId + "'");
                        }
                        match = iface;
                    }
                }
            }
            if (match == null) {
                throw new IllegalArgumentException(
                        "imported trait '" + sourceName
                                + "' has no public non-contract declaration in '" + targetId + "'");
            }
            if ((expectedKind == Ast.ImportKind.TRAIT
                            && match.declarationKind() != Ast.InterfaceKind.TRAIT)
                    || (expectedKind == Ast.ImportKind.INTERFACE
                            && match.declarationKind() != Ast.InterfaceKind.INTERFACE)) {
                throw new IllegalArgumentException(
                        "import " + expectedKind.name().toLowerCase()
                                + " '" + sourceName + "' resolved to a mismatched declaration kind in '"
                                + targetId + "'");
            }
            Ast.InterfaceDecl existing = linked.putIfAbsent(localName, match);
            if (existing != null && existing != match) {
                throw new IllegalArgumentException(
                        "conflicting imported trait nominal '" + localName + "'");
            }
            for (Ast.TypeRef parent : match.parents()) {
                // A declared parent must be resolved from the SAME authenticated
                // imported source unit. Never look it up by arbitrary path.
                collectImportedTraitAndAncestors(target, targetId, parent.name(),
                        parent.name(), null, linked, visiting, depth + 1);
            }
        } finally {
            visiting.remove(sourceName);
        }
    }

    private static final Set<String> LINKABLE_CONTRACT_TYPES = Set.of(
            "int", "uint", "float", "decimal", "complex", "bool", "String", "void",
            "Object", "List", "Map", "Tuple", "FixedArray", "Option", "Result", "Future",
            "Iterator", "AsyncIterator", "IteratorResult", "Generator", "AsyncGenerator",
            "Channel", "SelectCase", "SelectSet", "SelectResult", "Fnc");

    private static void validatePortableImportedContract(
            Ast.InterfaceDecl contract,
            String targetId) {
        Set<String> contractGenerics = Set.copyOf(contract.genericParameters());
        for (Ast.InterfaceMember member : contract.members()) {
            if (member instanceof Ast.InterfaceFieldDecl field) {
                validatePortableContractType(
                        field.type(), contractGenerics, targetId, contract.name() + "." + field.name());
            } else if (member instanceof Ast.InterfaceFunctionDecl fn) {
                LinkedHashSet<String> generics = new LinkedHashSet<>(contractGenerics);
                generics.addAll(fn.genericParameters());
                for (Ast.Param param : fn.parameters()) {
                    validatePortableContractType(
                            param.type(), generics, targetId, contract.name() + "." + fn.name());
                }
                validatePortableContractType(
                        fn.returnType(), generics, targetId, contract.name() + "." + fn.name());
            } else if (member instanceof Ast.InterfaceActorMethodDecl method) {
                for (Ast.Param param : method.parameters()) {
                    validatePortableContractType(param.type(), contractGenerics, targetId,
                            contract.name() + "." + method.actorName() + "." + method.name());
                }
                validatePortableContractType(method.returnType(), contractGenerics, targetId,
                        contract.name() + "." + method.actorName() + "." + method.name());
            }
        }
    }

    private static void validatePortableContractType(
            Ast.TypeRef ref,
            Set<String> generics,
            String targetId,
            String member) {
        if (ref == null) {
            throw new IllegalArgumentException(
                    "imported module contract member '" + member + "' from '" + targetId
                            + "' has an inferred type; linked contract ABI requires explicit types");
        }
        if (ref.isStringLiteral()) return;
        String name = ref.name();
        if (!generics.contains(name) && !LINKABLE_CONTRACT_TYPES.contains(name)) {
            throw new IllegalArgumentException(
                    "imported module contract member '" + member + "' from '" + targetId
                            + "' references nominal type '" + name + "' without linker provenance; "
                            + "imported contract conformance fails closed until nominal type identities are linked");
        }
        for (Ast.TypeRef argument : ref.arguments()) {
            validatePortableContractType(argument, generics, targetId, member);
        }
    }

    private static String abiDigest(Ast.Program program) {
        StringBuilder abi = new StringBuilder("ores-abi-v4\n");
        abi.append("namespace=").append(program.namespace() == null ? "" : program.namespace()).append('\n');

        for (Ast.ModuleDecl module : program.modules()) {
            abi.append("module ").append(module.name()).append('\n');
            for (String contract : module.contracts().stream()
                    .map(IncrementalCompiler::typeRef).sorted().toList()) {
                abi.append(" module-contract ").append(contract).append('\n');
            }
            for (Ast.Annotation annotation : module.annotations()) {
                if (annotation.name().equals("AdheresTo")) {
                    abi.append(" module-annotation AdheresTo:");
                    for (Ast.TypeRef arg : annotation.arguments()) abi.append(typeRef(arg)).append(',');
                    abi.append('\n');
                }
            }
            // Lexical declaration order is not public ABI. Keep each
            // declaration's internal parameter/member order and sort the
            // complete export entries to prevent false importer invalidation.
            List<String> declarations = new ArrayList<>();
            for (Ast.Decl decl : module.declarations()) {
                StringBuilder exported = new StringBuilder();
                appendAbi(exported, decl);
                if (!exported.isEmpty()) declarations.add(exported.toString());
            }
            declarations.stream().sorted().forEach(abi::append);
        }
        return digest(abi.toString());
    }

    private static void appendAbi(StringBuilder abi, Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) {
            if (fn.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(fn.actorKind()).append(' ');
            if (fn.async()) abi.append("async ");
            if (fn.generator()) abi.append("generator ");
            if (fn.structural()) abi.append("structural ");
            abi.append("suspends=").append(fn.suspends())
                    .append(" nonlexical=").append(fn.nonLexical())
                    .append(" trapped=").append(fn.trapped()).append(' ');
            abi.append(fn.kind()).append(" pub ").append(fn.name());
            appendGenericSpecs(abi, fn.genericSpecs());
            appendParams(abi, fn.parameters());
            abi.append("=>").append(typeRef(fn.returnType()));
            appendEffects(abi, fn.effects());
            appendAnnotations(abi, fn.annotations());
            abi.append('\n');
            return;
        }
        if (decl instanceof Ast.ClassDecl klass) {
            if (klass.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(klass.actorKind()).append(" pub class ").append(klass.name())
                    .append(" abstract=").append(klass.isAbstract())
                    .append(" concurrency=").append(klass.actorConcurrency().transport())
                    .append('/').append(klass.actorConcurrency().requestConcurrency())
                    .append('/').append(klass.actorConcurrency().messageConcurrency())
                    .append('/').append(klass.actorConcurrency().serialReceive());
            appendAnnotations(abi, klass.annotations());
            appendGenerics(abi, klass.genericParameters());
            abi.append(" extends ");
            for (Ast.TypeRef parent : klass.parents()) abi.append(typeRef(parent)).append(',');
            abi.append(" implements ");
            for (Ast.TypeRef iface : klass.interfaces()) abi.append(typeRef(iface)).append(',');
            abi.append('\n');
            // Computed class indexing is checked against the complete instance-field
            // domain, not only the public ABI surface. The first non-public field
            // changes an external dynamic receiver[key] from safe to rejected, so
            // that safety-domain transition must invalidate linked dependents.
            if (klass.actorKind() == Ast.ActorKind.NONE) {
                boolean hasNonPublicComputedIndexField = klass.fields().stream()
                        .anyMatch(field -> field.visibility() != Ast.Visibility.PUBLIC);
                abi.append(" computed-index-has-nonpublic-field=")
                        .append(hasNonPublicComputedIndexField)
                        .append('\n');
            }
            if (klass.constructor() != null
                    && klass.constructor().visibility() == Ast.Visibility.PUBLIC) {
                abi.append(" constructor");
                appendParams(abi, klass.constructor().parameters());
                appendAnnotations(abi, klass.constructor().annotations());
                abi.append('\n');
            } else if (klass.constructor() == null) {
                // Legacy synthesized constructors are externally visible when
                // their class is public; all positional field slots affect ABI.
                abi.append(" synthesized-constructor(");
                for (Ast.FieldDecl field : klass.fields()) {
                    abi.append(field.type() == null
                            ? "<inferred:" + field.initializer() + ">"
                            : typeRef(field.type())).append(',');
                }
                abi.append(")\n");
            }
            for (Ast.FieldDecl field : klass.fields()) {
                String fromJsonKey = AnnotationExpander.fromJsonKey(field);
                if (fromJsonKey != null) {
                    abi.append(" from-json ")
                            .append(field.name()).append('=')
                            .append(fromJsonKey.length()).append(':').append(fromJsonKey)
                            .append(':').append(field.type() == null ? "<inferred>" : typeRef(field.type())).append('\n');
                }
                if (field.visibility() != Ast.Visibility.PUBLIC) continue;
                abi.append(" field ").append(field.bindingKind()).append(' ')
                        .append(field.type() == null ? "<inferred:" + field.initializer() + ">" : typeRef(field.type()))
                        .append(' ').append(field.name()).append(" mutableReferent=")
                        .append(field.mutableReferent());
                appendAnnotations(abi, field.annotations());
                abi.append('\n');
            }
            List<String> methodEntries = new ArrayList<>();
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                methodEntries.add(classMethodAbi(method));
            }
            methodEntries.stream().sorted().forEach(abi::append);
            return;
        }
        if (decl instanceof Ast.InterfaceDecl iface) {
            // Private contracts/traits/interfaces are not part of the imported ABI.
            if (iface.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(iface.declarationKind().name().toLowerCase()).append(' ')
                    .append(iface.visibility()).append(' ').append(iface.name())
                    .append(" moduleContract=").append(iface.moduleContract());
            appendGenerics(abi, iface.genericParameters());
            abi.append(" extends ");
            for (Ast.TypeRef parent : iface.parents()) abi.append(typeRef(parent)).append(',');
            abi.append('\n');
            List<String> memberEntries = new ArrayList<>();
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    memberEntries.add(interfaceFunctionAbi(fn));
                } else if (member instanceof Ast.InterfaceActorDecl actor) {
                    // A required nominal actor is contract ABI even though it
                    // contributes no ordinary module-value member.
                    memberEntries.add(" contract-actor " + actor.name() + "\n");
                } else if (member instanceof Ast.InterfaceActorMethodDecl method) {
                    StringBuilder signature = new StringBuilder(" contract-actor-mtd ");
                    signature.append(method.visibility()).append(' ')
                            .append(method.actorName()).append('.').append(method.name())
                            .append("#arity").append(method.parameters().size());
                    appendParams(signature, method.parameters());
                    signature.append("=>").append(typeRef(method.returnType())).append('\n');
                    memberEntries.add(signature.toString());
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    String requirement = iface.moduleContract()
                            ? (field.bindingKind() == null ? "readable"
                                    : "rebindable=" + (field.bindingKind() == Ast.BindingKind.LET)
                                            + ",mutableReferent=" + field.mutableReferent())
                            : "mutableReferent=" + field.mutableReferent();
                    memberEntries.add(" iface-field " + requirement + " " + field.name()
                            + ":" + typeRef(field.type()) + "\n");
                }
            }
            memberEntries.stream().sorted().forEach(abi::append);
            return;
        }
        if (decl instanceof Ast.TypeAliasDecl alias) {
            if (alias.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append("pub type ").append(alias.name());
            appendGenerics(abi, alias.genericParameters());
            abi.append('=').append(typeRef(alias.target())).append('\n');
            return;
        }
        if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
            abi.append("binding ").append(field.bindingKind()).append(' ')
                    .append(field.type() == null ? "<inferred:" + field.initializer() + ">" : typeRef(field.type()))
                    .append(' ').append(field.name()).append(" mutableReferent=")
                    .append(field.mutableReferent());
            appendAnnotations(abi, field.annotations());
            abi.append('\n');
        }
    }

    private static String classMethodAbi(Ast.MethodDecl method) {
        StringBuilder out = new StringBuilder();
        if (method.async()) out.append("async ");
        if (method.structural()) out.append("structural ");
        out.append("suspends=").append(method.suspends())
                .append(" abstract=").append(method.isAbstract())
                .append(" critical=").append(method.critical())
                .append(" receiver=").append(typeRef(method.explicitReceiverType()));
        out.append(method.isStatic() ? " static-fnc " : " method ")
                .append(method.name())
                .append("#arity").append(method.arity());
        appendGenerics(out, method.genericParameters());
        appendParams(out, method.parameters());
        out.append("=>").append(typeRef(method.returnType()));
        appendEffects(out, method.effects());
        appendAnnotations(out, method.annotations());
        out.append('\n');
        return out.toString();
    }

    private static String interfaceFunctionAbi(Ast.InterfaceFunctionDecl fn) {
        StringBuilder out = new StringBuilder();
        if (fn.structural()) out.append("structural ");
        out.append(" iface-fnc ").append(fn.name())
                .append("#arity").append(fn.parameters().size());
        appendGenerics(out, fn.genericParameters());
        appendParams(out, fn.parameters());
        out.append("=>").append(typeRef(fn.returnType()));
        appendEffects(out, fn.effects());
        out.append('\n');
        return out.toString();
    }

    /** Generic defaults are public source-ABI, even when execution is erased. */
    private static void appendGenericSpecs(
            StringBuilder abi, List<Ast.GenericParameter> specs) {
        abi.append('<');
        for (Ast.GenericParameter spec : specs) {
            abi.append(spec.name());
            if (spec.defaultType() != null) {
                abi.append('=').append(typeRef(spec.defaultType()));
            }
            abi.append(',');
        }
        abi.append('>');
    }

    private static void appendGenerics(StringBuilder abi, List<String> generics) {
        abi.append('<');
        for (String generic : generics) abi.append(generic).append(',');
        abi.append('>');
    }

    private static void appendParams(StringBuilder abi, List<Ast.Param> params) {
        abi.append('(');
        for (Ast.Param param : params) {
            if (param.structural()) abi.append("structural ");
            abi.append(typeRef(param.type()));
            if (param.mutable()) abi.append(" mut");
            // Named arguments make source parameter names observable API.
            appendLengthPrefixed(abi, param.name());
            abi.append(',');
        }
        abi.append(')');
    }

    private static void appendLengthPrefixed(StringBuilder abi, String value) {
        if (value == null) {
            abi.append("-1:");
        } else {
            abi.append(value.length()).append(':').append(value);
        }
    }

    private static void appendEffects(StringBuilder abi, Ast.EffectSpec effects) {
        abi.append(" effects[throws=");
        effects.throwsTypes().stream().map(IncrementalCompiler::typeRef).sorted()
                .forEach(type -> appendLengthPrefixed(abi, type));
        abi.append(",raises=");
        effects.raisesTypes().stream().map(IncrementalCompiler::typeRef).sorted()
                .forEach(type -> appendLengthPrefixed(abi, type));
        abi.append(",panics=");
        effects.panicsTypes().stream().map(IncrementalCompiler::typeRef).sorted()
                .forEach(type -> appendLengthPrefixed(abi, type));
        abi.append(']');
    }

    private static void appendAnnotations(StringBuilder abi, List<Ast.Annotation> annotations) {
        abi.append(" annotations[");
        for (Ast.Annotation annotation : annotations) {
            appendLengthPrefixed(abi, annotation.name());
            abi.append('(');
            for (Ast.TypeRef argument : annotation.arguments()) {
                appendLengthPrefixed(abi, typeRef(argument));
            }
            abi.append(')');
        }
        abi.append(']');
    }

    private static String typeRef(Ast.TypeRef ref) {
        if (ref == null) return "<inferred>";
        if (ref.isStringLiteral()) return "'" + ref.stringLiteralValue() + "'";
        StringBuilder out = new StringBuilder(ref.name());
        if (ref.inferArguments()) return out.append("<>").toString();
        if (!ref.arguments().isEmpty()) {
            out.append('<');
            for (Ast.TypeRef arg : ref.arguments()) out.append(typeRef(arg)).append(',');
            out.append('>');
        }
        return out.toString();
    }

    private static Set<String> resolveDependencies(String unitId, Ast.Program program, Set<String> available) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Path parent = Path.of(unitId).getParent();
        for (Ast.ImportDecl imported : program.imports()) {
            String raw = imported.path().replace('\\', '/');
            Path candidatePath = raw.startsWith(".")
                    ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                    : Path.of(raw).normalize();
            String candidate = normalizeUnitId(candidatePath.toString());
            if (!available.contains(candidate) && !candidate.endsWith(".ores") && available.contains(candidate + ".ores")) {
                candidate += ".ores";
            }
            if (available.contains(candidate)) {
                result.add(candidate);
            } else if (raw.startsWith(".")) {
                throw new IllegalArgumentException("relative import '" + imported.path() + "' from '" + unitId
                        + "' does not resolve to a supplied Oreslang source unit");
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Map<String, String>> normalizeImportResolutions(
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Map<String, String>> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> importer : importResolutions.entrySet()) {
            String importerId = normalizeUnitId(importer.getKey());
            if (importer.getValue() == null) {
                throw new IllegalArgumentException("import resolution map cannot be null for '" + importerId + "'");
            }
            LinkedHashMap<String, String> imports = new LinkedHashMap<>();
            for (Map.Entry<String, String> resolution : importer.getValue().entrySet()) {
                if (resolution.getKey() == null || resolution.getKey().isBlank()) {
                    throw new IllegalArgumentException("resolved import path cannot be blank");
                }
                String targetId = normalizeUnitId(resolution.getValue());
                String previous = imports.putIfAbsent(resolution.getKey(), targetId);
                if (previous != null && !previous.equals(targetId)) {
                    throw new IllegalArgumentException(
                            "conflicting resolved import '" + resolution.getKey() + "' for '" + importerId + "'");
                }
            }
            normalized.put(importerId, Map.copyOf(imports));
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Set<String>> reverseDependencies(Map<String, Set<String>> dependencies) {
        LinkedHashMap<String, Set<String>> reverse = new LinkedHashMap<>();
        for (String id : dependencies.keySet()) reverse.put(id, new LinkedHashSet<>());
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                reverse.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        LinkedHashMap<String, Set<String>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : reverse.entrySet()) frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
        return Map.copyOf(frozen);
    }

    private static String packageId(String unitId) {
        // A source namespace is a compile-time name, not ownership authority.
        // Until a verified manifest-defined multi-file PackageInstanceId is
        // plumbed through this compiler, every code unit owns an independent
        // singleton package. In particular, two unrelated dependencies that
        // both spell "namespace billing;" must NEVER share a packageId.
        //
        // Preserve the exact normalized source-unit identity, including the
        // suffix, to avoid collisions between different source kinds.
        return "file:" + normalizeUnitId(unitId);
    }

    private static String normalizeUnitId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("source unit id cannot be blank");
        return Path.of(id).normalize().toString().replace('\\', '/');
    }

    private static String digest(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record CompiledUnit(
            String unitId,
            String packageId,
            String namespace,
            String sourceDigest,
            String abiDigest,
            Set<String> dependencies,
            String sourceText,
            Ast.Program program) {
        public CompiledUnit {
            dependencies = Set.copyOf(dependencies);
        }
    }

    public record BuildResult(
            Map<String, CompiledUnit> units,
            Set<String> rebuiltUnits,
            Set<String> reusedUnits,
            List<List<String>> initializationGroups,
            Set<String> reparsedUnits) {
        public BuildResult {
            units = Map.copyOf(units);
            rebuiltUnits = Set.copyOf(rebuiltUnits);
            reusedUnits = Set.copyOf(reusedUnits);
            reparsedUnits = Set.copyOf(reparsedUnits);
            initializationGroups = initializationGroups.stream()
                    .map(List::copyOf)
                    .toList();
        }

        /** Backward-compatible constructor for callers that track only checked units. */
        public BuildResult(
                Map<String, CompiledUnit> units,
                Set<String> rebuiltUnits,
                Set<String> reusedUnits,
                List<List<String>> initializationGroups) {
            this(units, rebuiltUnits, reusedUnits, initializationGroups, Set.of());
        }

        /** Backward-compatible constructor for callers that do not need lifecycle planning. */
        public BuildResult(
                Map<String, CompiledUnit> units,
                Set<String> rebuiltUnits,
                Set<String> reusedUnits) {
            this(units, rebuiltUnits, reusedUnits, List.of(), Set.of());
        }

        public boolean rebuilt(String unitId) { return rebuiltUnits.contains(normalizeUnitId(unitId)); }
        public boolean reused(String unitId) { return reusedUnits.contains(normalizeUnitId(unitId)); }

        /**
         * Flattens the dependency-first SCC plan. Units in the same inner list
         * form one load barrier: all of them must be linked before the first
         * init hook in that group executes.
         */
        public List<String> initializationOrder() {
            return initializationGroups.stream().flatMap(List::stream).toList();
        }
    }
}
