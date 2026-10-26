package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves source-language export declarations after a complete lexical
 * namespace has been collected.
 *
 * <p>This is source API metadata only. It does not grant JVM host interop,
 * native-image/ELF symbol visibility, Wasm exports, or any other backend ABI
 * exposure.</p>
 */
public final class ExportResolver {
    private ExportResolver() { }

    public enum BindingOrigin { IMPLICIT_PUB, EXPLICIT_EXPORT }

    public record ExportBinding(
            String ownerNamespaceId,
            String localSymbolId,
            Ast.ExportKind localKind,
            String localName,
            String externalName,
            Ast.ExportScope scope,
            BindingOrigin origin,
            int sourceLine,
            int sourceColumn) {
        public ExportBinding {
            Objects.requireNonNull(ownerNamespaceId, "owner namespace");
            Objects.requireNonNull(localSymbolId, "local symbol id");
            Objects.requireNonNull(localKind, "local kind");
            Objects.requireNonNull(localName, "local name");
            Objects.requireNonNull(externalName, "external name");
            Objects.requireNonNull(scope, "export scope");
            Objects.requireNonNull(origin, "export origin");
        }
    }

    public record Result(Map<String, List<ExportBinding>> namespaces) {
        public Result {
            LinkedHashMap<String, List<ExportBinding>> copy = new LinkedHashMap<>();
            namespaces.forEach((name, bindings) -> copy.put(name, List.copyOf(bindings)));
            namespaces = Collections.unmodifiableMap(copy);
        }

        public List<ExportBinding> bindings(String namespace) {
            return namespaces.getOrDefault(namespace, List.of());
        }
    }

    private record LocalFamily(
            String namespace,
            String name,
            Ast.ExportKind kind,
            List<Ast.Decl> declarations,
            List<Ast.DeclarationMetadata> metadata) {
        String id() {
            return namespace + "::" + kind.name().toLowerCase() + "::" + name;
        }

        boolean explicitPrivate() {
            return metadata.stream().anyMatch(meta ->
                    meta.explicitVisibility() && meta.visibility() == Ast.Visibility.PRIVATE);
        }

        boolean hasPublic() {
            return metadata.stream().anyMatch(meta -> meta.visibility() == Ast.Visibility.PUBLIC);
        }

        boolean hasPrivate() {
            return metadata.stream().anyMatch(meta -> meta.visibility() == Ast.Visibility.PRIVATE);
        }

        boolean inconsistentVisibility() {
            return hasPublic() && hasPrivate()
                    && metadata.stream().anyMatch(Ast.DeclarationMetadata::explicitVisibility);
        }
    }

    public static Result resolve(Ast.Program program) {
        Objects.requireNonNull(program, "program");

        LinkedHashMap<String, List<ExportBinding>> resolved = new LinkedHashMap<>();

        Ast.ModuleDecl root = null;
        for (Ast.ModuleDecl module : program.modules()) {
            if (module.name().equals(Parser.ROOT_MODULE)) {
                root = module;
                break;
            }
        }

        List<Ast.Decl> rootDeclarations = root == null ? List.of() : root.declarations();
        List<Ast.DeclarationMetadata> rootMetadata =
                normalizedMetadata(rootDeclarations, program.declarationMetadata());

        List<LocalFamily> rootFamilies = collectFamilies(
                Parser.ROOT_MODULE, rootDeclarations, rootMetadata);

        // Named modules are declarations in the file/root namespace even
        // though ModuleDecl is not an executable Decl node.
        for (Ast.ModuleDecl module : program.modules()) {
            if (module.name().equals(Parser.ROOT_MODULE)) continue;
            Ast.DeclarationMetadata meta = new Ast.DeclarationMetadata(
                    module.name(), Ast.ExportKind.MODULE, Ast.Visibility.PRIVATE, false);
            rootFamilies = appendFamily(rootFamilies, new LocalFamily(
                    Parser.ROOT_MODULE,
                    module.name(),
                    Ast.ExportKind.MODULE,
                    List.of(),
                    List.of(meta)));
        }

        resolved.put(
                Parser.ROOT_MODULE,
                resolveNamespace(
                        program,
                        Parser.ROOT_MODULE,
                        rootFamilies,
                        program.exports()));

        for (Ast.ModuleDecl module : program.modules()) {
            if (module.name().equals(Parser.ROOT_MODULE)) continue;
            List<Ast.DeclarationMetadata> metadata =
                    normalizedMetadata(module.declarations(), module.declarationMetadata());
            List<LocalFamily> families =
                    collectFamilies(module.name(), module.declarations(), metadata);
            resolved.put(
                    module.name(),
                    resolveNamespace(program, module.name(), families, module.exports()));
        }

        return new Result(resolved);
    }

