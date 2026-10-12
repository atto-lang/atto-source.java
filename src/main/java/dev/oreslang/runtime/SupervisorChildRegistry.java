package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted host bookkeeping for a supervisor's logical children.
 *
 * ChildId is stable across successful incarnation replacements; ActorId is
 * not. This is NOT a guest ActorRef, restart capability, or implementation of
 * lifecycle effects. The owning runtime must prove physical finalization
 * before calling finalized(), and must have successfully created/authorized
 * the replacement before calling replace().
 *
 * Mutations are package-private: guest Oreslang cannot mint a restart token
 * by constructing a ChildId or by reusing a terminated ActorId.
 */
public final class SupervisorChildRegistry {
    public record ChildId(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            UUID logicalId) {
        public ChildId {
            Objects.requireNonNull(supervisorId);
            Objects.requireNonNull(groupId);
            Objects.requireNonNull(logicalId);
        }
    }
    public enum Phase { ACTIVE, FINALIZED, RESTARTING, STOPPING, STOPPED }
    /** Authoritative, monotonic in-memory decision receipt. No implicit retry. */
    public enum StopReceiptStatus { PENDING, APPLIED, ACKNOWLEDGED, UNCERTAIN }
    public record StopReceipt(StopReservation reservation, StopReceiptStatus status) {
        public StopReceipt {
            Objects.requireNonNull(reservation);
            Objects.requireNonNull(status);
        }
    }

    /** Host-only STOP claim; holds a logical generation across an await. */
    public record StopReservation(ChildId child, ActorRuntime.ActorId failedActor,
                                  long incarnation, long turnGeneration, UUID nonce) {
        public StopReservation {
            Objects.requireNonNull(child);
            Objects.requireNonNull(failedActor);
            Objects.requireNonNull(nonce);
            if (incarnation < 1 || turnGeneration < 1)
                throw new IllegalArgumentException("invalid STOP generation");
        }
    }

    /** A single-use host-side claim; the UUID is not a guest capability. */
    public record RestartReservation(ChildId child, ActorRuntime.ActorId failedActor,
                                     long incarnation, UUID nonce) {
        public RestartReservation {
            Objects.requireNonNull(child);
            Objects.requireNonNull(failedActor);
            Objects.requireNonNull(nonce);
            if (incarnation <= 0) throw new IllegalArgumentException("invalid incarnation");
        }
    }
    public record Snapshot(
            ChildId child,
            ActorRuntime.ActorId actor,
            long incarnation,
            Phase phase) {
        public Snapshot {
            Objects.requireNonNull(child);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(phase);
            if (incarnation <= 0) throw new IllegalArgumentException("incarnation must be positive");
        }
    }

    /**
     * Host-internal registry transitions, deliberately package-private.
     * Registry-committed transitions only: NOT a full supervisor execution trace.
     * In particular, scheduler cleanup, CONTROL ownership and host STOP invocation
     * are not asserted by entries here. Each transition is captured under this
     * registry's own monitor, without invoking external observer callbacks.
     */
    record Transition(long sequence, String operation, Snapshot state,
                             UUID restartPermit, long stopTurn,
                             ActorRuntime.ActorId replacement, UUID stopPermit) {}
    private static final int MAX_TRANSITIONS = 100_000;
    private final List<Transition> transitions = new ArrayList<>();
    private boolean traceComplete = true;

    /** A dropped or failed transition permanently invalidates the audit history. */
    synchronized List<Transition> committedTransitions() {
        if (!traceComplete)
            throw new IllegalStateException("incomplete supervisor registry audit trace");
        return List.copyOf(transitions);
    }

    synchronized boolean auditTraceComplete() { return traceComplete; }

    private void recordTransition(String operation, Snapshot state, UUID permit,
                                  long stopTurn, ActorRuntime.ActorId candidate) {
        recordTransition(operation, state, permit, stopTurn, candidate, null);
    }

