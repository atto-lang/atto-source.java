package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import dev.oreslang.types.ActorReceiverBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * First executable, STATELESS namespace actor slice.
 *
 * Only PackageNamespaceBuild may call this lowerer, after one canonical
 * manifest/source snapshot has passed ActorContractWitness and ActorMethodIndex.
 * Guest callers cannot construct a witness to enter this route. This pass
 * deliberately refuses attached state: legacy actor fields would bypass
 * critical-read/critical-write loans, which are NOT lowered yet.
 *
 * Out-of-line Atto method bodies remain Atto AST and execute through the
 * existing Truffle actor scheduler. No Java application handler is synthesized.
 */
final class VerifiedStatelessActorGuestV1 {
    private VerifiedStatelessActorGuestV1() { }

    static Ast.Program lower(
            String packageIdentity,
            Map<String, Ast.Program> sourceUnits,
            PackageNamespaceBuild.ActorAudit audit) {
        if (!packageIdentity.equals(audit.contractWitness().packageIdentity())) {
            throw unsupported("actor witness belongs to a different package");
        }
        Map<ActorContractWitness.ActorKey, ActorContractWitness.ActorProof> actors =
                audit.contractWitness().actors();
        if (actors.size() != 1) {
            throw unsupported("v1 requires exactly one manifest-owned declared actor");
        }
        var actorKey = actors.keySet().iterator().next();
        var proof = actors.get(actorKey);
        if (proof.stateUnit() != null) {
            throw unsupported("sealed state/critical loans are not yet executable");
        }

        List<Ast.MethodDecl> methods = new ArrayList<>();
        int receiveCount = 0;
        Map<String, ActorReceiverBinder.SourceBinding> ordered = new TreeMap<>();
        for (var binding : audit.contractWitness().boundMethods().entrySet()) {
            var key = binding.getKey();
            if (!actorKey.packageIdentity().equals(key.packageIdentity())
                    || !actorKey.namespace().equals(key.namespace())
                    || !actorKey.actor().equals(key.actor())) {
                throw unsupported("unrelated actor receiver binding in executable source set");
            }
            String selector = key.name() + "/" + key.arity();
            if (ordered.putIfAbsent(selector, binding.getValue()) != null) {
                throw unsupported("duplicate executable actor receiver slot");
            }
        }
        for (var sourceBinding : ordered.values()) {
            var external = sourceBinding.nominalBinding().method();
            if (!sourceBinding.nominalBinding().nominalActor()
                    .equals(actorDeclaration(sourceUnits, proof.actorUnit(), actorKey.actor()))) {
                throw unsupported("method actor origin no longer matches the verified declaration");
            }
            // The existing actor TypeChecker verifies method bodies, parameter
            // transport, private hook signatures and ownership on this AST.
            methods.add(new Ast.MethodDecl(
                    external.name(), external.visibility(),
                    false, false, false, false, false, null,
                    List.of(), external.parameters(), external.returnType(),
                    List.of(), external.body()));
            if (external.name().equals("receive")) receiveCount++;
        }
        if (receiveCount != 1) {
            throw unsupported("v1 requires exactly one private receive(ActorMail<T>) handler");
        }

        List<Ast.Decl> contracts = new ArrayList<>();
        Ast.ClassDecl linkedActor = null;
        Ast.FunctionDecl mainEntry = null;
        String namespace = actorKey.namespace();
        for (var source : new TreeMap<>(sourceUnits).entrySet()) {
            Ast.Program program = source.getValue();
            if (!namespace.equals(program.namespace()) || !program.imports().isEmpty()) {
                throw unsupported("v1 requires one namespace without imports");
            }
            for (Ast.ModuleDecl module : program.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())
                        || !module.annotations().isEmpty()
                        || !module.contracts().isEmpty()) {
                    throw unsupported("v1 rejects named/annotated module constituents");
                }
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.DeclaredActorDecl actor) {
                        if (linkedActor != null || !actor.name().equals(actorKey.actor())
                                || !source.getKey().equals(proof.actorUnit())) {
                            throw unsupported("actor declaration identity changed during lowering");
                        }
                        linkedActor = new Ast.ClassDecl(
                                actor.name(), actor.visibility(), false,
                                actor.actorKind(), List.of(), List.of(),
                                actor.contracts(), List.of(), null, methods);
                    } else if (declaration instanceof Ast.ExternalActorMethodDecl) {
                        // Attached body is inserted above only from the verified binding.
                    } else if (declaration instanceof Ast.InterfaceDecl iface
                            && iface.actorContract()) {
                        contracts.add(iface);
                    } else if (declaration instanceof Ast.FunctionDecl fn
                            && fn.name().equals("main")) {
                        if (mainEntry != null) {
                            throw unsupported("duplicate Atto main entry");
                        }
                        mainEntry = fn;
                    } else {
                        throw unsupported("v1 admits only actor contracts, methods and a main entry");
                    }
                }
            }
        }
        if (linkedActor == null || mainEntry == null) {
            throw unsupported("v1 requires the declared actor and one Atto main entry");
        }
        // Resolve nominal contracts before the synthetic actor regardless
        // of physical file traversal order; execute main only after linking.
        List<Ast.Decl> linked = new ArrayList<>(contracts);
        linked.add(linkedActor);
        linked.add(mainEntry);

        // Real compiler and ownership checks are mandatory before any caller
        // receives a guest program. No program text or checked flag from a
        // guest can bypass the manifest-owned entrypoint.
        Ast.Program executable = new Ast.Program(namespace, List.of(),
                List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE, linked)));
        return OresCompiler.analyze(executable);
    }

    private static Ast.DeclaredActorDecl actorDeclaration(
            Map<String, Ast.Program> units, String sourceUnit, String actorName) {
        Ast.Program program = units.get(sourceUnit);
        if (program == null) throw unsupported("actor declaration unit disappeared");
        for (Ast.ModuleDecl module : program.modules()) {
            if (!Parser.ROOT_MODULE.equals(module.name())) continue;
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.DeclaredActorDecl actor
                        && actor.name().equals(actorName)) return actor;
            }
        }
        throw unsupported("actor declaration disappeared");
    }

    private static IllegalArgumentException unsupported(String reason) {
        return new IllegalArgumentException(
                "DECLARED_ACTOR_GUEST_V1_UNSUPPORTED: " + reason);
    }
}
