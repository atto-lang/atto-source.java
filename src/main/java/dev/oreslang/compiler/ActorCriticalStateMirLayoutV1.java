package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A typed, deterministic state-slot layout and restricted operation inventory
 * built only after manifest-owned actor binding and critical AST preflight.
 *
 * This is NOT executable MIR or a runtime-owned loan/capability. The compiler
 * MUST continue rejecting stateful guest execution until generation-fenced
 * runtime lowering and exactly-once cleanup are independently verified.
 */
public final class ActorCriticalStateMirLayoutV1 {
    private ActorCriticalStateMirLayoutV1() { }

    public enum Opcode { CHECKED_ADD_MAIL_I64, COPY_I64_TO_ACTOR_MAILBOX }

    public record Slot(int index, String name, String scalarType,
                       boolean mutable, Object literalDefault) {
        public Slot {
            if (index < 0 || index >= 32 || name == null || name.isBlank()
                    || !("int".equals(scalarType) || "bool".equals(scalarType))
                    || (scalarType.equals("int") && !(literalDefault instanceof Long))
                    || (scalarType.equals("bool") && !(literalDefault instanceof Boolean))) {
                throw new IllegalArgumentException("invalid checked actor state slot");
            }
        }
    }

    public record Operation(String sourceUnit, String method, Opcode opcode,
                            Ast.CriticalStateMode mode, int fieldIndex) {
        public Operation {
            Objects.requireNonNull(sourceUnit);
            Objects.requireNonNull(method);
            Objects.requireNonNull(opcode);
            Objects.requireNonNull(mode);
            if (fieldIndex < 0 || fieldIndex >= 32
                    || (mode == Ast.CriticalStateMode.WRITE
                        && opcode != Opcode.CHECKED_ADD_MAIL_I64)
                    || (mode == Ast.CriticalStateMode.READ
                        && opcode != Opcode.COPY_I64_TO_ACTOR_MAILBOX)) {
                throw new IllegalArgumentException("invalid checked actor state operation");
            }
        }
    }

    public record Plan(String packageIdentity, String namespace, String actor,
                       String stateUnit, String stateSourceSha256,
                       String manifestSha256, List<Slot> slots,
                       List<Operation> operations) {
        public Plan {
            Objects.requireNonNull(packageIdentity);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(stateUnit);
            requireSha256(stateSourceSha256);
            requireSha256(manifestSha256);
            slots = List.copyOf(slots);
            operations = List.copyOf(operations);
            if (slots.isEmpty() || slots.size() > 32) {
                throw new IllegalArgumentException("invalid actor state layout size");
            }
        }
    }

    private static void requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("state layout requires exact SHA-256");
        }
    }

    /**
     * Consumes a manifest-proven, immutable source snapshot and independently
     * recomputes the exact AST preflight inventory; supplied operation records
     * cannot be promoted by merely constructing public record instances.
     * Returned Plans are metadata, NEVER runtime tickets or execution permits.
     */
    public static List<Plan> stage(Map<String, Ast.Program> units,
                                   ActorContractWitness.Result witness,
                                   Map<String, String> sourceSha256,
                                   String manifestSha256,
                                   List<ActorCriticalStatePreflight.CriticalOperation> preflight) {
        Objects.requireNonNull(units);
        Objects.requireNonNull(witness);
        Objects.requireNonNull(sourceSha256);
        Objects.requireNonNull(preflight);
        requireSha256(manifestSha256);
        if (!sourceSha256.keySet().equals(units.keySet())) {
            throw new IllegalArgumentException("state layout source snapshot mismatch");
        }
        for (String digest : sourceSha256.values()) requireSha256(digest);
        if (!ActorCriticalStatePreflight.verify(units, witness).equals(preflight)) {
            throw new IllegalArgumentException("state layout requires original verified operations");
        }

        List<Plan> plans = new ArrayList<>();
        for (var entry : witness.actors().entrySet()) {
            var key = entry.getKey();
            String stateUnit = entry.getValue().stateUnit();
            if (stateUnit == null) continue;
            Ast.Program source = units.get(stateUnit);
            if (source == null || !key.namespace().equals(source.namespace())) {
                throw new IllegalArgumentException("state layout lost manifest-owned schema");
            }
            Ast.ActorStateAttachmentDecl schema = null;
            for (Ast.ModuleDecl module : source.modules()) {
                if (!Parser.ROOT_MODULE.equals(module.name())) continue;
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.ActorStateAttachmentDecl state
                            && state.actorName().equals(key.actor())) {
                        if (schema != null) throw new IllegalArgumentException("duplicate state layout");
                        schema = state;
                    }
                }
            }
            if (schema == null) throw new IllegalArgumentException("missing actor state layout");
            List<Slot> slots = new ArrayList<>();
            Map<String, Slot> indexed = new HashMap<>();
            for (var field : schema.fields()) {
                Object initializer = ((Ast.LiteralExpr) field.initializer()).value();
                var slot = new Slot(slots.size(), field.name(), field.type().name(),
                        field.bindingKind() == Ast.BindingKind.LET, initializer);
                if (indexed.putIfAbsent(field.name(), slot) != null) {
                    throw new IllegalArgumentException("ambiguous state layout slot");
                }
                slots.add(slot);
            }
            List<Operation> ops = new ArrayList<>();
            for (var op : preflight) {
                if (!key.namespace().equals(op.namespace())
                        || !key.actor().equals(op.actor())) continue;
                Slot field = indexed.get(op.field());
                if (field == null || !field.scalarType().equals(op.scalarType())
                        || !"int".equals(field.scalarType())
                        || (op.mode() == Ast.CriticalStateMode.WRITE && !field.mutable())
                        || !sourceSha256.containsKey(op.sourceUnit())) {
                    throw new IllegalArgumentException("critical state operation changed its verified field");
                }
                ops.add(new Operation(op.sourceUnit(), op.method(),
                        op.mode() == Ast.CriticalStateMode.WRITE
                                ? Opcode.CHECKED_ADD_MAIL_I64
                                : Opcode.COPY_I64_TO_ACTOR_MAILBOX,
                        op.mode(), field.index()));
            }
            plans.add(new Plan(key.packageIdentity(), key.namespace(), key.actor(),
                    stateUnit, sourceSha256.get(stateUnit), manifestSha256,
                    slots, ops));
        }
        return List.copyOf(plans);
    }
}
