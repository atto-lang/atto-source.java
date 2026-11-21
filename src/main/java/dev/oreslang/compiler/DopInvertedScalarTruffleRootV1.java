package dev.oreslang.compiler;

import com.oracle.truffle.api.RootCallTarget;
import dev.oreslang.OresLanguage;
import dev.oreslang.runtime.OresContext;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;
import java.util.Objects;

/**
 * Restricted typed-plan to Truffle RootCallTarget vertical slice.
 *
 * This executes compiler-verified scalar operations through explicit Truffle
 * child nodes, not by evaluating user source text, re-parsing the source at
 * runtime, or dispatching arbitrary host closures. Each call owns a fresh,
 * bounded private SoA region that is reclaimed even on a bounds exception.
 *
 * IMPORTANT: This is NOT registered as the general OresLanguage parse path.
 * Its backing store remains a Java host reference, not a native AOT or
 * zero-GC allocation proof. Actor/tenant/suspension integration remains
 * fail-closed until ownership and context admission are implemented.
 */
public final class DopInvertedScalarTruffleRootV1 extends RootNode {
    private final DopInvertedScalarCompilerV1.Plan plan;
    // Null only for the isolated host-owned calltarget test API; language
    // executions *must* hold the exact live single-use source scope.
    private final Object sourceLease;
    // Defense-in-depth against a parsed root being reused under another
    // polyglot context while the same host thread still holds its lease.
    private final java.util.concurrent.atomic.AtomicReference<java.util.UUID> boundContextId =
            new java.util.concurrent.atomic.AtomicReference<>();

    @Children private final OperationNode[] instructions;

    private DopInvertedScalarTruffleRootV1(
            OresLanguage language, DopInvertedScalarCompilerV1.Plan plan,
            Object sourceLease) {
        super(language);
        this.plan = Objects.requireNonNull(plan, "verified bounded compiler plan");
        this.sourceLease = sourceLease;
        List<DopInvertedScalarCompilerV1.Instruction> ops = plan.instructions();
        if (ops.isEmpty() || ops.getLast().op()
                != DopInvertedScalarCompilerV1.Op.RETURN_READ) {
            throw new IllegalArgumentException("DOP verified plan has no terminal read");
        }
        instructions = new OperationNode[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            var op = ops.get(i);
            instructions[i] = switch (op.op()) {
                case PUSH -> new PushNode(op.first(), op.second());
                case WRITE -> new WriteNode(op.index(), op.field(), op.first());
                case ERASE_SWAP -> new EraseSwapNode(op.index());
                case RETURN_READ -> new ReadNode(op.index(), op.field());
            };
            if (i < ops.size() - 1 &&
                    op.op() == DopInvertedScalarCompilerV1.Op.RETURN_READ) {
                throw new IllegalArgumentException("DOP early terminal operation");
            }
        }
    }

    /**
     * Isolated compiler API: only a Plan with a private compiler constructor
     * is accepted. Never attach this root to the general language parse path
     * or create a foreign AST escape route.
     */
    public static RootCallTarget lowerVerified(DopInvertedScalarCompilerV1.Plan plan) {
        return new DopInvertedScalarTruffleRootV1(null, plan, null).getCallTarget();
    }

    /**
     * Trusted OresLanguage parse-bound source scope. The invocation authority
     * remains checked on every execution, including cached calltarget reuse.
     * Arbitrary objects cannot grant this permission: OresLanguage verifies
     * reference identity against a private per-thread source lease.
     */
    public static RootCallTarget lowerForVerifiedSource(
            OresLanguage language, DopInvertedScalarCompilerV1.Plan plan,
            Object sourceLease) {
        Objects.requireNonNull(language, "registered OresLanguage");
        Objects.requireNonNull(sourceLease, "active verified DOP source lease");
        OresLanguage.requireLiveVerifiedDopLease(sourceLease);
        return new DopInvertedScalarTruffleRootV1(
                language, plan, sourceLease).getCallTarget();
    }

    @Override public String getName() { return "atto-dop-inverted-i64-v1"; }
    @Override public boolean isInternal() { return true; }

    @Override
    public Object execute(VirtualFrame frame) {
        // The verified function is main(): int with zero parameters.
        if (frame.getArguments().length != 0) {
            throw new IllegalArgumentException("DOP verified root takes zero arguments");
        }
        if (sourceLease != null) {
            // A trusted host opened the restricted source; it does not grant
            // general actor/tenant or adversarial-isolate execution.
            OresLanguage.requireLiveVerifiedDopLease(sourceLease);
            OresContext context = OresContext.get(this);
            java.util.UUID currentContextId = context.contextId();
            boundContextId.compareAndSet(null, currentContextId);
            if (!currentContextId.equals(boundContextId.get())) {
                throw new SecurityException(
                        "DOP_VERIFIED_CONTEXT_MISMATCH: root cannot cross isolate context");
            }
            if (context.isolatePolicy().adversarial()) {
                throw new SecurityException(
                        "DOP_VERIFIED_TENANT_UNSUPPORTED: adversarial isolate admission remains closed");
            }
        }
        try (InvertedSchemaStorageBridge columns = plan.openCheckedOwner()) {
            for (OperationNode op : instructions) {
                if (op instanceof ReadNode read) {
                    return read.read(columns);
                }
                op.execute(columns);
            }
            throw new IllegalStateException("DOP verified root missing terminal read");
        }
    }

    private abstract static class OperationNode extends Node {
        abstract void execute(InvertedSchemaStorageBridge columns);
    }

    private static final class PushNode extends OperationNode {
        private final long first;
        private final long second;

        PushNode(long first, long second) {
            this.first = first;
            this.second = second;
        }
        @Override void execute(InvertedSchemaStorageBridge columns) {
            columns.push(first, second);
        }
    }

    private static final class WriteNode extends OperationNode {
        private final int index;
        private final String field;
        private final long value;

        WriteNode(int index, String field, long value) {
            this.index = index;
            this.field = field;
            this.value = value;
        }
        @Override void execute(InvertedSchemaStorageBridge columns) {
            columns.write(columns.handleAt(index), field, value);
        }
    }

    private static final class EraseSwapNode extends OperationNode {
        private final int index;

        EraseSwapNode(int index) { this.index = index; }
        @Override void execute(InvertedSchemaStorageBridge columns) {
            columns.eraseSwap(columns.handleAt(index));
        }
    }

    private static final class ReadNode extends OperationNode {
        private final int index;
        private final String field;

        ReadNode(int index, String field) {
            this.index = index;
            this.field = field;
        }
        long read(InvertedSchemaStorageBridge columns) {
            return columns.get(columns.handleAt(index), field);
        }
        @Override void execute(InvertedSchemaStorageBridge columns) {
            throw new IllegalStateException("terminal read cannot execute as a statement");
        }
    }
}
