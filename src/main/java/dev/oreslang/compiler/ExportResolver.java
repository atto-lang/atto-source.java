package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves declarative source exports only after the complete local namespace
 * has been collected. This is source-language visibility metadata, not JVM,
 * Native Image, ELF/Mach-O/COFF, Wasm, CLR, or ES-module export authorization.
 */
public final class ExportResolver {
    private ExportResolver() { }

    public record Resolution(Map<String, List<Ast.ExportBinding>> byNamespace) {
        public Resolution {
            LinkedHashMap<String, List<Ast.ExportBinding>> copy = new LinkedHashMap<>();
            byNamespace.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            byNamespace = Collections.unmodifiableMap(copy);
        }

        public List<Ast.ExportBinding> bindingsFor(String namespaceId) {
            return byNamespace.getOrDefault(namespaceId, List.of());
        }
    }

    private record LocalFamily(
            String name,
            Ast.ExportKind kind,
            Ast.Visibility visibility,
            List<Ast.Decl> declarations) {
        LocalFamily {
            declarations = List.copyOf(declarations);
        }
    }

    private static final Set<String> BUILTIN_TYPES = Set.of(
            "void", "bool", "boolean", "int", "float", "string", "String", "Symbol",
            "Option", "Result", "Array", "List", "Map", "Tuple", "Fnc", "Function",
            "Future", "Task", "Channel", "ActorRef");

    private static final EnumSet<Ast.ExportKind> TYPE_KINDS = EnumSet.of(
            Ast.ExportKind.ACTOR, Ast.ExportKind.CLASS, Ast.ExportKind.INTERFACE,
            Ast.ExportKind.CONTRACT, Ast.ExportKind.TRAIT, Ast.ExportKind.STRUCT,
            Ast.ExportKind.TYPE);

    public static Resolution resolve(Ast.Program program) {
        LinkedHashMap<String, List<Ast.ExportBinding>> result = new LinkedHashMap<>();
        Map<String, Ast.ModuleDecl> modulesByName = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            if (!module.name().equals(Parser.ROOT_MODULE)) {
                modulesByName.put(module.name(), module);
            }
        }

        for (Ast.ModuleDecl module : program.modules()) {
            String owner = namespaceId(program, module);
            Map<String, List<LocalFamily>> families = collectFamilies(module);
            ArrayList<Ast.ExportBinding> bindings = new ArrayList<>();
            LinkedHashMap<String, Ast.ExportBinding> externalNames = new LinkedHashMap<>();
            LinkedHashMap<String, Ast.ExportBinding> exactBindings = new LinkedHashMap<>();

            for (List<LocalFamily> named : families.values()) {
                for (LocalFamily family : named) {
                    if (family.visibility() == Ast.Visibility.PUBLIC) {
                        addBinding(bindings, externalNames, exactBindings,
                                binding(owner, family, family.name(), Ast.ExportScope.PUBLIC,
                                        Ast.ExportOrigin.IMPLICIT_PUB, 0, 0));
                    }
                }
            }

            for (Ast.ExportDecl declaration : module.exports()) {
                for (Ast.ExportItem item : declaration.items()) {
                    LocalFamily family = resolveFamily(
                            program, module, families, modulesByName, declaration, item);
                    String external = item.externalName();
                    addBinding(bindings, externalNames, exactBindings,
                            binding(owner, family, external, declaration.scope(),
                                    Ast.ExportOrigin.EXPLICIT_EXPORT,
                                    declaration.line(), declaration.column()));
                }
            }

            bindings.sort(Comparator
                    .comparing(Ast.ExportBinding::externalName)
                    .thenComparing(binding -> binding.localKind().name())
                    .thenComparing(Ast.ExportBinding::localSymbolId)
                    .thenComparing(binding -> binding.scope().name()));
            result.put(owner, List.copyOf(bindings));
        }