    private void recordStopTransition(String operation, Snapshot state,
                                      StopReservation permit) {
        recordTransition(operation, state, null, permit.turnGeneration(),
                null, permit.nonce());
    }

    private void recordTransition(String operation, Snapshot state, UUID permit,
                                  long stopTurn, ActorRuntime.ActorId candidate,
                                  UUID stopPermit) {
        if (!traceComplete) return;
        if (transitions.size() >= MAX_TRANSITIONS) {
            traceComplete = false;
            return;
        }
        // Auditing must never run user code or disturb an already committed state.
        try {
            transitions.add(new Transition(transitions.size() + 1L,
                    operation, state, permit, stopTurn, candidate, stopPermit));
        } catch (Throwable failure) {
            traceComplete = false;
        }
    }

    private final ActorRuntime.SupervisorId supervisor;
    private final ActorRuntime.ActorGroupId group;
    private final Map<UUID, Snapshot> children = new HashMap<>();
    private final Map<ActorRuntime.ActorId, UUID> activeActors = new HashMap<>();
    private final Map<UUID, RestartReservation> reservations = new HashMap<>();
    private enum ReplacementPhase { SPAWNED, ADMITTED, CLEANED }
    private record ReplacementState(ActorRuntime.ActorId actor, long generation,
                                    ReplacementPhase phase) {}
    private final Map<UUID, ReplacementState> replacements = new HashMap<>();
    // A candidate's physical ActorId may belong to at most one pending claim.
    private final Map<ActorRuntime.ActorId, UUID> stagedActors = new HashMap<>();
    private boolean restartClosed;
    private final Map<UUID, StopReservation> stopReservations = new HashMap<>();
    // Exact, one-use host invocation claim, bounded by logical children.
    // Does not assert that an external effect succeeded.
    private final Set<UUID> invokedStops = new HashSet<>();
    /** Per logical-child ledger: bounded by the existing child registry. */
    private final Map<UUID, StopReceipt> stopReceipts = new HashMap<>();
    // ActorId must not be recycled (A -> B -> A is another form of ABA).
    // This is a host bookkeeping ledger, bounded by a per-supervisor ceiling.
    private static final int MAX_ACTOR_IDENTITIES = 1_000_000;
    private final Set<ActorRuntime.ActorId> seenActors = new HashSet<>();

    SupervisorChildRegistry(
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId group) {
        this.supervisor = Objects.requireNonNull(supervisor);
        this.group = Objects.requireNonNull(group);
    }

    /** Caller has already admitted this newly created actor to the group. */
    synchronized Snapshot register(ActorRuntime.ActorId actor) {
        Objects.requireNonNull(actor);
        requireFreshActor(actor);
        ChildId id = new ChildId(supervisor, group, UUID.randomUUID());
        Snapshot first = new Snapshot(id, actor, 1, Phase.ACTIVE);
        // Stage mutations, then roll back all indexes if an allocation fails.
        seenActors.add(actor);
        try {
            activeActors.put(actor, id.logicalId());
            children.put(id.logicalId(), first);
        } catch (Throwable failure) {
            children.remove(id.logicalId());
            activeActors.remove(actor);
            // Physical actor creation has already occurred. A failed registry
            // insertion must not make its ActorId reusable by stale callbacks.
            // Burn that identity even though no logical child was published.
            throw failure;
        }
        recordTransition("register", first, null, 0L, null);
        return first;
    }

    public synchronized Snapshot snapshot(ChildId child) {
        return requireCurrent(child);
    }
    /**
     * Trusted owner rolls back an admission or releases a still-active child
     * before it has physically terminated. Never treat this as finalization:
     * no Failure is emitted and no restart is authorized by the old key.
     */
    synchronized void detachActive(ChildId child, ActorRuntime.ActorId actor, long incarnation) {
        Snapshot current = requireExpected(child, actor, incarnation, Phase.ACTIVE);
        activeActors.remove(current.actor());
        children.remove(child.logicalId());
        recordTransition("detach-active", current, null, 0L, null);
        // Preserve seenActors: a detached actor identity must not be reused
        // as an unrelated incarnation of this supervisor (ABA).
    }


