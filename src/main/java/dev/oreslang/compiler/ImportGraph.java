package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-file import graph/link validation.
 *
 * Import cycles are legal. Strongly connected components are initialization
 * barriers: every unit in the component must be loaded/linked before any init
 * hook in that component may execute.
 */
final class ImportGraph {
    private ImportGraph() { }

    static String resolveImportUnitId(String unitId, Ast.ImportDecl imported, Set<String> available) {
        return resolveImportUnitId(unitId, imported, available, Map.of());
    }

    static String resolveImportUnitId(
            String unitId,
            Ast.ImportDecl imported,
            Set<String> available,
            Map<String, Map<String, String>> importResolutions) {
        ImportRules.validate(imported);
        if (ImportRules.isJavaPath(imported.path())) return null;

        String normalizedUnitId = normalizeUnitId(unitId);
        String resolved = importResolutions
                .getOrDefault(normalizedUnitId, Map.of())
                .get(imported.path());
        if (resolved != null) {
            String normalizedResolved = normalizeUnitId(resolved);
            rejectSelfImport(normalizedUnitId, imported.path(), normalizedResolved);
            if (!available.contains(normalizedResolved)) {
                throw new IllegalArgumentException(
                        "resolved import '" + imported.path() + "' from '" + unitId
                                + "' points to source unit that was not supplied: '" + normalizedResolved + "'");
            }
            return normalizedResolved;
        }

        String raw = imported.path().replace('\\', '/');
        Path parent = Path.of(unitId).getParent();
        Path candidatePath = raw.startsWith(".")
                ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                : Path.of(raw).normalize();
        String candidate = normalizeUnitId(candidatePath.toString());
        if (!available.contains(candidate) && !candidate.endsWith(".ores") && !candidate.endsWith(".java")) {
            if (available.contains(candidate + ".ores")) candidate += ".ores";
            else if (available.contains(candidate + ".java")) candidate += ".java";
        }
        rejectSelfImport(normalizedUnitId, imported.path(), candidate);
        if (available.contains(candidate)) return candidate;
        if (raw.startsWith(".")) {
            throw new IllegalArgumentException("relative import '" + imported.path() + "' from '" + unitId
                    + "' does not resolve to a supplied Oreslang/mixed source unit");
        }
        return null;
    }

    static Map<String, Set<String>> resolveDependencies(
            Map<String, Ast.Program> programs,
            Set<String> available) {
        return resolveDependencies(programs, available, Map.of());
    }

