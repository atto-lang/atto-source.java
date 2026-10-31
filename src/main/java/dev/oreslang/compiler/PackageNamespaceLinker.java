package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * First-stage, source-only namespace linker. A namespace is owned by exactly
 * one verified package instance, not by a directory, a source header alone,
 * or a "define module" block. This class does NOT perform executable lowering.
 *
 * Callers must supply an already-verified, closed package source inventory and
 * pinned alias->instance graph. This class cannot authenticate those maps
 * against a filesystem/lockfile; that remains the manifest resolver's job.
 */
public final class PackageNamespaceLinker {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String ROOT = Parser.ROOT_MODULE;

    private PackageNamespaceLinker() { }

    public record NamespaceId(String packageInstanceId, String name) {
        public NamespaceId {
            Objects.requireNonNull(packageInstanceId);
            Objects.requireNonNull(name);
        }
    }

    public record Export(String sourceUnitId, String name, int arity, Ast.FunctionDecl declaration) {
        public Export {
            Objects.requireNonNull(sourceUnitId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(declaration);
        }
    }

    public record Namespace(NamespaceId id, Set<String> sourceUnits, List<Export> exports) {
        public Namespace {
            sourceUnits = Set.copyOf(sourceUnits);
            exports = List.copyOf(exports);
        }
    }

    public record NamespaceImport(String sourceUnitId, String binding, Namespace target) { }

    public record Result(
            Map<NamespaceId, Namespace> namespaces,
            Map<String, Map<String, NamespaceImport>> importsBySource) {
        public Result {
            namespaces = Map.copyOf(namespaces);
            importsBySource = Map.copyOf(importsBySource);
        }
        public Namespace namespace(String packageInstanceId, String namespace) {
            return namespaces.get(new NamespaceId(packageInstanceId, namespace));
        }
        public NamespaceImport resolved(String sourceUnitId, String binding) {
            return importsBySource.getOrDefault(sourceUnitId, Map.of()).get(binding);
        }
    }