    /**
     * The lifecycle owner must complete the actor's physical finalization
     * before marking its exact incarnation finalized. This check prevents
     * stale finalizers from authorizing replacement of a newer incarnation.
     */
    synchronized Snapshot finalized(
            ChildId child, ActorRuntime.ActorId actor, long incarnation) {
        Snapshot before = requireExpected(child, actor, incarnation, Phase.ACTIVE);
        activeActors.remove(before.actor());
        Snapshot after = new Snapshot(child, actor, incarnation, Phase.FINALIZED);
        children.put(child.logicalId(), after);
        recordTransition("finalize-asserted", after, null, 0L, null);
        return after;
    }

    /**
     * Claim the finalized generation BEFORE creating a replacement actor.
     * At most one restart attempt may hold this claim at a time. This does
     * not spawn an actor or prove the finalization barrier.
     */
    synchronized RestartReservation beginRestart(
            ChildId child, ActorRuntime.ActorId failedActor, long incarnation) {
        Snapshot finalized = requireExpected(
                child, failedActor, incarnation, Phase.FINALIZED);
        if (restartClosed || replacements.containsKey(child.logicalId())
                || reservations.containsKey(child.logicalId()))
            throw new IllegalStateException("supervisor restart admission closed or conflicting");
        if (incarnation == Long.MAX_VALUE)
            throw new IllegalStateException("logical child generation exhausted");
        // Construct all immutable records before publishing an exclusive
        // restart claim. Partial failure must not leave a reservation
        // attached to a child still marked FINALIZED.
        RestartReservation permit = new RestartReservation(
                child, failedActor, incarnation, UUID.randomUUID());
        Snapshot restarting = new Snapshot(
                child, failedActor, incarnation, Phase.RESTARTING);
        try {
            reservations.put(child.logicalId(), permit);
            children.put(child.logicalId(), restarting);
        } catch (Throwable publicationFailure) {
            reservations.remove(child.logicalId());
            children.put(child.logicalId(), finalized);
            throw publicationFailure;
        }
        recordTransition("restart.reserve", restarting, permit.nonce(), 0L, null);
        return permit;
    }

    /**
     * Call after physical spawn, before routing the candidate. Admission proof
     * must be obtained from the owning scheduler, not guest-supplied IDs.
     */
    synchronized void stageReplacement(RestartReservation permit,
                                       ActorRuntime.ActorId candidate) {
        checkReservation(permit);
        if (restartClosed) throw new IllegalStateException("supervisor is closed");
        requireFreshActor(candidate);
        if (replacements.containsKey(permit.child().logicalId())
                || stagedActors.containsKey(candidate))
            throw new IllegalStateException("candidate already staged");
        ReplacementState state = new ReplacementState(
                candidate, permit.incarnation() + 1, ReplacementPhase.SPAWNED);
        // A spawned physical incarnation is permanently consumed, including
        // failed stage publication and cleanup-backed RESTART abort. Never
        // allow stale callbacks to target a recycled ActorId (ABA).
        seenActors.add(candidate);
        stagedActors.put(candidate, permit.child().logicalId());
        try {
            replacements.put(permit.child().logicalId(), state);
            recordTransition("restart.spawn-asserted",
                    requireCurrent(permit.child()), permit.nonce(), 0L, candidate);
        } catch (Throwable failed) {
            stagedActors.remove(candidate);
            throw failed;
        }
    }

    private ReplacementState requireReplacement(
            RestartReservation permit, ActorRuntime.ActorId actor) {
        checkReservation(permit);
        var result = replacements.get(permit.child().logicalId());
        if (result == null || !result.actor().equals(actor)
                || result.generation() != permit.incarnation() + 1)
            throw new IllegalStateException("missing or stale physical replacement");
        return result;
    }

