package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves source export metadata only after a namespace's complete local symbol
 * table exists. This pass never changes JVM/native linkage.
 */
public final class ExportResolver {
    private ExportResolver() { }

    public enum Origin { IMPLICIT_PUB, EXPLICIT_EXPORT }

    public record ExportBinding(
            String ownerNamespaceId,
            String localSymbolId,
            Ast.ExportKind localKind,
            String externalName,
            Ast.ExportScope scope,
            Origin origin,
            Ast.SourceSpan sourceSpan) {
        public ExportBinding {
            java.util.Objects.requireNonNull(ownerNamespaceId);
            java.util.Objects.requireNonNull(localSymbolId);
            java.util.Objects.requireNonNull(localKind);
            java.util.Objects.requireNonNull(externalName);
            java.util.Objects.requireNonNull(scope);
            java.util.Objects.requireNonNull(origin);
        }
    }

    public record Result(Ast.Program program, List<ExportBinding> bindings) {
        public Result {
            bindings = List.copyOf(bindings);
        }
    }

    private record Candidate(String name, Ast.ExportKind kind, List<Ast.Decl> declarations) {
        Candidate {
            declarations = List.copyOf(declarations);
        }

        Ast.Decl representative() {
            return declarations.isEmpty() ? null : declarations.getFirst();
        }

        void validateFamilyVisibility() {
            if (kind != Ast.ExportKind.FUNCTION || declarations.size() < 2) return;
            Ast.Visibility first = visibilityOf(declarations.getFirst());
            for (Ast.Decl declaration : declarations) {
                if (visibilityOf(declaration) != first) {
                    throw new IllegalArgumentException(
                            "[EXPORT_OVERLOAD_VISIBILITY_CONFLICT] overload family " + name
                                    + " mixes explicit visibility states");
                }
            }
        }

        String symbolId(String owner) {
            return owner + "::" + kind.name().toLowerCase(java.util.Locale.ROOT) + "::" + name;
        }
    }

    public static Result resolve(Ast.Program program) {
        List<ExportBinding> bindings = new ArrayList<>();
        Set<String> importedRootNames = importedRootNames(program.imports());

        for (Ast.ModuleDecl module : program.modules()) {
            boolean root = module.name().equals(Parser.ROOT_MODULE);
            Map<String, List<Candidate>> byName = collectCandidates(program, module, root);
            resolveNamespace(
                    module.name(),
                    module.exports(),
                    byName,
                    root ? importedRootNames : Set.of(),
                    bindings);
        }

        return new Result(program, bindings);
    }

