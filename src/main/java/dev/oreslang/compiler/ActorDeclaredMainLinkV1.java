package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Manifest-bound declaration and main-entry linkage for one declared actor.
 *
 * This is a closed-world provenance check, NOT executable main lowering.
 * It prevents an opaque, unchecked function/module or a second main from
 * being silently published inside an actor's immutable CodeImage while
 * execution of that entrypoint remains unsupported.
 */
final class ActorDeclaredMainLinkV1 {
    private ActorDeclaredMainLinkV1() { }

    record MainEntry(String sourceUnit, Ast.FunctionDecl function) { }

    static MainEntry verify(
            String packageIdentity, Map<String, Ast.Program> sourceUnits,
            PackageNamespaceBuild.ActorAudit audit,
            ActorContractWitness.ActorKey actor) {
        if (!packageIdentity.equals(actor.packageIdentity())
                || !packageIdentity.equals(audit.contractWitness().packageIdentity())) {
            throw deny("package/actor authority is not bound to the current manifest");
        }
        var proof = audit.contractWitness().actors().get(actor);
        if (proof == null || proof.stateUnit() == null) {
            throw deny("declared actor or sealed state is absent from the manifest witness");
        }
        if (sourceUnits.size() != audit.sourceSha256().size()
                || !sourceUnits.keySet().equals(audit.sourceSha256().keySet())) {
            throw deny("source set differs from the manifest's authenticated file inventory");
        }
        Set<String> declaredContracts = new HashSet<>();
        Ast.Program actorUnit = sourceUnits.get(proof.actorUnit());
        if (actorUnit == null) throw deny("declared actor unit disappeared");
        for (Ast.ModuleDecl module : actorUnit.modules()) {
            if (!Parser.ROOT_MODULE.equals(module.name())) continue;
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.DeclaredActorDecl declared
                        && declared.name().equals(actor.actor())) {
                    for (Ast.TypeRef contract : declared.contracts())
                        declaredContracts.add(contract.name());
                }
            }
        }

        MainEntry main = null;
        int actorCount = 0;
        int stateCount = 0;
        Set<String> contracts = new HashSet<>();
        for (var source : new TreeMap<>(sourceUnits).entrySet()) {
            Ast.Program program = source.getValue();
            if (!actor.namespace().equals(program.namespace())
                    || !program.imports().isEmpty()) {
                throw deny("main linker requires one verified namespace without imports");
            }
            for (Ast.ModuleDecl module : program.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())
                        || !module.annotations().isEmpty()
                        || !module.contracts().isEmpty()) {
                    throw deny("named/annotated modules are not admitted into stateful actor code image");
                }
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.DeclaredActorDecl declared) {
                        if (!source.getKey().equals(proof.actorUnit())
                                || !actor.actor().equals(declared.name())
                                || ++actorCount != 1) {
                            throw deny("declared actor identity or physical unit mismatch");
                        }
                    } else if (decl instanceof Ast.ActorStateAttachmentDecl state) {
                        if (!source.getKey().equals(proof.stateUnit())
                                || !actor.actor().equals(state.actorName())
                                || ++stateCount != 1) {
                            throw deny("sealed actor state identity or physical unit mismatch");
                        }
                    } else if (decl instanceof Ast.ExternalActorMethodDecl method) {
                        boolean found = audit.contractWitness().boundMethods().entrySet()
                                .stream().anyMatch(binding ->
                                        binding.getKey().packageIdentity().equals(packageIdentity)
                                        && binding.getKey().namespace().equals(actor.namespace())
                                        && binding.getKey().actor().equals(actor.actor())
                                        && binding.getKey().name().equals(method.name())
                                        && binding.getKey().arity() == method.parameters().size()
                                        && binding.getValue().methodUnit().equals(source.getKey())
                                        && binding.getValue().nominalBinding().method() == method);
                        if (!found) {
                            throw deny("unbound or substituted external actor method");
                        }
                    } else if (decl instanceof Ast.InterfaceDecl iface) {
                        if (!iface.actorContract()
                                || !declaredContracts.contains(iface.name())
                                || !contracts.add(iface.name())) {
                            throw deny("unverified or duplicate actor contract declaration");
                        }
                    } else if (decl instanceof Ast.FunctionDecl function) {
                        if (!function.name().equals("main") || main != null) {
                            throw deny("only one manifest-linked main routine is admitted");
                        }
                        if (function.visibility() != Ast.Visibility.PUBLIC
                                || function.kind() != Ast.CallableKind.ROUTINE
                                || !function.async() || function.generator()
                                || function.structural() || function.nonLexical()
                                || function.trapped()
                                || function.actorKind() != Ast.ActorKind.NONE
                                || !function.parameters().isEmpty()
                                || !function.genericParameters().isEmpty()
                                || !function.annotations().isEmpty()
                                || !function.returnType().name().equals("void")
                                || function.returnType().isBorrow()
                                || function.returnType().inferArguments()
                                || !function.returnType().arguments().isEmpty()) {
                            throw deny("main must be a public async zero-argument void routine");
                        }
                        main = new MainEntry(source.getKey(), function);
                    } else {
                        throw deny("unverified declaration kind in stateful actor code image");
                    }
                }
            }
        }
        if (main == null || actorCount != 1 || stateCount != 1
                || !contracts.containsAll(declaredContracts)
                || contracts.size() != declaredContracts.size()) {
            throw deny("the closed source set must include actor, state, contracts and one main");
        }
        return main;
    }

    private static IllegalArgumentException deny(String reason) {
        return new IllegalArgumentException(
                "ACTOR_DECLARED_MAIN_LINK_V1_UNSUPPORTED: " + reason);
    }
}
