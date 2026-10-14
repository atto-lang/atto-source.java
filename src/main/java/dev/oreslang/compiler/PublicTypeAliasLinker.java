package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eliminates source-private alias dependencies at a verified cross-unit type import.
 *
 * This linker expands only value-free, representation-preserving type syntax. A
 * foreign nominal class is deliberately unsupported until its public ABI can be
 * linked and provenance-checked; it must not degrade to an invented Named type.
 */
final class PublicTypeAliasLinker {
    private static final int MAX_EXPANSION_DEPTH = 64;
    private static final int MAX_EXPANSION_NODES = 16_384;

    private static final class Budget {
        int remaining = MAX_EXPANSION_NODES;
        void consume() {
            if (--remaining < 0) {
                throw new IllegalArgumentException(
                        "imported type alias expansion exceeds " + MAX_EXPANSION_NODES + " nodes");
            }
        }
    }
    private static final Set<String> BUILTIN_TYPES = Set.of(
            "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
            "int", "uint", "bigint", "f32", "f64", "float", "decimal",
            "complex64", "complex128", "complex", "bool", "Bool", "boolean",
            "string", "String", "void", "null",
            "Tuple", "Array", "List", "Vector", "Slice", "FixedArray", "FixedList",
            "Option", "Result", "Future", "Iterator", "AsyncIterator",
            "Generator", "AsyncGenerator", "Channel", "Fnc", "Function");

    private PublicTypeAliasLinker() { }

    static Map<String, Ast.TypeAliasDecl> link(
            String importerUnit,
            Ast.Program importer,
            Map<String, Ast.Program> parsed,
            Map<String, Map<String, String>> importResolutions) {
        Map<String, Ast.TypeAliasDecl> linked = new LinkedHashMap<>();
        for (Ast.ImportDecl use : importer.imports()) {
            if (use.wildcard() || (use.kind() != Ast.ImportKind.TYPE
                    && use.kind() != Ast.ImportKind.TYPES)) continue;
            String sourceId = ImportGraph.resolveImportUnitId(
                    importerUnit, use, parsed.keySet(), importResolutions);
            if (sourceId == null) {
                if (use.kind() == Ast.ImportKind.TYPE) {
                    throw new IllegalArgumentException("type alias import from '" + use.path()
                            + "' lacks a verified source unit");
                }
                continue;
            }
            Ast.Program source = parsed.get(sourceId);
            if (source == null) throw new IllegalArgumentException("missing linked alias source " + sourceId);
            for (String name : use.names()) {
                Ast.TypeAliasDecl selected = null;
                Map<String, Ast.TypeAliasDecl> localAliases = null;
                for (Ast.ModuleDecl module : source.modules()) {
                    Map<String, Ast.TypeAliasDecl> aliases = new HashMap<>();
                    for (Ast.Decl declaration : module.declarations()) {
                        if (declaration instanceof Ast.TypeAliasDecl alias
                                && aliases.putIfAbsent(alias.name(), alias) != null) {
                            throw new IllegalArgumentException("ambiguous aliases in " + sourceId);
                        }
                    }
                    Ast.TypeAliasDecl candidate = aliases.get(name);
                    if (candidate != null && candidate.visibility() == Ast.Visibility.PUBLIC) {
                        if (selected != null) {
                            throw new IllegalArgumentException("ambiguous public alias '" + name + "'");
                        }
                        selected = candidate;
                        localAliases = aliases;
                    }
                }
                if (selected == null) {
                    // 'import types' can also select interfaces. 'import type'
                    // specifically requires a verified and publicly exported alias.
                    if (use.kind() == Ast.ImportKind.TYPE) {
                        throw new IllegalArgumentException("public type alias '" + name
                                + "' could not be linked from " + sourceId);
                    }
                    continue;
                }

                Map<String, Ast.TypeRef> bindings = new HashMap<>();
                for (String generic : selected.genericParameters()) {
                    if (bindings.putIfAbsent(generic, Ast.TypeRef.simple(generic)) != null) {
                        throw new IllegalArgumentException("duplicate imported generic '" + generic + "'");
                    }
                }
                Ast.TypeRef expanded = expand(selected.target(), localAliases,
                        bindings, new HashSet<>(Set.of(selected.name())), 0, new Budget());
                String localName = ImportRules.localName(use, name);
                Ast.TypeAliasDecl exported = new Ast.TypeAliasDecl(localName,
                        Ast.Visibility.PUBLIC, selected.genericParameters(), expanded);
                if (linked.putIfAbsent(localName, exported) != null) {
                    throw new IllegalArgumentException("duplicate imported alias '" + localName + "'");
                }
            }
        }
        return Map.copyOf(linked);
    }