    private static List<LocalFamily> resolveFamiliesForName(
            List<LocalFamily> families,
            Ast.ExportItem item,
            Ast.ExportKind requestedKind) {
        Ast.ExportKind required = item.symbolLiteral() ? Ast.ExportKind.SYMBOL : requestedKind;
        List<LocalFamily> matches = new ArrayList<>();
        for (LocalFamily family : families) {
            if (!family.name().equals(item.localName())) continue;
            if (required != Ast.ExportKind.UNTYPED && family.kind() != required) continue;
            matches.add(family);
        }
        return matches;
    }

    private static List<ExportBinding> resolveNamespace(
            Ast.Program program,
            String namespace,
            List<LocalFamily> families,
            List<Ast.ExportDecl> exports) {
        List<ExportBinding> bindings = new ArrayList<>();
        LinkedHashMap<String, ExportBinding> external = new LinkedHashMap<>();

        for (LocalFamily family : families) {
            if (family.inconsistentVisibility()) {
                throw semantic(
                        "EXPORT_OVERLOAD_VISIBILITY_CONFLICT",
                        "logical export family '" + family.name()
                                + "' has incompatible declaration visibility");
            }
            if (!family.hasPublic()) continue;
            ExportBinding implicit = new ExportBinding(
                    namespace,
                    family.id(),
                    family.kind(),
                    family.name(),
                    externalSpelling(family.kind(), family.name()),
                    Ast.ExportScope.PUBLIC,
                    BindingOrigin.IMPLICIT_PUB,
                    0,
                    0);
            addBinding(bindings, external, implicit);
        }

        for (Ast.ExportDecl export : exports) {
            for (Ast.ExportItem item : export.items()) {
                if (item.symbolLiteral() && export.kind() != Ast.ExportKind.UNTYPED
                        && export.kind() != Ast.ExportKind.SYMBOL) {
                    throw semantic(
                            "EXPORT_KIND_MISMATCH",
                            "Symbol literal #" + item.localName()
                                    + " does not match export kind "
                                    + export.kind().name().toLowerCase());
                }

                List<LocalFamily> matches =
                        resolveFamiliesForName(families, item, export.kind());

                if (matches.isEmpty()) {
                    if (export.kind() != Ast.ExportKind.UNTYPED) {
                        boolean sameSpellingDifferentKind = families.stream().anyMatch(
                                family -> family.name().equals(item.localName()));
                        if (sameSpellingDifferentKind) {
                            throw semantic(
                                    "EXPORT_KIND_MISMATCH",
                                    "export kind " + export.kind().name().toLowerCase()
                                            + " does not match local declaration '"
                                            + displayItem(item) + "'");
                        }
                    }
                    if (isImportedLocalName(program, item.localName())) {
                        throw semantic(
                                "EXPORT_IMPORTED_NAME",
                                "local export syntax cannot re-export imported binding '"
                                        + item.localName() + "'");
                    }
                    throw semantic(
                            "EXPORT_UNKNOWN_TARGET",
                            "no local declaration matches export target '"
                                    + displayItem(item) + "'");
                }
                if (export.kind() == Ast.ExportKind.UNTYPED && matches.size() != 1) {
                    throw semantic(
                            "EXPORT_AMBIGUOUS_TARGET",
                            "untyped export target '" + displayItem(item)
                                    + "' is ambiguous; use a typed export");
                }
                if (export.kind() != Ast.ExportKind.UNTYPED && matches.size() > 1) {
                    throw semantic(
                            "EXPORT_AMBIGUOUS_TARGET",
                            "typed export target '" + displayItem(item)
                                    + "' resolves to multiple logical families");
                }

                LocalFamily family = matches.getFirst();
                if (family.explicitPrivate()) {
                    throw semantic(
                            "EXPORT_EXPLICIT_PRIVATE",
                            "explicit private declaration '" + family.name()
                                    + "' cannot be exported");
                }
                if (family.inconsistentVisibility()) {
                    throw semantic(
                            "EXPORT_OVERLOAD_VISIBILITY_CONFLICT",
                            "logical export family '" + family.name()
                                    + "' has incompatible declaration visibility");
                }
                if (family.hasPublic() && export.scope() != Ast.ExportScope.PUBLIC) {
                    throw semantic(
                            "EXPORT_SCOPE_CONFLICT",
                            "public declaration '" + family.name()
                                    + "' cannot also be exported at narrower "
                                    + export.scope().name().toLowerCase() + " scope");
                }

                validateApiLeakage(family, export.scope(), families);

                String externalName = item.symbolLiteral()
                        ? "#" + item.localName()
                        : item.externalName();
                ExportBinding explicit = new ExportBinding(
                        namespace,
                        family.id(),
                        family.kind(),
                        family.name(),
                        externalName,
                        export.scope(),
                        BindingOrigin.EXPLICIT_EXPORT,
                        export.line(),
                        export.column());
                addBinding(bindings, external, explicit);
            }
        }

        return List.copyOf(bindings);
    }

