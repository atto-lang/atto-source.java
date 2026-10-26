package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves source-level explicit exports after a namespace's declarations have
 * been fully collected.
 *
 * <p>This first Java slice is deliberately source-semantic only. It does not
 * create JVM host exports, Native Image exports, Wasm exports, or native linker
 * symbols.</p>
 */
public final class ExplicitExportResolver {
    private ExplicitExportResolver() { }

    public enum Origin { IMPLICIT_PUB, EXPLICIT_EXPORT }

    public record ExportBinding(
            String ownerNamespace,
            String localSymbolId,
            Ast.ExportKind localKind,
            String localName,
            String externalName,
            Ast.ExportScope scope,
            Origin origin,
            List<Integer> declarationIndices) {
        public ExportBinding {
            Objects.requireNonNull(ownerNamespace);
            Objects.requireNonNull(localSymbolId);
            Objects.requireNonNull(localKind);
            Objects.requireNonNull(localName);
            Objects.requireNonNull(externalName);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(origin);
            declarationIndices = List.copyOf(declarationIndices);
            if (declarationIndices.isEmpty()) {
                throw new IllegalArgumentException("export binding must reference at least one declaration");
            }
        }
    }

    public record Result(Map<String, List<ExportBinding>> bindingsByNamespace) {
        public Result {
            LinkedHashMap<String, List<ExportBinding>> copy = new LinkedHashMap<>();
            bindingsByNamespace.forEach((name, bindings) ->
                    copy.put(name, List.copyOf(bindings)));
            bindingsByNamespace = java.util.Collections.unmodifiableMap(copy);
        }

        public List<ExportBinding> bindings(String namespace) {
            return bindingsByNamespace.getOrDefault(namespace, List.of());
        }
    }

