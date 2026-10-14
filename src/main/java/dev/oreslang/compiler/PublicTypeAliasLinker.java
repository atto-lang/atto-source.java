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
 * A linker-verified, transparent alias expander. Each alias body is resolved in
 * its defining file/module, never in the importing caller's lexical namespace.
 * Public type names may cross files; private helper aliases remain private
 * names while their fully checked structural representations are expanded.
 */
final class PublicTypeAliasLinker {
    private static final int MAX_EXPANSION_DEPTH = 64;
    private static final int MAX_EXPANSION_NODES = 16_384;

    private static final Set<String> BUILTIN_TYPES = Set.of(
            "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64",
            "int", "uint", "bigint", "f32", "f64", "float", "decimal",
            "complex64", "complex128", "complex", "bool", "Bool", "boolean",
            "string", "String", "void", "null",
            "Tuple", "Array", "List", "Vector", "Slice", "FixedArray", "FixedList",
            "Option", "Result", "Future", "Iterator", "AsyncIterator",
            "Generator", "AsyncGenerator", "Channel", "Fnc", "Function");

    private static final class Budget {
        int remaining = MAX_EXPANSION_NODES;
        void consume() {
            if (--remaining < 0) {
                throw new IllegalArgumentException("imported type alias expansion exceeds "
                        + MAX_EXPANSION_NODES + " nodes");
            }
        }
    }

    /** The file and lexical module where an alias body was declared. */
    private record Scope(String unitId, String moduleName,
                         Map<String, Ast.TypeAliasDecl> aliases) {
        String identity(Ast.TypeAliasDecl alias) {
            return unitId + "\u0000" + moduleName + "\u0000" + alias.name();
        }
    }
    private record ResolvedAlias(Ast.TypeAliasDecl declaration, Scope scope) { }
    private record Context(Map<String, Ast.Program> programs,
                           Map<String, Map<String, String>> resolutions) { }

    private PublicTypeAliasLinker() { }