    private static void addBinding(
            List<ExportBinding> bindings,
            Map<String, ExportBinding> external,
            ExportBinding candidate) {
        ExportBinding existing = external.get(candidate.externalName());
        if (existing == null) {
            external.put(candidate.externalName(), candidate);
            bindings.add(candidate);
            return;
        }

        boolean identical = existing.localSymbolId().equals(candidate.localSymbolId())
                && existing.externalName().equals(candidate.externalName())
                && existing.scope() == candidate.scope();
        if (identical) return;

        if (existing.localSymbolId().equals(candidate.localSymbolId())
                && existing.externalName().equals(candidate.externalName())) {
            throw semantic(
                    "EXPORT_SCOPE_CONFLICT",
                    "export binding '" + candidate.externalName()
                            + "' is declared with conflicting scopes");
        }

        throw semantic(
                "EXPORT_ALIAS_COLLISION",
                "external export name '" + candidate.externalName()
                        + "' is already bound to a different local symbol");
    }

    private static List<LocalFamily> collectFamilies(
            String namespace,
            List<Ast.Decl> declarations,
            List<Ast.DeclarationMetadata> metadata) {
        if (declarations.size() != metadata.size()) {
            throw semantic(
                    "EXPORT_METADATA_MISMATCH",
                    "declaration/export metadata count mismatch in namespace " + namespace);
        }

        LinkedHashMap<String, MutableFamily> grouped = new LinkedHashMap<>();
        for (int i = 0; i < declarations.size(); i++) {
            Ast.Decl declaration = declarations.get(i);
            Ast.DeclarationMetadata meta = metadata.get(i);
            String key = meta.kind() + "\u0000" + meta.name();
            MutableFamily family = grouped.computeIfAbsent(
                    key, ignored -> new MutableFamily(namespace, meta.name(), meta.kind()));
            family.declarations.add(declaration);
            family.metadata.add(meta);
        }

        return grouped.values().stream().map(MutableFamily::freeze).toList();
    }

    private static List<LocalFamily> appendFamily(
            List<LocalFamily> families, LocalFamily added) {
        ArrayList<LocalFamily> copy = new ArrayList<>(families);
        copy.add(added);
        return List.copyOf(copy);
    }

    private static final class MutableFamily {
        private final String namespace;
        private final String name;
        private final Ast.ExportKind kind;
        private final List<Ast.Decl> declarations = new ArrayList<>();
        private final List<Ast.DeclarationMetadata> metadata = new ArrayList<>();

        private MutableFamily(String namespace, String name, Ast.ExportKind kind) {
            this.namespace = namespace;
            this.name = name;
            this.kind = kind;
        }

        private LocalFamily freeze() {
            return new LocalFamily(
                    namespace, name, kind, List.copyOf(declarations), List.copyOf(metadata));
        }
    }

    private static List<Ast.DeclarationMetadata> normalizedMetadata(
            List<Ast.Decl> declarations,
            List<Ast.DeclarationMetadata> metadata) {
        if (!metadata.isEmpty()) return metadata;

        List<Ast.DeclarationMetadata> synthesized = new ArrayList<>(declarations.size());
        for (Ast.Decl declaration : declarations) {
            synthesized.add(new Ast.DeclarationMetadata(
                    declarationName(declaration),
                    inferredKind(declaration),
                    declarationVisibility(declaration),
                    false));
        }
        return List.copyOf(synthesized);
    }

