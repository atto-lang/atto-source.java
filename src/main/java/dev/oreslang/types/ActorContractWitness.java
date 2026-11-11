package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Source-set-scoped STATIC contract witness for declaration-only actors.
 *
 * A witness is not a runtime capability, actor instance, guest type-checker
 * admission, HIR/MIR proof, or permission to dispatch a mailbox turn.
 * Only a trusted package build may promote the closed source-set result;
 * linkSourceSet alone accepts caller-selected source bytes and is NOT a
 * manifest provenance attestation.
 */
public final class ActorContractWitness {
    private ActorContractWitness() { }

    public record ActorKey(String packageIdentity, String namespace, String actor) { }
    public record MethodProof(
            String contractUnit, String methodUnit,
            String contract, String name, int arity) { }
    public record ActorProof(
            String actorUnit, String stateUnit, List<MethodProof> methods) {
        public ActorProof {
            methods = List.copyOf(methods);
        }
    }
    public record Result(
            String packageIdentity,
            Map<ActorKey, ActorProof> actors,
            Map<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding> boundMethods) {
        public Result {
            actors = Collections.unmodifiableMap(new LinkedHashMap<>(actors));
            boundMethods = Collections.unmodifiableMap(new LinkedHashMap<>(boundMethods));
        }
    }

    private record Located<T>(String sourceUnit, T declaration) { }
    private record Requirement(String contract, Located<Ast.InterfaceDecl> owner,
                               Ast.InterfaceFunctionDecl method) { }

    private static String name(String actor, String contract) {
        return actor + " implements " + contract;
    }

    private static String key(String name, int arity) {
        return name + "/" + arity;
    }

    /**
     * Validate ALL declaration-only actor contracts in one explicit package
     * source set. The caller owns completeness and trust of the source set.
     * This method deliberately does not make external mtd bodies executable.
     */
    public static Result linkSourceSet(
            String packageIdentity, Map<String, Ast.Program> sourceUnits) {
        var bound = ActorReceiverBinder.bindSourceSet(packageIdentity, sourceUnits);
        Map<String, List<Located<Ast.Decl>>> namespaces = new TreeMap<>();

        for (var file : new TreeMap<>(sourceUnits).entrySet()) {
            Ast.Program program = file.getValue();
            if (program.namespace() == null || program.namespace().isBlank()) {
                throw new IllegalArgumentException(
                        "actor contract witness requires explicit namespace: " + file.getKey());
            }
            if (!program.imports().isEmpty()) {
                throw new IllegalArgumentException(
                        "actor contract witness v1 forbids imports until trusted dependency linkage: "
                                + file.getKey());
            }
            for (Ast.ModuleDecl module : program.modules()) {
                if (!module.name().equals(Parser.ROOT_MODULE)) {
                    for (Ast.Decl decl : module.declarations()) {
                        if (decl instanceof Ast.DeclaredActorDecl
                                || decl instanceof Ast.ActorStateAttachmentDecl
                                || decl instanceof Ast.ExternalActorMethodDecl) {
                            throw new IllegalArgumentException(
                                    "actor constituents cannot be linked through named modules");
                        }
                    }
                    continue;
                }
                var target = namespaces.computeIfAbsent(program.namespace(),
                        ignored -> new ArrayList<>());
                for (Ast.Decl decl : module.declarations()) {
                    target.add(new Located<>(file.getKey(), decl));
                }
            }
        }

        Map<ActorKey, ActorProof> proofs = new LinkedHashMap<>();
        for (var namespace : namespaces.entrySet()) {
            Map<String, Located<Ast.InterfaceDecl>> contracts = new LinkedHashMap<>();
            Map<String, Located<Ast.DeclaredActorDecl>> actors = new LinkedHashMap<>();
            Map<String, Located<Ast.ActorStateAttachmentDecl>> state = new LinkedHashMap<>();

            for (var located : namespace.getValue()) {
                Ast.Decl decl = located.declaration();
                if (decl instanceof Ast.InterfaceDecl iface && !iface.moduleContract()) {
                    if (contracts.putIfAbsent(iface.name(),
                            new Located<>(located.sourceUnit(), iface)) != null) {
                        throw new IllegalArgumentException(
                                "ambiguous actor interface contract: " + iface.name());
                    }
                } else if (decl instanceof Ast.DeclaredActorDecl actor) {
                    if (actors.putIfAbsent(actor.name(),
                            new Located<>(located.sourceUnit(), actor)) != null) {
                        throw new IllegalArgumentException(
                                "duplicate declaration-only actor: " + actor.name());
                    }
                } else if (decl instanceof Ast.ActorStateAttachmentDecl attachment) {
                    if (state.putIfAbsent(attachment.actorName(),
                            new Located<>(located.sourceUnit(), attachment)) != null) {
                        throw new IllegalArgumentException(
                                "duplicate attached actor state: " + attachment.actorName());
                    }
                }
            }

            for (var actorEntry : actors.entrySet()) {
                String actorName = actorEntry.getKey();
                Ast.DeclaredActorDecl actor = actorEntry.getValue().declaration();
                List<MethodProof> methodProofs = new ArrayList<>();
                Map<String, String> fulfilled = new LinkedHashMap<>();

                for (Ast.TypeRef interfaceName : actor.contracts()) {
                    Located<Ast.InterfaceDecl> target = contracts.get(interfaceName.name());
                    if (target == null) {
                        throw new IllegalArgumentException(
                                "missing nominal actor interface contract "
                                        + name(actorName, interfaceName.name()));
                    }
                    Ast.InterfaceDecl iface = target.declaration();
                    if (!iface.genericParameters().isEmpty() || !iface.parents().isEmpty()) {
                        throw new IllegalArgumentException(
                                "actor interface contract generics/inheritance require verified lowering: "
                                        + iface.name());
                    }
                    if (actor.visibility() == Ast.Visibility.PUBLIC
                            && iface.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException(
                                "public actor exposes private actor contract: " + iface.name());
                    }

                    for (Ast.InterfaceMember member : iface.members()) {
                        if (!(member instanceof Ast.InterfaceFunctionDecl requirement)) {
                            throw new IllegalArgumentException(
                                    "actor interface contract v1 requires method-only members: "
                                            + iface.name());
                        }
                        if (requirement.structural()
                                || !requirement.genericParameters().isEmpty()
                                || !requirement.effects().isEmpty()) {
                            throw new IllegalArgumentException(
                                    "actor interface contract uses unsupported generic/structural/effect method: "
                                            + requirement.name());
                        }
                        validateSignatureTypes(requirement.parameters(),
                                requirement.returnType(), "actor contract requirement");
                        String selector = key(requirement.name(), requirement.parameters().size());
                        String previous = fulfilled.putIfAbsent(selector, iface.name());
                        if (previous != null) {
                            throw new IllegalArgumentException(
                                    "ambiguous duplicate actor contract slot " + actorName + "."
                                            + selector + " from " + previous + " and " + iface.name());
                        }
                        var sourceSlot = new ActorReceiverBinder.SourceSlot(
                                packageIdentity, namespace.getKey(), actorName,
                                requirement.name(), requirement.parameters().size());
                        var binding = bound.get(sourceSlot);
                        if (binding == null) {
                            throw new IllegalArgumentException(
                                    "missing actor contract implementation: "
                                            + name(actorName, iface.name()) + "." + selector);
                        }
                        Ast.ExternalActorMethodDecl method = binding.nominalBinding().method();
                        if (method.visibility() != Ast.Visibility.PUBLIC) {
                            throw new IllegalArgumentException(
                                    "actor contract method must be public: " + actorName + "." + selector);
                        }
                        validateSignatureTypes(method.parameters(),
                                method.returnType(), "external actor contract method");
                        if (!sameSignature(requirement, method)) {
                            throw new IllegalArgumentException(
                                    "actor contract signature mismatch: " + actorName + "."
                                            + selector + " requires exact argument mutability/types and return type");
                        }
                        methodProofs.add(new MethodProof(
                                target.sourceUnit(), binding.methodUnit(),
                                iface.name(), requirement.name(), requirement.parameters().size()));
                    }
                }

                // A declaration cannot accidentally count as a complete
                // implementation if neither code nor state was attached.
                boolean hasMethod = bound.keySet().stream().anyMatch(k ->
                        k.packageIdentity().equals(packageIdentity)
                                && k.namespace().equals(namespace.getKey())
                                && k.actor().equals(actorName));
                Located<Ast.ActorStateAttachmentDecl> ownedState = state.get(actorName);
                if (!hasMethod && ownedState == null) {
                    throw new IllegalArgumentException(
                            "declaration-only actor has no implementation constituents: " + actorName);
                }
                ActorKey id = new ActorKey(packageIdentity, namespace.getKey(), actorName);
                proofs.put(id, new ActorProof(actorEntry.getValue().sourceUnit(),
                        ownedState == null ? null : ownedState.sourceUnit(), methodProofs));
            }
        }
        return new Result(packageIdentity, proofs, bound);
    }

