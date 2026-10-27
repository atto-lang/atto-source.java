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

    private record Candidate(
            String name,
            Ast.ExportKind kind,
            List<Ast.Decl> declarations,
            Ast.Visibility namespaceVisibility) {
        Candidate {
            declarations = List.copyOf(declarations);
        }

        Candidate(String name, Ast.ExportKind kind, List<Ast.Decl> declarations) {
            this(name, kind, declarations, null);
        }

        Ast.Decl representative() {
            return declarations.isEmpty() ? null : declarations.getFirst();
        }

        void validateFamilyVisibility() {
            if (kind != Ast.ExportKind.FUNCTION && kind != Ast.ExportKind.ROUTINE) {
                if (declarations.size() > 1) {
                    throw new IllegalArgumentException(
                            "[DUPLICATE_LOCAL_SYMBOL] duplicate local " + kind.name().toLowerCase(java.util.Locale.ROOT)
                                    + " declaration " + name);
                }
                return;
            }
            if (declarations.size() < 2) return;
            java.util.EnumSet<Ast.Visibility> explicit = java.util.EnumSet.noneOf(Ast.Visibility.class);
            for (Ast.Decl declaration : declarations) {
                Ast.Visibility visibility = visibilityOf(declaration);
                if (visibility != Ast.Visibility.PRIVATE) explicit.add(visibility);
            }
            if (explicit.size() > 1) {
                throw new IllegalArgumentException(
                        "[OVERLOAD_VISIBILITY_CONFLICT] overload family " + name
                                + " mixes incompatible explicit visibility states " + explicit);
            }

            Set<String> signatures = new LinkedHashSet<>();
            for (Ast.Decl declaration : declarations) {
                Ast.FunctionDecl fn = (Ast.FunctionDecl) declaration;
                String signature = fn.parameters().stream()
                        .map(parameter -> parameter.type().toString())
                        .collect(java.util.stream.Collectors.joining(",", "(", ")"))
                        + "->" + fn.returnType();
                if (!signatures.add(signature)) {
                    throw new IllegalArgumentException(
                            "[DUPLICATE_OVERLOAD_SIGNATURE] overload family " + name
                                    + " repeats signature " + signature);
                }
            }
        }

        Ast.Visibility effectiveVisibility() {
            if ((kind == Ast.ExportKind.FUNCTION || kind == Ast.ExportKind.ROUTINE)
                    && declarations.size() > 1) {
                Ast.Visibility effective = Ast.Visibility.PRIVATE;
                for (Ast.Decl declaration : declarations) {
                    Ast.Visibility visibility = visibilityOf(declaration);
                    if (visibility != Ast.Visibility.PRIVATE) {
                        effective = visibility;
                        break;
                    }
                }
                return effective;
            }
            if (kind == Ast.ExportKind.MODULE && namespaceVisibility != null) {
                return namespaceVisibility;
            }
            Ast.Decl representative = representative();
            return representative == null ? Ast.Visibility.PRIVATE : visibilityOf(representative);
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
                    ownerScope(module, root),
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

        Map<String, List<Candidate>> result = new LinkedHashMap<>();
        for (var nameEntry : grouped.entrySet()) {
            List<Candidate> candidates = new ArrayList<>();
            for (var kindEntry : nameEntry.getValue().entrySet()) {
                candidates.add(new Candidate(nameEntry.getKey(), kindEntry.getKey(), kindEntry.getValue()));
            }
            result.put(nameEntry.getKey(), List.copyOf(candidates));
        }
        if (root) {
            for (Ast.ModuleDecl child : program.modules()) {
                if (child.name().equals(Parser.ROOT_MODULE)) continue;
                List<Candidate> candidates = new ArrayList<>(
                        result.getOrDefault(child.name(), List.of()));
                candidates.add(new Candidate(
                        child.name(), Ast.ExportKind.MODULE, List.of(), child.visibility()));
                result.put(child.name(), List.copyOf(candidates));
            }
        }
        return result;
    }

    private static void resolveNamespace(
            String owner,
            Ast.ExportScope ownerScope,
            List<Ast.ExportDecl> exports,
            Map<String, List<Candidate>> byName,
            Set<String> importedNames,
            List<ExportBinding> out) {
        Map<String, ExportBinding> exactBindings = new LinkedHashMap<>();
        Map<String, ExportBinding> externalBindings = new HashMap<>();
        Map<String, Ast.ExportScope> symbolExternalScopes = new HashMap<>();

        for (List<Candidate> sameName : byName.values()) {
            for (Candidate candidate : sameName) {
                candidate.validateFamilyVisibility();
                Ast.ExportScope declaredScope = exportScope(candidate.effectiveVisibility());
                if (declaredScope == null) continue;
                ExportBinding binding = new ExportBinding(
                        owner,
                        candidate.symbolId(owner),
                        candidate.kind(),
                        candidate.name(),
                        capScope(declaredScope, ownerScope),
                        Origin.IMPLICIT_PUB,
                        null);
                admit(binding, exactBindings, externalBindings, symbolExternalScopes);
            }
        }

        for (Ast.ExportDecl export : exports) {
            for (Ast.ExportItem item : export.items()) {
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

                candidate.validateFamilyVisibility();
                Ast.Visibility visibility = candidate.effectiveVisibility();
                if (visibility == Ast.Visibility.EXPLICIT_PRIVATE) {
                    throw new IllegalArgumentException(
                            "[EXPORT_EXPLICIT_PRIVATE] explicitly private declaration "
                                    + item.localName() + " cannot be exported");
                }
                Ast.ExportScope declaredScope = exportScope(visibility);
                if (declaredScope != null && declaredScope != export.scope()) {
                    throw new IllegalArgumentException(
                            "[EXPORT_SCOPE_CONFLICT] declaration " + item.localName()
                                    + " has scope " + declaredScope
                                    + " but explicit export requests " + export.scope());
                }

                ExportBinding binding = new ExportBinding(
                        owner,
                        candidate.symbolId(owner),
                        candidate.kind(),
                        item.externalName(),
                        capScope(export.scope(), ownerScope),
                        Origin.EXPLICIT_EXPORT,
                        export.sourceSpan());
                admit(binding, exactBindings, externalBindings, symbolExternalScopes);
            }
        }

        List<ExportBinding> resolved = List.copyOf(exactBindings.values());
        validateApiVisibility(owner, ownerScope, resolved, byName);
        out.addAll(resolved);
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
            String owner,
            Ast.ExportScope ownerScope,
            List<ExportBinding> bindings,
            Map<String, List<Candidate>> byName) {
        Map<String, Integer> effectiveRanks = new HashMap<>();
        for (List<Candidate> sameName : byName.values()) {
            for (Candidate candidate : sameName) {
                if (!isTypeLike(candidate.kind())) continue;
                int rank = visibilityRank(candidate.effectiveVisibility(), ownerScope);
                for (ExportBinding binding : bindings) {
                    if (binding.localSymbolId().equals(candidate.symbolId(owner))) {
                        rank = Math.max(rank, scopeRank(binding.scope()));
                    }
                }
                effectiveRanks.put(candidate.symbolId(owner), rank);
            }
        }

        for (ExportBinding binding : bindings) {
            List<Candidate> named = byName.get(localName(binding.localSymbolId()));
            if (named == null) continue;
            Candidate source = named.stream()
                    .filter(candidate -> candidate.symbolId(owner).equals(binding.localSymbolId()))
                    .findFirst()
                    .orElse(null);
            if (source == null) continue;
            validateCandidateSignature(
                    owner, source, scopeRank(binding.scope()), byName,
                    effectiveRanks, new LinkedHashSet<>());
        }
    }

    private static void validateCandidateSignature(
            String owner,
            Candidate source,
            int audience,
            Map<String, List<Candidate>> byName,
            Map<String, Integer> effectiveRanks,
            Set<String> seen) {
        String sourceId = source.symbolId(owner);
        if (!seen.add(sourceId)) return;

        Set<String> referenced = new LinkedHashSet<>();
        for (Ast.Decl declaration : source.declarations()) {
            collectPublicSignatureTypes(declaration, referenced);
        }
        for (String name : referenced) {
            List<Candidate> locals = byName.get(name);
            if (locals == null) continue;
            for (Candidate target : locals) {
                if (!isTypeLike(target.kind())) continue;
                String targetId = target.symbolId(owner);
                if (effectiveRanks.getOrDefault(targetId, 0) < audience) {
                    throw new IllegalArgumentException(
                            "[EXPORT_API_LEAK] export " + source.name()
                                    + " exposes narrower local type " + name);
                }
                validateCandidateSignature(owner, target, audience, byName, effectiveRanks, seen);
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
            Set<String> generics = Set.copyOf(fn.genericParameters());
            for (Ast.GenericParameter spec : fn.genericSpecs()) collectType(spec.defaultType(), out, generics);
            for (Ast.Param p : fn.parameters()) collectType(p.type(), out, generics);
            collectType(fn.returnType(), out, generics);
            collectEffects(fn.effects(), out, generics);
            collectAnnotations(fn.annotations(), out, generics);
            return;
        }
        if (decl instanceof Ast.ClassDecl cls) {
            Set<String> classGenerics = Set.copyOf(cls.genericParameters());
            cls.parents().forEach(t -> collectType(t, out, classGenerics));
            cls.interfaces().forEach(t -> collectType(t, out, classGenerics));
            for (Ast.FieldDecl field : cls.fields()) {
                if (field.visibility() == Ast.Visibility.PUBLIC) {
                    collectType(field.type(), out, classGenerics);
                    collectAnnotations(field.annotations(), out, classGenerics);
                }
            }
            if (cls.constructor() == null) {
                // The legacy synthesized constructor is externally callable for
                // an exported class and accepts every instance field in storage
                // order, regardless of the field's own visibility. Its parameter
                // types are therefore part of the exported API surface.
                for (Ast.FieldDecl field : cls.fields()) {
                    collectType(field.type(), out, classGenerics);
                }
            } else if (cls.constructor().visibility() == Ast.Visibility.PUBLIC) {
                for (Ast.Param p : cls.constructor().parameters()) collectType(p.type(), out, classGenerics);
                collectAnnotations(cls.constructor().annotations(), out, classGenerics);
            }
            for (Ast.MethodDecl method : cls.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                Set<String> methodGenerics = new LinkedHashSet<>(classGenerics);
                methodGenerics.addAll(method.genericParameters());
                collectType(method.explicitReceiverType(), out, methodGenerics);
                for (Ast.Param p : method.parameters()) collectType(p.type(), out, methodGenerics);
                collectType(method.returnType(), out, methodGenerics);
                collectEffects(method.effects(), out, methodGenerics);
                collectAnnotations(method.annotations(), out, methodGenerics);
            }
            return;
        }
        if (decl instanceof Ast.InterfaceDecl iface) {
            Set<String> interfaceGenerics = Set.copyOf(iface.genericParameters());
            iface.parents().forEach(t -> collectType(t, out, interfaceGenerics));
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    Set<String> memberGenerics = new LinkedHashSet<>(interfaceGenerics);
                    memberGenerics.addAll(fn.genericParameters());
                    for (Ast.Param p : fn.parameters()) collectType(p.type(), out, memberGenerics);
                    collectType(fn.returnType(), out, memberGenerics);
                    collectEffects(fn.effects(), out, memberGenerics);
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    collectType(field.type(), out, interfaceGenerics);
                }
            }
            return;
        }
        if (decl instanceof Ast.TypeAliasDecl alias) {
            collectType(alias.target(), out, Set.copyOf(alias.genericParameters()));
            return;
        }
        if (decl instanceof Ast.FieldDecl field) {
            collectType(field.type(), out, Set.of());
            collectAnnotations(field.annotations(), out, Set.of());
        }
    }

    private static void collectAnnotations(
            List<Ast.Annotation> annotations,
            Set<String> out,
            Set<String> generics) {
        for (Ast.Annotation annotation : annotations) {
            for (Ast.TypeRef argument : annotation.arguments()) {
                collectType(argument, out, generics);
            }
        }
    }

    private static void collectEffects(Ast.EffectSpec effects, Set<String> out, Set<String> generics) {
        effects.throwsTypes().forEach(type -> collectType(type, out, generics));
        effects.raisesTypes().forEach(type -> collectType(type, out, generics));
        effects.panicsTypes().forEach(type -> collectType(type, out, generics));
    }

    private static void collectType(Ast.TypeRef type, Set<String> out, Set<String> generics) {
        if (type == null) return;
        String name = type.name();
        if (!generics.contains(name) && !name.startsWith("$") && !isPrimitive(name)) out.add(name);
        for (Ast.TypeRef arg : type.arguments()) collectType(arg, out, generics);
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
        if (decl instanceof Ast.SymbolDecl symbol) return symbol.name();
        if (decl instanceof Ast.FieldDecl field) return field.name();
        throw new IllegalArgumentException("unsupported declaration in export resolver: " + decl.getClass().getName());
    }

    private static Ast.ExportKind declarationKind(Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) {
            return fn.kind() == Ast.CallableKind.ROUTINE
                    ? Ast.ExportKind.ROUTINE
                    : Ast.ExportKind.FUNCTION;
        }
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
        if (decl instanceof Ast.SymbolDecl) return Ast.ExportKind.SYMBOL;
        if (decl instanceof Ast.FieldDecl field) {
            return switch (field.bindingKind()) {
                case CONST -> Ast.ExportKind.CONST;
                case VAL -> Ast.ExportKind.VAL;
                case LET -> Ast.ExportKind.LET;
            };
        }
        throw new IllegalArgumentException("unsupported declaration in export resolver: " + decl.getClass().getName());
    }

    private static Ast.ExportScope ownerScope(Ast.ModuleDecl module, boolean root) {
        if (root) return Ast.ExportScope.PUBLIC;
        return switch (module.visibility()) {
            case PUBLIC -> Ast.ExportScope.PUBLIC;
            case PACKAGE -> Ast.ExportScope.PACKAGE;
            case FILE, PRIVATE, EXPLICIT_PRIVATE -> Ast.ExportScope.FILE;
        };
    }

    private static Ast.ExportScope exportScope(Ast.Visibility visibility) {
        return switch (visibility) {
            case FILE -> Ast.ExportScope.FILE;
            case PACKAGE -> Ast.ExportScope.PACKAGE;
            case PUBLIC -> Ast.ExportScope.PUBLIC;
            case PRIVATE, EXPLICIT_PRIVATE -> null;
        };
    }

    private static Ast.ExportScope capScope(Ast.ExportScope scope, Ast.ExportScope owner) {
        return scopeRank(scope) <= scopeRank(owner) ? scope : owner;
    }

    private static int visibilityRank(Ast.Visibility visibility, Ast.ExportScope owner) {
        Ast.ExportScope scope = exportScope(visibility);
        return scope == null ? 0 : scopeRank(capScope(scope, owner));
    }

    private static int scopeRank(Ast.ExportScope scope) {
        return switch (scope) {
            case FILE -> 1;
            case PACKAGE -> 2;
            case PUBLIC -> 3;
        };
    }

    private static String localName(String symbolId) {
        int marker = symbolId.lastIndexOf("::");
        return marker < 0 ? symbolId : symbolId.substring(marker + 2);
    }

    private static Ast.Visibility visibilityOf(Ast.Decl decl) {
        if (decl instanceof Ast.FunctionDecl fn) return fn.visibility();
        if (decl instanceof Ast.ClassDecl cls) return cls.visibility();
        if (decl instanceof Ast.InterfaceDecl iface) return iface.visibility();
        if (decl instanceof Ast.TypeAliasDecl alias) return alias.visibility();
        if (decl instanceof Ast.SymbolDecl symbol) return symbol.visibility();
        if (decl instanceof Ast.FieldDecl field) return field.visibility();
        throw new IllegalArgumentException("unsupported declaration in export resolver: " + decl.getClass().getName());
    }
}
