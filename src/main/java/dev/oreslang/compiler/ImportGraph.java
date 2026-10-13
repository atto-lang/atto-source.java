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

    private static int exportedMatches(Ast.Program program, Ast.ImportKind kind, String name) {
        int matches = 0;
        for (Ast.ModuleDecl module : program.modules()) {
            if (kind == Ast.ImportKind.MODULE) {
                if (module.name().equals(name)) matches++;
                continue;
            }

            // An overloaded function family is one exported name, not one
            // import candidate per arity. Different modules exporting the
            // same name remain ambiguous.
            boolean matchedFunctionFamily = false;
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
                            && alias.name().equals(name);
                    case TYPES -> (decl instanceof Ast.InterfaceDecl iface
                                    && iface.visibility() == Ast.Visibility.PUBLIC
                                    && iface.name().equals(name))
                            || (decl instanceof Ast.TypeAliasDecl alias
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
                if (matched) {
                    if (decl instanceof Ast.FunctionDecl
                            && (kind == Ast.ImportKind.FUNCTION || kind == Ast.ImportKind.ALL)) {
                        if (matchedFunctionFamily) continue;
                        matchedFunctionFamily = true;
                    }
                    matches++;
                }
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

        // Iterative dependency-first traversal: importing a very deep chain
        // must not exhaust the Java compiler's native call stack.
        List<List<String>> ordered = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Set<Integer> visiting = new HashSet<>();
        for (int component : componentIds) {
            if (visited.contains(component)) continue;
            ArrayDeque<ComponentFrame> pending = new ArrayDeque<>();
            pending.push(new ComponentFrame(component, componentDeps, components));
            visiting.add(component);
            while (!pending.isEmpty()) {
                ComponentFrame current = pending.peek();
                if (current.nextDependency < current.dependencies.size()) {
                    int dependency = current.dependencies.get(current.nextDependency++);
                    if (visited.contains(dependency)) continue;
                    if (!visiting.add(dependency)) {
                        throw new IllegalStateException(
                                "condensed import graph unexpectedly contains a cycle");
                    }
                    pending.push(new ComponentFrame(dependency, componentDeps, components));
                } else {
                    pending.pop();
                    visiting.remove(current.component);
                    visited.add(current.component);
                    ordered.add(components.get(current.component));
                }
            }
        }
        return List.copyOf(ordered);
    }

    private static final class ComponentFrame {
        private final int component;
        private final List<Integer> dependencies;
        private int nextDependency;

        private ComponentFrame(
                int component,
                Map<Integer, Set<Integer>> componentDeps,
                List<List<String>> components) {
            this.component = component;
            this.dependencies = new ArrayList<>(componentDeps.getOrDefault(component, Set.of()));
            this.dependencies.sort(Comparator.comparing(i -> components.get(i).getFirst()));
        }
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

        private void strongConnect(String root) {
            // Explicit DFS frames preserve Tarjan low-link propagation and
            // deterministic traversal without recursive Java stack frames.
            ArrayDeque<Frame> pending = new ArrayDeque<>();
            enter(root, null, pending);
            while (!pending.isEmpty()) {
                Frame frame = pending.peek();
                if (frame.nextEdge < frame.edges.size()) {
                    String next = frame.edges.get(frame.nextEdge++);
                    if (!graph.containsKey(next)) continue;
                    if (!index.containsKey(next)) {
                        enter(next, frame.vertex, pending);
                    } else if (onStack.contains(next)) {
                        lowLink.put(frame.vertex,
                                Math.min(lowLink.get(frame.vertex), index.get(next)));
                    }
                    continue;
                }

                pending.pop();
                if (frame.parent != null) {
                    lowLink.put(frame.parent,
                            Math.min(lowLink.get(frame.parent), lowLink.get(frame.vertex)));
                }
                if (lowLink.get(frame.vertex).equals(index.get(frame.vertex))) {
                    List<String> component = new ArrayList<>();
                    String member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while (!member.equals(frame.vertex));
                    component.sort(String::compareTo);
                    components.add(List.copyOf(component));
                }
            }
        }

        private void enter(String vertex, String parent, ArrayDeque<Frame> pending) {
            int current = nextIndex++;
            index.put(vertex, current);
            lowLink.put(vertex, current);
            stack.push(vertex);
            onStack.add(vertex);
            pending.push(new Frame(vertex, parent, graph.getOrDefault(vertex, Set.of())));
        }

        private static final class Frame {
            private final String vertex;
            private final String parent;
            private final List<String> edges;
            private int nextEdge;

            private Frame(String vertex, String parent, Set<String> outgoing) {
                this.vertex = vertex;
                this.parent = parent;
                this.edges = new ArrayList<>(outgoing);
                this.edges.sort(String::compareTo);
            }
        }
    }
}