    static Map<String, Set<String>> resolveDependencies(
            Map<String, Ast.Program> programs,
            Set<String> available,
            Map<String, Map<String, String>> importResolutions) {
        LinkedHashMap<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Program> entry : programs.entrySet()) {
            LinkedHashSet<String> resolved = new LinkedHashSet<>();
            for (Ast.ImportDecl imported : entry.getValue().imports()) {
                String target = resolveImportUnitId(entry.getKey(), imported, available, importResolutions);
                if (target != null) resolved.add(target);
            }
            dependencies.put(entry.getKey(), Set.copyOf(resolved));
        }
        return Map.copyOf(dependencies);
    }

    static void validateLinkedImports(Map<String, Ast.Program> programs) {
        validateLinkedImports(programs, Map.of());
    }

    static void validateLinkedImports(
            Map<String, Ast.Program> programs,
            Map<String, Map<String, String>> importResolutions) {
        Set<String> available = programs.keySet();
        for (Map.Entry<String, Ast.Program> entry : programs.entrySet()) {
            String importer = entry.getKey();
            for (Ast.ImportDecl imported : entry.getValue().imports()) {
                String targetId = resolveImportUnitId(importer, imported, available, importResolutions);
                if (targetId == null) continue; // package resolver owns unresolved non-relative imports.
                Ast.Program target = programs.get(targetId);
                // Imported functions and module/class namespaces do not yet
                // carry authenticated caller-role policy across source units.
                // Never silently erase @CallableBy while creating an import.
                rejectUnverifiedCallerRoleImports(importer, imported, targetId, target);
                if (imported.wildcard()) continue;
                for (String name : imported.names()) {
                    int matches = exportedMatches(target, imported.kind(), name);
                    if (matches == 0) {
                        throw new IllegalArgumentException("import " + imported.kind().name().toLowerCase()
                                + " '" + name + "' from '" + imported.path() + "' in '" + importer
                                + "' does not match an exported declaration in '" + targetId + "'");
                    }
                    if (matches > 1) {
                        throw new IllegalArgumentException("import " + imported.kind().name().toLowerCase()
                                + " '" + name + "' from '" + imported.path() + "' in '" + importer
                                + "' is ambiguous in '" + targetId + "'");
                    }
                }
            }
        }
    }

    private static void rejectUnverifiedCallerRoleImports(
            String importer, Ast.ImportDecl imported, String targetId, Ast.Program target) {
        boolean functions = imported.kind() == Ast.ImportKind.FUNCTION
                || imported.kind() == Ast.ImportKind.ALL
                || imported.kind() == Ast.ImportKind.MODULE;
        boolean classes = imported.kind() == Ast.ImportKind.CLASS
                || imported.kind() == Ast.ImportKind.ACTOR
                || imported.kind() == Ast.ImportKind.ALL
                || imported.kind() == Ast.ImportKind.MODULE;
        if (!functions && !classes) return;
        for (Ast.ModuleDecl module : target.modules()) {
            // import module Selected restricts the namespace to Selected;
            // import * and import fnc * select all matching exports.
            if (imported.kind() == Ast.ImportKind.MODULE
                    && !imported.wildcard()
                    && !imported.names().contains(module.name())) continue;
            for (Ast.Decl decl : module.declarations()) {
                String symbol = null;
                if (functions && decl instanceof Ast.FunctionDecl fn
                        && fn.visibility() == Ast.Visibility.PUBLIC
                        // Named "import fnc" only exports ordinary functions.
                        // Whole-module / wildcard imports may also surface
                        // other public callable kinds; do not let an actor
                        // entry's CallerRole grant disappear in that path.
                        && (imported.kind() != Ast.ImportKind.FUNCTION
                            || fn.actorKind() == Ast.ActorKind.NONE)
                        && (imported.kind() != Ast.ImportKind.FUNCTION
                            || fn.kind() == Ast.CallableKind.FNC)
                        && (imported.kind() == Ast.ImportKind.MODULE || imported.wildcard()
                            || imported.names().contains(fn.name()))
                        && hasCallerPolicy(fn.annotations())) {
                    symbol = module.name() + "." + fn.name();
                }
                if (classes && decl instanceof Ast.ClassDecl klass
                        && klass.visibility() == Ast.Visibility.PUBLIC
                        && (imported.kind() != Ast.ImportKind.ACTOR
                            || klass.actorKind() != Ast.ActorKind.NONE)
                        && (imported.kind() != Ast.ImportKind.CLASS
                            || klass.actorKind() == Ast.ActorKind.NONE)
                        && (imported.kind() == Ast.ImportKind.MODULE
                            || imported.wildcard() || imported.names().contains(klass.name()))) {
                    // An imported class exposes inherited public static
                    // functions too. Inspect local ancestry, and reject any
                    // parent whose identity cannot be proven in this source.
                    symbol = protectedClassSurface(
                            target, module, klass, new HashSet<>());
                }
                if (symbol != null) {
                    throw new IllegalArgumentException(
                            "caller-role annotated import of '" + symbol + "' from '" + targetId
                                    + "' into '" + importer
                                    + "' requires linked declaration identity and verified caller-role ABI; "
                                    + "unlinked role-policy imports are forbidden");
                }
            }
        }
    }

    /**
     * Find protected public methods reachable through a locally resolved
     * class hierarchy. Unknown ancestors fail closed: a class import must
     * not erase a protected inherited static function's linked identity.
     * Imported parent schemas can be admitted once signed/verified class
     * ancestry is available to this linker.
     */
    private static String protectedClassSurface(
            Ast.Program program, Ast.ModuleDecl module, Ast.ClassDecl klass,
            Set<String> visiting) {
        String qualified = module.name() + "." + klass.name();
        if (!visiting.add(qualified)) {
            throw new IllegalArgumentException(
                    "cyclic inherited class hierarchy in import of '" + qualified + "'");
        }
        try {
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.visibility() == Ast.Visibility.PUBLIC
                        && hasCallerPolicy(method.annotations())) {
                    return qualified + "." + method.name();
                }
            }
            for (Ast.TypeRef parent : klass.parents()) {
                String name = parent.name();
                String parentQualified = name.contains(".")
                        ? name : module.name() + "." + name;
                Ast.ModuleDecl foundModule = null;
                Ast.ClassDecl foundClass = null;
                for (Ast.ModuleDecl candidateModule : program.modules()) {
                    for (Ast.Decl declaration : candidateModule.declarations()) {
                        if (declaration instanceof Ast.ClassDecl candidate
                                && (candidateModule.name() + "." + candidate.name())
                                        .equals(parentQualified)) {
                            if (foundClass != null) {
                                throw new IllegalArgumentException(
                                        "ambiguous inherited class '" + parentQualified + "'");
                            }
                            foundModule = candidateModule;
                            foundClass = candidate;
                        }
                    }
                }
                if (foundClass == null) {
                    throw new IllegalArgumentException(
                            "unverified inherited callable policy for class '"
                                    + qualified + "': parent '" + name
                                    + "' has no linked declaration identity");
                }
                String guarded = protectedClassSurface(
                        program, foundModule, foundClass, visiting);
                if (guarded != null) return guarded;
            }
            return null;
        } finally {
            visiting.remove(qualified);
        }
    }

    private static boolean hasCallerPolicy(java.util.List<Ast.Annotation> annotations) {
        for (Ast.Annotation annotation : annotations) {
            // CallerRole is an authority-bearing grant just as CallableBy is
            // an authority-bearing restriction. Importers cannot erase either
            // until the linker verifies exact declaration and enum identities.
            if (annotation.name().equals("CallableBy")
                    || annotation.name().equals("CallerRole")) return true;
        }
        return false;
    }

    private static int exportedMatches(Ast.Program program, Ast.ImportKind kind, String name) {
        int matches = 0;
        for (Ast.ModuleDecl module : program.modules()) {
            if (kind == Ast.ImportKind.MODULE) {
                if (module.name().equals(name)) matches++;
                continue;
            }

            for (Ast.Decl decl : module.declarations()) {
                boolean matched = switch (kind) {
                    case FUNCTION -> decl instanceof Ast.FunctionDecl fn
                            && fn.visibility() == Ast.Visibility.PUBLIC
                            && fn.kind() == Ast.CallableKind.FNC
                            && fn.actorKind() == Ast.ActorKind.NONE
                            && fn.genericParameters().isEmpty()
                            && fn.name().equals(name);
                    case ACTOR -> decl instanceof Ast.ClassDecl klass
                            && klass.actorKind() != Ast.ActorKind.NONE
                            && klass.visibility() == Ast.Visibility.PUBLIC
                            && klass.name().equals(name);
                    case CLASS -> decl instanceof Ast.ClassDecl klass
                            && klass.visibility() == Ast.Visibility.PUBLIC
                            && klass.actorKind() == Ast.ActorKind.NONE
                            && klass.name().equals(name);
                    case INTERFACE -> decl instanceof Ast.InterfaceDecl iface
                            && !iface.moduleContract()
                            && iface.visibility() == Ast.Visibility.PUBLIC
                            && iface.name().equals(name);
                    case CONTRACT -> decl instanceof Ast.InterfaceDecl iface
                            && iface.moduleContract()
                            && iface.visibility() == Ast.Visibility.PUBLIC
                            && iface.name().equals(name);
                    case TYPE -> decl instanceof Ast.TypeAliasDecl alias
                            && alias.visibility() == Ast.Visibility.PUBLIC
                            && alias.name().equals(name);
                    case TYPES -> (decl instanceof Ast.InterfaceDecl iface
                                    && iface.visibility() == Ast.Visibility.PUBLIC
                                    && iface.name().equals(name))
                            || (decl instanceof Ast.TypeAliasDecl alias
                                    && alias.visibility() == Ast.Visibility.PUBLIC
                                    && alias.name().equals(name));
                    case TRAIT, STRUCT -> false;
                    case ALL -> (decl instanceof Ast.FunctionDecl fn
                                    && fn.visibility() == Ast.Visibility.PUBLIC
                                    && fn.name().equals(name))
                            || (decl instanceof Ast.ClassDecl klass
                                    && klass.visibility() == Ast.Visibility.PUBLIC
                                    && klass.name().equals(name));
                    case MODULE -> false;
                };
                if (matched) matches++;
            }
        }
        return matches;
    }

    /**
     * Dependency-first SCC order. Members inside one SCC are lexicographically
     * ordered so init order is deterministic even though the cycle itself does
     * not define an order.
     */
    static List<List<String>> initializationGroups(Map<String, Set<String>> dependencies) {
        if (dependencies.isEmpty()) return List.of();

        Tarjan tarjan = new Tarjan(dependencies);
        List<List<String>> components = tarjan.components();
        Map<String, Integer> componentOf = new HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (String unit : components.get(i)) componentOf.put(unit, i);
        }

        Map<Integer, Set<Integer>> componentDeps = new LinkedHashMap<>();
        for (int i = 0; i < components.size(); i++) componentDeps.put(i, new LinkedHashSet<>());
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            int from = componentOf.get(entry.getKey());
            for (String dependency : entry.getValue()) {
                Integer to = componentOf.get(dependency);
                if (to != null && to != from) componentDeps.get(from).add(to);
            }
        }

        List<Integer> componentIds = new ArrayList<>(componentDeps.keySet());
        componentIds.sort(Comparator.comparing(i -> components.get(i).getFirst()));

        List<List<String>> ordered = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Set<Integer> visiting = new HashSet<>();
        for (int component : componentIds) {
            visitComponent(component, componentDeps, components, visited, visiting, ordered);
        }
        return List.copyOf(ordered);
    }

    private static void visitComponent(
            int component,
            Map<Integer, Set<Integer>> dependencies,
            List<List<String>> components,
            Set<Integer> visited,
            Set<Integer> visiting,
            List<List<String>> ordered) {
        if (visited.contains(component)) return;
        if (!visiting.add(component)) {
            throw new IllegalStateException("condensed import graph unexpectedly contains a cycle");
        }
        List<Integer> deps = new ArrayList<>(dependencies.getOrDefault(component, Set.of()));
        deps.sort(Comparator.comparing(i -> components.get(i).getFirst()));
        for (int dependency : deps) {
            visitComponent(dependency, dependencies, components, visited, visiting, ordered);
        }
        visiting.remove(component);
        visited.add(component);
        ordered.add(components.get(component));
    }

    private static void rejectSelfImport(String importerId, String importPath, String targetId) {
        if (importerId.equals(targetId)) {
            throw new IllegalArgumentException(
                    "source unit '" + importerId + "' cannot import itself via '" + importPath + "'");
        }
    }

    static String normalizeUnitId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("source unit id cannot be blank");
        return Path.of(id).normalize().toString().replace('\\', '/');
    }

    private static final class Tarjan {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final ArrayDeque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<List<String>> components = new ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        private List<List<String>> components() {
            List<String> vertices = new ArrayList<>(graph.keySet());
            vertices.sort(String::compareTo);
            for (String vertex : vertices) {
                if (!index.containsKey(vertex)) strongConnect(vertex);
            }
            return List.copyOf(components);
        }

        private void strongConnect(String vertex) {
            int current = nextIndex++;
            index.put(vertex, current);
            lowLink.put(vertex, current);
            stack.push(vertex);
            onStack.add(vertex);

            List<String> edges = new ArrayList<>(graph.getOrDefault(vertex, Set.of()));
            edges.sort(String::compareTo);
            for (String next : edges) {
                if (!graph.containsKey(next)) continue;
                if (!index.containsKey(next)) {
                    strongConnect(next);
                    lowLink.put(vertex, Math.min(lowLink.get(vertex), lowLink.get(next)));
                } else if (onStack.contains(next)) {
                    lowLink.put(vertex, Math.min(lowLink.get(vertex), index.get(next)));
                }
            }

            if (lowLink.get(vertex).equals(index.get(vertex))) {
                List<String> component = new ArrayList<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(vertex));
                component.sort(String::compareTo);
                components.add(List.copyOf(component));
            }
        }
    }
}
