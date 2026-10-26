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
            if (resolved.putIfAbsent(module.name(), resolveModule(module)) != null) {
                throw semantic("duplicate export namespace '" + module.name() + "'");
            }
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
            Ast.ExportKind kind = declarationKind(first);
            // A public overload publishes the logical family, including
            // unmodified overloads. PRIVATE in this AST also means omitted
            // visibility; only retained explicit-private provenance forbids it.
            List<Integer> completeFamily = filterByKind(
                    declarations, byName.get(declarationName(first)), kind);
            validateFamily(module, completeFamily, kind, declarationName(first));
            addBinding(
                    external,
                    bindingFor(module, declarations, completeFamily, kind,
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

                validateFamily(module, candidates, resolvedKind, item.localName());

                String externalName = item.effectiveExternalName();
                addBinding(
                        external,
                        bindingFor(module, declarations, candidates, resolvedKind,
                                item.localName(), externalName,
                                export.scope(), Origin.EXPLICIT_EXPORT));
            }
        }

        List<ExportBinding> bindings = List.copyOf(external.values());
        validateLocalApiVisibility(module, declarations, bindings);
        return bindings;
    }

    private static void validateFamily(
            Ast.ModuleDecl module,
            List<Integer> family, Ast.ExportKind kind, String name) {
        if (module.explicitlyPrivateSymbols().contains(kind.name() + "\0" + name)) {
            throw semantic("explicitly private declaration '" + name + "' cannot be exported");
        }
        if (family.size() > 1
                && kind != Ast.ExportKind.FUNCTION && kind != Ast.ExportKind.ROUTINE) {
            throw semantic("duplicate non-callable export target '" + name + "'");
        }
        // PUBLIC plus unmodified (AST PRIVATE) overloads is compatible.
        // Scoped visibility remains rejected before this point; extending
        // this AST requires checking all explicit scope provenance here too.
    }

    private static void validateLocalApiVisibility(
            Ast.ModuleDecl module,
            List<Ast.Decl> declarations,
            List<ExportBinding> bindings) {
        java.util.Set<String> publicTypeSymbols = new java.util.HashSet<>();
        for (ExportBinding binding : bindings) {
            if (binding.scope() != Ast.ExportScope.PUBLIC || !isTypeKind(binding.localKind())) continue;
            publicTypeSymbols.add(binding.localKind().name() + "\0" + binding.localName());
        }

        LinkedHashMap<String, List<Ast.ExportKind>> localTypesByName = new LinkedHashMap<>();
        for (Ast.Decl declaration : declarations) {
            Ast.ExportKind kind = declarationKind(declaration);
            if (!isTypeKind(kind)) continue;
            localTypesByName.computeIfAbsent(declarationName(declaration), ignored -> new ArrayList<>())
                    .add(kind);
        }

        for (ExportBinding binding : bindings) {
            if (binding.scope() != Ast.ExportScope.PUBLIC) continue;
            for (int index : binding.declarationIndices()) {
                Ast.Decl declaration = declarations.get(index);
                for (SignatureType type : exportedSignatureTypes(declaration)) {
                    validateTypeRefVisibility(
                            module, type.type(), localTypesByName, publicTypeSymbols,
                            binding.externalName(), type.genericNames());
                }
            }
        }
    }

    private static boolean isTypeKind(Ast.ExportKind kind) {
        return kind == Ast.ExportKind.CLASS
                || kind == Ast.ExportKind.ACTOR
                || kind == Ast.ExportKind.INTERFACE
                || kind == Ast.ExportKind.CONTRACT
                || kind == Ast.ExportKind.TYPE
                || kind == Ast.ExportKind.TRAIT
                || kind == Ast.ExportKind.STRUCT
                || kind == Ast.ExportKind.MODEL;
    }

    private record SignatureType(Ast.TypeRef type, java.util.Set<String> genericNames) {
        private SignatureType {
            genericNames = java.util.Set.copyOf(genericNames);
        }
    }

    private static java.util.Set<String> genericNames(
            List<String> enclosing, List<String> local) {
        java.util.Set<String> names = new java.util.HashSet<>(enclosing);
        names.addAll(local);
        return java.util.Set.copyOf(names);
    }

    private static void addSignature(
            List<SignatureType> target, Ast.TypeRef type, java.util.Set<String> names) {
        if (type != null) target.add(new SignatureType(type, names));
    }

    private static List<SignatureType> exportedSignatureTypes(Ast.Decl declaration) {
        ArrayList<SignatureType> result = new ArrayList<>();
        if (declaration instanceof Ast.FunctionDecl d) {
            java.util.Set<String> defaults = new java.util.HashSet<>();
            for (Ast.GenericParameter generic : d.genericSpecs()) {
                addSignature(result, generic.defaultType(), defaults);
                defaults.add(generic.name());
            }
            java.util.Set<String> names = java.util.Set.copyOf(d.genericParameters());
            for (Ast.Param parameter : d.parameters()) addSignature(result, parameter.type(), names);
            addSignature(result, d.returnType(), names);
            addEffectTypes(result, d.effects(), names);
            return List.copyOf(result);
        }
        if (declaration instanceof Ast.FieldDecl d) {
            addSignature(result, d.type(), java.util.Set.of());
            return List.copyOf(result);
        }
        if (declaration instanceof Ast.TypeAliasDecl d) {
            addSignature(result, d.target(), java.util.Set.copyOf(d.genericParameters()));
            return List.copyOf(result);
        }
        if (declaration instanceof Ast.ClassDecl d) {
            java.util.Set<String> names = java.util.Set.copyOf(d.genericParameters());
            for (Ast.TypeRef type : d.parents()) addSignature(result, type, names);
            for (Ast.TypeRef type : d.interfaces()) addSignature(result, type, names);
            for (Ast.FieldDecl field : d.fields()) {
                if (field.visibility() == Ast.Visibility.PUBLIC) addSignature(result, field.type(), names);
            }
            if (d.constructor() != null
                    && d.constructor().visibility() == Ast.Visibility.PUBLIC) {
                for (Ast.Param parameter : d.constructor().parameters()) addSignature(result, parameter.type(), names);
            }
            for (Ast.MethodDecl method : d.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                java.util.Set<String> methodNames = genericNames(d.genericParameters(), method.genericParameters());
                addSignature(result, method.explicitReceiverType(), methodNames);
                for (Ast.Param parameter : method.parameters()) addSignature(result, parameter.type(), methodNames);
                addSignature(result, method.returnType(), methodNames);
                addEffectTypes(result, method.effects(), methodNames);
            }
            return List.copyOf(result);
        }
        if (declaration instanceof Ast.InterfaceDecl d) {
            java.util.Set<String> names = java.util.Set.copyOf(d.genericParameters());
            for (Ast.TypeRef type : d.parents()) addSignature(result, type, names);
            for (Ast.InterfaceMember member : d.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    java.util.Set<String> memberNames = genericNames(d.genericParameters(), fn.genericParameters());
                    for (Ast.Param parameter : fn.parameters()) addSignature(result, parameter.type(), memberNames);
                    addSignature(result, fn.returnType(), memberNames);
                    addEffectTypes(result, fn.effects(), memberNames);
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    addSignature(result, field.type(), names);
                }
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    private static void addEffectTypes(
            List<SignatureType> target,
            Ast.EffectSpec effects, java.util.Set<String> names) {
        for (Ast.TypeRef type : effects.throwsTypes()) addSignature(target, type, names);
        for (Ast.TypeRef type : effects.raisesTypes()) addSignature(target, type, names);
        for (Ast.TypeRef type : effects.panicsTypes()) addSignature(target, type, names);
    }

    private static void validateTypeRefVisibility(
            Ast.ModuleDecl module,
            Ast.TypeRef type,
            Map<String, List<Ast.ExportKind>> localTypesByName,
            java.util.Set<String> publicTypeSymbols,
            String exportedName, java.util.Set<String> genericNames) {
        if (type == null) return;

        List<Ast.ExportKind> localKinds = localTypesByName.get(type.name());
        if (!genericNames.contains(type.name()) && localKinds != null && !localKinds.isEmpty()) {
            long exposed = localKinds.stream()
                    .filter(kind -> publicTypeSymbols.contains(kind.name() + "\0" + type.name()))
                    .count();
            if (exposed == 0) {
                throw semantic("public export '" + exportedName
                        + "' leaks non-public local type '" + type.name()
                        + "' from namespace '" + module.name() + "'");
            }
            if (localKinds.size() > 1 && exposed != 1) {
                throw semantic("public export '" + exportedName
                        + "' references ambiguous local type spelling '" + type.name() + "'");
            }
        }

        for (Ast.TypeRef argument : type.arguments()) {
            validateTypeRefVisibility(
                    module, argument, localTypesByName, publicTypeSymbols, exportedName, genericNames);
        }
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
        String localSymbolId = module.name() + "::" + kind.name().toLowerCase(java.util.Locale.ROOT) + "::" + localName;
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
