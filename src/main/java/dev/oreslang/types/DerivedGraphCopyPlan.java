package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Candidate finite descriptor graph for a future verified deep-copy lowering.
 * Not wired to the legacy rt copy/copy! evaluator. No guest objects are stored.
 * Scope: exact non-generic identity classes without inheritance, typed sequences,
 * Option, and immutable scalar values. Unsupported shapes refuse admission.
 */
public final class DerivedGraphCopyPlan {
    private DerivedGraphCopyPlan() { }

    public sealed interface Node permits Scalar, Sequence, OptionalValue, Instance { }
    public record Scalar(String typeName) implements Node { }
    public record Sequence(String typeName, int element) implements Node { }
    public record OptionalValue(int element) implements Node { }
    public record Field(String name, int descriptor) { }
    public record Instance(String className, List<Field> fields) implements Node {
        public Instance { fields = List.copyOf(fields); }
    }
    public record Graph(int root, List<Node> nodes) {
        public Graph { nodes = List.copyOf(nodes); }
    }

    private static final int MAX_NODES = 16384;
    private static final int MAX_EDGES = 32768;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_FIELDS = 128;
    private static final Set<String> SCALARS = Set.of(
            "int", "i8", "i16", "i32", "i64", "f32", "f64",
            "float", "bool", "Bool", "string", "String");

    // Keep the compiler and transaction validator on the same closed set.
    // Unsigned values require a verified runtime representation first.
    static boolean supportsScalar(String name) {
        return name != null && SCALARS.contains(name);
    }

    public static Graph derive(Ast.TypeRef type, Function<String, Ast.ClassDecl> lookup) {
        if (lookup == null) throw denied("missing class resolver");
        Builder builder = new Builder(lookup);
        int root = builder.visit(type, 0);
        // List.copyOf refuses any unfinished placeholder; no partial plan escapes.
        return new Graph(root, builder.nodes);
    }

    private record ContainerKey(String name, int element) { }

    private static final class Builder {
        private final Function<String, Ast.ClassDecl> lookup;
        private final List<Node> nodes = new ArrayList<>();
        private final IdentityHashMap<Ast.ClassDecl, Integer> classes = new IdentityHashMap<>();
        private final Map<String, Ast.ClassDecl> classNames = new HashMap<>();
        private final Map<String, Integer> scalars = new HashMap<>();
        private final Map<ContainerKey, Integer> containers = new HashMap<>();
        private int edges;

        Builder(Function<String, Ast.ClassDecl> lookup) { this.lookup = lookup; }

        int reserve() {
            if (nodes.size() >= MAX_NODES) throw denied("descriptor node budget");
            int id = nodes.size();
            nodes.add(null);
            return id;
        }

        int visit(Ast.TypeRef type, int depth) {
            if (++edges > MAX_EDGES || depth > MAX_DEPTH) {
                throw denied("descriptor expansion budget");
            }
            if (type == null || type.isBorrow() || type.inferArguments()
                    || type.isUnion() || type.isComplement() || type.isTupleType()
                    || type.isRecordType() || type.name().startsWith("$")) {
                throw denied("unsupported type");
            }
            String name = type.name();
            Ast.ClassDecl klass = lookup.apply(name);
            if (klass == null && supportsScalar(name) && type.arguments().isEmpty()) {
                Integer prior = scalars.get(name);
                if (prior != null) return prior;
                int id = reserve();
                nodes.set(id, new Scalar(name));
                scalars.put(name, id);
                return id;
            }
            if (klass == null && Set.of("Array", "List", "Option").contains(name)) {
                if (type.arguments().size() != 1) throw denied("container arity");
                int element = visit(type.arguments().getFirst(), depth + 1);
                ContainerKey key = new ContainerKey(name, element);
                Integer prior = containers.get(key);
                if (prior != null) return prior;
                int id = reserve();
                nodes.set(id, name.equals("Option")
                        ? new OptionalValue(element) : new Sequence(name, element));
                containers.put(key, id);
                return id;
            }
            if (klass == null || !type.arguments().isEmpty()
                    || !klass.genericParameters().isEmpty() || klass.isAbstract()
                    || klass.actorKind() != Ast.ActorKind.NONE
                    || klass.annotations().stream().anyMatch(a ->
                            Set.of("NoCopy", "ManualCopy").contains(a.name()))
                    || klass.methods().stream().anyMatch(m -> !m.isStatic()
                            && Set.of("copy", "copy_deep").contains(m.name()))
                    || klass.parents().stream().anyMatch(p -> !p.name().equals("Object")
                            || !p.arguments().isEmpty())) {
                throw denied("unproved class or resource: " + name);
            }
            // This prototype cannot encode qualified class identity yet. Refuse
            // same-name different declarations instead of conflating them.
            Ast.ClassDecl previous = classNames.putIfAbsent(klass.name(), klass);
            if (previous != null && previous != klass) throw denied("ambiguous class identity");
            Integer prior = classes.get(klass);
            if (prior != null) return prior;
            if (klass.fields().size() > MAX_FIELDS) throw denied("field budget");
            int id = reserve();
            classes.put(klass, id); // finite knot before following fields
            List<Field> fields = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Ast.FieldDecl field : klass.fields()) {
                if (!names.add(field.name()) || field.annotations().stream()
                        .anyMatch(a -> a.name().equals("NoCopy"))) {
                    throw denied("duplicate or forbidden field: " + field.name());
                }
                fields.add(new Field(field.name(), visit(field.type(), depth + 1)));
            }
            nodes.set(id, new Instance(klass.name(), fields));
            return id;
        }
    }

    private static IllegalArgumentException denied(String detail) {
        return new IllegalArgumentException("E_COPY_GRAPH_UNPROVEN: " + detail);
    }
}
