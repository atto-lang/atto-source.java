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

    /**
     * Mandatory overflow exit obligations for a future backend. These are
     * semantic requirements, not executable cleanup instructions or grants
     * of runtime authority. Until the backend and supervisor implement every
     * step, TypeChecker must keep virtual actor execution disabled.
     *
     * On checked overflow: never STORE, unwind the exclusive write loan,
     * fence the failed physical incarnation, deliver sanitized terminal
     * CONTROL, and wait for asynchronous supervisor acknowledgement before
     * resolving the next generation.
     */
    public enum OverflowExitStep {
        DISCARD_UNCOMMITTED_WRITE,
        UNWIND_EXCLUSIVE_WRITE_LOAN,
        FENCE_FAILED_PHYSICAL_GENERATION,
        EMIT_SANITIZED_TERMINAL_CONTROL,
        AWAIT_SUPERVISOR_ACK_BEFORE_REACTIVATION
    }

    public static final class OverflowExit {
        private final Op trigger;
        private final List<OverflowExitStep> requiredSteps;

        private OverflowExit(Op trigger, List<OverflowExitStep> steps) {
            this.trigger = Objects.requireNonNull(trigger, "trigger");
            this.requiredSteps = List.copyOf(steps);
        }

        public Op trigger() { return trigger; }
        public List<OverflowExitStep> requiredSteps() { return requiredSteps; }
    }

    private static final OverflowExit CHECKED_ADD_OVERFLOW = new OverflowExit(
            Op.ADD_I64_TRAP_ON_OVERFLOW, List.of(
                    OverflowExitStep.DISCARD_UNCOMMITTED_WRITE,
                    OverflowExitStep.UNWIND_EXCLUSIVE_WRITE_LOAN,
                    OverflowExitStep.FENCE_FAILED_PHYSICAL_GENERATION,
                    OverflowExitStep.EMIT_SANITIZED_TERMINAL_CONTROL,
                    OverflowExitStep.AWAIT_SUPERVISOR_ACK_BEFORE_REACTIVATION));

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
        private final OverflowExit overflowExit;

        private MethodPlan(VirtualActorProtectedMirV1.CheckedAdd signature,
                           int localOrdinal, List<Instruction> instructions,
                           OverflowExit overflowExit) {
            this.signature = Objects.requireNonNull(signature, "signature");
            if (localOrdinal < 0) throw new IllegalArgumentException("negative ordinal");
            this.localOrdinal = localOrdinal;
            this.instructions = List.copyOf(instructions);
            this.overflowExit = Objects.requireNonNull(overflowExit, "overflowExit");
            verifyCandidate(this.instructions, this.overflowExit);
        }

        public VirtualActorProtectedMirV1.CheckedAdd signature() { return signature; }
        public int localOrdinal() { return localOrdinal; }
        public List<Instruction> instructions() { return instructions; }
        public OverflowExit overflowExit() { return overflowExit; }
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
            // Guard against future partial lowering or slot/ordinal mismatch.
            List<VirtualActorProtectedMirV1.CheckedAdd> sorted =
                    new ArrayList<>(admittedSource.methods());
            sorted.sort(Comparator.comparing(VirtualActorProtectedMirV1.CheckedAdd::method));
            for (int i = 0; i < this.methods.size(); i++) {
                MethodPlan method = this.methods.get(i);
                if (method.localOrdinal() != i || !method.signature().equals(sorted.get(i))
                        || !method.signature().field().equals(admittedSource.state().name())) {
                    throw new IllegalArgumentException("incomplete or reordered protected method slot");
                }
            }
        }

        public String declaration() { return admittedSource.declaration(); }
        public VirtualActorProtectedMirV1.ScalarState state() { return admittedSource.state(); }
        public VirtualActorProtectedMirV1.TypedRefContract referenceType() {
            return admittedSource.referenceType();
        }
        public List<MethodPlan> methods() { return methods; }
    }

    /**
     * Verify a *candidate representation* without authorizing execution or
     * constructing a MethodPlan. This is also used by negative mutation tests.
     * No other instruction order or overflow edge is accepted in v1.
     */
    public static void verifyCandidate(List<Instruction> instructions, OverflowExit overflow) {
        if (instructions == null || !CHECKED_ADD_SEQUENCE.equals(instructions))
            throw new IllegalArgumentException("VIRTUAL_ACTOR_OPS_MIR_REJECTED: unsafe instruction stream");
        if (overflow == null || overflow.trigger() != CHECKED_ADD_OVERFLOW.trigger()
                || !CHECKED_ADD_OVERFLOW.requiredSteps().equals(overflow.requiredSteps()))
            throw new IllegalArgumentException("VIRTUAL_ACTOR_OPS_MIR_REJECTED: missing overflow cleanup and fencing");
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
            methods.add(new MethodPlan(method, ordinal, CHECKED_ADD_SEQUENCE,
                    CHECKED_ADD_OVERFLOW));
        }
        return new ProgramPlan(admitted, methods);
    }
}
