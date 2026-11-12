package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ActorContractWitness;
import dev.oreslang.types.ActorReceiverBinder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in NON-EXECUTABLE actor-owned critical state source admission.
 *
 * Checks exact lexical callback shape, sealed field identity and scalar types
 * of a deliberately restricted read/write subset. This does not allocate
 * state, acquire scheduler-owned RW permits, check a general method body,
 * produce MIR or grant a runnable actor-context capability.
 */
public final class ExternalActorCriticalStateCheck {
    private ExternalActorCriticalStateCheck() { }

    public enum Kind { READ, WRITE_LITERAL, WRITE_INCREMENT_UNLOWERED }

    public record CheckedLoan(
            ActorMethodIndex.MethodKey selector,
            String actorUnit, String methodUnit, String stateUnit,
            String field, String fieldType, Kind kind) { }

    static Map<ActorMethodIndex.MethodKey, CheckedLoan> verify(
            ActorContractWitness.Result witness,
            ActorMethodIndex index,
            Map<ActorMethodIndex.ActorKey, DeclaredActorDescriptor> descriptors) {
        var checked = new LinkedHashMap<ActorMethodIndex.MethodKey, CheckedLoan>();
        for (var actorEntry : witness.actors().entrySet()) {
            var actorId = actorEntry.getKey();
            var proof = actorEntry.getValue();
            var actorKey = new ActorMethodIndex.ActorKey(
                    actorId.packageIdentity(), actorId.namespace(), actorId.actor());
            var descriptor = descriptors.get(actorKey);
            if (descriptor == null || !descriptor.actorUnit().equals(proof.actorUnit())
                    || !Objects.equals(index.declaringUnit(actorKey), proof.actorUnit())) {
                throw new IllegalArgumentException(
                        "ACTOR_STATE_LOAN_ORIGIN_MISMATCH: " + actorKey);
            }
            for (var required : proof.methods()) {
                var selector = new ActorMethodIndex.MethodKey(
                        actorKey, required.name(), required.arity());
                var sourceSlot = new ActorReceiverBinder.SourceSlot(
                        actorKey.packageId(), actorKey.namespace(),
                        actorKey.actorName(), required.name(), required.arity());
                var binding = witness.boundMethods().get(sourceSlot);
                var indexed = index.method(selector);
                if (binding == null || indexed == null
                        || !indexed.declarationEligible()
                        || !Objects.equals(binding.actorUnit(), proof.actorUnit())
                        || !Objects.equals(indexed.declaringUnit(), required.methodUnit())
                        || !Objects.equals(binding.methodUnit(), required.methodUnit())) {
                    throw new IllegalArgumentException(
                            "ACTOR_STATE_LOAN_ORIGIN_MISMATCH: " + selector);
                }
                Ast.ExternalActorMethodDecl method = binding.nominalBinding().method();
                Ast.CallExpr call = stateCall(method);
                if (call == null) continue; // no proof; runtime admission stays closed
                if (proof.stateUnit() == null || descriptor.sealedFields().isEmpty()) {
                    throw new IllegalArgumentException(
                            "ACTOR_STATE_LOAN_MISSING_SCHEMA: " + selector);
                }
                var checkedLoan = verifyCall(method, call, selector,
                        descriptor, proof.actorUnit(), proof.stateUnit(), required.methodUnit());
                if (checkedLoan != null
                        && checked.putIfAbsent(selector, checkedLoan) != null) {
                    throw new IllegalArgumentException(
                            "ACTOR_STATE_LOAN_DUPLICATE_METHOD: " + selector);
                }
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(checked));
    }

    private static Ast.CallExpr stateCall(Ast.ExternalActorMethodDecl method) {
        var body = method.body();
        // The first stage permits exactly one expression-returning READ or
        // one WRITE expression followed by explicit return; no branch, defer,
        // suspension, nested call or loan escape can be silently accepted.
        if (body.size() == 1 && body.getFirst() instanceof Ast.ReturnStmt r
                && r.value() instanceof Ast.CallExpr c) {
            return c;
        }
        if (body.size() == 2 && body.getFirst() instanceof Ast.ExprStmt e
                && e.expression() instanceof Ast.CallExpr c
                && body.get(1) instanceof Ast.ReturnStmt r && r.value() == null) {
            return c;
        }
        return null;
    }

    private static CheckedLoan verifyCall(
            Ast.ExternalActorMethodDecl method, Ast.CallExpr call,
            ActorMethodIndex.MethodKey selector, DeclaredActorDescriptor descriptor,
            String actorUnit, String stateUnit, String methodUnit) {
        if (!(call.callee() instanceof Ast.MemberExpr member)
                || !member.member().equals("handle_state")) {
            // This method is not a state-loan proof, even if it returns
            // another actor method or performs an ordinary scalar call.
            return null;
        }
        if (!(member.receiver() instanceof Ast.NameExpr receiver)
                || !receiver.name().equals(method.receiverName())) {
            throw new IllegalArgumentException(
                    "ACTOR_STATE_LOAN_WRONG_RECEIVER: " + selector);
        }
        if (call.typeArgumentsPresent() || call.arguments().size() != 1
                || !(call.arguments().getFirst() instanceof Ast.RuntimeCallExpr intrinsic)
                || intrinsic.arguments().size() != 1
                || !(intrinsic.arguments().getFirst() instanceof Ast.LambdaExpr callback)) {
            throw new IllegalArgumentException(
                    "ACTOR_STATE_LOAN_INVALID_CALLBACK: " + selector);
        }
        boolean read = intrinsic.operation().equals("actor_state_critical_read_unlowered");
        boolean write = intrinsic.operation().equals("actor_state_critical_write_unlowered");
        if (!read && !write || callback.nonLexical()
                || callback.parameters().size() != 1) {
            throw new IllegalArgumentException(
                    "ACTOR_STATE_LOAN_INVALID_CALLBACK: " + selector);
        }
        var stateBinding = callback.parameters().getFirst();
        if (stateBinding.mutable() || stateBinding.structural()
                || !stateBinding.type().equals(Ast.TypeRef.inferred())
                || stateBinding.name().equals(method.receiverName())) {
            throw new IllegalArgumentException(
                    "ACTOR_STATE_LOAN_INVALID_BINDING: " + selector);
        }

        String field;
        Kind kind;
        if (read) {
            if (method.body().size() != 1
                    || !(method.body().getFirst() instanceof Ast.ReturnStmt)
                    || callback.blockBody() != null) {
                throw new IllegalArgumentException(
                        "ACTOR_STATE_LOAN_READ_SHAPE_UNSUPPORTED: " + selector);
            }
            field = stateField(callback.expressionBody(), stateBinding.name());
            kind = Kind.READ;
        } else {
            if (method.body().size() != 2
                    || !(method.body().getFirst() instanceof Ast.ExprStmt)
                    || callback.expressionBody() != null
                    || callback.blockBody() == null
                    || callback.blockBody().size() != 1
                    || !(callback.blockBody().getFirst() instanceof Ast.ExprStmt statement)
                    || !(statement.expression() instanceof Ast.AssignExpr assign)) {
                throw new IllegalArgumentException(
                        "ACTOR_STATE_LOAN_WRITE_SHAPE_UNSUPPORTED: " + selector);
            }
            field = stateField(assign.target(), stateBinding.name());
            if (field == null) {
                throw new IllegalArgumentException(
                        "ACTOR_STATE_LOAN_UNKNOWN_FIELD: " + selector);
            }
            var schema = schemaField(descriptor, field, selector);
            if (!schema.binding().equals("LET")) {
                throw new IllegalArgumentException(
                        "ACTOR_STATE_LOAN_IMMUTABLE_FIELD: " + field);
            }
            if (typedLiteral(assign.value(), schema.type())) {
                kind = Kind.WRITE_LITERAL;
            } else if (schema.type().equals("int")
                    && assign.value() instanceof Ast.BinaryExpr sum
                    && sum.operator().equals("+")
                    && Objects.equals(field, stateField(sum.left(), stateBinding.name()))
                    && typedLiteral(sum.right(), "int")) {
                // Only a shape proof. No overflow mode or checked arithmetic
                // lowering is authorized by this syntactic classification.
                kind = Kind.WRITE_INCREMENT_UNLOWERED;
            } else {
                throw new IllegalArgumentException(
                        "ACTOR_STATE_LOAN_WRITE_VALUE_UNSUPPORTED: " + selector);
            }
        }
        if (field == null) {
            throw new IllegalArgumentException(
                    "ACTOR_STATE_LOAN_UNKNOWN_FIELD: " + selector);
        }
        var schema = schemaField(descriptor, field, selector);
        if (read && !method.returnType().name().equals(schema.type())) {
            throw new IllegalArgumentException(
                    "ACTOR_STATE_LOAN_RETURN_TYPE_MISMATCH: " + selector);
        }
        if (write && !method.returnType().name().equals("void")) {
            throw new IllegalArgumentException(
                    "ACTOR_STATE_LOAN_RETURN_TYPE_MISMATCH: " + selector);
        }
        return new CheckedLoan(selector, actorUnit, methodUnit, stateUnit,
                field, schema.type(), kind);
    }

    private static String stateField(Ast.Expr expr, String binding) {
        if (expr instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr receiver
                && receiver.name().equals(binding)) {
            return member.member();
        }
        return null;
    }

    private static DeclaredActorDescriptor.SealedField schemaField(
            DeclaredActorDescriptor descriptor, String field,
            ActorMethodIndex.MethodKey selector) {
        for (var candidate : descriptor.sealedFields()) {
            if (candidate.name().equals(field)) return candidate;
        }
        throw new IllegalArgumentException(
                "ACTOR_STATE_LOAN_UNKNOWN_FIELD: " + selector + "." + field);
    }

    private static boolean typedLiteral(Ast.Expr expr, String type) {
        if (!(expr instanceof Ast.LiteralExpr literal)) return false;
        return (type.equals("int") && literal.value() instanceof Long)
                || (type.equals("bool") && literal.value() instanceof Boolean);
    }
}
