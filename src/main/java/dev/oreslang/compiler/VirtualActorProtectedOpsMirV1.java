package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A finite, typed protected-state operation MIR for the Cart-shaped virtual actor.
 *
 * This is compiler-owned *pre-backend* IR, not executable bytecode. It does
 * not allocate an actor, grant a live state loan, or authorize guest dispatch.
 * Every accepted source body first passes VirtualActorProtectedMirV1's exact
 * typed token admission. No arbitrary guest tokens become operations.
 *
 * A backend must provide real checked overflow traps, actor-owned storage,
 * nonescaping loan scopes, generation-fenced dispatch and supervisor CONTROL
 * before the production virtual-actor execution gate may be removed.
 */
public final class VirtualActorProtectedOpsMirV1 {
    private VirtualActorProtectedOpsMirV1() { }

    public enum Op {
        BEGIN_EXCLUSIVE_WRITE_LOAN,
        LOAD_STATE_I64,
        LOAD_ARGUMENT_I64,
        ADD_I64_TRAP_ON_OVERFLOW,
        STORE_STATE_I64,
        END_EXCLUSIVE_WRITE_LOAN,
        RETURN_VOID
    }

    /** SSA-like scalar values; none is a pointer, borrow or runtime capability. */
    public enum Register { NONE, STATE_I64, ARGUMENT_I64, SUM_I64 }

    public record Instruction(Op op, Register destination,
                              Register left, Register right) {
        public Instruction {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    private static final List<Instruction> CHECKED_ADD_SEQUENCE = List.of(
            new Instruction(Op.BEGIN_EXCLUSIVE_WRITE_LOAN,
                    Register.NONE, Register.NONE, Register.NONE),
            new Instruction(Op.LOAD_STATE_I64,
                    Register.STATE_I64, Register.NONE, Register.NONE),
            new Instruction(Op.LOAD_ARGUMENT_I64,
                    Register.ARGUMENT_I64, Register.NONE, Register.NONE),
            new Instruction(Op.ADD_I64_TRAP_ON_OVERFLOW,
                    Register.SUM_I64, Register.STATE_I64, Register.ARGUMENT_I64),
            new Instruction(Op.STORE_STATE_I64,
                    Register.NONE, Register.SUM_I64, Register.NONE),
            new Instruction(Op.END_EXCLUSIVE_WRITE_LOAN,
                    Register.NONE, Register.NONE, Register.NONE),
            new Instruction(Op.RETURN_VOID,
                    Register.NONE, Register.NONE, Register.NONE));

    /**
     * A local compiler ordering index only: NOT a persistent dispatch slot,
     * wire identifier or ABI suitable for hot-upgrade compatibility checks.
     */
    public static final class MethodPlan {
        private final VirtualActorProtectedMirV1.CheckedAdd signature;
        private final int localOrdinal;
        private final List<Instruction> instructions;

        private MethodPlan(VirtualActorProtectedMirV1.CheckedAdd signature,
                           int localOrdinal, List<Instruction> instructions) {
            this.signature = Objects.requireNonNull(signature, "signature");
            if (localOrdinal < 0) throw new IllegalArgumentException("negative ordinal");
            this.localOrdinal = localOrdinal;
            this.instructions = List.copyOf(instructions);
            if (!this.instructions.equals(CHECKED_ADD_SEQUENCE))
                throw new IllegalArgumentException("unverified protected-state instruction stream");
        }

        public VirtualActorProtectedMirV1.CheckedAdd signature() { return signature; }
        public int localOrdinal() { return localOrdinal; }
        public List<Instruction> instructions() { return instructions; }
    }

    /**
     * Only this lowering pass can construct an authoritative candidate plan;
     * returned lists are immutable and contain no physical actor authority.
     */
    public static final class ProgramPlan {
        private final VirtualActorProtectedMirV1.Plan admittedSource;
        private final List<MethodPlan> methods;

        private ProgramPlan(VirtualActorProtectedMirV1.Plan admittedSource,
                            List<MethodPlan> methods) {
            this.admittedSource = Objects.requireNonNull(admittedSource, "admittedSource");
            this.methods = List.copyOf(methods);
            if (this.methods.isEmpty() || this.methods.size() != admittedSource.methods().size())
                throw new IllegalArgumentException("incomplete protected method lowering");
        }

        public String declaration() { return admittedSource.declaration(); }
        public VirtualActorProtectedMirV1.ScalarState state() { return admittedSource.state(); }
        public VirtualActorProtectedMirV1.TypedRefContract referenceType() {
            return admittedSource.referenceType();
        }
        public List<MethodPlan> methods() { return methods; }
    }

    /**
     * Lower the closed, statically admitted grammar to a fixed linear MIR.
     * Overflow trapping precedes state STORE; the exclusive state loan spans
     * every state operation and ends before RETURN. No suspension, closure,
     * arbitrary call, shared pointer or branch exists in this v1 grammar.
     */
    public static ProgramPlan lower(Ast.VirtualActorDecl actor) {
        VirtualActorProtectedMirV1.Plan admitted = VirtualActorProtectedMirV1.analyze(actor);
        List<VirtualActorProtectedMirV1.CheckedAdd> slots = new ArrayList<>(admitted.methods());
        slots.sort(Comparator.comparing(VirtualActorProtectedMirV1.CheckedAdd::method));
        List<MethodPlan> methods = new ArrayList<>(slots.size());
        for (int ordinal = 0; ordinal < slots.size(); ordinal++) {
            VirtualActorProtectedMirV1.CheckedAdd method = slots.get(ordinal);
            if (!method.field().equals(admitted.state().name())
                    || !method.parameterType().equals(Ast.TypeRef.simple("int"))
                    || !method.returnType().equals(Ast.TypeRef.simple("void"))) {
                throw new IllegalArgumentException("protected MIR signature/field invariant violated");
            }
            methods.add(new MethodPlan(method, ordinal, CHECKED_ADD_SEQUENCE));
        }
        return new ProgramPlan(admitted, methods);
    }
}