    private static boolean compilerGeneratedTypeRef(String name) {
        return switch (name) {
            case "$record$", "$readonly_record$", "$tuple$", "$union$",
                    "$intersection$", "$borrow$", "$borrow_mut$", "$shape$",
                    "$repeat_many$", "$infer$" -> true;
            default -> (name.startsWith("$field$") && name.length() > "$field$".length())
                    || name.startsWith("$repeat_exact$")
                    || name.startsWith("$int$")
                    || name.startsWith("$bool$")
                    || name.startsWith("$string$")
                    || name.startsWith("$named$");
        };
    }

    private static Ast.TypeRef expand(
            Ast.TypeRef ref,
            Map<String, Ast.TypeAliasDecl> localAliases,
            Map<String, Ast.TypeRef> bindings,
            Set<String> visiting,
            int depth,
            Budget budget) {
        budget.consume();
        if (depth >= MAX_EXPANSION_DEPTH) {
            throw new IllegalArgumentException("imported type alias expansion exceeds "
                    + MAX_EXPANSION_DEPTH + " levels");
        }
        Ast.TypeRef generic = bindings.get(ref.name());
        if (generic != null) {
            if (!ref.arguments().isEmpty() || ref.inferArguments()) {
                throw new IllegalArgumentException("generic parameter '" + ref.name()
                        + "' cannot be applied as a type constructor");
            }
            return generic;
        }
        // A foreign alias body may reference only its own declared generic
        // parameters. Never inherit generics from the exported alias: doing so
        // would let an unbound private alias capture a caller's T accidentally.
        Ast.TypeAliasDecl alias = localAliases.get(ref.name());
        if (alias != null) {
            if (ref.inferArguments() || alias.genericParameters().size() != ref.arguments().size()) {
                throw new IllegalArgumentException("linked alias '" + alias.name()
                        + "' expects " + alias.genericParameters().size() + " arguments");
            }
            // Nested instantiations such as Box<Box<int>> are acyclic.
            // Resolve their arguments in the caller's lexical environment
            // before adding the callee alias to the recursion stack.
            Map<String, Ast.TypeRef> applied = new HashMap<>();
            for (int i = 0; i < alias.genericParameters().size(); i++) {
                applied.put(alias.genericParameters().get(i),
                        expand(ref.arguments().get(i), localAliases, bindings,
                                visiting, depth + 1, budget));
            }
            if (!visiting.add(alias.name())) {
                throw new IllegalArgumentException("imported type alias cycle involving '" + alias.name() + "'");
            }
            try {
                return expand(alias.target(), localAliases, applied,
                        visiting, depth + 1, budget);
            } finally {
                visiting.remove(alias.name());
            }
        }

        // Synthetic AST record/tuple/union/borrow forms have '$' prefixes and
        // carry only recursively checked subtypes. No user-defined identifier
        // can inject itself into this reserved compiler namespace.
        if (!(BUILTIN_TYPES.contains(ref.name()) || compilerGeneratedTypeRef(ref.name()))) {
            throw new IllegalArgumentException("imported alias target '" + ref.name()
                    + "' lacks a verified nominal/public ABI; cannot treat it as opaque");
        }
        List<Ast.TypeRef> parts = ref.arguments().stream()
                .map(arg -> expand(arg, localAliases, bindings,
                        visiting, depth + 1, budget)).toList();
        return new Ast.TypeRef(ref.name(), parts, ref.inferArguments());
    }
}
