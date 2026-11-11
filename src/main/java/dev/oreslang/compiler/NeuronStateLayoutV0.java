package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.ActorStateReadWriteLoans;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Checked scalar memory layout for a neuron actor's protected state schema.
 *
 * This emits a TYPE/LAYOUT PLAN ONLY. Guest handle_state MIR and physical
 * actor scheduler admission are deliberately not implemented here.
 * Rejection at the ordinary TypeChecker stage remains mandatory.
 */
public record NeuronStateLayoutV0(String actorName, List<String> floatFields) {
    public NeuronStateLayoutV0 {
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(floatFields, "floatFields");
        if (actorName.isBlank() || actorName.length() > 128
                || floatFields.isEmpty() || floatFields.size() > 128)
            throw new IllegalArgumentException("NEURON_STATE_LAYOUT_INVALID: invalid actor or schema size");
        floatFields = List.copyOf(floatFields);
        Set<String> seen = new HashSet<>();
        for (String field : floatFields) {
            if (field.isBlank() || field.length() > 128 || field.startsWith("$")
                    || !seen.add(field))
                throw new IllegalArgumentException("NEURON_STATE_LAYOUT_INVALID: duplicate or blank field");
        }
    }

    /**
     * Only plain, actor-owned float slots are supported in scalar v0.
     * Initializer evaluation cannot be hidden in a host constructor: it needs
     * checked actor activation and critical-write execution, so block it.
     */
    public static NeuronStateLayoutV0 fromActor(Ast.ClassDecl actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.actorKind() == Ast.ActorKind.NONE
                || actor.actorConcurrency().role() != Ast.ActorRole.NEURON
                || actor.actorConcurrency().transport() != Ast.ActorTransport.GENERIC
                || actor.actorConcurrency().requestConcurrency() != 1
                || actor.actorConcurrency().messageConcurrency() != 1
                || !actor.fields().isEmpty()
                || actor.stateFields().isEmpty()
                || !actor.genericParameters().isEmpty()
                || !actor.parents().isEmpty()) {
            throw new IllegalArgumentException(
                    "NEURON_STATE_LAYOUT_INVALID: protected scalar neuron actor required");
        }
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (Ast.FieldDecl field : actor.stateFields()) {
            if (field.visibility() != Ast.Visibility.PRIVATE
                    || field.bindingKind() != Ast.BindingKind.LET
                    || !Ast.TypeRef.simple("float").equals(field.type())
                    || !field.annotations().isEmpty()
                    || field.initializer() != null) {
                throw new IllegalArgumentException(
                        "NEURON_STATE_LAYOUT_UNSUPPORTED: private 'let float "
                                + field.name() + ";' required; state initializers "
                                + "need compiler-checked activation");
            }
            names.add(field.name());
        }
        return new NeuronStateLayoutV0(actor.name(), names);
    }

    /**
     * Allocate the VM's private scalar region for ONE actor generation.
     * No raw storage or authority is returned to guest/supervisor code.
     * Scheduler integration is still required before this becomes callable
     * from emitted AttoLang programs.
     */
    public ActorStateReadWriteLoans newHostState(long generation, int maxQueuedLoans) {
        return new ActorStateReadWriteLoans(generation, maxQueuedLoans, floatFields);
    }
}