    /** Verify host scheduler admission outside the registry monitor. */
    void acknowledgeReplacementAdmission(
            RestartReservation permit, ActorRuntime.ActorId actor,
            java.util.function.BooleanSupplier admittedByScheduler) {
        Objects.requireNonNull(admittedByScheduler);
        synchronized (this) {
            if (requireReplacement(permit, actor).phase() != ReplacementPhase.SPAWNED
                    || restartClosed)
                throw new IllegalStateException("replacement cannot be admitted");
        }
        if (!admittedByScheduler.getAsBoolean())
            throw new IllegalStateException("scheduler has not admitted replacement");
        synchronized (this) {
            if (requireReplacement(permit, actor).phase() != ReplacementPhase.SPAWNED
                    || restartClosed)
                throw new IllegalStateException("admission became stale");
            replacements.put(permit.child().logicalId(), new ReplacementState(
                    actor, permit.incarnation() + 1, ReplacementPhase.ADMITTED));
            recordTransition("restart.admit-verified",
                    requireCurrent(permit.child()), permit.nonce(), 0L, actor);
        }
    }

    /** Verify physical termination outside the registry monitor. */
    void acknowledgeReplacementCleanup(
            RestartReservation permit, ActorRuntime.ActorId actor,
            java.util.function.BooleanSupplier cleanedByScheduler) {
        Objects.requireNonNull(cleanedByScheduler);
        synchronized (this) {
            var status = requireReplacement(permit, actor).phase();
            if (status != ReplacementPhase.SPAWNED && status != ReplacementPhase.ADMITTED)
                throw new IllegalStateException("candidate not live");
        }
        if (!cleanedByScheduler.getAsBoolean())
            throw new IllegalStateException("candidate still physically live");
        synchronized (this) {
            var status = requireReplacement(permit, actor).phase();
            if (status != ReplacementPhase.SPAWNED && status != ReplacementPhase.ADMITTED)
                throw new IllegalStateException("cleanup became stale");
            replacements.put(permit.child().logicalId(), new ReplacementState(
                    actor, permit.incarnation() + 1, ReplacementPhase.CLEANED));
            recordTransition("restart.cleanup-verified",
                    requireCurrent(permit.child()), permit.nonce(), 0L, actor);
        }
    }

    /**
     * Read-only, exact one-use reservation validation before a physical spawn.
     * A logical ChildId alone cannot authorize allocating a replacement.
     * This is consulted only by trusted host code; the actor scheduler must
     * separately pin physical admission when publishing the candidate.
     */
    synchronized void requireOpenRestartReservation(RestartReservation permit) {
        if (restartClosed)
            throw new IllegalStateException("supervisor restart admission is closed");
        checkReservation(permit);
    }

