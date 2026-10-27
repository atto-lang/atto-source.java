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

    public synchronized BuildResult compile(Map<String, String> sources) {
        return compile(sources, Map.of());
    }

    public synchronized BuildResult compile(
            Map<String, String> sources,
            Map<String, Map<String, String>> importResolutions) {
        if (importResolutions == null) throw new IllegalArgumentException("import resolutions cannot be null");
        if (sources.isEmpty()) return new BuildResult(Map.of(), Set.of(), Set.of(), List.of());

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

        // Parse the complete source set before resolving imports. This is the
        // first half of cycle tolerance: A may name B while B names A because
        // neither unit is recursively compiled while discovering the other.
        for (Map.Entry<String, String> entry : normalized.entrySet()) {
            hashes.put(entry.getKey(), digest(entry.getValue()));
            Ast.Program program = AnnotationExpander.expand(Parser.parse(entry.getValue()));
            parsed.put(entry.getKey(), program);
            abiHashes.put(entry.getKey(), abiDigest(program));
        }

        Map<String, Map<String, String>> normalizedImportResolutions =
                normalizeImportResolutions(importResolutions);
        ImportGraph.validateLinkedImports(parsed, normalizedImportResolutions);
        Map<String, Set<String>> dependencies =
                ImportGraph.resolveDependencies(parsed, normalized.keySet(), normalizedImportResolutions);
        List<List<String>> initializationGroups = ImportGraph.initializationGroups(dependencies);

        LinkedHashSet<String> dirty = new LinkedHashSet<>();
        LinkedHashSet<String> abiChanged = new LinkedHashSet<>();
        for (String id : normalized.keySet()) {
            CompiledUnit previous = cache.get(id);
            boolean sourceChanged = previous == null || !previous.sourceDigest().equals(hashes.get(id));
            boolean depsChanged = previous == null || !previous.dependencies().equals(dependencies.get(id));
            if (sourceChanged || depsChanged) dirty.add(id);
            if (previous == null || !previous.abiDigest().equals(abiHashes.get(id))) abiChanged.add(id);
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
            if (!dirty.contains(id) && cache.containsKey(id)) {
                next.put(id, cache.get(id));
                reused.add(id);
                continue;
            }

            Ast.Program checked;
            LinkedClasses linked = linkedPublicClasses(
                    id, parsed.get(id), parsed, normalizedImportResolutions);
            Map<String, Ast.InterfaceDecl> contracts = linkedImportedContracts(
                    id, parsed.get(id), parsed, normalizedImportResolutions);
            try {
                TypeChecker.TypeCheckResult analysis =
                        TypeChecker.checkTypesWithLinkedMetadataWithDiagnostics(
                                Parser.parse(normalized.get(id)),
                                linked.classes(), linked.aliases(), contracts);
                checked = analysis.program();
                OwnershipChecker.checkWithImportedClasses(
                        checked, linked.classes(), analysis.shortInferredAliases());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Oreslang source unit '" + id + "': " + invalid.getMessage(), invalid);
            }
            CompiledUnit unit = new CompiledUnit(
                    id,
                    packageId(id, checked),
                    checked.namespace(),
                    hashes.get(id),
                    abiHashes.get(id),
                    dependencies.get(id),
                    normalized.get(id),
                    checked);
            next.put(id, unit);
            rebuilt.add(id);
        }

        cache.keySet().retainAll(normalized.keySet());
        cache.putAll(next);
        return new BuildResult(
                Map.copyOf(next),
                Set.copyOf(rebuilt),
                Set.copyOf(reused),
                initializationGroups);
    }

    private record LinkedClasses(
            Map<String, Ast.ClassDecl> classes,
            Map<String, Map<String, Ast.TypeAliasDecl>> aliases) { }

    private static LinkedClasses linkedPublicClasses(
            String unitId,
            Ast.Program importer,
            Map<String, Ast.Program> parsed,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Ast.ClassDecl> linked = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, Ast.TypeAliasDecl>> aliases = new LinkedHashMap<>();
        for (Ast.ImportDecl imported : importer.imports()) {
            if (imported.kind() != Ast.ImportKind.CLASS || imported.wildcard()) continue;
            String sourceId = ImportGraph.resolveImportUnitId(
                    unitId, imported, parsed.keySet(), importResolutions);
            if (sourceId == null) continue; // external resolution remains opaque
            Ast.Program source = parsed.get(sourceId);
            if (source == null) {
                throw new IllegalArgumentException("missing linked class source '" + sourceId + "'");
            }
            ExportResolver.Result sourceExports = ExportResolver.resolve(source);
            for (String name : imported.names()) {
                Ast.ClassDecl resolved = null;
                Map<String, Ast.TypeAliasDecl> classAliases = Map.of();
                for (ExportResolver.ExportBinding binding : sourceExports.bindings()) {
                    if (binding.scope() != Ast.ExportScope.PUBLIC
                            || binding.localKind() != Ast.ExportKind.CLASS
                            || !binding.externalName().equals(name)) {
                        continue;
                    }
                    Ast.ModuleDecl owner = source.modules().stream()
                            .filter(module -> module.name().equals(binding.ownerNamespaceId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "export binding owner '" + binding.ownerNamespaceId()
                                            + "' is missing in linked source '" + sourceId + "'"));
                    String localName = exportLocalName(binding.localSymbolId());
                    for (Ast.Decl declaration : owner.declarations()) {
                        if (declaration instanceof Ast.ClassDecl klass
                                && klass.actorKind() == Ast.ActorKind.NONE
                                && klass.name().equals(localName)) {
                            if (resolved != null && resolved != klass) {
                                throw new IllegalArgumentException(
                                        "ambiguous linked public class export '" + name + "'");
                            }
                            resolved = klass;
                            LinkedHashMap<String, Ast.TypeAliasDecl> moduleAliases = new LinkedHashMap<>();
                            for (Ast.Decl companion : owner.declarations()) {
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
                if (resolved == null) {
                    throw new IllegalArgumentException(
                            "missing public linked class export '" + name
                                    + "' in source unit '" + sourceId + "'");
                }
                String localName = ImportRules.localName(imported, name);
                Ast.ClassDecl linkedView = linkedClassView(resolved, localName);
                if (linked.putIfAbsent(localName, linkedView) != null) {
                    throw new IllegalArgumentException(
                            "duplicate linked class import name '" + localName + "'");
                }
                LinkedHashMap<String, Ast.TypeAliasDecl> projectedAliases = new LinkedHashMap<>();
                for (Map.Entry<String, Ast.TypeAliasDecl> entry : classAliases.entrySet()) {
                    projectedAliases.put(entry.getKey(),
                            linkedAliasView(entry.getValue(), resolved.name(), localName));
                }
                aliases.put(localName, Map.copyOf(projectedAliases));
            }
        }
        return new LinkedClasses(Map.copyOf(linked), Map.copyOf(aliases));
    }

    /**
     * Build an importer-local, type-only view after ExportResolver has proven
     * the PUBLIC source binding. The original declaration remains authoritative
     * for runtime execution; this projection prevents a source-local class name
     * from leaking through an export/import alias and preserves constructor
     * visibility in static checking.
     */
    private static Ast.ClassDecl linkedClassView(Ast.ClassDecl source, String localName) {
        String self = source.name();
        return new Ast.ClassDecl(
                localName,
                Ast.Visibility.PUBLIC,
                source.isAbstract(),
                source.actorKind(),
                source.genericParameters(),
                source.parents().stream().map(type -> rewriteLinkedSelf(type, self, localName)).toList(),
                source.interfaces().stream().map(type -> rewriteLinkedSelf(type, self, localName)).toList(),
                source.fields().stream().map(field -> new Ast.FieldDecl(
                        field.name(),
                        field.visibility(),
                        field.bindingKind(),
                        rewriteLinkedSelf(field.type(), self, localName),
                        rewriteLinkedAnnotations(field.annotations(), self, localName),
                        field.initializer(),
                        field.mutableReferent())).toList(),
                source.constructor() == null ? null : new Ast.ConstructorDecl(
                        source.constructor().visibility(),
                        rewriteLinkedParams(source.constructor().parameters(), self, localName),
                        rewriteLinkedAnnotations(source.constructor().annotations(), self, localName),
                        source.constructor().body()),
                source.methods().stream().map(method -> new Ast.MethodDecl(
                        method.name(),
                        method.visibility(),
                        method.isStatic(),
                        method.isAbstract(),
                        method.async(),
                        method.suspends(),
                        method.structural(),
                        rewriteLinkedSelf(method.explicitReceiverType(), self, localName),
                        method.genericParameters(),
                        rewriteLinkedParams(method.parameters(), self, localName),
                        rewriteLinkedSelf(method.returnType(), self, localName),
                        rewriteLinkedAnnotations(method.annotations(), self, localName),
                        method.body(),
                        rewriteLinkedEffects(method.effects(), self, localName))).toList(),
                source.actorConcurrency());
    }

    private static Ast.TypeAliasDecl linkedAliasView(
            Ast.TypeAliasDecl alias,
            String self,
            String localName) {
        return new Ast.TypeAliasDecl(
                alias.name(),
                alias.visibility(),
                alias.genericParameters(),
                rewriteLinkedSelf(alias.target(), self, localName));
    }

    private static List<Ast.Param> rewriteLinkedParams(
            List<Ast.Param> params,
            String self,
            String localName) {
        return params.stream().map(param -> new Ast.Param(
                rewriteLinkedSelf(param.type(), self, localName),
                param.name(),
                param.structural(),
                param.mutable())).toList();
    }

    private static List<Ast.Annotation> rewriteLinkedAnnotations(
            List<Ast.Annotation> annotations,
            String self,
            String localName) {
        return annotations.stream().map(annotation -> new Ast.Annotation(
                annotation.name(),
                annotation.arguments().stream()
                        .map(type -> rewriteLinkedSelf(type, self, localName))
                        .toList())).toList();
    }

    private static Ast.EffectSpec rewriteLinkedEffects(
            Ast.EffectSpec effects,
            String self,
            String localName) {
        return new Ast.EffectSpec(
                effects.throwsTypes().stream().map(type -> rewriteLinkedSelf(type, self, localName)).toList(),
                effects.raisesTypes().stream().map(type -> rewriteLinkedSelf(type, self, localName)).toList(),
                effects.panicsTypes().stream().map(type -> rewriteLinkedSelf(type, self, localName)).toList());
    }

    private static Ast.TypeRef rewriteLinkedSelf(
            Ast.TypeRef type,
            String self,
            String localName) {
        if (type == null) return null;
        String name = type.name().equals(self) ? localName : type.name();
        return new Ast.TypeRef(
                name,
                type.arguments().stream()
                        .map(child -> rewriteLinkedSelf(child, self, localName))
                        .toList(),
                type.inferArguments());
    }

    private static String exportLocalName(String localSymbolId) {
        int marker = localSymbolId.lastIndexOf("::");
        if (marker < 0 || marker + 2 >= localSymbolId.length()) {
            throw new IllegalArgumentException("malformed export local symbol id: " + localSymbolId);
        }
        return localSymbolId.substring(marker + 2);
    }

    public synchronized void clear() {
        cache.clear();
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
        StringBuilder abi = new StringBuilder("ores-abi-v3\n");
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
            for (Ast.Decl decl : module.declarations()) appendAbi(abi, decl);
        }

        appendResolvedExportAbi(abi, program);
        return digest(abi.toString());
    }

    /**
     * Explicit exports are part of the cross-unit source ABI even when the
     * local declaration is default-private. Keep this projection separate from
     * the legacy declaration-side public ABI so aliases, scopes, and exported
     * private-local signatures invalidate importers without making source
     * exports host/native symbols.
     *
     * Some pre-existing source interfaces (notably inferred bindings and const
     * values) do not yet have canonical ExportAbiMetadata encodings. For those,
     * fail safe for incremental reuse by hashing the complete parsed AST: this
     * may rebuild more dependents, but can never reuse one across an unknown
     * source-interface change.
     */
    private static void appendResolvedExportAbi(StringBuilder abi, Ast.Program program) {
        ExportResolver.Result resolved = ExportResolver.resolve(program);
        List<ExportResolver.ExportBinding> bindings = new ArrayList<>(resolved.bindings());
        bindings.sort(java.util.Comparator
                .comparing(ExportResolver.ExportBinding::ownerNamespaceId)
                .thenComparing(ExportResolver.ExportBinding::externalName)
                .thenComparing(binding -> binding.localKind().name())
                .thenComparing(ExportResolver.ExportBinding::localSymbolId)
                .thenComparing(binding -> binding.scope().name())
                .thenComparing(binding -> binding.origin().name()));

        abi.append("source-exports-v1\n");
        for (ExportResolver.ExportBinding binding : bindings) {
            abi.append(" export ")
                    .append(binding.ownerNamespaceId()).append('|')
                    .append(binding.localSymbolId()).append('|')
                    .append(binding.localKind()).append('|')
                    .append(binding.externalName()).append('|')
                    .append(binding.scope()).append('|')
                    .append(binding.origin()).append('\n');
        }

        try {
            ExportAbiMetadata.Document metadata = ExportAbiMetadata.collect(program);
            abi.append(" export-metadata-version=").append(metadata.version()).append('\n');
            for (ExportAbiMetadata.Entry entry : metadata.entries()) {
                abi.append(" export-signature ")
                        .append(entry.ownerNamespaceId()).append('|')
                        .append(entry.localSymbolId()).append('|')
                        .append(entry.localKind()).append('|')
                        .append(entry.externalName()).append('|')
                        .append(entry.scope()).append('|')
                        .append(entry.origin()).append('|')
                        .append(entry.signatureDigest()).append('\n');
            }
        } catch (IllegalArgumentException unsupported) {
            String message = unsupported.getMessage();
            if (message == null
                    || (!message.contains("EXPORT_CONST_ABI_VALUE_UNENCODED")
                        && !message.contains("EXPORT_METADATA_UNRESOLVED_TYPE"))) {
                throw unsupported;
            }
            abi.append(" export-signature-fallback=")
                    .append(digest(program.toString()))
                    .append('\n');
        }
    }

    private static void appendAbi(StringBuilder abi, Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) {
            if (fn.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(fn.actorKind()).append(' ');
            if (fn.async()) abi.append("async ");
            if (fn.generator()) abi.append("generator ");
            if (fn.structural()) abi.append("structural ");
            abi.append(fn.kind()).append(" pub ").append(fn.name());
            appendGenericSpecs(abi, fn.genericSpecs());
            appendParams(abi, fn.parameters());
            abi.append("=>").append(typeRef(fn.returnType())).append('\n');
            return;
        }
        if (decl instanceof Ast.ClassDecl klass) {
            if (klass.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append(klass.actorKind()).append(" pub class ").append(klass.name());
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
                        .append(' ').append(field.name()).append('\n');
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
            abi.append(iface.moduleContract() ? "contract " : "interface ")
                    .append(iface.visibility()).append(' ').append(iface.name());
            appendGenerics(abi, iface.genericParameters());
            abi.append(" extends ");
            for (Ast.TypeRef parent : iface.parents()) abi.append(typeRef(parent)).append(',');
            abi.append('\n');
            List<String> memberEntries = new ArrayList<>();
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    memberEntries.add(interfaceFunctionAbi(fn));
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
        if (decl instanceof Ast.SymbolDecl symbol) {
            if (symbol.visibility() != Ast.Visibility.PUBLIC) return;
            abi.append("pub symbol ").append(symbol.name()).append('\n');
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
                    .append(' ').append(field.name()).append('\n');
        }
    }

    private static String classMethodAbi(Ast.MethodDecl method) {
        StringBuilder out = new StringBuilder();
        if (method.async()) out.append("async ");
        if (method.structural()) out.append("structural ");
        out.append(method.isStatic() ? " static-fnc " : " method ")
                .append(method.name())
                .append("#arity").append(method.arity());
        appendGenerics(out, method.genericParameters());
        appendParams(out, method.parameters());
        out.append("=>").append(typeRef(method.returnType())).append('\n');
        return out.toString();
    }

    private static String interfaceFunctionAbi(Ast.InterfaceFunctionDecl fn) {
        StringBuilder out = new StringBuilder();
        if (fn.structural()) out.append("structural ");
        out.append(" iface-fnc ").append(fn.name())
                .append("#arity").append(fn.parameters().size());
        appendGenerics(out, fn.genericParameters());
        appendParams(out, fn.parameters());
        out.append("=>").append(typeRef(fn.returnType())).append('\n');
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
            abi.append(',');
        }
        abi.append(')');
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

    private static String packageId(String unitId, Ast.Program program) {
        if (program.namespace() != null) return program.namespace();
        String id = unitId;
        if (id.endsWith(".ores")) id = id.substring(0, id.length() - ".ores".length());
        return id;
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
            List<List<String>> initializationGroups) {
        public BuildResult {
            units = Map.copyOf(units);
            rebuiltUnits = Set.copyOf(rebuiltUnits);
            reusedUnits = Set.copyOf(reusedUnits);
            initializationGroups = initializationGroups.stream()
                    .map(List::copyOf)
                    .toList();
        }

        /** Backward-compatible constructor for callers that do not need lifecycle planning. */
        public BuildResult(
                Map<String, CompiledUnit> units,
                Set<String> rebuiltUnits,
                Set<String> reusedUnits) {
            this(units, rebuiltUnits, reusedUnits, List.of());
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