    private static String declarationName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        throw semantic("EXPORT_UNKNOWN_DECLARATION", "unknown declaration node");
    }

    private static Ast.ExportKind inferredKind(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            return fn.kind() == Ast.CallableKind.FNC
                    ? Ast.ExportKind.FNC : Ast.ExportKind.ROUTINE;
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            return klass.actorKind() == Ast.ActorKind.NONE
                    ? Ast.ExportKind.CLASS : Ast.ExportKind.ACTOR;
        }
        if (declaration instanceof Ast.InterfaceDecl iface) {
            return iface.moduleContract()
                    ? Ast.ExportKind.CONTRACT : Ast.ExportKind.INTERFACE;
        }
        if (declaration instanceof Ast.TypeAliasDecl) return Ast.ExportKind.TYPE;
        if (declaration instanceof Ast.FieldDecl field) {
            return switch (field.bindingKind()) {
                case CONST -> Ast.ExportKind.CONST;
                case VAL -> Ast.ExportKind.VAL;
                case LET -> Ast.ExportKind.LET;
            };
        }
        throw semantic("EXPORT_UNKNOWN_DECLARATION", "unknown declaration node");
    }

    private static Ast.Visibility declarationVisibility(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.visibility();
        if (declaration instanceof Ast.ClassDecl klass) return klass.visibility();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.visibility();
        if (declaration instanceof Ast.FieldDecl field) return field.visibility();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.visibility();
        throw semantic("EXPORT_UNKNOWN_DECLARATION", "unknown declaration node");
    }

    private static String externalSpelling(Ast.ExportKind kind, String name) {
        return kind == Ast.ExportKind.SYMBOL ? "#" + name : name;
    }

    private static String displayItem(Ast.ExportItem item) {
        return item.symbolLiteral() ? "#" + item.localName() : item.localName();
    }

    private static boolean isImportedLocalName(Ast.Program program, String name) {
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.wildcard()) {
                if (name.equals(imported.namespace())) return true;
                continue;
            }
            if (imported.namespace() != null && imported.names().size() == 1) {
                if (name.equals(imported.namespace())) return true;
            } else if (imported.names().contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Current Java visibility has only private/public. A referenced local type
     * that remains private is therefore narrower than every explicit export
     * scope (file/package/public) and cannot leak through the exported API.
     */
    private static void validateApiLeakage(
            LocalFamily family,
            Ast.ExportScope scope,
            List<LocalFamily> families) {
        Map<String, LocalFamily> localTypes = new LinkedHashMap<>();
        for (LocalFamily candidate : families) {
            if (candidate.kind() == Ast.ExportKind.CLASS
                    || candidate.kind() == Ast.ExportKind.ACTOR
                    || candidate.kind() == Ast.ExportKind.INTERFACE
                    || candidate.kind() == Ast.ExportKind.TRAIT
                    || candidate.kind() == Ast.ExportKind.CONTRACT
                    || candidate.kind() == Ast.ExportKind.TYPE
                    || candidate.kind() == Ast.ExportKind.STRUCT) {
                localTypes.putIfAbsent(candidate.name(), candidate);
            }
        }

        LinkedHashSet<String> referenced = new LinkedHashSet<>();
        for (Ast.Decl declaration : family.declarations()) {
            collectApiTypes(declaration, referenced);
        }

        for (String typeName : referenced) {
            LocalFamily referencedFamily = localTypes.get(typeName);
            if (referencedFamily == null) continue;
            if (!referencedFamily.hasPublic()) {
                throw semantic(
                        "EXPORT_API_LEAK",
                        "exported " + family.kind().name().toLowerCase()
                                + " '" + family.name() + "' at "
                                + scope.name().toLowerCase()
                                + " scope exposes narrower local type '"
                                + typeName + "'");
            }
        }
    }

    private static void collectApiTypes(Ast.Decl declaration, Set<String> names) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            fn.parameters().forEach(param -> collectType(param.type(), names));
            collectType(fn.returnType(), names);
            fn.effects().throwsTypes().forEach(type -> collectType(type, names));
            fn.effects().raisesTypes().forEach(type -> collectType(type, names));
            fn.effects().panicsTypes().forEach(type -> collectType(type, names));
            return;
        }

        if (declaration instanceof Ast.ClassDecl klass) {
            klass.parents().forEach(type -> collectType(type, names));
            klass.interfaces().forEach(type -> collectType(type, names));
            if (klass.constructor() != null
                    && klass.constructor().visibility() == Ast.Visibility.PUBLIC) {
                klass.constructor().parameters().forEach(param -> collectType(param.type(), names));
            }
            for (Ast.FieldDecl field : klass.fields()) {
                if (field.visibility() == Ast.Visibility.PUBLIC) collectType(field.type(), names);
            }
            for (Ast.MethodDecl method : klass.methods()) {
                if (method.visibility() != Ast.Visibility.PUBLIC) continue;
                method.parameters().forEach(param -> collectType(param.type(), names));
                collectType(method.returnType(), names);
                method.effects().throwsTypes().forEach(type -> collectType(type, names));
                method.effects().raisesTypes().forEach(type -> collectType(type, names));
                method.effects().panicsTypes().forEach(type -> collectType(type, names));
            }
            return;
        }

        if (declaration instanceof Ast.InterfaceDecl iface) {
            iface.parents().forEach(type -> collectType(type, names));
            for (Ast.InterfaceMember member : iface.members()) {
                if (member instanceof Ast.InterfaceFunctionDecl fn) {
                    fn.parameters().forEach(param -> collectType(param.type(), names));
                    collectType(fn.returnType(), names);
                    fn.effects().throwsTypes().forEach(type -> collectType(type, names));
                    fn.effects().raisesTypes().forEach(type -> collectType(type, names));
                    fn.effects().panicsTypes().forEach(type -> collectType(type, names));
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
        String name = type.name();
        if (!name.startsWith("$")) names.add(name);
        for (Ast.TypeRef argument : type.arguments()) collectType(argument, names);
    }

    private static IllegalArgumentException semantic(String code, String message) {
        return new IllegalArgumentException("[" + code + "] " + message);
    }
}