    /**
     * Host-only physical-DONE cleanup reconciliation for a candidate that
     * may never have crossed stageReplacement. A stopped, never-staged actor
     * is not a live logical incarnation and must not strand RESTART forever.
     * A staged candidate is marked CLEANED only when the original scheduler
     * confirms the exact physical actor has finished. The probe runs outside
     * the registry monitor; termination is monotonic and cannot be undone.
     */
    void acknowledgeCleanupIfStaged(
            RestartReservation permit, ActorRuntime.ActorId candidate,
            java.util.function.BooleanSupplier physicallyDone) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(physicallyDone);
        if (!physicallyDone.getAsBoolean())
            throw new IllegalStateException("restart candidate has not physically terminated");
        synchronized (this) {
            checkReservation(permit);
            ReplacementState current = replacements.get(permit.child().logicalId());
            if (current == null) return; // never staged, still one-use permit
            if (!current.actor().equals(candidate)
                    || current.generation() != permit.incarnation() + 1)
                throw new IllegalStateException("foreign or stale cleanup candidate");
            if (current.phase() == ReplacementPhase.CLEANED) return;
            if (current.phase() != ReplacementPhase.SPAWNED
                    && current.phase() != ReplacementPhase.ADMITTED)
                throw new IllegalStateException("invalid replacement cleanup state");
            replacements.put(permit.child().logicalId(), new ReplacementState(
                    candidate, current.generation(), ReplacementPhase.CLEANED));
            recordTransition("restart.cleanup-verified",
                    requireCurrent(permit.child()), permit.nonce(), 0L, candidate);
        }
    }

    /** A closed supervisor may clean up, but cannot publish a new incarnation. */
    synchronized void closeRestartAdmission() {
        if (restartClosed) return;
        restartClosed = true;
        recordTransition("restart.close", null, null, 0L, null);
    }

    /** Caller must have admitted the actual replacement before commit. */
    synchronized Snapshot replace(
            RestartReservation permit, ActorRuntime.ActorId replacement) {
        var admitted = requireReplacement(permit, replacement);
        if (restartClosed || admitted.phase() != ReplacementPhase.ADMITTED)
            throw new IllegalStateException("replacement lacks physical admission or supervisor closed");
        if (!seenActors.contains(replacement) || activeActors.containsKey(replacement)
                || !permit.child().logicalId().equals(stagedActors.get(replacement)))
            throw new IllegalStateException("candidate is not exclusively staged");
        Snapshot after = new Snapshot(
                permit.child(), replacement, permit.incarnation() + 1, Phase.ACTIVE);
        // Commit both actor indexes or neither; a failed commit keeps the
        // reservation so the trusted owner can clean up and abort.
        try {
            activeActors.put(replacement, permit.child().logicalId());
            children.put(permit.child().logicalId(), after);
        } catch (Throwable failure) {
            activeActors.remove(replacement);
            // Do NOT release seenActors: this ActorId was physically spawned.
            throw failure;
        }
        stagedActors.remove(replacement);
        replacements.remove(permit.child().logicalId());
        reservations.remove(permit.child().logicalId());
        recordTransition("restart.commit", after, permit.nonce(), 0L, replacement);
        return after;
    }

    /** Trusted owner rolls back a failed spawn/admission; STOP may then win. */
    synchronized Snapshot abortRestart(RestartReservation permit) {
        checkReservation(permit);
        var candidate = replacements.get(permit.child().logicalId());
        if (candidate != null && candidate.phase() != ReplacementPhase.CLEANED)
            throw new IllegalStateException("physical cleanup required before abort");
        Snapshot restored = new Snapshot(
                permit.child(), permit.failedActor(), permit.incarnation(), Phase.FINALIZED);
        children.put(permit.child().logicalId(), restored);
        if (candidate != null) stagedActors.remove(candidate.actor());
        replacements.remove(permit.child().logicalId());
        reservations.remove(permit.child().logicalId());
        recordTransition("restart.abort", restored, permit.nonce(), 0L,
                candidate == null ? null : candidate.actor());
        return restored;
    }

    private void checkReservation(RestartReservation permit) {
        Objects.requireNonNull(permit);
        requireExpected(permit.child(), permit.failedActor(),
                permit.incarnation(), Phase.RESTARTING);
        if (!permit.equals(reservations.get(permit.child().logicalId())))
            throw new IllegalStateException("foreign or stale restart reservation");
    }

    private void requireFreshActor(ActorRuntime.ActorId actor) {
        Objects.requireNonNull(actor);
        if (seenActors.contains(actor) || activeActors.containsKey(actor)
                || stagedActors.containsKey(actor))
            throw new IllegalStateException("actor incarnation identity was already used");
        if (seenActors.size() >= MAX_ACTOR_IDENTITIES)
            throw new IllegalStateException("actor identity ledger capacity exhausted");
    }

    /**
     * Reserve STOP before invoking any host effect. RESTART and competing
     * STOP requests cannot claim this generation while the host is awaiting
     * acknowledgment. On ambiguous host failure this remains STOPPING until
     * a trusted lifecycle reconciler resolves it; never auto-retry/restart.
     */
    synchronized StopReservation beginStop(
            ChildId child, ActorRuntime.ActorId actor,
            long incarnation, long turnGeneration) {
        Snapshot finalized = requireExpected(
                child, actor, incarnation, Phase.FINALIZED);
        if (stopReservations.containsKey(child.logicalId())
                || stopReceipts.containsKey(child.logicalId())) {
            throw new IllegalStateException("logical child already has a STOP ledger entry");
        }
        // Allocate all value objects before touching the authoritative maps.
        // If index publication fails, never leave a PENDING receipt or lease
        // attached to a child still seen as FINALIZED.
        StopReservation reservation = new StopReservation(
                child, actor, incarnation, turnGeneration, UUID.randomUUID());
        StopReceipt pending = new StopReceipt(
                reservation, StopReceiptStatus.PENDING);
        Snapshot stopping = new Snapshot(child, actor, incarnation, Phase.STOPPING);
        try {
            stopReservations.put(child.logicalId(), reservation);
            stopReceipts.put(child.logicalId(), pending);
            children.put(child.logicalId(), stopping);
        } catch (Throwable admissionFailure) {
            stopReservations.remove(child.logicalId());
            stopReceipts.remove(child.logicalId());
            // The previous FINALIZED entry is restored even if publication
            // failed during the last map write. No host effect has run yet.
            children.put(child.logicalId(), finalized);
            throw admissionFailure;
        }
        recordStopTransition("stop.reserve", stopping, reservation);
        return reservation;
    }

    /**
     * Source compiler's actual host-effect entry boundary. Claim before
     * invoking the external STOP callback, never on Future completion.
     * Only the original PENDING turn can enter, exactly once; an uncertain
     * outcome cannot be replayed as a new effect.
     */
    synchronized void claimHostStopInvocation(StopReservation permit) {
        StopReceipt receipt = requireStopReceipt(permit);
        if (receipt.status() != StopReceiptStatus.PENDING
                || invokedStops.contains(permit.child().logicalId())) {
            throw new IllegalStateException("STOP host effect was already invoked or classified");
        }
        if (!invokedStops.add(permit.child().logicalId())) {
            throw new IllegalStateException("STOP host invocation replay");
        }
        recordStopTransition("stop.invoke-asserted",
                requireCurrent(permit.child()), permit);
    }

    /** A host receipt is trusted only for the exact reserved CONTROL turn. */
    public synchronized StopReceipt stopReceipt(ChildId child) {
        requireCurrent(child);
        StopReceipt current = stopReceipts.get(child.logicalId());
        if (current == null) throw new IllegalStateException("no STOP decision receipt");
        return current;
    }

    private StopReceipt requireStopReceipt(StopReservation permit) {
        Objects.requireNonNull(permit);
        requireExpected(permit.child(), permit.failedActor(),
                permit.incarnation(), Phase.STOPPING);
        if (!permit.equals(stopReservations.get(permit.child().logicalId())))
            throw new IllegalStateException("stale or foreign STOP reservation");
        StopReceipt receipt = stopReceipts.get(permit.child().logicalId());
        if (receipt == null || !permit.equals(receipt.reservation()))
            throw new IllegalStateException("missing or mismatched STOP ledger turn");
        return receipt;
    }

    /** Confirmed external STOP effect, but logical finalization still pending. */
    synchronized StopReceipt markStopApplied(StopReservation permit) {
        StopReceipt current = requireStopReceipt(permit);
        if (current.status() != StopReceiptStatus.PENDING)
            throw new IllegalStateException("STOP host effect already classified");
        if (!invokedStops.contains(permit.child().logicalId()))
            throw new IllegalStateException("STOP cannot be applied before host invocation claim");
        StopReceipt updated = new StopReceipt(permit, StopReceiptStatus.APPLIED);
        stopReceipts.put(permit.child().logicalId(), updated);
        recordStopTransition("stop.applied", requireCurrent(permit.child()), permit);
        return updated;
    }

    /**
     * Unknown host-effect outcome: leave the child STOPPING and prohibit
     * automatic RESTART, retry, or reclassification as cleanly finalized.
     * Idempotent for the exact turn; never overwrite an acknowledged receipt.
     */
    synchronized StopReceipt markStopUncertain(StopReservation permit) {
        StopReceipt current = requireStopReceipt(permit);
        if (current.status() == StopReceiptStatus.ACKNOWLEDGED
                || current.status() == StopReceiptStatus.APPLIED)
            throw new IllegalStateException("cannot downgrade confirmed STOP effect");
        if (current.status() == StopReceiptStatus.UNCERTAIN) return current;
        StopReceipt updated = new StopReceipt(permit, StopReceiptStatus.UNCERTAIN);
        stopReceipts.put(permit.child().logicalId(), updated);
        recordStopTransition("stop.uncertain", requireCurrent(permit.child()), permit);
        return updated;
    }

    /** Record only an already applied STOP as acknowledged and terminal. */
    synchronized Snapshot commitStop(StopReservation permit) {
        StopReceipt receipt = requireStopReceipt(permit);
        if (receipt.status() != StopReceiptStatus.APPLIED)
            throw new IllegalStateException("STOP must have applied host receipt");
        Snapshot stopped = new Snapshot(permit.child(), permit.failedActor(),
                permit.incarnation(), Phase.STOPPED);
        StopReceipt acknowledged = new StopReceipt(
                permit, StopReceiptStatus.ACKNOWLEDGED);
        // Allocate the receipt before publishing the terminal transition.
        children.put(permit.child().logicalId(), stopped);
        stopReceipts.put(permit.child().logicalId(), acknowledged);
        stopReservations.remove(permit.child().logicalId());
        recordStopTransition("stop.ack-applied", stopped, permit);
        return stopped;
    }

    /**
     * Read-only inventory for a trusted supervisor reconciler. Snapshot copies
     * never mutate STOP ownership and include APPLIED-but-uncommitted turns.
     * No automatic retry is authorized by observing this list.
     */
    synchronized java.util.List<StopReceipt> unresolvedStops() {
        return java.util.List.copyOf(stopReceipts.values().stream()
                .filter(receipt -> receipt.status() != StopReceiptStatus.ACKNOWLEDGED)
                .toList());
    }

    /**
     * Trusted reconciler confirmed the *existing* host effect by independent
     * evidence. This operation neither invokes nor retries the host effect.
     * Atomic with STOPPING/STOPPED and receipt state, and idempotent only for
     * the exact authorized reservation already ACKNOWLEDGED.
     */
    synchronized Snapshot reconcileVerifiedStop(StopReservation permit) {
        StopReceipt receipt = validateReconciliationCandidate(permit);
        if (receipt.status() == StopReceiptStatus.ACKNOWLEDGED)
            return requireCurrent(permit.child());
        Snapshot stopped = new Snapshot(permit.child(), permit.failedActor(),
                permit.incarnation(), Phase.STOPPED);
        StopReceipt acknowledged = new StopReceipt(
                permit, StopReceiptStatus.ACKNOWLEDGED);
        // Allocate the receipt before publishing the terminal transition.
        children.put(permit.child().logicalId(), stopped);
        stopReceipts.put(permit.child().logicalId(), acknowledged);
        stopReservations.remove(permit.child().logicalId());
        recordStopTransition("stop.ack-reconciled", stopped, permit);
        return stopped;
    }

    /**
     * Preflight a reconciliation ticket before invoking any external host
     * verifier. The subsequent commit rechecks all state under this monitor.
     */
    synchronized StopReceipt validateReconciliationCandidate(StopReservation permit) {
        Objects.requireNonNull(permit);
        Snapshot current = requireCurrent(permit.child());
        StopReceipt receipt = stopReceipts.get(permit.child().logicalId());
        if (receipt == null || !permit.equals(receipt.reservation())
                || !current.actor().equals(permit.failedActor())
                || current.incarnation() != permit.incarnation()) {
            throw new IllegalStateException("foreign or stale STOP reconciliation");
        }
        if (receipt.status() == StopReceiptStatus.ACKNOWLEDGED) {
            if (current.phase() != Phase.STOPPED
                    || stopReservations.containsKey(permit.child().logicalId()))
                throw new IllegalStateException("inconsistent acknowledged STOP");
        } else {
            requireStopReceipt(permit);
        }
        return receipt;
    }

    /**
     * The original host Future succeeded. A trusted reconciler may already
     * have acknowledged the exact ticket: this is an idempotent observation,
     * not permission to repeat its effect. All checks and writes are atomic.
     */
    synchronized Snapshot acknowledgeHostStop(StopReservation permit) {
        StopReceipt receipt = validateReconciliationCandidate(permit);
        if (receipt.status() == StopReceiptStatus.ACKNOWLEDGED)
            return requireCurrent(permit.child());
        if (receipt.status() == StopReceiptStatus.UNCERTAIN)
            throw new IllegalStateException("uncertain STOP requires independent reconciliation");
        if (!invokedStops.contains(permit.child().logicalId()))
            throw new IllegalStateException("STOP host acknowledgment lacks invocation claim");
        Snapshot stopped = new Snapshot(permit.child(), permit.failedActor(),
                permit.incarnation(), Phase.STOPPED);
        StopReceipt acknowledged = new StopReceipt(
                permit, StopReceiptStatus.ACKNOWLEDGED);
        children.put(permit.child().logicalId(), stopped);
        stopReceipts.put(permit.child().logicalId(), acknowledged);
        stopReservations.remove(permit.child().logicalId());
        recordStopTransition("stop.ack-host", stopped, permit);
        return stopped;
    }

    /** A permanent STOP applies to the finalized incarnation only. */
    synchronized Snapshot stop(
            ChildId child, ActorRuntime.ActorId failedActor, long incarnation) {
        requireExpected(child, failedActor, incarnation, Phase.FINALIZED);
        Snapshot stopped = new Snapshot(child, failedActor, incarnation, Phase.STOPPED);
        children.put(child.logicalId(), stopped);
        recordTransition("stop-direct", stopped, null, 0L, null);
        return stopped;
    }

    /** Retire permanently stopped records; old keys never authorize reentry. */
    synchronized void retireStopped(ChildId child) {
        Snapshot state = requireCurrent(child);
        if (state.phase() != Phase.STOPPED)
            throw new IllegalStateException("cannot retire active/restartable child");
        children.remove(child.logicalId());
        stopReceipts.remove(child.logicalId());
        invokedStops.remove(child.logicalId());
        recordTransition("retire-stopped", state, null, 0L, null);
    }

    public synchronized int trackedChildren() { return children.size(); }
    public synchronized int trackedActorIdentities() { return seenActors.size(); }

    private Snapshot requireCurrent(ChildId id) {
        Objects.requireNonNull(id);
        if (!supervisor.equals(id.supervisorId()) || !group.equals(id.groupId()))
            throw new SecurityException("foreign supervisor child identity");
        Snapshot result = children.get(id.logicalId());
        if (result == null || !result.child().equals(id))
            throw new IllegalStateException("unknown or retired logical child");
        return result;
    }

    private Snapshot requireExpected(
            ChildId id, ActorRuntime.ActorId actor,
            long incarnation, Phase phase) {
        Snapshot current = requireCurrent(id);
        if (!current.actor().equals(Objects.requireNonNull(actor))
                || current.incarnation() != incarnation
                || current.phase() != phase)
            throw new IllegalStateException("stale child incarnation or lifecycle phase");
        return current;
    }
}