    public static Result resolve(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        LinkedHashMap<String, List<ExportBinding>> resolved = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            resolved.put(module.name(), resolveModule(module));
        }
        return new Result(resolved);
    }

    private static List<ExportBinding> resolveModule(Ast.ModuleDecl module) {
        List<Ast.Decl> declarations = module.declarations();
        LinkedHashMap<String, List<Integer>> byName = new LinkedHashMap<>();
        for (int i = 0; i < declarations.size(); i++) {
            byName.computeIfAbsent(declarationName(declarations.get(i)), ignored -> new ArrayList<>()).add(i);
        }

        LinkedHashMap<String, ExportBinding> external = new LinkedHashMap<>();

        // Declaration-site pub creates the same binding table used by explicit export.
        LinkedHashMap<String, List<Integer>> publicFamilies = new LinkedHashMap<>();
        for (int i = 0; i < declarations.size(); i++) {
            Ast.Decl decl = declarations.get(i);
            if (declarationVisibility(decl) != Ast.Visibility.PUBLIC) continue;
            Ast.ExportKind kind = declarationKind(decl);
            String name = declarationName(decl);
            String key = kind + "\0" + name;
            publicFamilies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }
        for (List<Integer> family : publicFamilies.values()) {
            Ast.Decl first = declarations.get(family.getFirst());
            addBinding(
                    external,
                    bindingFor(module, declarations, family, declarationKind(first),
                            declarationName(first), declarationName(first),
                            Ast.ExportScope.PUBLIC, Origin.IMPLICIT_PUB));
        }

        for (Ast.ExportDecl export : module.exports()) {
            if (export.scope() != Ast.ExportScope.PUBLIC) {
                throw semantic("Java explicit exports currently support public scope only; "
                        + "pub[file]/pub[pkg] parity is tracked separately");
            }

            for (Ast.ExportItem item : export.items()) {
                if (item.symbolLiteral()) {
                    throw semantic("Symbol declaration export resolution is not implemented on Java main yet; "
                            + "the parser preserves the v1 syntax but must fail closed before lowering");
                }

                List<Integer> named = byName.getOrDefault(item.localName(), List.of());
                if (named.isEmpty()) {
                    throw semantic("unknown export target '" + item.localName() + "'");
                }

                List<Integer> candidates = filterByKind(declarations, named, export.kind());
                if (candidates.isEmpty()) {
                    if (export.kind() == Ast.ExportKind.TRAIT) {
                        throw semantic("typed trait export requires declaration-kind provenance not yet retained "
                                + "by the current Java InterfaceDecl AST");
                    }
                    if (export.kind() == Ast.ExportKind.MODULE
                            || export.kind() == Ast.ExportKind.STRUCT
                            || export.kind() == Ast.ExportKind.SYMBOL
                            || export.kind() == Ast.ExportKind.MODEL) {
                        throw semantic("export kind '" + export.kind().name().toLowerCase()
                                + "' is parsed but not yet represented as a resolvable Java top-level declaration");
                    }
                    throw semantic("export kind mismatch for '" + item.localName() + "'");
                }

                Ast.ExportKind resolvedKind = declarationKind(declarations.get(candidates.getFirst()));
                for (int index : candidates) {
                    if (declarationKind(declarations.get(index)) != resolvedKind) {
                        throw semantic("ambiguous untyped export target '" + item.localName()
                                + "'; use a typed export");
                    }
                }

                if (export.kind() == Ast.ExportKind.INFERRED) {
                    long distinctKinds = named.stream()
                            .map(index -> declarationKind(declarations.get(index)))
                            .distinct()
                            .count();
                    if (distinctKinds != 1) {
                        throw semantic("ambiguous untyped export target '" + item.localName()
                                + "'; use a typed export");
                    }
                    candidates = List.copyOf(named);
                    resolvedKind = declarationKind(declarations.get(candidates.getFirst()));
                }

                if (module.explicitlyPrivateNames().contains(item.localName())) {
                    throw semantic("explicitly private declaration '" + item.localName()
                            + "' cannot be exported");
                }

                // Named-module declaration visibility provenance is not yet retained by
                // Java main. Do not guess whether PRIVATE means default-private or an
                // explicit private modifier.
                if (!module.name().equals(dev.oreslang.parser.Parser.ROOT_MODULE)) {
                    for (int index : candidates) {
                        if (declarationVisibility(declarations.get(index)) == Ast.Visibility.PRIVATE) {
                            throw semantic("named-module export of private-by-default declaration '"
                                    + item.localName()
                                    + "' requires visibility-origin metadata before Java can distinguish "
                                    + "default-private from explicit-private");
                        }
                    }
                }

                Ast.Visibility familyVisibility = declarationVisibility(declarations.get(candidates.getFirst()));
                for (int index : candidates) {
                    if (declarationVisibility(declarations.get(index)) != familyVisibility) {
                        throw semantic("overload family '" + item.localName()
                                + "' has incompatible declaration visibility");
                    }
                }

                String externalName = item.effectiveExternalName();
                addBinding(
                        external,
                        bindingFor(module, declarations, candidates, resolvedKind,
                                item.localName(), externalName,
                                export.scope(), Origin.EXPLICIT_EXPORT));
            }
        }

        return List.copyOf(external.values());
    }

    private static List<Integer> filterByKind(
            List<Ast.Decl> declarations,
            List<Integer> named,
            Ast.ExportKind requested) {
        if (requested == Ast.ExportKind.INFERRED) return List.copyOf(named);
        ArrayList<Integer> result = new ArrayList<>();
        for (int index : named) {
            if (declarationKind(declarations.get(index)) == requested) result.add(index);
        }
        return List.copyOf(result);
    }

    private static ExportBinding bindingFor(
            Ast.ModuleDecl module,
            List<Ast.Decl> declarations,
            List<Integer> declarationIndices,
            Ast.ExportKind kind,
            String localName,
            String externalName,
            Ast.ExportScope scope,
            Origin origin) {
        String localSymbolId = module.name() + "::" + kind.name().toLowerCase() + "::" + localName;
        return new ExportBinding(
                module.name(),
                localSymbolId,
                kind,
                localName,
                externalName,
                scope,
                origin,
                declarationIndices);
    }

    private static void addBinding(
            Map<String, ExportBinding> external,
            ExportBinding binding) {
        ExportBinding previous = external.get(binding.externalName());
        if (previous == null) {
            external.put(binding.externalName(), binding);
            return;
        }

        boolean sameLogicalSymbol =
                previous.localSymbolId().equals(binding.localSymbolId())
                        && previous.scope() == binding.scope();
        if (sameLogicalSymbol) {
            // Inline pub + identical explicit export and exact duplicate explicit
            // exports are idempotent. Preserve the implicit binding when present.
            if (previous.origin() == Origin.IMPLICIT_PUB) return;
            external.put(binding.externalName(), previous);
            return;
        }

        throw semantic("export name collision for external name '" + binding.externalName() + "'");
    }

    private static Ast.ExportKind declarationKind(Ast.Decl decl) {
        if (decl instanceof Ast.ClassDecl d) {
            return d.actorKind() == Ast.ActorKind.NONE
                    ? Ast.ExportKind.CLASS
                    : Ast.ExportKind.ACTOR;
        }
        if (decl instanceof Ast.FunctionDecl d) {
            return d.kind() == Ast.CallableKind.FNC
                    ? Ast.ExportKind.FUNCTION
                    : Ast.ExportKind.ROUTINE;
        }
        if (decl instanceof Ast.InterfaceDecl d) {
            return d.moduleContract()
                    ? Ast.ExportKind.CONTRACT
                    : Ast.ExportKind.INTERFACE;
        }
        if (decl instanceof Ast.TypeAliasDecl) return Ast.ExportKind.TYPE;
        if (decl instanceof Ast.FieldDecl d) {
            return switch (d.bindingKind()) {
                case CONST -> Ast.ExportKind.CONST;
                case VAL -> Ast.ExportKind.VAL;
                case LET -> Ast.ExportKind.LET;
            };
        }
        throw semantic("unsupported declaration class " + decl.getClass().getName());
    }

    private static String declarationName(Ast.Decl decl) {
        if (decl instanceof Ast.ClassDecl d) return d.name();
        if (decl instanceof Ast.FunctionDecl d) return d.name();
        if (decl instanceof Ast.InterfaceDecl d) return d.name();
        if (decl instanceof Ast.TypeAliasDecl d) return d.name();
        if (decl instanceof Ast.FieldDecl d) return d.name();
        throw semantic("unsupported declaration class " + decl.getClass().getName());
    }

    private static Ast.Visibility declarationVisibility(Ast.Decl decl) {
        if (decl instanceof Ast.ClassDecl d) return d.visibility();
        if (decl instanceof Ast.FunctionDecl d) return d.visibility();
        if (decl instanceof Ast.InterfaceDecl d) return d.visibility();
        if (decl instanceof Ast.TypeAliasDecl d) return d.visibility();
        if (decl instanceof Ast.FieldDecl d) return d.visibility();
        throw semantic("unsupported declaration class " + decl.getClass().getName());
    }

    private static IllegalArgumentException semantic(String message) {
        return new IllegalArgumentException("Oreslang export error: " + message);
    }
}
