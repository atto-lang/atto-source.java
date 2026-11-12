package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ActorContractWitness;
import dev.oreslang.types.ActorReceiverBinder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * First closed-world, NON-EXECUTABLE type check for external actor method
 * bodies that return an exact scalar literal or a declared scalar parameter.
 *
 * This is an explicit subset check, not the general expression type checker:
 * state loans, calls, assignments, checked arithmetic, control flow, effects,
 * suspension, and actor-context authority are NOT proven here. Even a passing
 * result cannot authorize dispatch, actor allocation or runtime codegen.
 */
public final class ExternalActorScalarBodyCheck {
    private ExternalActorScalarBodyCheck() { }

    public enum ReturnKind { VOID, PARAMETER, LITERAL }

    public record VerifiedBody(
            String declaringUnit, String actorUnit, String contractUnit,
            ActorMethodIndex.MethodKey selector, String returnType,
            ReturnKind returnKind) {
        public VerifiedBody {
            Objects.requireNonNull(declaringUnit);
            Objects.requireNonNull(actorUnit);
            Objects.requireNonNull(contractUnit);
            Objects.requireNonNull(selector);
            Objects.requireNonNull(returnType);
            Objects.requireNonNull(returnKind);
        }
    }

    /**
     * Uses only the same parsed snapshot and the witness already checked by
     * PackageNamespaceBuild; no arbitrary external file or ambient import is
     * consulted. Public output is descriptive metadata, not a signed proof.
     */
    static Map<ActorMethodIndex.MethodKey, VerifiedBody> verify(
            ActorContractWitness.Result witness,
            ActorMethodIndex index) {
        Map<ActorMethodIndex.MethodKey, VerifiedBody> verified = new LinkedHashMap<>();
        var actors = new TreeMap<String, ActorContractWitness.ActorProof>();
        for (var actor : witness.actors().entrySet()) {
            String key = actor.getKey().namespace() + "\u0000" + actor.getKey().actor();
            if (actors.putIfAbsent(key, actor.getValue()) != null) {
                throw new IllegalArgumentException("duplicate verified actor body identity");
            }
        }
        for (var actor : witness.actors().entrySet()) {
            // Identity and origin are bound by the witness, not inferred from
            // the spelling of the external receiver in another source file.
            var actorKey = new ActorMethodIndex.ActorKey(
                    actor.getKey().packageIdentity(),
                    actor.getKey().namespace(), actor.getKey().actor());
            var proof = actor.getValue();
            if (!Objects.equals(index.declaringUnit(actorKey), proof.actorUnit())) {
                throw new IllegalArgumentException("scalar body nominal actor origin mismatch");
            }
            for (var requirement : proof.methods()) {
                var selector = new ActorMethodIndex.MethodKey(
                        actorKey, requirement.name(), requirement.arity());
                var sourceSlot = new ActorReceiverBinder.SourceSlot(
                        actorKey.packageId(), actorKey.namespace(), actorKey.actorName(),
                        requirement.name(), requirement.arity());
                var binding = witness.boundMethods().get(sourceSlot);
                var signature = index.method(selector);
                if (binding == null || signature == null
                        || !Objects.equals(binding.actorUnit(), proof.actorUnit())
                        || !Objects.equals(binding.methodUnit(), requirement.methodUnit())
                        || !Objects.equals(signature.declaringUnit(), requirement.methodUnit())
                        || !signature.declarationEligible()) {
                    throw new IllegalArgumentException(
                            "EXTERNAL_MTD_BODY_ORIGIN_MISMATCH: " + selector);
                }
                Ast.ExternalActorMethodDecl method = binding.nominalBinding().method();
                if (!method.name().equals(selector.methodName())
                        || method.parameters().size() != selector.arity()
                        || !method.returnType().equals(signature.returnType())) {
                    throw new IllegalArgumentException(
                            "EXTERNAL_MTD_BODY_SIGNATURE_MISMATCH: " + selector);
                }
                ReturnKind kind = checkScalarReturn(method);
                var verifiedMethod = new VerifiedBody(requirement.methodUnit(),
                        proof.actorUnit(), requirement.contractUnit(),
                        selector, method.returnType().name(), kind);
                if (verified.putIfAbsent(selector, verifiedMethod) != null) {
                    throw new IllegalArgumentException(
                            "EXTERNAL_MTD_DUPLICATE_BODY: " + selector);
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(verified));
    }

    private static ReturnKind checkScalarReturn(Ast.ExternalActorMethodDecl method) {
        String expected = method.returnType().name();
        if (!scalar(expected, true) || !method.returnType().arguments().isEmpty()
                || method.returnType().inferArguments() || method.returnType().isBorrow()) {
            throw new IllegalArgumentException(
                    "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED: non-scalar return");
        }
        if (method.body().size() != 1
                || !(method.body().getFirst() instanceof Ast.ReturnStmt returned)) {
            throw new IllegalArgumentException(
                    "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED: expected exactly one return statement in "
                            + method.name());
        }
        Ast.Expr expression = returned.value();
        if (expected.equals("void")) {
            if (expression != null) {
                throw new IllegalArgumentException(
                        "EXTERNAL_MTD_SCALAR_RETURN_TYPE_MISMATCH: void method returned value in "
                                + method.name());
            }
            return ReturnKind.VOID;
        }
        if (expression == null) {
            throw new IllegalArgumentException(
                    "EXTERNAL_MTD_SCALAR_RETURN_TYPE_MISMATCH: missing value in "
                            + method.name());
        }
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            String actual = value instanceof Long ? "int"
                    : value instanceof Boolean ? "bool"
                    : value instanceof String ? "string" : "";
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        "EXTERNAL_MTD_SCALAR_RETURN_TYPE_MISMATCH: expected "
                                + expected + " literal, found " + actual + " in " + method.name());
            }
            return ReturnKind.LITERAL;
        }
        if (expression instanceof Ast.NameExpr name) {
            if (name.name().equals(method.receiverName())) {
                throw new IllegalArgumentException(
                        "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED: actor receiver cannot be a scalar result");
            }
            String type = null;
            for (Ast.Param param : method.parameters()) {
                if (param.name().equals(name.name())) {
                    if (type != null) {
                        throw new IllegalArgumentException(
                                "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED: duplicate parameter");
                    }
                    if (param.structural() || param.type().isBorrow()
                            || param.type().inferArguments()
                            || !param.type().arguments().isEmpty()) {
                        throw new IllegalArgumentException(
                                "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED: non-scalar parameter");
                    }
                    type = param.type().name();
                }
            }
            if (!expected.equals(type)) {
                throw new IllegalArgumentException(
                        "EXTERNAL_MTD_SCALAR_RETURN_TYPE_MISMATCH: expected " + expected
                                + ", parameter '" + name.name() + "' is " + type);
            }
            return ReturnKind.PARAMETER;
        }
        throw new IllegalArgumentException(
                "EXTERNAL_MTD_SCALAR_BODY_UNSUPPORTED: unlowered expression "
                        + expression.getClass().getSimpleName() + " in " + method.name());
    }

    private static boolean scalar(String name, boolean allowVoid) {
        return name.equals("int") || name.equals("bool")
                || name.equals("string") || (allowVoid && name.equals("void"));
    }
}
