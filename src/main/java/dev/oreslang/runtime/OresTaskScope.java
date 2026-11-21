package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Trusted runtime primitive for lexical structured Task lifetimes.
 *
 * <p>This is intentionally not a guest-visible ownership capability. The compiler may
 * lower a future scoped-Task construct onto this object only after it has proved capture
 * lifetimes and region access. The scope tracks scheduler-confirmed <em>physical
 * quiescence</em>, not merely Future settlement: cancellation can make a Future terminal
 * while its guest carrier is still unwinding.</p>
 *
 * <p>Scope exit is asynchronous. Blocking a scheduler carrier while waiting for child
 * Tasks can deadlock a one-carrier scheduler, so callers seal the scope and await the
 * returned Future. Normal exit uses {@link #quiesce()}; exceptional/cancelled exit uses
 * {@link #cancelAndQuiesce()}.</p>
 */
public final class OresTaskScope {
    private enum State {
        OPEN,
        QUIESCING,
        CANCELLING,
        POISONED,
        CLOSED
    }

    /**
     * Runtime-only immutable region authority attached to one structured child.
     *
     * <p>Current scoped borrowing admits only whole-region reads. Path-sensitive
     * claims stay fail-closed until the compiler/runtime can prove disjoint
     * projections end-to-end.</p>
     */
    public static final class ScopedReadClaim {
        private final String bindingName;
        private final long regionId;
        private final long generation;
        private final String accessPath;
        private final AtomicBoolean issued = new AtomicBoolean();

        public ScopedReadClaim(
                String bindingName,
                long regionId,
                long generation,
                String accessPath) {
            this.bindingName = Objects.requireNonNull(bindingName, "bindingName");
            this.accessPath = Objects.requireNonNull(accessPath, "accessPath");
            this.regionId = regionId;
            this.generation = generation;
            if (bindingName.isBlank()) {
                throw new IllegalArgumentException(
                        "scoped read binding name cannot be blank");
            }
            if (regionId <= 0L || generation < 0L) {
                throw new IllegalArgumentException(
                        "scoped read claim requires a positive region id and non-negative generation");
            }
            if (!accessPath.equals("$")) {
                throw new IllegalArgumentException(
                        "only whole-region scoped read claims are enabled");
            }
        }

        public String bindingName() { return bindingName; }
        public long regionId() { return regionId; }
        public long generation() { return generation; }
        public String accessPath() { return accessPath; }

        private boolean tryIssue() {
            return issued.compareAndSet(false, true);
        }

        private void rollbackIssueAfterFailedAdmission() {
            if (!issued.compareAndSet(true, false)) {
                throw new IllegalStateException(
                        "scoped read claim issuance rollback failed for '"
                                + bindingName + "'");
            }
        }

        boolean issued() {
            return issued.get();
        }
    }

    private static final class RegionReadState {
        private final long generation;
        private int readers;

        private RegionReadState(long generation) {
            this.generation = generation;
        }
    }

    private static final class Child {
        private volatile OresFuture<?> future;
        // Guarded by the enclosing scope lock.
        private boolean quiescent;
        private boolean terminal;
        private boolean retirementClaimed;
        private List<ScopedReadClaim> readClaims = List.of();
        private boolean readClaimsReleased;
    }

    private final OresScheduler scheduler;
    private final Consumer<OresTaskScope> onClosed;
    private final Object lock = new Object();
    private final Set<Child> children =
            Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * Scope-local live read authority. A compiler region id is only meaningful
     * inside this lexical scope instance: distinct invocations may reuse the
     * same compile-time id, while this table prevents concurrent children in
     * one live scope from disagreeing about the region generation.
     */
    private final Map<Long, RegionReadState> scopedReadRegions = new HashMap<>();
    /**
     * Exact child-lease identities. Region/generation membership alone is not
     * sufficient: one sibling reader must never keep another retired child's
     * stale ticket valid.
     */
    private final Set<ScopedReadClaim> liveScopedReadClaims =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private int activeScopedReadClaims;
    // Runtime barrier: it is intentionally non-cancellable. Cancelling the
    // barrier itself must never manufacture a false reclamation witness.
    private final OresFuture<Void> drained =
            new OresFuture<>(() -> false, () -> { });
    private State state = State.OPEN;
    private Throwable poisonFailure;
    private boolean poisonFinalized;

    public OresTaskScope(OresScheduler scheduler) {
        this(scheduler, ignored -> { }, ignored -> { });
    }

    OresTaskScope(
            OresScheduler scheduler,
            Consumer<OresTaskScope> registrar,
            Consumer<OresTaskScope> onClosed) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        Objects.requireNonNull(registrar, "registrar").accept(this);
    }

    /**
     * Open a lexical structured scope owned by the currently executing Task.
     * This is the entry point intended for future guest lowering.
     */
    public static OresTaskScope openCurrent() {
        return OresScheduler.openCurrentTaskScope();
    }

    /**
     * Start one child Task owned by this scope.
     *
     * <p>A child retires from the scope only after both of these independent
     * conditions hold: the scheduler reports physical quiescence, and terminal
     * authority is irrevocably established (including cancellation revocation).
     * Ordinary Future subscriber delivery is deliberately outside this lifetime
     * proof and may finish later.</p>
     */
    public <T> OresFuture<T> fork(OresScheduler.Task<T> task) {
        return fork(task, List.of());
    }

    /**
     * Trusted compiler/runtime admission for a child that carries immutable
     * scoped-region authority. Guest code never receives these claims.
     *
     * <p>Claims are reserved before scheduler admission and are released only
     * when the child is both terminal and physically quiescent. Future
     * settlement alone therefore cannot restore parent region authority.</p>
     */
    public <T> OresFuture<T> fork(
            OresScheduler.Task<T> task,
            List<ScopedReadClaim> readClaims) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(readClaims, "readClaims");
        List<ScopedReadClaim> claims = List.copyOf(readClaims);

        OresFuture<T> admitted = null;
        Throwable admissionFailure = null;
        Throwable rollbackPoison = null;

        synchronized (lock) {
            if (state != State.OPEN) {
                throw new IllegalStateException("Task scope is already sealed");
            }

            Child child = new Child();
            reserveScopedReadsLocked(child, claims);
            children.add(child);
            try {
                admitted = scheduler.start(
                        task,
                        () -> childQuiescent(child),
                        () -> childTerminal(child));
                child.future = admitted;
            } catch (RuntimeException | Error failure) {
                // Admission can fail before TaskRunner ownership exists, in
                // which case no lifecycle callback will arrive. Prove complete
                // authority rollback before removing the forensic child.
                try {
                    releaseScopedReadsLocked(child);
                    rollbackScopedReadIssuesLocked(child);
                    if (!children.remove(child)) {
                        throw new IllegalStateException(
                                "failed-admission Task child disappeared during rollback");
                    }
                    admissionFailure = failure;
                } catch (RuntimeException | Error rollbackFailure) {
                    if (rollbackFailure != failure) {
                        rollbackFailure.addSuppressed(failure);
                    }
                    // No carrier was admitted, so this child is already
                    // physically quiescent. Its authority remains pinned if
                    // rollback could not be proved.
                    child.terminal = true;
                    child.quiescent = true;
                    child.retirementClaimed = true;
                    poisonScopeLocked(rollbackFailure);
                    rollbackPoison = poisonedDrainReadyLocked();
                }
            }
        }

        if (rollbackPoison != null) {
            completePoisonedDrain(rollbackPoison);
            throw rethrowUnchecked(rollbackPoison);
        }
        if (admissionFailure != null) {
            throw rethrowUnchecked(admissionFailure);
        }
        return Objects.requireNonNull(
                admitted, "Task scope scheduler admission produced no Future");
    }

    /**
     * Seal the scope against new children and complete only after every admitted
     * child is terminal and physically quiescent. Existing children are not cancelled.
     */
    public OresFuture<Void> quiesce() {
        boolean complete = false;
        synchronized (lock) {
            if (state == State.OPEN) {
                state = State.QUIESCING;
            }
            if (state != State.OPEN
                    && state != State.POISONED
                    && children.isEmpty()
                    && state != State.CLOSED) {
                state = State.CLOSED;
                complete = true;
            }
        }
        if (complete) completeDrain();
        return drained;
    }

    /**
     * Seal the scope, request cancellation of all remaining children, and complete
     * only after every child's terminal state and scheduler-confirmed physical
     * quiescence have both been observed.
     *
     * <p>Cancellation is a request for logical termination, not a reclamation
     * witness. A running child's Future can become cancelled before this barrier
     * completes.</p>
     */
    public OresFuture<Void> cancelAndQuiesce() {
        List<OresFuture<?>> cancel = List.of();
        boolean complete = false;
        synchronized (lock) {
            if (state == State.CLOSED) return drained;
            if (state == State.POISONED) {
                // Poison is a failed lifetime proof, not permission to leave
                // sibling carriers running. Repeated cancellation remains
                // useful while the poisoned barrier waits for physical exit.
                cancel = childFuturesLocked();
            } else {
                if (state != State.CANCELLING) {
                    state = State.CANCELLING;
                    cancel = childFuturesLocked();
                }
                if (children.isEmpty()) {
                    state = State.CLOSED;
                    complete = true;
                }
            }
        }

        cancelChildren(cancel);
        if (complete) completeDrain();
        return drained;
    }

    public boolean isOpen() {
        synchronized (lock) {
            return state == State.OPEN;
        }
    }

    public int activeChildren() {
        synchronized (lock) {
            return children.size();
        }
    }

    /**
     * Aggregate region continuity check for runtime-package diagnostics/tests.
     * Cross-package execution code must use exact claim identity instead.
     */
    boolean hasScopedReadLease(long regionId, long generation) {
        synchronized (lock) {
            RegionReadState region = scopedReadRegions.get(regionId);
            return region != null
                    && region.generation == generation
                    && region.readers > 0;
        }
    }

    /**
     * Exact runtime continuity check used by hidden scoped-read tickets.
     *
     * <p>Identity is deliberate. Structurally equal claims owned by sibling
     * children are distinct lifetime capabilities.</p>
     */
    public boolean hasScopedReadLease(ScopedReadClaim claim) {
        Objects.requireNonNull(claim, "claim");
        synchronized (lock) {
            if (!claim.issued() || !liveScopedReadClaims.contains(claim)) {
                return false;
            }
            RegionReadState region = scopedReadRegions.get(claim.regionId());
            return region != null
                    && region.generation == claim.generation()
                    && region.readers > 0;
        }
    }

    int activeScopedReadClaims() {
        synchronized (lock) {
            return activeScopedReadClaims;
        }
    }

    int activeScopedReadRegions() {
        synchronized (lock) {
            return scopedReadRegions.size();
        }
    }

    private void childQuiescent(Child child) {
        childLifecycleChanged(child, false);
    }

    private void childTerminal(Child child) {
        childLifecycleChanged(child, true);
    }

    private void childLifecycleChanged(Child child, boolean terminalEvent) {
        boolean complete = false;
        Throwable finalizePoison = null;
        List<OresFuture<?>> cancel = List.of();
        synchronized (lock) {
            if (terminalEvent) child.terminal = true;
            else child.quiescent = true;

            if (state == State.POISONED) {
                try {
                    // Poison pins only the child whose retirement proof failed.
                    // Healthy siblings still retire their exact claims after
                    // terminal + physical quiescence so stale tickets cannot
                    // remain valid merely because another child corrupted its
                    // accounting.
                    retireChildIfReadyLocked(child);
                } catch (RuntimeException | Error failure) {
                    poisonScopeLocked(failure);
                }
                finalizePoison = poisonedDrainReadyLocked();
            } else {
                try {
                    complete = retireChildIfReadyLocked(child);
                } catch (RuntimeException | Error failure) {
                    poisonScopeLocked(failure);
                    cancel = childFuturesLocked();
                    finalizePoison = poisonedDrainReadyLocked();
                }
            }
        }

        // Never publish a poisoned drain while another child can still execute
        // against borrowed state. Revoke all siblings first, then wait for their
        // independent terminal + physical-quiescence callbacks.
        cancelChildren(cancel);
        if (finalizePoison != null) {
            completePoisonedDrain(finalizePoison);
        } else if (complete) {
            completeDrain();
        }
    }

    /**
     * @return true when this transition closes the entire scope and the barrier
     *         must be completed after releasing the lock.
     */
    private boolean retireChildIfReadyLocked(Child child) {
        if (!child.quiescent
                || !child.terminal
                || child.retirementClaimed
                || !children.contains(child)) {
            return false;
        }
        child.retirementClaimed = true;

        // Memory authority retires before the child can leave the physical
        // join set. If accounting is corrupted, the exception poisons the
        // scope and this child remains pinned in children: no success witness
        // can be manufactured from a partially retired lifetime.
        releaseScopedReadsLocked(child);

        if (!children.remove(child)) {
            throw new IllegalStateException(
                    "structured Task child disappeared during retirement");
        }
        if (state != State.OPEN
                && state != State.POISONED
                && children.isEmpty()
                && state != State.CLOSED) {
            state = State.CLOSED;
            return true;
        }
        return false;
    }

    private void poisonScopeLocked(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (poisonFailure == null) {
            poisonFailure = failure instanceof StructuredTaskScopePoisonException
                    ? failure
                    : new StructuredTaskScopePoisonException(
                            "structured Task scope authority accounting poisoned",
                            failure);
        } else if (poisonFailure != failure) {
            poisonFailure.addSuppressed(failure);
        }
        state = State.POISONED;
    }

    /**
     * A poisoned drain is still a physical-lifetime barrier. Failure becomes
     * observable only after every admitted child is terminal and physically
     * quiescent; otherwise a catch/cleanup path could race a still-running
     * borrower.
     */
    private Throwable poisonedDrainReadyLocked() {
        if (state != State.POISONED || poisonFinalized) return null;
        for (Child child : children) {
            if (!child.terminal || !child.quiescent) return null;
        }
        poisonFinalized = true;
        return Objects.requireNonNull(
                poisonFailure, "poisoned Task scope missing failure cause");
    }

    private void completePoisonedDrain(Throwable failure) {
        Throwable terminalFailure = Objects.requireNonNull(failure, "failure");
        try {
            // Deregistration is safe now because every child carrier is
            // physically gone. The poisoned Future still prevents this event
            // from being mistaken for successful region-accounting proof.
            onClosed.accept(this);
        } catch (RuntimeException | Error ownerFailure) {
            if (ownerFailure != terminalFailure) {
                terminalFailure.addSuppressed(ownerFailure);
            }
        }
        if (!drained.failFromRuntime(terminalFailure)) {
            throw new IllegalStateException(
                    "Task scope drain settled before scoped-read retirement poison",
                    terminalFailure);
        }
    }

    private static RuntimeException rethrowUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(
                "unexpected checked failure in Task scope runtime", failure);
    }

    private List<OresFuture<?>> childFuturesLocked() {
        ArrayList<OresFuture<?>> snapshot = new ArrayList<>(children.size());
        for (Child child : children) {
            OresFuture<?> future = child.future;
            if (future == null) {
                // scheduler.start(...) may synchronously establish terminal +
                // quiescent state before fork() publishes the returned Future
                // into Child. Such a child has no remaining authority to revoke.
                if (child.terminal && child.quiescent) continue;
                throw new IllegalStateException(
                        "live Task scope child is missing its Future handle");
            }
            snapshot.add(future);
        }
        return List.copyOf(snapshot);
    }

    private void cancelChildren(List<OresFuture<?>> cancel) {
        if (cancel.isEmpty()) return;

        // Two-phase structured cancellation: first claim every still-pending
        // child, then revoke every claimed child before publishing any ordinary
        // Future subscribers. A blocking subscriber on child A must never
        // prevent revocation of child B.
        ArrayList<OresFuture.RuntimeCancellation<?>> claimed =
                new ArrayList<>(cancel.size());
        for (OresFuture<?> child : cancel) {
            OresFuture.RuntimeCancellation<?> cancellation =
                    child.claimCancellationFromRuntime();
            if (cancellation != null) claimed.add(cancellation);
        }
        for (OresFuture.RuntimeCancellation<?> cancellation : claimed) {
            cancellation.revoke();
        }
        if (claimed.isEmpty()) return;

        List<OresFuture.RuntimeCancellation<?>> fallback = new ArrayList<>();
        for (OresFuture.RuntimeCancellation<?> cancellation : claimed) {
            try {
                Thread.ofVirtual()
                        .name("ores-task-scope-cancel-publish")
                        .start(cancellation::publish);
            } catch (RuntimeException | Error unavailable) {
                fallback.add(cancellation);
            }
        }
        // Attempt every asynchronous publication before a synchronous fallback
        // can block. Revocation is already complete for all children.
        for (OresFuture.RuntimeCancellation<?> cancellation : fallback) {
            cancellation.publish();
        }
    }

    boolean isPoisoned() {
        synchronized (lock) {
            return state == State.POISONED;
        }
    }

    private void reserveScopedReadsLocked(
            Child child, List<ScopedReadClaim> claims) {
        Set<String> bindings = new HashSet<>();
        Map<Long, Long> requestedGenerations = new HashMap<>();
        Set<ScopedReadClaim> identities =
                Collections.newSetFromMap(new IdentityHashMap<>());

        // Validate the complete claim set before mutating live authority.
        for (ScopedReadClaim claim : claims) {
            if (!identities.add(claim)) {
                throw new IllegalArgumentException(
                        "duplicate scoped read claim identity for binding '"
                                + claim.bindingName() + "'");
            }
            if (claim.issued()) {
                throw new IllegalStateException(
                        "scoped read claim identity was already issued: '"
                                + claim.bindingName() + "'");
            }
            if (!bindings.add(claim.bindingName())) {
                throw new IllegalArgumentException(
                        "duplicate scoped read claim for binding '"
                                + claim.bindingName() + "'");
            }
            Long requested = requestedGenerations.putIfAbsent(
                    claim.regionId(), claim.generation());
            if (requested != null && requested.longValue() != claim.generation()) {
                throw new IllegalArgumentException(
                        "one child cannot claim multiple generations of region "
                                + claim.regionId());
            }
            RegionReadState live = scopedReadRegions.get(claim.regionId());
            if (live != null && live.generation != claim.generation()) {
                throw new IllegalStateException(
                        "stale scoped read generation for region "
                                + claim.regionId() + ": live=" + live.generation
                                + ", requested=" + claim.generation());
            }
        }

        // Claim one-shot identities before mutating region reader counts.
        // A concurrent attempt from another scope may win after validation;
        // roll back every identity acquired in this batch before failing.
        ArrayList<ScopedReadClaim> issuedNow = new ArrayList<>(claims.size());
        for (ScopedReadClaim claim : claims) {
            if (!claim.tryIssue()) {
                for (ScopedReadClaim acquired : issuedNow) {
                    acquired.rollbackIssueAfterFailedAdmission();
                }
                throw new IllegalStateException(
                        "scoped read claim identity was concurrently issued: '"
                                + claim.bindingName() + "'");
            }
            issuedNow.add(claim);
        }

        child.readClaims = claims;
        for (ScopedReadClaim claim : claims) {
            if (!liveScopedReadClaims.add(claim)) {
                throw new IllegalStateException(
                        "scoped read claim identity changed during reservation");
            }
            RegionReadState region = scopedReadRegions.computeIfAbsent(
                    claim.regionId(),
                    ignored -> new RegionReadState(claim.generation()));
            region.readers++;
            activeScopedReadClaims++;
        }
    }

    private void releaseScopedReadsLocked(Child child) {
        if (child.readClaimsReleased) return;

        Map<Long, Integer> releases = new HashMap<>();
        Map<Long, Long> generations = new HashMap<>();
        for (ScopedReadClaim claim : child.readClaims) {
            releases.merge(claim.regionId(), 1, Integer::sum);
            Long prior = generations.putIfAbsent(
                    claim.regionId(), claim.generation());
            if (prior != null && prior.longValue() != claim.generation()) {
                throw new IllegalStateException(
                        "structured Task child contains mixed generations for region "
                                + claim.regionId());
            }
        }

        // Validate the complete retirement before mutating any live authority.
        if (activeScopedReadClaims < child.readClaims.size()) {
            throw new IllegalStateException(
                    "structured Task scoped-read claim count underflow");
        }
        for (ScopedReadClaim claim : child.readClaims) {
            if (!liveScopedReadClaims.contains(claim)) {
                throw new IllegalStateException(
                        "structured Task scoped-read lease identity retired early for '"
                                + claim.bindingName() + "'");
            }
        }
        for (Map.Entry<Long, Integer> release : releases.entrySet()) {
            long regionId = release.getKey();
            RegionReadState region = scopedReadRegions.get(regionId);
            long generation = generations.get(regionId);
            if (region == null
                    || region.generation != generation
                    || region.readers < release.getValue()) {
                throw new IllegalStateException(
                        "structured Task scoped-read accounting corrupted for region "
                                + regionId);
            }
        }

        for (Map.Entry<Long, Integer> release : releases.entrySet()) {
            long regionId = release.getKey();
            RegionReadState region = scopedReadRegions.get(regionId);
            region.readers -= release.getValue();
            if (region.readers == 0) {
                scopedReadRegions.remove(regionId);
            }
        }
        for (ScopedReadClaim claim : child.readClaims) {
            if (!liveScopedReadClaims.remove(claim)) {
                throw new IllegalStateException(
                        "structured Task scoped-read lease identity removal failed for '"
                                + claim.bindingName() + "'");
            }
        }
        activeScopedReadClaims -= child.readClaims.size();
        if (activeScopedReadClaims != liveScopedReadClaims.size()) {
            throw new IllegalStateException(
                    "structured Task scoped-read identity/count accounting diverged");
        }
        child.readClaimsReleased = true;
    }

    private void rollbackScopedReadIssuesLocked(Child child) {
        for (ScopedReadClaim claim : child.readClaims) {
            claim.rollbackIssueAfterFailedAdmission();
        }
    }

    private void completeDrain() {
        try {
            onClosed.accept(this);
        } catch (RuntimeException | Error failure) {
            // Owner deregistration is trusted lifetime bookkeeping. Guest code
            // must not catch/trap it and continue as though the structured
            // scope were cleanly retired.
            StructuredTaskScopePoisonException poison =
                    failure instanceof StructuredTaskScopePoisonException existing
                            ? existing
                            : new StructuredTaskScopePoisonException(
                                    "structured Task scope owner finalization poisoned",
                                    failure);
            synchronized (lock) {
                poisonScopeLocked(poison);
                poisonFinalized = true;
            }
            if (!drained.failFromRuntime(poison)) {
                throw new IllegalStateException(
                        "Task scope drain was already settled before owner failure",
                        poison);
            }
            return;
        }
        if (!drained.completeFromRuntime(null)) {
            throw new IllegalStateException(
                    "Task scope drain completed more than once");
        }
    }
}
