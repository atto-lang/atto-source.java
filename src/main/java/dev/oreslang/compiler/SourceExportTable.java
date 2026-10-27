package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Cross-file/source-runtime view of {@link ExportResolver} bindings.
 *
 * <p>This table is source-language authority only. It never creates JVM,
 * Native Image, native linker, Wasm, CLR, or JavaScript host exports.</p>
 */
public final class SourceExportTable {
    private SourceExportTable() { }

    public static List<ExportResolver.ExportBinding> matchingPublic(
            Ast.Program program,
            Ast.ImportKind kind,
            String externalName) {
        return matchingPublic(program, null, kind, externalName);
    }

    public static List<ExportResolver.ExportBinding> matchingPublic(
            Ast.Program program,
            String ownerNamespace,
            Ast.ImportKind kind,
            String externalName) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(kind, "import kind");
        if (externalName == null || externalName.isBlank()) {
            throw new IllegalArgumentException("export lookup name cannot be blank");
        }

        ExportResolver.Result resolved = ExportResolver.resolve(program);
        ArrayList<ExportResolver.ExportBinding> matches = new ArrayList<>();
        for (var namespace : resolved.namespaces().entrySet()) {
            if (ownerNamespace != null && !ownerNamespace.equals(namespace.getKey())) continue;
            for (ExportResolver.ExportBinding binding : namespace.getValue()) {
                if (binding.scope() != Ast.ExportScope.PUBLIC) continue;
                if (!binding.externalName().equals(externalName)) continue;
                if (!kindMatches(kind, binding.localKind())) continue;
                if (!isImportSelectable(program, binding, kind)) continue;
                matches.add(binding);
            }
        }

        // Named modules predate first-class export declarations and currently
        // have no declaration-side visibility bit. Preserve that compatibility
        // only when the module has no explicit source export binding at all.
        // Once a module is explicitly exported (including under an alias), its
        // export table is authoritative and the local spelling must not leak.
        if (matches.isEmpty()
                && ownerNamespace == null
                && (kind == Ast.ImportKind.MODULE || kind == Ast.ImportKind.ALL)
                && !hasBoundLocalModule(resolved, externalName)) {
            for (Ast.ModuleDecl module : program.modules()) {
                if (module.name().equals(Parser.ROOT_MODULE)) continue;
                if (!module.name().equals(externalName)) continue;
                matches.add(new ExportResolver.ExportBinding(
                        Parser.ROOT_MODULE,
                        Parser.ROOT_MODULE + "::module::" + module.name(),
                        Ast.ExportKind.MODULE,
                        module.name(),
                        module.name(),
                        Ast.ExportScope.PUBLIC,
                        ExportResolver.BindingOrigin.IMPLICIT_PUB,
                        0,
                        0));
            }
        }