        Resolution resolution = new Resolution(result);
        validateLocalApiLeakage(program, resolution);
        return resolution;
    }

    public static String namespaceId(Ast.Program program, Ast.ModuleDecl module) {
        String prefix = program.namespace() == null || program.namespace().isBlank()
                ? "<file>"
                : program.namespace();
        return prefix + "::" + module.name();
    }

    private static Map<String, List<LocalFamily>> collectFamilies(Ast.ModuleDecl module) {
        LinkedHashMap<String, LinkedHashMap<Ast.ExportKind, ArrayList<Ast.Decl>>> grouped =
                new LinkedHashMap<>();

        for (Ast.Decl declaration : module.declarations()) {
            String name = declarationName(declaration);
            Ast.ExportKind kind = declarationKind(declaration);
            if (name == null || kind == null) continue;
            grouped.computeIfAbsent(name, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(kind, ignored -> new ArrayList<>())
                    .add(declaration);
        }

        LinkedHashMap<String, List<LocalFamily>> result = new LinkedHashMap<>();
        grouped.forEach((name, byKind) -> {
            ArrayList<LocalFamily> families = new ArrayList<>();
            byKind.forEach((kind, declarations) -> {
                Ast.Visibility visibility = declarationVisibility(declarations.getFirst());
                for (Ast.Decl declaration : declarations) {
                    if (declarationVisibility(declaration) != visibility) {
                        throw semantic("EXPORT_OVERLOAD_VISIBILITY_CONFLICT",
                                "logical export family '" + name + "' mixes public and private declarations");
                    }
                }
                if (declarations.size() > 1
                        && kind != Ast.ExportKind.FNC
                        && kind != Ast.ExportKind.ROUTINE) {
                    throw semantic("EXPORT_DUPLICATE_LOCAL_SYMBOL",
                            "multiple local declarations share " + kind.name().toLowerCase()
                                    + " name '" + name + "'");
                }
                families.add(new LocalFamily(name, kind, visibility, declarations));
            });
            result.put(name, List.copyOf(families));
        });
        return result;
    }

    private static LocalFamily resolveFamily(
            Ast.Program program,
            Ast.ModuleDecl module,
            Map<String, List<LocalFamily>> families,
            Map<String, Ast.ModuleDecl> modulesByName,
            Ast.ExportDecl export,
            Ast.ExportItem item) {
        if (item.symbol()) {
            throw semantic("EXPORT_SYMBOL_DECLARATION_NOT_LOWERED",
                    "Symbol export syntax is parsed, but Java main does not yet expose a first-class Symbol declaration for "
                            + item.localName());
        }

        List<LocalFamily> candidates = new ArrayList<>(
                families.getOrDefault(item.localName(), List.of()));

        if (module.name().equals(Parser.ROOT_MODULE)
                && modulesByName.containsKey(item.localName())) {
            candidates.add(new LocalFamily(
                    item.localName(), Ast.ExportKind.MODULE, Ast.Visibility.PRIVATE, List.of()));
        }

        if (export.kind() != null) {
            candidates.removeIf(candidate -> candidate.kind() != export.kind());
            if (candidates.isEmpty()) {
                if (hasAnyLocal(program, module, families, modulesByName, item.localName())) {
                    throw semantic("EXPORT_KIND_MISMATCH",
                            "export kind " + export.kind().name().toLowerCase()
                                    + " does not match local declaration '" + item.localName() + "'");
                }
                if (isImportedName(program, module, item.localName())) {
                    throw semantic("EXPORT_IMPORTED_NAME",
                            "local export cannot re-export imported binding '" + item.localName() + "'");
                }
                throw semantic("EXPORT_UNKNOWN_TARGET",
                        "unknown local export target '" + item.localName() + "'");
            }
        } else {
            if (candidates.isEmpty()) {
                if (isImportedName(program, module, item.localName())) {
                    throw semantic("EXPORT_IMPORTED_NAME",
                            "local export cannot re-export imported binding '" + item.localName() + "'");
                }
                throw semantic("EXPORT_UNKNOWN_TARGET",
                        "unknown local export target '" + item.localName() + "'");
            }
            if (candidates.size() != 1) {
                throw semantic("EXPORT_AMBIGUOUS_TARGET",
                        "untyped export target '" + item.localName()
                                + "' resolves to multiple declaration families");
            }
        }

        return candidates.getFirst();
    }

    private static boolean hasAnyLocal(
            Ast.Program program,
            Ast.ModuleDecl module,
            Map<String, List<LocalFamily>> families,
            Map<String, Ast.ModuleDecl> modulesByName,
            String name) {
        return families.containsKey(name)
                || module.name().equals(Parser.ROOT_MODULE) && modulesByName.containsKey(name);
    }

    private static boolean isImportedName(
            Ast.Program program, Ast.ModuleDecl module, String name) {
        if (!module.name().equals(Parser.ROOT_MODULE)) return false;
        for (Ast.ImportDecl use : program.imports()) {
            if (use.wildcard()) {
                if (name.equals(use.namespace())) return true;
                continue;
            }
            if (use.names().size() == 1 && use.namespace() != null && !use.namespace().isBlank()) {
                if (name.equals(use.namespace())) return true;
            } else if (use.names().contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static Ast.ExportBinding binding(
            String owner,
            LocalFamily family,
            String external,
            Ast.ExportScope scope,
            Ast.ExportOrigin origin,
            int line,
            int column) {
        String symbolId = owner + "::" + family.kind().name().toLowerCase()
                + "::" + family.name();
        return new Ast.ExportBinding(
                owner, symbolId, family.name(), family.kind(), external,
                scope, origin, line, column);
    }

    private static void addBinding(
            List<Ast.ExportBinding> bindings,
            Map<String, Ast.ExportBinding> externalNames,
            Map<String, Ast.ExportBinding> exactBindings,
            Ast.ExportBinding candidate) {
        String exactKey = candidate.localSymbolId() + "\u0000" + candidate.externalName();
        Ast.ExportBinding priorExact = exactBindings.get(exactKey);
        if (priorExact != null) {
            if (priorExact.scope() != candidate.scope()) {
                throw semantic("EXPORT_SCOPE_CONFLICT",
                        "export binding '" + candidate.externalName()
                                + "' has conflicting scopes " + priorExact.scope()
                                + " and " + candidate.scope());
            }
            return; // exact duplicate / implicit pub + identical explicit export is idempotent
        }

        Ast.ExportBinding priorExternal = externalNames.get(candidate.externalName());
        if (priorExternal != null
                && !priorExternal.localSymbolId().equals(candidate.localSymbolId())) {
            throw semantic("EXPORT_ALIAS_COLLISION",
                    "external export name '" + candidate.externalName()
                            + "' is already bound to a different local symbol");
        }

        exactBindings.put(exactKey, candidate);
        externalNames.putIfAbsent(candidate.externalName(), candidate);
        bindings.add(candidate);
    }

    private static void validateLocalApiLeakage(Ast.Program program, Resolution resolution) {
        for (Ast.ModuleDecl module : program.modules()) {
            String owner = namespaceId(program, module);
            Map<String, List<LocalFamily>> families = collectFamilies(module);
            Map<String, Integer> widestScopeBySymbol = new LinkedHashMap<>();
            for (Ast.ExportBinding binding : resolution.bindingsFor(owner)) {
                widestScopeBySymbol.merge(
                        binding.localSymbolId(), rank(binding.scope()), Math::max);
            }

            Map<String, List<LocalFamily>> localTypes = new LinkedHashMap<>();
            families.forEach((name, named) -> {
                List<LocalFamily> typeFamilies = named.stream()
                        .filter(family -> TYPE_KINDS.contains(family.kind()))
                        .toList();
                if (!typeFamilies.isEmpty()) localTypes.put(name, typeFamilies);
            });

            for (Ast.ExportBinding binding : resolution.bindingsFor(owner)) {
                if (binding.localKind() == Ast.ExportKind.MODULE) continue;
                LocalFamily exported = families.getOrDefault(binding.localName(), List.of()).stream()
                        .filter(family -> family.kind() == binding.localKind())
                        .findFirst()
                        .orElse(null);
                if (exported == null) continue;

                LinkedHashSet<String> referenced = new LinkedHashSet<>();
                for (Ast.Decl declaration : exported.declarations()) {
                    collectSignatureTypes(declaration, referenced);
                }
                referenced.remove(binding.localName());

                for (String typeName : referenced) {
                    if (BUILTIN_TYPES.contains(typeName) || typeName.startsWith("$")) continue;
                    List<LocalFamily> referencedFamilies = localTypes.get(typeName);
                    if (referencedFamilies == null || referencedFamilies.isEmpty()) continue;
                    if (referencedFamilies.size() != 1) {
                        throw semantic("EXPORT_AMBIGUOUS_SIGNATURE_TYPE",
                                "exported signature references ambiguous local type '" + typeName + "'");
                    }
                    LocalFamily referencedFamily = referencedFamilies.getFirst();
                    String referencedId = owner + "::" + referencedFamily.kind().name().toLowerCase()
                            + "::" + referencedFamily.name();
                    int available = widestScopeBySymbol.getOrDefault(referencedId, -1);
                    int required = rank(binding.scope());
                    if (available < required) {
                        throw semantic("EXPORT_API_LEAK",
                                "export '" + binding.externalName() + "' at "
                                        + binding.scope().name().toLowerCase()
                                        + " scope exposes narrower local type '" + typeName + "'");
                    }
                }
            }
        }
    }

    private static int rank(Ast.ExportScope scope) {
        return switch (scope) {
            case FILE -> 0;
            case PACKAGE -> 1;
            case PUBLIC -> 2;
        };
    }

    private static void collectSignatureTypes(Ast.Decl declaration, Set<String> names) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            for (Ast.Param parameter : fn.parameters()) collectType(parameter.type(), names);
            collectType(fn.returnType(), names);
            for (Ast.TypeRef type : fn.effects().throwsTypes()) collectType(type, names);
            for (Ast.TypeRef type : fn.effects().raisesTypes()) collectType(type, names);
            for (Ast.TypeRef type : fn.effects().panicsTypes()) collectType(type, names);
            return;
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            for (Ast.TypeRef parent : klass.parents()) collectType(parent, names);
            for (Ast.TypeRef iface : klass.interfaces()) collectType(iface, names);
            if (klass.constructor() != null
                    && klass.constructor().visibility() == Ast.Visibility.PUBLIC) {
                for (Ast.Param parameter : klass.constructor().parameters()) {
                    collectType(parameter.type(), names);
                }
            }
            for (Ast.FieldDecl field : klass.fields()) {
                if (field.visibility() == Ast.Visibility.PUBLIC) collectType(field.type(), names);
            }
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                for (Ast.Param parameter : method.parameters()) collectType(parameter.type(), names);
                collectType(method.returnType(), names);
                for (Ast.TypeRef type : method.effects().throwsTypes()) collectType(type, names);
                for (Ast.TypeRef type : method.effects().raisesTypes()) collectType(type, names);
                for (Ast.TypeRef type : method.effects().panicsTypes()) collectType(type, names);
            }
            return;
        }
        if (declaration instanceof Ast.InterfaceDecl iface) {
            for (Ast.TypeRef parent : iface.parents()) collectType(parent, names);
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    for (Ast.Param parameter : fn.parameters()) collectType(parameter.type(), names);
                    collectType(fn.returnType(), names);
                    for (Ast.TypeRef type : fn.effects().throwsTypes()) collectType(type, names);
                    for (Ast.TypeRef type : fn.effects().raisesTypes()) collectType(type, names);
                    for (Ast.TypeRef type : fn.effects().panicsTypes()) collectType(type, names);
                } else if (member instanceof Ast.InterfaceFieldDecl field) {
                    collectType(field.type(), names);
                }
            }
            return;
        }
        if (declaration instanceof Ast.TypeAliasDecl alias) {
            collectType(alias.target(), names);
            return;
        }
        if (declaration instanceof Ast.FieldDecl field) {
            collectType(field.type(), names);
        }
    }

    private static void collectType(Ast.TypeRef type, Set<String> names) {
        if (type == null) return;
        if (!type.name().startsWith("$")) names.add(type.name());
        for (Ast.TypeRef argument : type.arguments()) collectType(argument, names);
    }

    private static String declarationName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        return null;
    }

    private static Ast.ExportKind declarationKind(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            return fn.kind() == Ast.CallableKind.ROUTINE
                    ? Ast.ExportKind.ROUTINE
                    : Ast.ExportKind.FNC;
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            return klass.actorKind() == Ast.ActorKind.NONE
                    ? Ast.ExportKind.CLASS
                    : Ast.ExportKind.ACTOR;
        }
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.declarationKind();
        if (declaration instanceof Ast.TypeAliasDecl) return Ast.ExportKind.TYPE;
        if (declaration instanceof Ast.FieldDecl field) {
            return switch (field.bindingKind()) {
                case CONST -> Ast.ExportKind.CONST;
                case VAL -> Ast.ExportKind.VAL;
                case LET -> Ast.ExportKind.LET;
            };
        }
        return null;
    }

    private static Ast.Visibility declarationVisibility(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.visibility();
        if (declaration instanceof Ast.ClassDecl klass) return klass.visibility();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.visibility();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.visibility();
        if (declaration instanceof Ast.FieldDecl field) return field.visibility();
        return Ast.Visibility.PRIVATE;
    }

    private static IllegalArgumentException semantic(String code, String message) {
        return new IllegalArgumentException("Oreslang export error [" + code + "]: " + message);
    }
}
