package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Deliberately serialized, trusted-HOST integration proof joining immutable
 * bounded proxy mail to real source-actor physical send and exact terminal
 * receipts.
 *
 * <p>Only signed 64-bit scalar mail is encoded here; arbitrary guest objects
 * and schemas require a compiler-owned typed wire codec. Successful physical
 * enqueue is not logical completion: the proxy refunds credits only after the
 * runtime-minted terminal witness for that exact accepted physical sequence
 * succeeds. No parallel guest-visible dispatch, durable exactly-once effects,
 * or isolated/untrusted placement is claimed. A failed/ambiguous send,
 * terminal receipt, or deadline seals dispatch without retrying possibly
 * accepted work.</p>
 */
public final class LogicalSourceActorProxyBridgeV1 implements AutoCloseable {
    private final Object dispatchLock = new Object();
    // Bridge audit: all upgrade publication is serialized by dispatchLock.
    // Separate from dispatchLock: admission remains open while upgrading,
    // but close must atomically seal admission before physical teardown.
    private final Object admissionLock = new Object();
    private final LogicalSourceActorRouterV1 router;
    private final LogicalActorProxyMailboxV1 mailbox;
    private final String messageAbiSha256;
    // Non-null only for the explicit compiler-ABI-bound scalar transport.
    // Legacy SHARED host-scalar behavior remains a separate compatibility path.
    private final SourceActorScalarWireV1.Binding scalarWireBinding;
    private final SourceActorTypedWireV2.Binding typedWireBinding;
    private final int maxMessageBytes;
    private final ActorHotUpgradeTaskOwnerV1 taskOwner;
    private volatile boolean poisoned;
    private volatile boolean closed;
    // Under dispatchLock. Sealing admission does not prove that physical
    // ActorRuntime.stop and generation retirement have completed.
    private boolean physicallyRetired;

    private LogicalSourceActorProxyBridgeV1(
            LogicalSourceActorRouterV1 router,
            LogicalActorProxyMailboxV1 mailbox,
            boolean compilerAbiScalar, boolean typedPrimitive,
            int maxMessageBytes) {
        this.router = Objects.requireNonNull(router);
        this.mailbox = Objects.requireNonNull(mailbox);
        this.maxMessageBytes = maxMessageBytes;
        this.taskOwner = new ActorHotUpgradeTaskOwnerV1(router);
        router.bindProxyMailboxForSupervisor(mailbox, compilerAbiScalar, typedPrimitive);
        this.scalarWireBinding = compilerAbiScalar
                ? router.scalarWireBindingForProxy(mailbox) : null;
        this.typedWireBinding = typedPrimitive
                ? router.typedWireBindingForProxy(mailbox) : null;
        this.messageAbiSha256 =
                router.supervisorPublication(mailbox).messageAbiSha256();
    }

    public static LogicalSourceActorProxyBridgeV1 spawnShared(
            HotReloadManager.Generation first,
            String actorTypeName,
            Duration readyTimeout,
            int maxMessages,
            int maxMessageBytes,
            long maxRetainedBytes)
            throws InterruptedException, ExecutionException, TimeoutException {
        return spawnSharedMode(first, actorTypeName, readyTimeout,
                maxMessages, maxMessageBytes, maxRetainedBytes, false, false);
    }

    /**
     * Transitional compiler-ABI-framed signed-i64 SHARED source actor bridge.
     * The ABI digest is minted by the linked actor compiler, not provided by
     * the sender. This does NOT enable arbitrary guest struct/tuple messages
     * or protected PRIVATE/UNTRUSTED transports.
     */
    public static LogicalSourceActorProxyBridgeV1 spawnSharedAbiBoundLong(
            HotReloadManager.Generation first,
            String actorTypeName,
            Duration readyTimeout,
            int maxMessages,
            int maxMessageBytes,
            long maxRetainedBytes)
            throws InterruptedException, ExecutionException, TimeoutException {
        return spawnSharedMode(first, actorTypeName, readyTimeout,
                maxMessages, maxMessageBytes, maxRetainedBytes, true, false);
    }

    /**
     * Compiler-verified primitive ActorMail<int|bool|string> transport.
     * ORM2 is host-only SHARED routing, not struct/union encoding or IPC.
     */
    public static LogicalSourceActorProxyBridgeV1 spawnSharedTypedPrimitive(
            HotReloadManager.Generation first,
            String actorTypeName,
            Duration readyTimeout,
            int maxMessages,
            int maxMessageBytes,
            long maxRetainedBytes)
            throws InterruptedException, ExecutionException, TimeoutException {
        return spawnSharedMode(first, actorTypeName, readyTimeout,
                maxMessages, maxMessageBytes, maxRetainedBytes, false, true);
    }