        return List.copyOf(matches);
    }

    public static ExportResolver.ExportBinding resolvePublic(
            Ast.Program program,
            Ast.ImportKind kind,
            String externalName) {
        return requireAtMostOne(matchingPublic(program, kind, externalName), kind, externalName);
    }

    public static ExportResolver.ExportBinding resolvePublic(
            Ast.Program program,
            String ownerNamespace,
            Ast.ImportKind kind,
            String externalName) {
        return requireAtMostOne(
                matchingPublic(program, ownerNamespace, kind, externalName),
                kind,
                externalName);
    }

    /**
     * Mixed Java/Ores source execution historically accepts public routines
     * as well as fnc. A routine remains direct-call-only for source imports;
     * this explicit host bridge is not a JVM symbol export.
     */
    public static ExportResolver.ExportBinding resolvePublicCallable(
            Ast.Program program,
            String externalName) {
        return resolvePublicCallable(program, null, externalName);
    }

    /**
     * Direct-call lookup for source call sites that intentionally admit both
     * fnc and routine. This is distinct from import fnc, which remains
     * first-class/reifiable and therefore fnc-only.
     */
    public static ExportResolver.ExportBinding resolvePublicCallable(
            Ast.Program program,
            String ownerNamespace,
            String externalName) {
        Objects.requireNonNull(program, "program");
        ArrayList<ExportResolver.ExportBinding> matches = new ArrayList<>();
        for (var namespace : ExportResolver.resolve(program).namespaces().entrySet()) {
            if (ownerNamespace != null && !ownerNamespace.equals(namespace.getKey())) continue;
            for (ExportResolver.ExportBinding binding : namespace.getValue()) {
                if (binding.scope() != Ast.ExportScope.PUBLIC
                        || !binding.externalName().equals(externalName)
                        || (binding.localKind() != Ast.ExportKind.FNC
                                && binding.localKind() != Ast.ExportKind.ROUTINE)) {
                    continue;
                }
                if (!localDeclarations(program, binding).isEmpty()) {
                    matches.add(binding);
                }
            }
        }
        return requireAtMostOne(matches, Ast.ImportKind.FUNCTION, externalName);
    }

    public static ExportResolver.ExportBinding resolvePublicAny(
            Ast.Program program,
            String externalName) {
        return requireAtMostOne(
                matchingPublic(program, Ast.ImportKind.ALL, externalName),
                Ast.ImportKind.ALL,
                externalName);
    }

    public static ExportResolver.ExportBinding resolvePublicAny(
            Ast.Program program,
            String ownerNamespace,
            String externalName) {
        return requireAtMostOne(
                matchingPublic(program, ownerNamespace, Ast.ImportKind.ALL, externalName),
                Ast.ImportKind.ALL,
                externalName);
    }

    public static List<Ast.Decl> localDeclarations(
            Ast.Program program,
            ExportResolver.ExportBinding binding) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(binding, "binding");
        ArrayList<Ast.Decl> matches = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            if (!module.name().equals(binding.ownerNamespaceId())) continue;
            for (Ast.Decl declaration : module.declarations()) {
                if (!localName(declaration).equals(binding.localName())) continue;
                if (localKind(declaration) != binding.localKind()) continue;
                matches.add(declaration);
            }
        }
        return List.copyOf(matches);
    }

    private static ExportResolver.ExportBinding requireAtMostOne(
            List<ExportResolver.ExportBinding> matches,
            Ast.ImportKind kind,
            String externalName) {
        if (matches.isEmpty()) return null;
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "ambiguous public source export '" + externalName
                            + "' for import kind " + kind.name().toLowerCase());
        }
        return matches.getFirst();
    }

    private static boolean hasBoundLocalModule(
            ExportResolver.Result resolved,
            String localName) {
        for (var bindings : resolved.namespaces().values()) {
            for (ExportResolver.ExportBinding binding : bindings) {
                if (binding.localKind() == Ast.ExportKind.MODULE
                        && binding.localName().equals(localName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean kindMatches(Ast.ImportKind imported, Ast.ExportKind exported) {
        return switch (imported) {
            case MODULE -> exported == Ast.ExportKind.MODULE;
            case ACTOR -> exported == Ast.ExportKind.ACTOR;
            case CLASS -> exported == Ast.ExportKind.CLASS;
            case FUNCTION -> exported == Ast.ExportKind.FNC;
            case INTERFACE -> exported == Ast.ExportKind.INTERFACE;
            case CONTRACT -> exported == Ast.ExportKind.CONTRACT;
            case TRAIT -> exported == Ast.ExportKind.TRAIT;
            case STRUCT -> exported == Ast.ExportKind.STRUCT;
            case TYPE -> exported == Ast.ExportKind.TYPE;
            case TYPES -> exported == Ast.ExportKind.TYPE
                    || exported == Ast.ExportKind.INTERFACE
                    || exported == Ast.ExportKind.CONTRACT;
            case ALL -> exported != Ast.ExportKind.UNTYPED
                    && exported != Ast.ExportKind.ROUTINE
                    && exported != Ast.ExportKind.SYMBOL;
        };
    }

    private static boolean isImportSelectable(
            Ast.Program program,
            ExportResolver.ExportBinding binding,
            Ast.ImportKind imported) {
        if (binding.localKind() == Ast.ExportKind.MODULE) return true;
        List<Ast.Decl> family = localDeclarations(program, binding);
        if (family.isEmpty()) return false;

        return switch (imported) {
            // Importing a function as a value must not admit only the
            // reifiable subset of an overloaded family: doing so lets the
            // external binding resolve, then fail or select a different
            // hidden/generic overload at runtime. Reject the entire family
            // unless every overload is a monomorphic, ordinary fnc.
            case FUNCTION -> family.stream().allMatch(declaration ->
                    declaration instanceof Ast.FunctionDecl fn
                            && fn.kind() == Ast.CallableKind.FNC
                            && fn.actorKind() == Ast.ActorKind.NONE
                            && fn.genericParameters().isEmpty());
            case ACTOR -> family.stream().allMatch(declaration ->
                    declaration instanceof Ast.ClassDecl klass
                            && klass.actorKind() != Ast.ActorKind.NONE);
            case CLASS -> family.stream().allMatch(declaration ->
                    declaration instanceof Ast.ClassDecl klass
                            && klass.actorKind() == Ast.ActorKind.NONE);
            case INTERFACE -> family.stream().allMatch(declaration ->
                    declaration instanceof Ast.InterfaceDecl iface && !iface.moduleContract());
            case CONTRACT -> family.stream().allMatch(declaration ->
                    declaration instanceof Ast.InterfaceDecl iface && iface.moduleContract());
            case TYPE -> family.stream().allMatch(Ast.TypeAliasDecl.class::isInstance);
            case TYPES -> family.stream().allMatch(declaration ->
                    declaration instanceof Ast.TypeAliasDecl
                            || declaration instanceof Ast.InterfaceDecl);
            case MODULE -> false;
            case TRAIT, STRUCT -> false; // AST/runtime provenance not lowered yet.
            case ALL -> true;
        };
    }

    private static String localName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) return fn.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        throw new IllegalArgumentException("unknown declaration node in source export table");
    }

    private static Ast.ExportKind localKind(Ast.Decl declaration) {
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
        throw new IllegalArgumentException("unknown declaration node in source export table");
    }
}
