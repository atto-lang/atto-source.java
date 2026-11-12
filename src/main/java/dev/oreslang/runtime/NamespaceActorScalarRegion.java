package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Actor-affine physical RW-loan substrate for the internal closed scalar MIR.
 *
 * <p>This is runtime/compiler infrastructure, not a guest-callable state API.
 * Only a real ActorRuntime dispatcher turn can initialize, read or write it.
 * The compiler never receives a raw pointer or writable state object.
 *
 * <p>One logical actor instance owns one scalar allocation domain and one
 * ActorStateRwLoans manager. Pending capacity is zero: critical methods never
 * park a carrier, await, or queue for a state loan. The physical manager from
 * #2796 enforces exclusive writers, reader consistency and STOP retirement.
 */
public final class NamespaceActorScalarRegion {
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorId owner;
    private final SharedCodeImageStore.CodeImage image;
    private final long allocation;
    private final ActorStateRwLoans loans = new ActorStateRwLoans(0);
    private final boolean booleanField;
    private long integerValue;
    private boolean booleanValue;
    private boolean retired;

    private NamespaceActorScalarRegion(
            ActorRuntime.ActorContext<Object> context,
            long allocation,
            long initialInteger,
            boolean initialBoolean,
            boolean booleanField) {
        this.runtime = context.runtime();
        this.owner = context.self().id();
        this.image = context.codeImage().orElseThrow(
                () -> new SecurityException("sealed scalar allocation requires code image"));
        this.allocation = allocation;
        this.booleanField = booleanField;
        this.integerValue = initialInteger;
        this.booleanValue = initialBoolean;
    }

    /** Runtime-controlled, owner-local allocation during external init only. */
    public static NamespaceActorScalarRegion initialize(
            ActorRuntime.ActorContext<Object> context,
            long initialInteger,
            boolean initialBoolean,
            boolean booleanField) {
        Objects.requireNonNull(context, "actor runtime context");
        SharedCodeImageStore.CodeImage sourceImage = context.codeImage().orElseThrow(
                () -> new SecurityException("source actor init needs code image"));
        ActorRuntime.requireSourceStateTurn(
                context, sourceImage, ActorRuntime.SourceStatePhase.INIT);
        if (context.currentMail().isPresent()) {
            throw new SecurityException("sealed scalar state may be allocated only on init");
        }
        long domain = ActorRuntime.issueSourceStateAllocation(context, sourceImage);
        return new NamespaceActorScalarRegion(
                context, domain, initialInteger, initialBoolean, booleanField);
    }

    /**
     * Compiler-lowered nonescaping scalar write. This operation is checked
     * before mutation and cannot leave a live loan on overflow or failure.
     */
    public void increment(ActorRuntime.ActorContext<Object> context) {
        ExecutionContext execution = authority(context, true);
        if (booleanField) throw new SecurityException("bool state is not integer state");
        try (var loan = loans.acquireWriteAsync(execution).join()) {
            requireWriter(loan, execution);
            integerValue = Math.addExact(integerValue, 1L);
        }
    }

    /** Compiler-lowered exact boolean write; there is no int/bool coercion. */
    public void setTrue(ActorRuntime.ActorContext<Object> context) {
        ExecutionContext execution = authority(context, true);
        if (!booleanField) throw new SecurityException("int state is not bool state");
        try (var loan = loans.acquireWriteAsync(execution).join()) {
            requireWriter(loan, execution);
            booleanValue = true;
        }
    }

    /*
     * Runtime-internal scalar read proof. No actor-ref accessor is exported,
     * and the current compiler subset does not yet lower guest state reads.
     */
    long readIntegerInsideTurn(ActorRuntime.ActorContext<Object> context) {
        ExecutionContext execution = authority(context, false);
        if (booleanField) throw new SecurityException("bool state is not integer state");
        try (var loan = loans.acquireReadAsync(execution).join()) {
            requireReader(loan, execution);
            return integerValue;
        }
    }

    boolean readBooleanInsideTurn(ActorRuntime.ActorContext<Object> context) {
        ExecutionContext execution = authority(context, false);
        if (!booleanField) throw new SecurityException("int state is not bool state");
        try (var loan = loans.acquireReadAsync(execution).join()) {
            requireReader(loan, execution);
            return booleanValue;
        }
    }

    /**
     * A synchronous terminal failure or graceful actor teardown revokes future
     * acquisitions. Quiescence must be observed before any owner reclamation.
     */
    public void retire(ActorRuntime.ActorContext<Object> context) {
        // Genuine failed receives and graceful TEARDOWN can retire this
        // allocation. A forged or stale INIT/host context cannot revoke it.
        try {
            requireOwner(context, ActorRuntime.SourceStatePhase.RECEIVE);
        } catch (SecurityException notReceive) {
            requireOwner(context, ActorRuntime.SourceStatePhase.TEARDOWN);
        }
        if (retired) return;
        retired = true;
        loans.close();
        if (!loans.physicallyRetired()) {
            throw new IllegalStateException(
                    "cannot retire actor scalar while state loans remain physically live");
        }
        integerValue = 0;
        booleanValue = false;
    }

    public ActorRuntime.ActorId ownerActorId() {
        return owner;
    }

    private ExecutionContext authority(
            ActorRuntime.ActorContext<Object> context, boolean requireMail) {
        requireOwner(context, ActorRuntime.SourceStatePhase.RECEIVE);
        if (retired) throw new SecurityException("actor scalar allocation has retired");
        if (requireMail && context.currentMail().isEmpty()) {
            throw new SecurityException("critical state write requires mailbox ingress");
        }
        // Only ActorRuntime chooses the execution identity / slot.
        // It remains stable for reads and writes in this same REAL turn,
        // and is impossible to replay after source dispatch returns.
        return ActorRuntime.sourceStateExecution(context, image, allocation);
    }

    private void requireOwner(
            ActorRuntime.ActorContext<Object> context,
            ActorRuntime.SourceStatePhase phase) {
        ActorRuntime.requireSourceStateTurn(context, image, phase);
        if (context.runtime() != runtime
                || !owner.equals(context.self().id())
                || context.codeImage().orElse(null) != image) {
            throw new SecurityException("cross-actor scalar state or code-image access");
        }
    }

    private static void requireWriter(
            ActorStateRwLoans.StateLoan loan, ExecutionContext execution) {
        if (loan.access() != ActorStateRwLoans.Access.WRITE
                || !loan.heldBy(execution)) {
            throw new SecurityException("scalar write lacks physical exclusive state loan");
        }
    }

    private static void requireReader(
            ActorStateRwLoans.StateLoan loan, ExecutionContext execution) {
        if (loan.access() != ActorStateRwLoans.Access.READ
                || !loan.heldBy(execution)) {
            throw new SecurityException("scalar read lacks physical state loan");
        }
    }
}