    /**
     * Validate and index namespace membership and imports.
     *
     * @param programs all verified source units of the closed package graph
     * @param sourceOwners source unit -> pinned PackageInstanceId; exactly the same keys as programs
     * @param pinnedAliases importing PackageInstanceId -> alias -> pinned target PackageInstanceId
     */
    public static Result link(
            Map<String, Ast.Program> programs,
            Map<String, String> sourceOwners,
            Map<String, Map<String, String>> pinnedAliases) {
        Objects.requireNonNull(programs, "programs");
        Objects.requireNonNull(sourceOwners, "sourceOwners");
        Objects.requireNonNull(pinnedAliases, "pinnedAliases");
        if (!programs.keySet().equals(sourceOwners.keySet())) {
            throw new IllegalArgumentException(
                    "namespace link requires exact verified source ownership for every source unit");
        }

        Map<NamespaceId, MutableNamespace> namespaceTable = new LinkedHashMap<>();
        Set<String> normalizedIds = new LinkedHashSet<>();
        Set<String> instances = new LinkedHashSet<>();
        List<String> sources = new ArrayList<>(programs.keySet());
        sources.sort(String::compareTo);

        for (String source : sources) {
            if (source == null || source.isBlank()
                    || !normalizedIds.add(ImportGraph.normalizeUnitId(source))) {
                throw new IllegalArgumentException("duplicate or invalid normalized namespace source identity");
            }
            Ast.Program program = Objects.requireNonNull(programs.get(source), "source program");
            String owner = sourceOwners.get(source);
            if (owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("empty or absent verified package instance for '" + source + "'");
            }
            instances.add(owner);
            String name = program.namespace();
            if (name == null) continue;
            if (!validName(name) || name.equals(ROOT)) {
                throw new IllegalArgumentException("invalid or reserved namespace header in '" + source + "'");
            }
            NamespaceId id = new NamespaceId(owner, name);
            MutableNamespace target = namespaceTable.computeIfAbsent(id, ignored -> new MutableNamespace());
            target.sources.add(source);

            // Only file-root declarations enter the file-header namespace.
            // "define module X" remains an independent implementation scope.
            for (Ast.ModuleDecl module : program.modules()) {
                if (!module.name().equals(ROOT)) continue;
                for (Ast.Decl decl : module.declarations()) {
                    if (!(decl instanceof Ast.FunctionDecl fn)) {
                        // Do not invent semantics for class/type/actor exports in
                        // a runtime namespace facade before typed lowering exists.
                        if (isPublic(decl)) {
                            throw new IllegalArgumentException(
                                    "public non-function namespace members require checked type/ABI lowering in '"
                                            + source + "'");
                        }
                        continue;
                    }
                    if (fn.visibility() != Ast.Visibility.PUBLIC) continue;
                    if (fn.kind() != Ast.CallableKind.FNC
                            || fn.actorKind() != Ast.ActorKind.NONE
                            || !fn.genericParameters().isEmpty()
                            || fn.async() || fn.suspends() || fn.generator()
                            || fn.trapped() || fn.structural()
                            || !fn.effects().isEmpty()
                            || fn.returnType() == null
                            || !(fn.returnType().name().equals("int")
                                            || fn.returnType().name().equals("bool"))
                            || !fn.returnType().arguments().isEmpty()
                            || fn.parameters().stream().anyMatch(p ->
                                    p.mutable() || p.type() == null
                                            || !(p.type().name().equals("int")
                                                || p.type().name().equals("bool"))
                                            || !p.type().arguments().isEmpty())) {
                        throw new IllegalArgumentException(
                                "unsupported public namespace callable '" + fn.name()
                                        + "' in '" + source + "'; checked callable lowering required");
                    }
                    String key = fn.name() + "\0" + fn.parameters().size();
                    if (!target.signatures.add(key)) {
                        throw new IllegalArgumentException(
                                "duplicate public namespace declaration '" + name + "." + fn.name()
                                        + "/" + fn.parameters().size() + "' in package '" + owner + "'");
                    }
                    target.exports.add(new Export(source, fn.name(), fn.parameters().size(), fn));
                }
            }
        }

        Map<NamespaceId, Namespace> namespaces = new LinkedHashMap<>();
        for (Map.Entry<NamespaceId, MutableNamespace> entry : namespaceTable.entrySet()) {
            MutableNamespace data = entry.getValue();
            data.exports.sort(Comparator.comparing(Export::name)
                    .thenComparingInt(Export::arity).thenComparing(Export::sourceUnitId));
            namespaces.put(entry.getKey(),
                    new Namespace(entry.getKey(), data.sources, data.exports));
        }

        Map<String, Map<String, NamespaceImport>> importTable = new LinkedHashMap<>();
        for (String source : sources) {
            String importerPackage = sourceOwners.get(source);
            Map<String, NamespaceImport> selected = new LinkedHashMap<>();
            Ast.Program program = programs.get(source);
            for (Ast.ImportDecl imported : program.imports()) {
                ImportRules.validate(imported);
                if (imported.kind() != Ast.ImportKind.NAMESPACE) continue;
                String packageAlias = imported.path().substring("pkg:".length());
                Map<String, String> aliases = pinnedAliases.get(importerPackage);
                if (aliases == null || !aliases.containsKey(packageAlias)) {
                    throw new IllegalArgumentException(
                            "undeclared or unpinned package alias '" + packageAlias + "' in '" + source + "'");
                }
                String targetPackage = aliases.get(packageAlias);
                if (targetPackage == null || !instances.contains(targetPackage)) {
                    throw new IllegalArgumentException(
                            "package alias '" + packageAlias
                                    + "' is not backed by a verified package source inventory");
                }
                String namespaceName = imported.names().getFirst();
                Namespace namespace = namespaces.get(new NamespaceId(targetPackage, namespaceName));
                if (namespace == null) {
                    throw new IllegalArgumentException(
                            "namespace '" + namespaceName + "' is absent from pinned package '"
                                    + targetPackage + "' (no cross-package fallback)");
                }
                NamespaceImport binding = new NamespaceImport(source, namespaceName, namespace);
                if (selected.putIfAbsent(namespaceName, binding) != null) {
                    throw new IllegalArgumentException(
                            "duplicate lexical namespace import binding '" + namespaceName
                                    + "' in '" + source + "'");
                }
            }
            importTable.put(source, Map.copyOf(selected));
        }
        return new Result(namespaces, importTable);
    }

    private static boolean validName(String name) {
        return IDENTIFIER.matcher(name).matches()
                && !name.equals("__proto__") && !name.equals("prototype")
                && !name.equals("constructor");
    }

    private static boolean isPublic(Ast.Decl decl) {
        if (decl instanceof Ast.ClassDecl klass) return klass.visibility() == Ast.Visibility.PUBLIC;
        if (decl instanceof Ast.InterfaceDecl iface) return iface.visibility() == Ast.Visibility.PUBLIC;
        if (decl instanceof Ast.TypeAliasDecl alias) return alias.visibility() == Ast.Visibility.PUBLIC;
        if (decl instanceof Ast.FieldDecl field) return field.visibility() == Ast.Visibility.PUBLIC;
        return false;
    }

    private static final class MutableNamespace {
        private final Set<String> sources = new LinkedHashSet<>();
        private final Set<String> signatures = new LinkedHashSet<>();
        private final List<Export> exports = new ArrayList<>();
    }
}
