package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Rewrites the declaration signatures of an imported module so they can be
 * type-checked in the importer's scope (typed linked IR v1, stage 2).
 *
 * <p>Inside its own source, a module refers to its sibling classes, interfaces
 * and type aliases by short name ({@code Command}). The importer binds the
 * module under a local alias, so the same nominal is only reachable as
 * {@code alias.Command}. This class qualifies every such short name in
 * parameter, return, field, parent and generic-default types. Generic
 * parameters shadow module names exactly as they do in the source.
 *
 * <p>Callable and constructor bodies are not rewritten: linked bodies are
 * never re-checked by the importer; their own unit already checked them.
 */
final class ForeignModuleQualifier {
    private final String alias;
    private final Set<String> nominals;

    private ForeignModuleQualifier(String alias, Set<String> nominals) {
        this.alias = alias;
        this.nominals = nominals;
    }

    /** Names of the nominal declarations a module owns. */
    static Set<String> nominalNames(Ast.ModuleDecl module) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Ast.Decl declaration : module.declarations()) {
            // Type aliases stay short: linked classes resolve their field
            // aliases privately, and a public signature naming a module alias
            // is not linkable (the importer keeps it opaque).
            if (declaration instanceof Ast.ClassDecl klass) names.add(klass.name());
            else if (declaration instanceof Ast.InterfaceDecl iface) names.add(iface.name());
        }
        return Set.copyOf(names);
    }

    static Ast.ModuleDecl qualify(String alias, Ast.ModuleDecl module) {
        ForeignModuleQualifier qualifier = new ForeignModuleQualifier(alias, nominalNames(module));
        List<Ast.Decl> declarations = new ArrayList<>(module.declarations().size());
        for (Ast.Decl declaration : module.declarations()) {
            declarations.add(qualifier.rewriteDecl(declaration, Set.of()));
        }
        return new Ast.ModuleDecl(module.name(), module.annotations(), module.contracts(),
                declarations);
    }

    static Ast.ClassDecl qualify(String alias, Ast.ModuleDecl owner, Ast.ClassDecl klass) {
        return new ForeignModuleQualifier(alias, nominalNames(owner))
                .rewriteClassDecl(klass, Set.of());
    }

    private Ast.TypeRef qualifyType(Ast.TypeRef ref, Set<String> generics) {
        if (ref == null) return null;
        List<Ast.TypeRef> arguments = new ArrayList<>(ref.arguments().size());
        boolean changed = false;
        for (Ast.TypeRef argument : ref.arguments()) {
            Ast.TypeRef rewritten = qualifyType(argument, generics);
            changed |= rewritten != argument;
            arguments.add(rewritten);
        }
        String name = ref.name();
        if (nominals.contains(name) && !generics.contains(name)) {
            name = alias + "." + name;
            changed = true;
        }
        return changed ? new Ast.TypeRef(name, arguments, ref.inferArguments()) : ref;
    }

    // Exhaustive sealed declaration switches deliberately require updates when
    // the AST grows. All constructor/accessor calls are visible to Native Image.
    private Ast.Decl rewriteDecl(Ast.Decl node, Set<String> scope) {
        if (node == null) return null;
        return switch (node) {
            case Ast.FunctionDecl value -> rewriteFunctionDecl(value, scope);
            case Ast.ClassDecl value -> rewriteClassDecl(value, scope);
            case Ast.InterfaceDecl value -> rewriteInterfaceDecl(value, scope);
            case Ast.FieldDecl value -> rewriteFieldDecl(value, scope);
            case Ast.TypeAliasDecl value -> rewriteTypeAliasDecl(value, scope);
            case Ast.StateContainerDecl value -> rewriteStateContainerDecl(value, scope);
            case Ast.VirtualActorDecl value -> rewriteVirtualActorDecl(value, scope);
            case Ast.GpuActorDecl value -> rewriteGpuActorDecl(value, scope);
            case Ast.StandaloneActorDecl value -> rewriteStandaloneActorDecl(value, scope);
            case Ast.ExternalActorMethodDecl value -> rewriteExternalActorMethodDecl(value, scope);
            case Ast.SingletonDecl value -> rewriteSingletonDecl(value, scope);
        };
    }

    private Ast.InterfaceMember rewriteInterfaceMember(Ast.InterfaceMember node, Set<String> scope) {
        if (node == null) return null;
        return switch (node) {
            case Ast.InterfaceFunctionDecl value -> rewriteInterfaceFunctionDecl(value, scope);
            case Ast.InterfaceFieldDecl value -> rewriteInterfaceFieldDecl(value, scope);
            case Ast.InterfaceActorDecl value -> rewriteInterfaceActorDecl(value, scope);
            case Ast.InterfaceActorMethodDecl value -> rewriteInterfaceActorMethodDecl(value, scope);
        };
    }

    private static <T> List<T> rewriteList(List<T> input, Set<String> scope,
                                            BiFunction<T, Set<String>, T> rewrite) {
        if (input == null) return null;
        List<T> out = null;
        for (int i = 0; i < input.size(); i++) {
            T original = input.get(i);
            T next = rewrite.apply(original, scope);
            if (out == null && next != original) {
                out = new ArrayList<>(input.size());
                out.addAll(input.subList(0, i));
            }
            if (out != null) out.add(next);
        }
        return out == null ? input : out;
    }

    private static Set<String> withGenerics(Set<String> outer, List<String> names) {
        if (names == null || names.isEmpty()) return outer;
        HashSet<String> scope = new HashSet<>(outer);
        scope.addAll(names);
        return Set.copyOf(scope);
    }

    private Ast.Param rewriteParam(Ast.Param node, Set<String> scope) {
        if (node == null) return null;
        Ast.TypeRef type = qualifyType(node.type(), scope);
        if (type == node.type()) return node;
        return new Ast.Param(type,
                node.name(),
                node.structural(),
                node.mutable());
    }

    private Ast.GenericParameter rewriteGenericParameter(Ast.GenericParameter node, Set<String> scope) {
        if (node == null) return null;
        Ast.TypeRef defaultType = qualifyType(node.defaultType(), scope);
        if (defaultType == node.defaultType()) return node;
        return new Ast.GenericParameter(node.name(),
                defaultType);
    }

    private Ast.EffectSpec rewriteEffectSpec(Ast.EffectSpec node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.TypeRef> throwsTypes = rewriteList(node.throwsTypes(), scope, this::qualifyType);
        List<Ast.TypeRef> raisesTypes = rewriteList(node.raisesTypes(), scope, this::qualifyType);
        List<Ast.TypeRef> panicsTypes = rewriteList(node.panicsTypes(), scope, this::qualifyType);
        if (throwsTypes == node.throwsTypes()
                && raisesTypes == node.raisesTypes()
                && panicsTypes == node.panicsTypes()) return node;
        return new Ast.EffectSpec(throwsTypes,
                raisesTypes,
                panicsTypes);
    }

    private Ast.FunctionDecl rewriteFunctionDecl(Ast.FunctionDecl node, Set<String> outer) {
        if (node == null) return null;
        Set<String> scope = withGenerics(outer, node.genericParameters());
        List<Ast.Param> parameters = rewriteList(node.parameters(), scope, this::rewriteParam);
        Ast.TypeRef returnType = qualifyType(node.returnType(), scope);
        List<Ast.GenericParameter> genericSpecs = rewriteList(node.genericSpecs(), scope, this::rewriteGenericParameter);
        Ast.EffectSpec effects = rewriteEffectSpec(node.effects(), scope);
        if (parameters == node.parameters()
                && returnType == node.returnType()
                && genericSpecs == node.genericSpecs()
                && effects == node.effects()) return node;
        return new Ast.FunctionDecl(node.name(),
                node.kind(),
                node.visibility(),
                node.async(),
                node.suspends(),
                node.generator(),
                node.structural(),
                node.nonLexical(),
                node.trapped(),
                node.actorKind(),
                node.genericParameters(),
                parameters,
                returnType,
                node.annotations(),
                node.body(),
                genericSpecs,
                effects);
    }

    private Ast.FieldDecl rewriteFieldDecl(Ast.FieldDecl node, Set<String> scope) {
        if (node == null) return null;
        Ast.TypeRef type = qualifyType(node.type(), scope);
        if (type == node.type()) return node;
        return new Ast.FieldDecl(node.name(),
                node.visibility(),
                node.bindingKind(),
                type,
                node.annotations(),
                node.initializer(),
                node.mutableReferent());
    }

    private Ast.ConstructorDecl rewriteConstructorDecl(Ast.ConstructorDecl node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.Param> parameters = rewriteList(node.parameters(), scope, this::rewriteParam);
        if (parameters == node.parameters()) return node;
        return new Ast.ConstructorDecl(node.visibility(),
                parameters,
                node.annotations(),
                node.body());
    }

    private Ast.MethodDecl rewriteMethodDecl(Ast.MethodDecl node, Set<String> outer) {
        if (node == null) return null;
        Set<String> scope = withGenerics(outer, node.genericParameters());
        Ast.TypeRef explicitReceiverType = qualifyType(node.explicitReceiverType(), scope);
        List<Ast.Param> parameters = rewriteList(node.parameters(), scope, this::rewriteParam);
        Ast.TypeRef returnType = qualifyType(node.returnType(), scope);
        Ast.EffectSpec effects = rewriteEffectSpec(node.effects(), scope);
        if (explicitReceiverType == node.explicitReceiverType()
                && parameters == node.parameters()
                && returnType == node.returnType()
                && effects == node.effects()) return node;
        return new Ast.MethodDecl(node.name(),
                node.visibility(),
                node.isStatic(),
                node.isAbstract(),
                node.async(),
                node.suspends(),
                node.structural(),
                explicitReceiverType,
                node.genericParameters(),
                parameters,
                returnType,
                node.annotations(),
                node.body(),
                effects,
                node.critical());
    }

    private Ast.ActorConcurrency rewriteActorConcurrency(Ast.ActorConcurrency node, Set<String> scope) {
        return node;
    }

    private Ast.ProtectedStateField rewriteProtectedStateField(Ast.ProtectedStateField node, Set<String> scope) {
        if (node == null) return null;
        Ast.TypeRef type = qualifyType(node.type(), scope);
        if (type == node.type()) return node;
        return new Ast.ProtectedStateField(node.name(),
                type,
                node.initializer());
    }

    private Ast.ActorMessageContract rewriteActorMessageContract(Ast.ActorMessageContract node, Set<String> scope) {
        if (node == null) return null;
        Ast.TypeRef receives = qualifyType(node.receives(), scope);
        Ast.TypeRef sends = qualifyType(node.sends(), scope);
        if (receives == node.receives()
                && sends == node.sends()) return node;
        return new Ast.ActorMessageContract(receives,
                sends);
    }

    private Ast.ClassDecl rewriteClassDecl(Ast.ClassDecl node, Set<String> outer) {
        if (node == null) return null;
        Set<String> scope = withGenerics(outer, node.genericParameters());
        List<Ast.TypeRef> parents = rewriteList(node.parents(), scope, this::qualifyType);
        List<Ast.TypeRef> interfaces = rewriteList(node.interfaces(), scope, this::qualifyType);
        List<Ast.FieldDecl> fields = rewriteList(node.fields(), scope, this::rewriteFieldDecl);
        Ast.ConstructorDecl constructor = rewriteConstructorDecl(node.constructor(), scope);
        List<Ast.MethodDecl> methods = rewriteList(node.methods(), scope, this::rewriteMethodDecl);
        Ast.ActorConcurrency actorConcurrency = rewriteActorConcurrency(node.actorConcurrency(), scope);
        List<Ast.ProtectedStateField> sealedStateFields = rewriteList(node.sealedStateFields(), scope, this::rewriteProtectedStateField);
        Ast.ActorMessageContract actorMessageContract = rewriteActorMessageContract(node.actorMessageContract(), scope);
        if (parents == node.parents()
                && interfaces == node.interfaces()
                && fields == node.fields()
                && constructor == node.constructor()
                && methods == node.methods()
                && actorConcurrency == node.actorConcurrency()
                && sealedStateFields == node.sealedStateFields()
                && actorMessageContract == node.actorMessageContract()) return node;
        return new Ast.ClassDecl(node.name(),
                node.visibility(),
                node.isAbstract(),
                node.actorKind(),
                node.genericParameters(),
                parents,
                interfaces,
                fields,
                constructor,
                methods,
                actorConcurrency,
                sealedStateFields,
                node.annotations(),
                actorMessageContract);
    }

    private Ast.InterfaceDecl rewriteInterfaceDecl(Ast.InterfaceDecl node, Set<String> outer) {
        if (node == null) return null;
        Set<String> scope = withGenerics(outer, node.genericParameters());
        List<Ast.TypeRef> parents = rewriteList(node.parents(), scope, this::qualifyType);
        List<Ast.InterfaceMember> members = rewriteList(node.members(), scope, this::rewriteInterfaceMember);
        if (parents == node.parents()
                && members == node.members()) return node;
        return new Ast.InterfaceDecl(node.name(),
                node.visibility(),
                node.genericParameters(),
                parents,
                members,
                node.moduleContract(),
                node.declarationKind());
    }

    private Ast.TypeAliasDecl rewriteTypeAliasDecl(Ast.TypeAliasDecl node, Set<String> outer) {
        if (node == null) return null;
        Set<String> scope = withGenerics(outer, node.genericParameters());
        Ast.TypeRef target = qualifyType(node.target(), scope);
        if (target == node.target()) return node;
        return new Ast.TypeAliasDecl(node.name(),
                node.visibility(),
                node.genericParameters(),
                target);
    }

    private Ast.ProtectedMethod rewriteProtectedMethod(Ast.ProtectedMethod node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.Param> parameters = rewriteList(node.parameters(), scope, this::rewriteParam);
        Ast.TypeRef returnType = qualifyType(node.returnType(), scope);
        if (parameters == node.parameters()
                && returnType == node.returnType()) return node;
        return new Ast.ProtectedMethod(node.name(),
                parameters,
                returnType,
                node.bodyTokens(),
                node.bodyTokenKinds());
    }

    private Ast.StateContainerDecl rewriteStateContainerDecl(Ast.StateContainerDecl node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.ProtectedStateField> stateFields = rewriteList(node.stateFields(), scope, this::rewriteProtectedStateField);
        List<Ast.ProtectedMethod> methods = rewriteList(node.methods(), scope, this::rewriteProtectedMethod);
        if (stateFields == node.stateFields()
                && methods == node.methods()) return node;
        return new Ast.StateContainerDecl(node.name(),
                node.visibility(),
                stateFields,
                methods);
    }

    private Ast.VirtualActorDecl rewriteVirtualActorDecl(Ast.VirtualActorDecl node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.ProtectedStateField> stateFields = rewriteList(node.stateFields(), scope, this::rewriteProtectedStateField);
        List<Ast.ProtectedMethod> methods = rewriteList(node.methods(), scope, this::rewriteProtectedMethod);
        if (stateFields == node.stateFields()
                && methods == node.methods()) return node;
        return new Ast.VirtualActorDecl(node.name(),
                node.visibility(),
                stateFields,
                methods);
    }

    private Ast.GpuActorDecl rewriteGpuActorDecl(Ast.GpuActorDecl node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.ProtectedStateField> stateFields = rewriteList(node.stateFields(), scope, this::rewriteProtectedStateField);
        List<Ast.ProtectedMethod> methods = rewriteList(node.methods(), scope, this::rewriteProtectedMethod);
        if (stateFields == node.stateFields()
                && methods == node.methods()) return node;
        return new Ast.GpuActorDecl(node.name(),
                node.visibility(),
                stateFields,
                methods);
    }

    private Ast.StandaloneActorDecl rewriteStandaloneActorDecl(Ast.StandaloneActorDecl node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.TypeRef> implementedContracts = rewriteList(node.implementedContracts(), scope, this::qualifyType);
        if (implementedContracts == node.implementedContracts()) return node;
        return new Ast.StandaloneActorDecl(node.name(),
                node.receiverName(),
                node.visibility(),
                node.actorKind(),
                implementedContracts);
    }

    private Ast.ExternalActorMethodDecl rewriteExternalActorMethodDecl(Ast.ExternalActorMethodDecl node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.Param> parameters = rewriteList(node.parameters(), scope, this::rewriteParam);
        Ast.TypeRef returnType = qualifyType(node.returnType(), scope);
        if (parameters == node.parameters()
                && returnType == node.returnType()) return node;
        return new Ast.ExternalActorMethodDecl(node.actorName(),
                node.receiverName(),
                node.name(),
                node.visibility(),
                parameters,
                returnType,
                node.body());
    }

    private Ast.SingletonField rewriteSingletonField(Ast.SingletonField node, Set<String> scope) {
        if (node == null) return null;
        Ast.FieldDecl declaration = rewriteFieldDecl(node.declaration(), scope);
        if (declaration == node.declaration()) return node;
        return new Ast.SingletonField(declaration,
                node.line(),
                node.column());
    }

    private Ast.SingletonDecl rewriteSingletonDecl(Ast.SingletonDecl node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.SingletonField> fields = rewriteList(node.fields(), scope, this::rewriteSingletonField);
        if (fields == node.fields()) return node;
        return new Ast.SingletonDecl(node.name(),
                node.visibility(),
                fields,
                node.line(),
                node.column());
    }

    private Ast.InterfaceFunctionDecl rewriteInterfaceFunctionDecl(Ast.InterfaceFunctionDecl node, Set<String> outer) {
        if (node == null) return null;
        Set<String> scope = withGenerics(outer, node.genericParameters());
        List<Ast.Param> parameters = rewriteList(node.parameters(), scope, this::rewriteParam);
        Ast.TypeRef returnType = qualifyType(node.returnType(), scope);
        Ast.EffectSpec effects = rewriteEffectSpec(node.effects(), scope);
        if (parameters == node.parameters()
                && returnType == node.returnType()
                && effects == node.effects()) return node;
        return new Ast.InterfaceFunctionDecl(node.name(),
                node.structural(),
                node.genericParameters(),
                parameters,
                returnType,
                effects);
    }

    private Ast.InterfaceFieldDecl rewriteInterfaceFieldDecl(Ast.InterfaceFieldDecl node, Set<String> scope) {
        if (node == null) return null;
        Ast.TypeRef type = qualifyType(node.type(), scope);
        if (type == node.type()) return node;
        return new Ast.InterfaceFieldDecl(node.name(),
                type,
                node.bindingKind(),
                node.mutableReferent());
    }

    private Ast.InterfaceActorDecl rewriteInterfaceActorDecl(Ast.InterfaceActorDecl node, Set<String> scope) {
        return node;
    }

    private Ast.InterfaceActorMethodDecl rewriteInterfaceActorMethodDecl(Ast.InterfaceActorMethodDecl node, Set<String> scope) {
        if (node == null) return null;
        List<Ast.Param> parameters = rewriteList(node.parameters(), scope, this::rewriteParam);
        Ast.TypeRef returnType = qualifyType(node.returnType(), scope);
        if (parameters == node.parameters()
                && returnType == node.returnType()) return node;
        return new Ast.InterfaceActorMethodDecl(node.actorName(),
                node.name(),
                node.visibility(),
                parameters,
                returnType);
    }
}
