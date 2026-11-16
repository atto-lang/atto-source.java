package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Closed-world, compiler-derived explicit copy plan for a deliberately bounded
 * class/scalar/typed-sequence guest subset. This is NOT the implicit Copy trait:
 * only an explicit {@code rt copy} may consume a ClassPlan.
 *
 * Immutable plan nodes do not contain guest objects or host pointers. The guest
 * evaluator must interpret each plan by allocating distinct owned mutable
 * containers and checking each runtime value against its exact plan.
 */
public final class DerivedCopyPlanV1 {
    private DerivedCopyPlanV1() { }

    public sealed interface Plan permits Scalar, Sequence, Instance { }
    public record Scalar(String typeName) implements Plan { }
    public record Sequence(String typeName, Plan element) implements Plan { }
    public record Field(String name, Plan plan) { }
    public record Instance(String className, List<Field> fields) implements Plan {
        public Instance { fields = List.copyOf(fields); }
    }

    private static final int MAX_DEPTH = 32;
    private static final int MAX_FIELDS = 128;
    private static final Set<String> SCALARS = Set.of(
            "int", "uint", "i8", "i16", "i32", "i64",
            "u8", "u16", "u32", "u64", "f32", "f64",
            "float", "bool", "Bool", "string", "String");

    /**
     * Derive a fresh owned value plan, never an address/handle alias. Unresolved
     * generics, dynamic layouts, actors, resources and cycles are rejected.
     */
    public static Plan derive(
            Ast.TypeRef type, Function<String, Ast.ClassDecl> findClass) {
        if (findClass == null) throw new IllegalArgumentException("E_COPY_UNPROVEN: missing class resolver");
        return build(type, findClass, new LinkedHashSet<>(), 0);
    }

    private static Plan build(
            Ast.TypeRef type, Function<String, Ast.ClassDecl> lookup,
            Set<String> visiting, int depth) {
        if (type == null || depth > MAX_DEPTH || type.isBorrow()
                || type.inferArguments() || type.isUnion()
                || type.isComplement() || type.isTupleType()
                || type.name().startsWith("$")) {
            throw fail("E_COPY_UNPROVEN", "unresolved or unsupported explicit-copy type");
        }
        if (SCALARS.contains(type.name()) && type.arguments().isEmpty()
                && lookup.apply(type.name()) == null) {
            return new Scalar(type.name());
        }
        if ((type.name().equals("Array") || type.name().equals("List"))
                && lookup.apply(type.name()) == null) {
            if (type.arguments().size() != 1) {
                throw fail("E_COPY_UNPROVEN", "typed Array/List requires exactly one element type");
            }
            return new Sequence(type.name(), build(
                    type.arguments().getFirst(), lookup, visiting, depth + 1));
        }
        // Every other nominal or resource type remains noncopyable until its
        // compiler/runtime shape is explicitly supported.
        Ast.ClassDecl klass = lookup.apply(type.name());
        if (klass == null) {
            throw fail("E_COPY_UNPROVEN", "no derived copy for type " + type.name());
        }
        if (klass.actorKind() != Ast.ActorKind.NONE) {
            throw fail("E_COPY_FORBIDDEN", "actor instances are not copyable: " + klass.name());
        }
        if (has(klass.annotations(), "NoCopy")) {
            throw fail("E_COPY_FORBIDDEN", "@NoCopy class " + klass.name());
        }
        if (has(klass.annotations(), "ManualCopy")) {
            throw fail("E_COPY_UNPROVEN", "@ManualCopy requires checked custom lowering: " + klass.name());
        }
        if (klass.isAbstract() || !klass.genericParameters().isEmpty()
                || !type.arguments().isEmpty()) {
            throw fail("E_COPY_UNPROVEN", "polymorphic/generic class not admitted in copy v1: " + klass.name());
        }
        // An unverified handwritten method cannot replace compiler allocation.
        if (klass.methods().stream().anyMatch(m -> m.name().equals("copy") && !m.isStatic())) {
            throw fail("E_COPY_OVERRIDE", "class copy override requires verified super.copy lowering: " + klass.name());
        }
        if (!visiting.add(type.name())) {
            throw fail("E_COPY_GRAPH_UNPROVEN", "cyclic class layout: " + type.name());
        }
        try {
            LinkedHashMap<String, Field> fields = new LinkedHashMap<>();
            for (Ast.TypeRef parentType : klass.parents()) {
                if (parentType.name().equals("Object") || parentType.name().equals("List")) {
                    continue; // builtin, field-free base
                }
                Plan parentPlan = build(parentType, lookup, visiting, depth + 1);
                if (!(parentPlan instanceof Instance base)) {
                    throw fail("E_COPY_UNPROVEN", "class parent is not copyable: " + parentType.name());
                }
                for (Field entry : base.fields()) {
                    if (fields.putIfAbsent(entry.name(), entry) != null) {
                        throw fail("E_COPY_UNPROVEN", "ambiguous inherited field " + entry.name());
                    }
                }
            }
            for (Ast.FieldDecl field : klass.fields()) {
                if (has(field.annotations(), "NoCopy")) {
                    throw fail("E_COPY_TRANSITIVE", klass.name() + "." + field.name() + " is @NoCopy");
                }
                if (fields.containsKey(field.name())) {
                    throw fail("E_COPY_UNPROVEN", "shadowed inherited field " + field.name());
                }
                Plan fieldPlan;
                try {
                    fieldPlan = build(field.type(), lookup, visiting, depth + 1);
                } catch (IllegalArgumentException failure) {
                    throw fail("E_COPY_TRANSITIVE", klass.name() + "." + field.name()
                            + ": " + failure.getMessage());
                }
                fields.put(field.name(), new Field(field.name(), fieldPlan));
                if (fields.size() > MAX_FIELDS) {
                    throw fail("E_COPY_UNPROVEN", "too many owned fields in " + klass.name());
                }
            }
            return new Instance(klass.name(), new ArrayList<>(fields.values()));
        } finally {
            visiting.remove(type.name());
        }
    }

    private static boolean has(List<Ast.Annotation> annotations, String name) {
        return annotations.stream().anyMatch(annotation -> annotation.name().equals(name));
    }

    private static IllegalArgumentException fail(String code, String detail) {
        return new IllegalArgumentException(code + ": " + detail);
    }
}