    private static Set<String> importedRootNames(List<Ast.ImportDecl> imports) {
        Set<String> result = new LinkedHashSet<>();
        for (Ast.ImportDecl imp : imports) {
            if (imp.namespace() != null && !imp.namespace().isBlank()) {
                result.add(imp.namespace());
            } else {
                result.addAll(imp.names());
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, List<Candidate>> collectCandidates(
            Ast.Program program, Ast.ModuleDecl module, boolean root) {
        Map<String, LinkedHashMap<Ast.ExportKind, List<Ast.Decl>>> grouped = new LinkedHashMap<>();

        for (Ast.Decl decl : module.declarations()) {
            String name = declarationName(decl);
            Ast.ExportKind kind = declarationKind(decl);
            grouped.computeIfAbsent(name, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(kind, ignored -> new ArrayList<>())
                    .add(decl);
        }

        if (root) {
            for (Ast.ModuleDecl child : program.modules()) {
                if (child.name().equals(Parser.ROOT_MODULE)) continue;
                grouped.computeIfAbsent(child.name(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(Ast.ExportKind.MODULE, ignored -> new ArrayList<>());
            }
        }

        Map<String, List<Candidate>> result = new LinkedHashMap<>();
        for (var nameEntry : grouped.entrySet()) {
            List<Candidate> candidates = new ArrayList<>();
            for (var kindEntry : nameEntry.getValue().entrySet()) {
                candidates.add(new Candidate(nameEntry.getKey(), kindEntry.getKey(), kindEntry.getValue()));
            }
            result.put(nameEntry.getKey(), List.copyOf(candidates));
        }
        return result;
    }

    private static void resolveNamespace(
            String owner,
            List<Ast.ExportDecl> exports,
            Map<String, List<Candidate>> byName,
            Set<String> importedNames,
            List<ExportBinding> out) {
        Map<String, ExportBinding> exactBindings = new LinkedHashMap<>();
        Map<String, ExportBinding> externalBindings = new HashMap<>();
        Map<String, Ast.ExportScope> symbolExternalScopes = new HashMap<>();

        // Inline pub is an implicit public export binding.
        for (List<Candidate> sameName : byName.values()) {
            for (Candidate candidate : sameName) {
                candidate.validateFamilyVisibility();
                Ast.Decl representative = candidate.representative();
                if (representative != null && visibilityOf(representative) == Ast.Visibility.PUBLIC) {
                    ExportBinding binding = new ExportBinding(
                            owner,
                            candidate.symbolId(owner),
                            candidate.kind(),
                            candidate.name(),
                            Ast.ExportScope.PUBLIC,
                            Origin.IMPLICIT_PUB,
                            null);
                    admit(binding, exactBindings, externalBindings, symbolExternalScopes);
                    validateApiVisibility(binding, candidate, byName);
                }
            }
        }

        for (Ast.ExportDecl export : exports) {
            for (Ast.ExportItem item : export.items()) {
                if (item.symbol()) {
                    // Symbol declarations are not represented by current Java main yet.
                    // Keep syntax parsed, but fail closed at semantic admission.
                    throw new IllegalArgumentException(
                            "[EXPORT_SYMBOL_DECLARATION_UNAVAILABLE] Java current main has no declaration AST for "
                                    + item.localName());
                }

                List<Candidate> named = byName.get(item.localName());
                if (named == null || named.isEmpty()) {
                    if (importedNames.contains(item.localName())) {
                        throw new IllegalArgumentException(
                                "[EXPORT_IMPORTED_NAME] local export cannot re-export imported name "
                                        + item.localName());
                    }
                    throw new IllegalArgumentException(
                            "[EXPORT_UNKNOWN_TARGET] no local declaration named " + item.localName());
                }

                Candidate candidate;
                if (export.kind() == null) {
                    if (named.size() != 1) {
                        throw new IllegalArgumentException(
                                "[EXPORT_AMBIGUOUS_TARGET] export " + item.localName()
                                        + " resolves to multiple local declaration families; use a typed export");
                    }
                    candidate = named.getFirst();
                } else {
                    candidate = named.stream()
                            .filter(c -> c.kind() == export.kind())
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "[EXPORT_KIND_MISMATCH] " + item.localName()
                                            + " is not a local " + export.kind().name().toLowerCase(java.util.Locale.ROOT)
                                            + " declaration"));
                }

                Ast.Decl representative = candidate.representative();
                if (representative != null
                        && visibilityOf(representative) == Ast.Visibility.PUBLIC
                        && export.scope() != Ast.ExportScope.PUBLIC) {
                    throw new IllegalArgumentException(
                            "[EXPORT_SCOPE_CONFLICT] inline pub declaration " + item.localName()
                                    + " cannot be narrowed by explicit export scope " + export.scope());
                }

                ExportBinding binding = new ExportBinding(
                        owner,
                        candidate.symbolId(owner),
                        candidate.kind(),
                        item.externalName(),
                        export.scope(),
                        Origin.EXPLICIT_EXPORT,
                        export.sourceSpan());
                admit(binding, exactBindings, externalBindings, symbolExternalScopes);
                validateApiVisibility(binding, candidate, byName);
            }
        }

        out.addAll(exactBindings.values());
    }

    private static void admit(
            ExportBinding binding,
            Map<String, ExportBinding> exactBindings,
            Map<String, ExportBinding> externalBindings,
            Map<String, Ast.ExportScope> symbolExternalScopes) {
        String symbolExternal = binding.localSymbolId() + "\u0000" + binding.externalName();
        Ast.ExportScope previousScope = symbolExternalScopes.putIfAbsent(symbolExternal, binding.scope());
        if (previousScope != null && previousScope != binding.scope()) {
            throw new IllegalArgumentException(
                    "[EXPORT_SCOPE_CONFLICT] same local symbol/external name exported at conflicting scopes: "
                            + binding.externalName());
        }

        ExportBinding previousExternal = externalBindings.putIfAbsent(binding.externalName(), binding);
        if (previousExternal != null
                && !previousExternal.localSymbolId().equals(binding.localSymbolId())) {
            throw new IllegalArgumentException(
                    "[EXPORT_ALIAS_COLLISION] external export name " + binding.externalName()
                            + " is already bound to a different local symbol");
        }

        String exactKey = binding.localSymbolId() + "\u0000" + binding.externalName() + "\u0000" + binding.scope();
        exactBindings.putIfAbsent(exactKey, binding);
    }

    private static void validateApiVisibility(
            ExportBinding binding,
            Candidate candidate,
            Map<String, List<Candidate>> byName) {
        if (binding.scope() != Ast.ExportScope.PUBLIC) return;
        Ast.Decl decl = candidate.representative();
        if (decl == null) return;

        Set<String> referenced = new LinkedHashSet<>();
        collectPublicSignatureTypes(decl, referenced);
        for (String name : referenced) {
            List<Candidate> locals = byName.get(name);
            if (locals == null) continue;
            for (Candidate local : locals) {
                Ast.Decl localDecl = local.representative();
                if (localDecl != null && isTypeLike(local.kind())
                        && visibilityOf(localDecl) != Ast.Visibility.PUBLIC) {
                    throw new IllegalArgumentException(
                            "[EXPORT_API_LEAK] public export " + binding.externalName()
                                    + " exposes narrower local type " + name);
                }
            }
        }
    }

    private static boolean isTypeLike(Ast.ExportKind kind) {
        return kind == Ast.ExportKind.CLASS || kind == Ast.ExportKind.ACTOR
                || kind == Ast.ExportKind.INTERFACE || kind == Ast.ExportKind.TRAIT
                || kind == Ast.ExportKind.CONTRACT || kind == Ast.ExportKind.TYPE
                || kind == Ast.ExportKind.STRUCT;
    }

    private static void collectPublicSignatureTypes(Ast.Decl decl, Set<String> out) {
        if (decl instanceof Ast.FunctionDecl fn) {
            for (Ast.Param p : fn.parameters()) collectType(p.type(), out);
            collectType(fn.returnType(), out);
            return;
        }
        if (decl instanceof Ast.ClassDecl cls) {
            cls.parents().forEach(t -> collectType(t, out));
            cls.interfaces().forEach(t -> collectType(t, out));
            for (Ast.FieldDecl field : cls.fields()) {
                if (field.visibility() == Ast.Visibility.PUBLIC) collectType(field.type(), out);
            }
            if (cls.constructor() != null && cls.constructor().visibility() == Ast.Visibility.PUBLIC) {
                for (Ast.Param p : cls.constructor().parameters()) collectType(p.type(), out);
            }
            for (Ast.MethodDecl method : cls.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                for (Ast.Param p : method.parameters()) collectType(p.type(), out);
                collectType(method.returnType(), out);
            }
            return;
        }
        if (decl instanceof Ast.InterfaceDecl iface) {
            iface.parents().forEach(t -> collectType(t, out));
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    for (Ast.Param p : fn.parameters()) collectType(p.type(), out);
                    collectType(fn.returnType(), out);
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    collectType(field.type(), out);
                }
            }
            return;
        }
        if (decl instanceof Ast.TypeAliasDecl alias) {
            collectType(alias.target(), out);
            return;
        }
        if (decl instanceof Ast.FieldDecl field) {
            collectType(field.type(), out);
        }
    }

    private static void collectType(Ast.TypeRef type, Set<String> out) {
        if (type == null) return;
        String name = type.name();
        if (!name.startsWith("$") && !isPrimitive(name)) out.add(name);
        for (Ast.TypeRef arg : type.arguments()) collectType(arg, out);
    }

    private static boolean isPrimitive(String name) {
        return name.equals("void") || name.equals("bool") || name.equals("int")
                || name.equals("float") || name.equals("String") || name.equals("string");
    }

    private static String declarationName(Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) return fn.name();
        if (decl instanceof Ast.ClassDecl cls) return cls.name();
        if (decl instanceof Ast.InterfaceDecl iface) return iface.name();
        if (decl instanceof Ast.TypeAliasDecl alias) return alias.name();
        if (decl instanceof Ast.FieldDecl field) return field.name();
        throw new IllegalArgumentException("unsupported declaration in export resolver: " + decl.getClass().getName());
    }

    private static Ast.ExportKind declarationKind(Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl) return Ast.ExportKind.FUNCTION;
        if (decl instanceof Ast.ClassDecl cls) {
            return cls.actorKind() == Ast.ActorKind.NONE ? Ast.ExportKind.CLASS : Ast.ExportKind.ACTOR;
        }
        if (decl instanceof Ast.InterfaceDecl iface) {
            return switch (iface.flavor()) {
                case INTERFACE -> Ast.ExportKind.INTERFACE;
                case TRAIT -> Ast.ExportKind.TRAIT;
                case CONTRACT -> Ast.ExportKind.CONTRACT;
            };
        }
        if (decl instanceof Ast.TypeAliasDecl) return Ast.ExportKind.TYPE;
        if (decl instanceof Ast.FieldDecl field) {
            return switch (field.bindingKind()) {
                case CONST -> Ast.ExportKind.CONST;
                case VAL -> Ast.ExportKind.VAL;
                case LET -> Ast.ExportKind.LET;
            };
        }
        throw new IllegalArgumentException("unsupported declaration in export resolver: " + decl.getClass().getName());
    }

    private static Ast.Visibility visibilityOf(Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) return fn.visibility();
        if (decl instanceof Ast.ClassDecl cls) return cls.visibility();
        if (decl instanceof Ast.InterfaceDecl iface) return iface.visibility();
        if (decl instanceof Ast.TypeAliasDecl alias) return alias.visibility();
        if (decl instanceof Ast.FieldDecl field) return field.visibility();
        throw new IllegalArgumentException("unsupported declaration in export resolver: " + decl.getClass().getName());
    }
}