    private static boolean sameSignature(
            Ast.InterfaceFunctionDecl requirement, Ast.ExternalActorMethodDecl method) {
        if (!requirement.returnType().equals(method.returnType())
                || requirement.parameters().size() != method.parameters().size()) {
            return false;
        }
        for (int i = 0; i < method.parameters().size(); i++) {
            Ast.Param wanted = requirement.parameters().get(i);
            Ast.Param actual = method.parameters().get(i);
            if (!wanted.type().equals(actual.type())
                    || wanted.mutable() != actual.mutable()
                    || wanted.structural() || actual.structural()) {
                return false;
            }
        }
        return true;
    }

    private static void validateSignatureTypes(
            List<Ast.Param> parameters, Ast.TypeRef result, String context) {
        for (Ast.Param param : parameters) {
            if (param.structural()) {
                throw new IllegalArgumentException(context + ": structural parameter unsupported");
            }
            safeScalar(param.type(), false, context);
        }
        safeScalar(result, true, context);
    }

    // Stage 1 exposes only scalar, copy-safe, exact type identities. Extended
    // nominal types, unions, references, generic payloads, and mailbox sendability
    // must be proven by a subsequent compiler-owned type/effect pass.
    private static void safeScalar(Ast.TypeRef type, boolean allowVoid, String context) {
        if (!type.arguments().isEmpty() || type.inferArguments()
                || !(type.name().equals("int") || type.name().equals("bool")
                     || type.name().equals("string")
                     || (allowVoid && type.name().equals("void")))) {
            throw new IllegalArgumentException(
                    context + ": requires exact scalar int/bool/string"
                            + (allowVoid ? "/void" : "") + " types; no borrows or generics");
        }
    }
}