    private static LogicalSourceActorProxyBridgeV1 spawnSharedMode(
            HotReloadManager.Generation first,
            String actorTypeName,
            Duration readyTimeout,
            int maxMessages,
            int maxMessageBytes,
            long maxRetainedBytes,
            boolean compilerAbiScalar, boolean typedPrimitive)
            throws InterruptedException, ExecutionException, TimeoutException {
        // Capacity validation must precede physical actor spawn.
        if (compilerAbiScalar
                && maxMessageBytes < SourceActorScalarWireV1.BYTE_LENGTH)
            throw new IllegalArgumentException(
                    "compiler-ABI scalar wire exceeds configured message byte budget");
        if (typedPrimitive
                && (maxMessageBytes < SourceActorTypedWireV2.HEADER_BYTES + Long.BYTES
                    || maxMessageBytes > SourceActorTypedWireV2.MAX_FRAME_BYTES))
            throw new IllegalArgumentException(
                    "ORM2 actor mail per-message capacity outside bounded range");
        var mailbox = new LogicalActorProxyMailboxV1(
                maxMessages, maxMessageBytes, maxRetainedBytes);
        var router = LogicalSourceActorRouterV1.spawn(
                first, ActorRuntime.ActorKind.SHARED, actorTypeName, readyTimeout);
        try {
            return new LogicalSourceActorProxyBridgeV1(
                    router, mailbox, compilerAbiScalar, typedPrimitive,
                    maxMessageBytes);
        } catch (RuntimeException | Error failure) {
            try {
                router.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Read-only logical address, never an ActorRef carrying a raw send().
     * The bridge must not leak a bypass around its bounded proxy credits.
     */
    public LogicalSourceActorRouterV1.LogicalAddress address() {
        requireHost();
        // Do not expose the router's new epoch while upgradeTo() still holds
        // an uncommitted proxy mailbox freeze ticket.
        synchronized (dispatchLock) {
            return router.ref().address();
        }
    }

    /**
     * The only coherent bridge-level publication observation: physical
     * route and proxy mailbox epoch are captured under the same monitor
     * as the upgrade transaction. Separate address()/snapshot() calls
     * cannot promise one cross-call point-in-time observation.
     */
    public record Publication(
            LogicalSourceActorRouterV1.LogicalAddress address,
            LogicalActorProxyMailboxV1.Snapshot mailbox,
            long generationId,
            String codeSha256,
            String linkedCodeSha256,
            String stateSchemaSha256,
            String messageAbiSha256,
            boolean failSealed,
            boolean retirementQuarantined) {
        /**
         * A diagnostic snapshot is not a usable publication certificate
         * after an ambiguous physical send or upgrade. Callers must inspect
         * the immutable quarantine flag instead of inferring health from
         * matching generation, ABI and route epochs.
         */
        public boolean authoritativeForDispatch() {
            // A fully coherent diagnostic may still represent a legitimate
            // in-progress supervisor freeze. Frozen dispatch is NOT an
            // authority to submit physical work, even before fail sealing.
            return !failSealed && !retirementQuarantined
                    && !mailbox.closed() && !mailbox.dispatchFrozen()
                    && address.routeEpoch() == mailbox.routeEpoch();
        }
    }

    public Publication publication() {
        requireHost();
        synchronized (dispatchLock) {
            // One physical route monitor witnesses code generation, exact
            // physical actor and immutable proxy route epoch together.
            // dispatchLock also excludes proxy dispatch and upgrade while
            // the committed publication is being observed.
            var witness = router.supervisorPublication(mailbox);
            if (!poisoned
                    && witness.address().routeEpoch()
                            != witness.mailbox().routeEpoch()) {
                poisonAndSealAdmission();
                throw new IllegalStateException(
                        "inconsistent code-image and proxy route publication");
            }
            // Every bound proxy retains immutable admitted bytes across
            // route publication, including the legacy fixed-i64 proof mode.
            // The compiler receive ABI is therefore stable for the entire
            // logical incarnation independently of the selected wire codec.
            if (!poisoned
                    && !messageAbiSha256.equals(witness.messageAbiSha256())) {
                poisonAndSealAdmission();
                throw new SecurityException(
                        "bound proxy compiler message ABI changed");
            }
            // Compiler-ABI-bound retained mail belongs to one nominal
            // receive contract for the entire logical incarnation. The
            // supervisor must not certify a successor whose linked message
            // ABI has drifted even when route/mailbox epochs still agree.
            if (!poisoned && scalarWireBinding != null
                    && !scalarWireBinding.messageAbiSha256()
                            .equals(witness.messageAbiSha256())) {
                poisonAndSealAdmission();
                throw new SecurityException(
                        "compiler message ABI differs from immutable proxy binding");
            }
            // ORM2 has its own compiler-bound nominal receive contract.
            // A healthy code/route epoch must not certify an ActorMail<T>
            // frame under a foreign or drifted receive ABI.
            if (!poisoned && typedWireBinding != null
                    && !typedWireBinding.identity().messageAbiSha256()
                            .equals(witness.messageAbiSha256())) {
                poisonAndSealAdmission();
                throw new SecurityException(
                        "ORM2 compiler message ABI differs from immutable proxy binding");
            }
            return new Publication(
                    witness.address(), witness.mailbox(),
                    witness.generationId(), witness.codeSha256(),
                    witness.linkedCodeSha256(),
                    witness.stateSchemaSha256(),
                    witness.messageAbiSha256(),
                    poisoned || closed,
                    witness.retirementQuarantined());
        }
    }

    public GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> nextOutput(
            Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        requireHost();
        return router.ref().nextOutput(timeout);
    }

    /**
     * Host-only verification hook: bind one resumable scheduler frame to the
     * current physical actor before its source generation can be retired.
     * Ownership/cancellation/physical join now use canonical OresTaskScope;
     * this bridge remains proof-only because the compiler has not yet issued
     * an actor/route/code-affinity receipt for a lowered source Task frame.
     */
    <T> OresFuture<T> startHostPinnedTaskForUpgrade(
            OresScheduler scheduler, OresScheduler.Task<T> frame) {
        requireHost();
        synchronized (dispatchLock) {
            requireUsable();
            return taskOwner.start(scheduler, frame);
        }
    }

    ActorHotUpgradeTaskOwnerV1 childHostTaskScopeForUpgrade() {
        requireHost();
        synchronized (dispatchLock) {
            requireUsable();
            return taskOwner.childScope();
        }
    }

    public LogicalActorProxyMailboxV1.Snapshot snapshot() {
        requireHost();
        synchronized (dispatchLock) {
            return mailbox.snapshot();
        }
    }

    public boolean failSealed() { return poisoned; }

    // Test-only coherent observation under the physical route monitor.
    boolean routeEpochsAlignedForTesting() {
        requireHost();
        return router.proxyRouteEpochAlignedForTesting(mailbox);
    }

    /**
     * Admission is bounded and remains available while the upgrade holds
     * dispatchLock. Legacy mode stores immutable endian-stable i64 bytes;
     * compiler-ABI scalar mode stores a canonical immutable frame bound to
     * the linked compiler's message ABI and this logical actor identity.
     * It does not claim arbitrary source message typing or physical delivery.
     */
    public LogicalActorProxyMailboxV1.Envelope admitLong(long value) {
        requireHost();
        synchronized (admissionLock) {
            if (closed || poisoned)
                throw new IllegalStateException(
                        "logical proxy bridge is closed or fail-sealed");
            final byte[] bytes;
            if (typedWireBinding != null) {
                bytes = SourceActorTypedWireV2.encodeLong(
                        typedWireBinding, value, maxMessageBytes);
            } else if (scalarWireBinding != null) {
                bytes = SourceActorScalarWireV1.encodeLong(
                        scalarWireBinding, value);
            } else {
                bytes = new byte[Long.BYTES];
                for (int i = Long.BYTES - 1; i >= 0; --i) {
                    bytes[i] = (byte) value;
                    value >>>= Byte.SIZE;
                }
            }
            return mailbox.admit(bytes);
        }
    }

    public LogicalActorProxyMailboxV1.Envelope admitBoolean(boolean value) {
        requireHost();
        synchronized (admissionLock) {
            if (closed || poisoned)
                throw new IllegalStateException(
                        "logical proxy bridge is closed or fail-sealed");
            if (typedWireBinding == null)
                throw new SecurityException("boolean mail requires ORM2 compiler binding");
            return mailbox.admit(SourceActorTypedWireV2.encodeBoolean(
                    typedWireBinding, value, maxMessageBytes));
        }
    }

    public LogicalActorProxyMailboxV1.Envelope admitString(String value) {
        requireHost();
        synchronized (admissionLock) {
            if (closed || poisoned)
                throw new IllegalStateException(
                        "logical proxy bridge is closed or fail-sealed");
            if (typedWireBinding == null)
                throw new SecurityException("string mail requires ORM2 compiler binding");
            return mailbox.admit(SourceActorTypedWireV2.encodeString(
                    typedWireBinding, value, maxMessageBytes));
        }
    }

    /**
     * Dispatch at most one message; only a successful *physical drain* removes
     * its retention credit. All dispatches are serialized for this v1 bridge,
     * making the actor's drain prefix an unambiguous per-dispatch barrier.
     * Physical acceptance/drain failure is ambiguous and leaves the envelope
     * outstanding until explicit supervisor reconciliation; NEVER auto-retry.
     */
    public boolean dispatchOne(Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        requireHost();
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("dispatch deadline must be positive");
        synchronized (dispatchLock) {
            requireUsable();
            try {
                // A failed or corrupted dispatch reservation may have changed
                // mailbox accounting; it must not escape the fail-seal handler.
                var envelope = mailbox.pollDispatch();
                if (envelope == null) return false;
                // Only the authoritative physical router may decode and
                // send these immutable bytes. Its opaque receipt binds the
                // exact envelope, route epoch and physical actor instance;
                // only its successful exact-message terminal receipt may
                // refund proxy credits.
                long routeEpoch = mailbox.snapshot().routeEpoch();
                var receipt = router.sendAndDrainForProxy(
                        mailbox, envelope, routeEpoch);
                receipt.awaitAndComplete(envelope, timeout);
                return true;
            } catch (InterruptedException | ExecutionException | TimeoutException
                    | RuntimeException | Error uncertain) {
                poisonAndSealAdmission();
                throw uncertain;
            }
        }
    }

    /**
     * Freeze proxy dispatch, then migrate the physical route at a drained
     * watermark. Bounded admission can continue concurrently. If upgrade
     * raises an exception, retain the freeze and fail sealed, because the
     * physical router may already have published a candidate.
     */
    public boolean upgradeTo(
            HotReloadManager.Generation candidate, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        requireHost();
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("upgrade deadline must be positive");
        synchronized (dispatchLock) {
            requireUsable();
            try {
                // The router owns freeze acquisition, rollback and commit as
                // part of one SupervisorUpgradeTxn. The bridge contributes
                // only its dispatchLock serialization and mailbox identity;
                // it never holds an independently reusable freeze capability.
                if (!router.upgradeToForProxy(
                        candidate, timeout, mailbox)) {
                    return false;
                }
                if (mailbox.snapshot().routeEpoch() != router.ref().routeEpoch()) {
                    poisonAndSealAdmission();
                    throw new IllegalStateException(
                            "logical proxy and physical route publication epochs disagree");
                }
                // Force every committed route, including legacy i64 mode,
                // through the compiler-ABI publication witness before this
                // call can report success or later dispatch can resume.
                publication();
                return true;
            } catch (InterruptedException | ExecutionException | TimeoutException
                    | RuntimeException | Error uncertain) {
                // The router transaction alone decides whether a precommit
                // freeze was rolled back or publication became irreversible.
                // Any exception remains fail-sealed at this bridge boundary
                // and requires supervisor retirement/reconciliation.
                poisonAndSealAdmission();
                throw uncertain;
            }
        }
    }

    private static void requireHost() {
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()
                || ActorRuntime.isActorCarrierThread()
                || OresScheduler.current() != null
                || OresScheduler.isSchedulerCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()) {
            // Carrier/terminal callbacks can run after CURRENT turn was
            // cleared. They are still not a trusted blocking supervisor.
            throw new SecurityException(
                    "bounded logical source proxy bridge is a HOST-only API");
        }
    }

    /**
     * Commit the terminal failure fence under the SAME lock as admission.
     * The invoking dispatch/upgrade/publication holds dispatchLock first,
     * preserving the established dispatch -> admission lock order.
     * A producer that observed the pre-failure flag cannot publish a
     * newly admitted envelope after this terminal seal.
     */
    private void poisonAndSealAdmission() {
        synchronized (admissionLock) {
            poisoned = true;
        }
    }

    private void requireUsable() {
        if (closed || poisoned)
            throw new IllegalStateException("logical proxy bridge is closed or fail-sealed");
    }

    @Override public void close() {
        requireHost();
        synchronized (dispatchLock) {
            synchronized (admissionLock) {
                if (physicallyRetired) return;
                if (!closed) {
                    // Sealing is permanent once admission is quiescent. A
                    // previously failed physical retirement must not reopen
                    // admission during a later cleanup attempt.
                    if (mailbox.snapshot().retainedMessages() != 0) {
                        throw new IllegalStateException(
                                "cannot close bounded proxy with outstanding or queued mail");
                    }
                    closed = true;
                    poisoned = true;
                }
            }
            // ActorRuntime.stop() has a bounded wait. If it throws, leave
            // physicallyRetired=false so a later close retries the router's
            // pinned-generation cleanup without admitting new messages.
            // A cancelled result is not a physical Task exit. The owning
            // scope cancels all descendants and joins every generation pin
            // before the router is allowed to retire actor code/context.
            taskOwner.close();
            router.close();
            mailbox.close();
            physicallyRetired = true;
        }
    }
}
