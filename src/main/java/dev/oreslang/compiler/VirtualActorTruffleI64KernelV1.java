package dev.oreslang.compiler;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JVM/GraalVM backend's first pure, checked-i64 Truffle kernel lowered from
 * the finite protected virtual-actor operations MIR. This is actual Truffle
 * call-target generation for the arithmetic candidate, NOT guest virtual
 * actor execution, heap access, actor activation, or an ownership grant.
 *
 * Compiling a scalar expression separately from its state transaction means
 * the result cannot be committed without a future compiler-generated exclusive
 * loan and actor/generation/physical-quiescence validation. The production
 * TypeChecker deliberately continues rejecting virtual actor declarations.
 */
public final class VirtualActorTruffleI64KernelV1 {
    private VirtualActorTruffleI64KernelV1() { }

    /**
     * Actual backend node for ADD_I64_TRAP_ON_OVERFLOW. The guest arithmetic
     * cannot use wrapping '+'; Math.addExact has precisely signed i64 overflow
     * semantics on both HotSpot/JIT and Native Image, subject to real tests.
     *
     * There is NO reference to actor state and NO callback under a state loan.
     */
    private static final class CheckedAddI64Node extends Node {
        private long executeI64(long current, long argument) {
            return Math.addExact(current, argument);
        }
    }

    /** Language-independent scalar kernel: no Context, actor or heap authority. */
    private static final class KernelRootNode extends RootNode {
        @Child private CheckedAddI64Node add = new CheckedAddI64Node();

        private KernelRootNode() {
            super(null); // Pure numeric call target; never a guest-visible Oreslang root.
        }

        @Override public String getName() { return "atto-virtual-actor-pure-i64-add-v1"; }
        @Override public boolean isInternal() { return true; }

        @Override public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            if (args.length != 2
                    || !(args[0] instanceof Long current)
                    || !(args[1] instanceof Long argument)) {
                throw new IllegalArgumentException(
                        "VIRTUAL_ACTOR_KERNEL_REJECTED: exact two signed i64 operands required");
            }
            return add.executeI64(current, argument);
        }
    }

    /**
     * A compiler-generated pure arithmetic method, NOT a guest-visible
     * VirtualActorRef method, public actor function or state writer.
     * An ABI slot cannot be inferred from localOrdinal across hot upgrades.
     */
    public static final class CompiledMethod {
        private final VirtualActorProtectedMirV1.CheckedAdd signature;
        private final int localOrdinal;
        private final RootCallTarget scalarCallTarget;

        private CompiledMethod(VirtualActorProtectedOpsMirV1.MethodPlan admitted) {
            this.signature = Objects.requireNonNull(admitted, "method").signature();
            this.localOrdinal = admitted.localOrdinal();
            VirtualActorProtectedOpsMirV1.verifyCandidate(
                    admitted.instructions(), admitted.overflowExit());
            if (localOrdinal < 0 || signature.arity() != 1
                    || !Ast.TypeRef.simple("int").equals(signature.parameterType())
                    || !Ast.TypeRef.simple("void").equals(signature.returnType()))
                throw new IllegalArgumentException("VIRTUAL_ACTOR_KERNEL_REJECTED: invalid method signature");
            this.scalarCallTarget = new KernelRootNode().getCallTarget();
        }

        public String name() { return signature.method(); }
        public int localOrdinal() { return localOrdinal; }

        /**
         * Compute the value to be committed, never write it. A future actor
         * backend must call this only within a pinned physical turn and
         * exclusive state-write transaction, with the generation fence checked
         * at the actual commit linearization point. Overflow MUST leave state
         * unchanged and transfer the failure to supervisor terminal CONTROL.
         */
        public long computeCandidate(long current, long argument) {
            Object result = scalarCallTarget.call(current, argument);
            if (!(result instanceof Long value))
                throw new IllegalStateException("virtual actor scalar backend returned non-i64");
            return value;
        }
    }

    /**
     * Compile-time method selection only. There is no logical address,
     * dispatch table on live actors, reflection or guest method authority.
     */
    public static final class CompiledProgram {
        private final VirtualActorProtectedMirV1.TypedRefContract contract;
        private final List<CompiledMethod> methods;

        private CompiledProgram(VirtualActorProtectedMirV1.TypedRefContract contract,
                                List<CompiledMethod> methods) {
            this.contract = Objects.requireNonNull(contract, "contract");
            this.methods = List.copyOf(methods);
        }

        public String declaration() { return contract.declaredActor(); }
        public List<CompiledMethod> methods() { return methods; }

        public CompiledMethod requireCandidate(String name, List<Ast.TypeRef> argumentTypes) {
            VirtualActorProtectedMirV1.CheckedAdd admitted =
                    contract.requireCall(name, argumentTypes);
            for (CompiledMethod method : methods) {
                if (method.name().equals(admitted.method()))
                    return method;
            }
            throw new IllegalStateException("compiler method table lost checked signature");
        }
    }

    /**
     * The only backend entry: accepts only the private-constructor operations
     * plan minted by the exact AST/provenance validator. Re-check the complete
     * instruction stream and the overflow exceptional-exit obligations here.
     *
     * This cannot be installed in OresLanguage.parse / guest dispatch until
     * separate actor-state loans, storage, CONTROL, lifetime and ownership
     * obligations are implemented and proven.
     */
    public static CompiledProgram lower(VirtualActorProtectedOpsMirV1.ProgramPlan admitted) {
        Objects.requireNonNull(admitted, "admitted MIR");
        List<CompiledMethod> compiled = new ArrayList<>(admitted.methods().size());
        for (VirtualActorProtectedOpsMirV1.MethodPlan method : admitted.methods()) {
            if (method.localOrdinal() != compiled.size()
                    || !admitted.referenceType().requireCall(method.signature().method(),
                            List.of(method.signature().parameterType())).equals(method.signature())
                    || !method.signature().field().equals(admitted.state().name())) {
                throw new IllegalArgumentException(
                        "VIRTUAL_ACTOR_KERNEL_REJECTED: inconsistent typed method slot");
            }
            compiled.add(new CompiledMethod(method));
        }
        if (compiled.isEmpty() || compiled.size() != admitted.methods().size())
            throw new IllegalArgumentException("VIRTUAL_ACTOR_KERNEL_REJECTED: incomplete lowering");
        return new CompiledProgram(admitted.referenceType(), compiled);
    }
}
