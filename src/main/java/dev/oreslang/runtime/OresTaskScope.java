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
        CLOSED
    }

    /**
     * Runtime-only immutable region authority attached to one structured child.
     *
     * <p>Current scoped borrowing admits only whole-region reads. Path-sensitive
     * claims stay fail-closed until the compiler/runtime can prove disjoint
     * projections end-to-end.</p>
     */
    public record ScopedReadClaim(
            String bindingName,
            long regionId,
            long generation,
            String accessPath) {
        public ScopedReadClaim {
            Objects.requireNonNull(bindingName, "bindingName");
            Objects.requireNonNull(accessPath, "accessPath");
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
    private int activeScopedReadClaims;
    // Runtime barrier: it is intentionally non-cancellable. Cancelling the
    // barrier itself must never manufacture a false reclamation witness.
    private final OresFuture<Void> drained =
            new OresFuture<>(() -> false, () -> { });
    private State state = State.OPEN;

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
        synchronized (lock) {
            if (state != State.OPEN) {
                throw new IllegalStateException("Task scope is already sealed");
            }

            Child child = new Child();
            reserveScopedReadsLocked(child, claims);
            children.add(child);
            final OresFuture<T> future;
            try {
                future = scheduler.start(
                        task,
                        () -> childQuiescent(child),
                        () -> childTerminal(child));
            } catch (RuntimeException | Error failure) {
                // Admission can fail before TaskRunner ownership exists, in which
                // case no onQuiescent callback will arrive.
                children.remove(child);
                releaseScopedReadsLocked(child);
                throw failure;
            }

            child.future = future;
            return future;
        }
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
            if (state != State.OPEN && children.isEmpty() && state != State.CLOSED) {
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
            if (state != State.CANCELLING) {
                state = State.CANCELLING;
                ArrayList<OresFuture<?>> snapshot = new ArrayList<>(children.size());
                for (Child child : children) {
                    // fork() holds the same lock through start + Future binding,
                    // so every child visible here has a handle.
                    snapshot.add(Objects.requireNonNull(
                            child.future, "Task scope child missing Future"));
                }
                cancel = List.copyOf(snapshot);
            }
            if (children.isEmpty()) {
                state = State.CLOSED;
                complete = true;
            }
        }

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
        if (!claimed.isEmpty()) {
            List<OresFuture.RuntimeCancellation<?>> fallback = new ArrayList<>();
            // Ordinary Future subscribers are not part of the structured
            // lifetime proof. After every child is revoked, publish each
            // Future independently so a blocking subscriber on child A cannot
            // pin the parent carrier or starve notifications for child B.
            for (OresFuture.RuntimeCancellation<?> cancellation : claimed) {
                try {
                    Thread.ofVirtual()
                            .name("ores-task-scope-cancel-publish")
                            .start(cancellation::publish);
                } catch (RuntimeException | Error unavailable) {
                    fallback.add(cancellation);
                }
            }
            // Attempt every asynchronous publication before a synchronous
            // fallback can block. Revocation is already complete for all
            // children, so this path cannot weaken the reclamation invariant.
            for (OresFuture.RuntimeCancellation<?> cancellation : fallback) {
                cancellation.publish();
            }
        }

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

    /** Runtime continuity check used by hidden scoped-read tickets. */
    public boolean hasScopedReadLease(long regionId, long generation) {
        synchronized (lock) {
            RegionReadState region = scopedReadRegions.get(regionId);
            return region != null
                    && region.generation == generation
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
        boolean complete;
        synchronized (lock) {
            child.quiescent = true;
            complete = retireChildIfReadyLocked(child);
        }
        if (complete) completeDrain();
    }

    private void childTerminal(Child child) {
        boolean complete;
        synchronized (lock) {
            child.terminal = true;
            complete = retireChildIfReadyLocked(child);
        }
        if (complete) completeDrain();
    }

    /**
     * @return true when this transition closes the entire scope and the barrier
     *         must be completed after releasing the lock.
     */
    private boolean retireChildIfReadyLocked(Child child) {
        if (!child.quiescent || !child.terminal || !children.remove(child)) {
            return false;
        }
        releaseScopedReadsLocked(child);
        if (state != State.OPEN && children.isEmpty() && state != State.CLOSED) {
            state = State.CLOSED;
            return true;
        }
        return false;
    }

    private void reserveScopedReadsLocked(
            Child child, List<ScopedReadClaim> claims) {
        Set<String> bindings = new HashSet<>();
        Map<Long, Long> requestedGenerations = new HashMap<>();

        // Validate the complete claim set before mutating live authority.
        for (ScopedReadClaim claim : claims) {
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

        child.readClaims = claims;
        for (ScopedReadClaim claim : claims) {
            RegionReadState region = scopedReadRegions.computeIfAbsent(
                    claim.regionId(),
                    ignored -> new RegionReadState(claim.generation()));
            region.readers++;
            activeScopedReadClaims++;
        }
    }

    private void releaseScopedReadsLocked(Child child) {
        if (child.readClaimsReleased) return;
        child.readClaimsReleased = true;

        for (ScopedReadClaim claim : child.readClaims) {
            RegionReadState region = scopedReadRegions.get(claim.regionId());
            if (region == null
                    || region.generation != claim.generation()
                    || region.readers <= 0) {
                throw new IllegalStateException(
                        "structured Task scoped-read accounting corrupted for region "
                                + claim.regionId());
            }
            region.readers--;
            activeScopedReadClaims--;
            if (region.readers == 0) {
                scopedReadRegions.remove(claim.regionId());
            }
        }
        if (activeScopedReadClaims < 0) {
            throw new IllegalStateException(
                    "structured Task scoped-read claim count underflow");
        }
    }

    private void completeDrain() {
        try {
            onClosed.accept(this);
        } catch (RuntimeException | Error failure) {
            // The drain Future is a lifetime/reclamation witness. Never report
            // successful quiescence if the owning TaskRunner failed to
            // deregister/finalize this scope. Surface the owner failure to the
            // waiter and keep success fail-closed.
            if (!drained.failFromRuntime(failure)) {
                throw new IllegalStateException(
                        "Task scope drain was already settled before owner failure",
                        failure);
            }
            return;
        }
        if (!drained.completeFromRuntime(null)) {
            throw new IllegalStateException(
                    "Task scope drain completed more than once");
        }
    }
}