    static Map<String, Ast.TypeAliasDecl> link(
            String importerUnit,
            Ast.Program importer,
            Map<String, Ast.Program> parsed,
            Map<String, Map<String, String>> importResolutions) {
        Context context = new Context(parsed, importResolutions);
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

            for (String name : use.names()) {
                ResolvedAlias selected = exportedAlias(sourceId, name, context);
                if (selected == null) {
                    // TYPES may import interfaces too; those cannot be
                    // fabricated into structural alias witnesses.
                    if (use.kind() == Ast.ImportKind.TYPE) {
                        throw new IllegalArgumentException("public type alias '" + name
                                + "' could not be linked from " + sourceId);
                    }
                    continue;
                }
                Ast.TypeAliasDecl declaration = selected.declaration();
                Map<String, Ast.TypeRef> bindings = genericBindings(declaration);
                Set<String> visiting = new HashSet<>();
                visiting.add(selected.scope().identity(declaration));
                Ast.TypeRef expanded = expand(declaration.target(), selected.scope(),
                        context, bindings, visiting, 0, new Budget());
                String localName = ImportRules.localName(use, name);
                Ast.TypeAliasDecl exported = new Ast.TypeAliasDecl(localName,
                        Ast.Visibility.PUBLIC, declaration.genericParameters(), expanded);
                if (linked.putIfAbsent(localName, exported) != null) {
                    throw new IllegalArgumentException("duplicate imported alias '" + localName + "'");
                }
            }
        }
        return Map.copyOf(linked);
    }

    private static Map<String, Ast.TypeRef> genericBindings(Ast.TypeAliasDecl alias) {
        Map<String, Ast.TypeRef> bindings = new HashMap<>();
        for (String generic : alias.genericParameters()) {
            if (bindings.putIfAbsent(generic, Ast.TypeRef.simple(generic)) != null) {
                throw new IllegalArgumentException("duplicate imported generic '" + generic + "'");
            }
        }
        return bindings;
    }

    /** Resolve an explicitly exported alias, never a private or ambiguous name. */
    private static ResolvedAlias exportedAlias(String sourceId, String name, Context ctx) {
        Ast.Program program = ctx.programs().get(sourceId);
        if (program == null) {
            throw new IllegalArgumentException("missing linked alias source " + sourceId);
        }
        ResolvedAlias selected = null;
        for (Ast.ModuleDecl module : program.modules()) {
            Scope scope = scope(sourceId, module);
            Ast.TypeAliasDecl candidate = scope.aliases().get(name);
            if (candidate != null && candidate.visibility() == Ast.Visibility.PUBLIC) {
                if (selected != null) {
                    throw new IllegalArgumentException("ambiguous public alias '" + name
                            + "' in " + sourceId);
                }
                selected = new ResolvedAlias(candidate, scope);
            }
        }
        return selected;
    }

    private static Scope scope(String unitId, Ast.ModuleDecl module) {
        Map<String, Ast.TypeAliasDecl> aliases = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.TypeAliasDecl alias
                    && aliases.putIfAbsent(alias.name(), alias) != null) {
                throw new IllegalArgumentException("ambiguous aliases in " + unitId
                        + ", module " + module.name() + ": " + alias.name());
            }
        }
        return new Scope(unitId, module.name(), Map.copyOf(aliases));
    }

    /**
     * Find an imported TYPE/TYPES alias in the defining file. An alias may be
     * renamed using `import type Foo as Bar`; only Bar is visible in that
     * file, and the actual target must be public in the resolved source unit.
     */
    private static ResolvedAlias importedAlias(String localName, Scope caller, Context ctx) {
        Ast.Program program = ctx.programs().get(caller.unitId());
        if (program == null) throw new IllegalArgumentException("missing alias owner " + caller.unitId());
        ResolvedAlias result = null;
        for (Ast.ImportDecl use : program.imports()) {
            if (use.wildcard() || (use.kind() != Ast.ImportKind.TYPE
                    && use.kind() != Ast.ImportKind.TYPES)) continue;
            for (String name : use.names()) {
                if (!ImportRules.localName(use, name).equals(localName)) continue;
                String importedUnit = ImportGraph.resolveImportUnitId(caller.unitId(),
                        use, ctx.programs().keySet(), ctx.resolutions());
                if (importedUnit == null) {
                    throw new IllegalArgumentException("imported type '" + localName
                            + "' has no verified source in " + caller.unitId());
                }
                ResolvedAlias candidate = exportedAlias(importedUnit, name, ctx);
                if (candidate == null) {
                    throw new IllegalArgumentException("imported type '" + localName
                            + "' has no verified public alias in " + importedUnit);
                }
                if (result != null) {
                    throw new IllegalArgumentException("ambiguous imported alias '" + localName
                            + "' in " + caller.unitId());
                }
                result = candidate;
            }
        }
        return result;
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
            Ast.TypeRef ref, Scope scope, Context ctx,
            Map<String, Ast.TypeRef> bindings,
            Set<String> visiting, int depth, Budget budget) {
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

        Ast.TypeAliasDecl alias = scope.aliases().get(ref.name());
        ResolvedAlias target = alias != null
                ? new ResolvedAlias(alias, scope)
                : importedAlias(ref.name(), scope, ctx);
        if (target != null) {
            alias = target.declaration();
            if (ref.inferArguments() || alias.genericParameters().size() != ref.arguments().size()) {
                throw new IllegalArgumentException("linked alias '" + alias.name()
                        + "' expects " + alias.genericParameters().size() + " arguments");
            }

            // The caller resolves arguments in the caller's own lexical scope.
            // The callee resolves its body only in its declaring scope.
            Map<String, Ast.TypeRef> applied = new HashMap<>();
            for (int i = 0; i < alias.genericParameters().size(); i++) {
                applied.put(alias.genericParameters().get(i),
                        expand(ref.arguments().get(i), scope, ctx, bindings,
                                visiting, depth + 1, budget));
            }
            String identity = target.scope().identity(alias);
            if (!visiting.add(identity)) {
                throw new IllegalArgumentException("imported type alias cycle involving '"
                        + alias.name() + "' in " + target.scope().unitId());
            }
            try {
                return expand(alias.target(), target.scope(), ctx, applied,
                        visiting, depth + 1, budget);
            } finally {
                visiting.remove(identity);
            }
        }
        if (!BUILTIN_TYPES.contains(ref.name()) && !compilerGeneratedTypeRef(ref.name())) {
            throw new IllegalArgumentException("imported alias target '" + ref.name()
                    + "' lacks a verified nominal/public ABI; cannot treat it as opaque");
        }
        List<Ast.TypeRef> parts = ref.arguments().stream()
                .map(arg -> expand(arg, scope, ctx, bindings,
                        visiting, depth + 1, budget)).toList();
        return new Ast.TypeRef(ref.name(), parts, ref.inferArguments());
    }
}
