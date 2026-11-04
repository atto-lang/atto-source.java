package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed, explicit-root source-to-descriptor bridge. The only production
 * caller is OresCompiler after the normal type and ownership passes.
 *
 * This is NOT a guest type_of intrinsic, runtime reflection API, or proof
 * of a native AOT binary. Unsupported type shapes fail closed.
 */
final class AotReflectionSourceRootsV1 {
    private static final int MAX_ROOTS = 1024;
    // Deliberately small until resolution and visibility for nominal field types
    // are bound to the production symbol table, rather than guessed from text.
    private static final Set<String> CLOSED_SCALARS =
            Set.of("int", "i64", "bool", "string", "float", "double", "byte");

    private AotReflectionSourceRootsV1() { }

    static AotReflectionTableV1.Encoded lowerChecked(
            Ast.Program checked, String packageIdentity, List<String> explicitRoots) {
        Objects.requireNonNull(checked, "checked type/ownership program");
        if (packageIdentity == null || packageIdentity.isBlank() || packageIdentity.length() > 512
                || explicitRoots == null || explicitRoots.isEmpty()
                || explicitRoots.size() > MAX_ROOTS) {
            throw new IllegalArgumentException("bounded package identity and explicit reflection roots required");
        }
        // Freeze potentially caller-owned root lists before validation and lookup.
        // The same immutable snapshot drives both admission and emission; a
        // concurrently modified list must not alter which descriptor is emitted.
        final List<String> stableRoots;
        try {
            stableRoots = List.copyOf(explicitRoots);
        } catch (NullPointerException malformedRoots) {
            throw new IllegalArgumentException("null reflection root", malformedRoots);
        }
        // V1 deliberately has no namespace/import dependency identity field.
        // Never hash two distinct namespaced or imported symbols as the same
        // apparently closed "__root__.Type" descriptor.
        if (checked.namespace() != null || !checked.imports().isEmpty()) {
            throw new IllegalArgumentException(
                    "AOT reflection v1 requires an import-free, namespace-free checked program");
        }

        // Validate and bound the explicit root set *before* scanning declarations.
        // Only retained roots are indexed: unrelated classes must not grow the
        // compiler metadata map, and unrooted declarations cannot leak.
        Set<String> rootsSeen = new HashSet<>(stableRoots.size());
        for (String root : stableRoots) {
            if (root == null || root.isBlank() || root.length() > 512 || !rootsSeen.add(root)) {
                throw new IllegalArgumentException("missing or duplicate reflection root");
            }
        }
        Map<String, Ast.ClassDecl> declarations = new HashMap<>();
        for (Ast.ModuleDecl module : checked.modules()) {
            if (module == null || module.name() == null || module.name().isBlank()) {
                throw new IllegalArgumentException("missing resolved source module");
            }
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) {
                    String qualified = module.name() + "." + klass.name();
                    if (!rootsSeen.contains(qualified)) continue;
                    if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
                        throw new IllegalArgumentException(
                                "module annotations/contracts require resolved AOT metadata identity: " + qualified);
                    }
                    if (declarations.putIfAbsent(qualified, klass) != null) {
                        throw new IllegalArgumentException("ambiguous reflection class symbol: " + qualified);
                    }
                }
            }
        }

        List<AotReflectionTableV1.ResolvedType> entries = new ArrayList<>(stableRoots.size());
        for (String root : stableRoots) {
            Ast.ClassDecl klass = declarations.get(root);
            if (klass == null || klass.visibility() != Ast.Visibility.PUBLIC
                    || klass.actorKind() != Ast.ActorKind.NONE
                    || !klass.genericParameters().isEmpty()
                    || !klass.parents().isEmpty()
                    || !klass.interfaces().isEmpty()) {
                throw new IllegalArgumentException("unsupported, private, or unresolved reflection root: " + root);
            }

            List<AotReflectionTableV1.Field> fields = new ArrayList<>();
            for (Ast.FieldDecl field : klass.fields()) {
                // Private field identities and their types never enter the table.
                if (field.visibility() != Ast.Visibility.PUBLIC) continue;
                // V1 carries field name + type only, not wire aliases,
                // defaults, converter identities or versioned schema rules.
                // An annotated public field must NOT silently deserialize
                // using its declaration name instead of a @FromJson key.
                if (!field.annotations().isEmpty()) {
                    throw new IllegalArgumentException(
                            "AOT reflection v1 cannot lower annotated public wire fields: "
                            + root + "." + field.name());
                }
                Ast.TypeRef type = field.type();
                if (type == null || type.inferArguments() || !type.arguments().isEmpty()
                        || !CLOSED_SCALARS.contains(type.name())) {
                    throw new IllegalArgumentException(
                            "public reflection field requires an explicitly resolved closed scalar: " + root);
                }
                fields.add(new AotReflectionTableV1.Field(
                        field.name(), type.name(), AotReflectionTableV1.Visibility.PUBLIC));
            }
            // The encoder checks canonical UTF-8, identity uniqueness and byte budgets.
            entries.add(new AotReflectionTableV1.ResolvedType(
                    packageIdentity, root, List.of(), fields));
        }
        return AotReflectionTableV1.encode(entries);
    }
}
