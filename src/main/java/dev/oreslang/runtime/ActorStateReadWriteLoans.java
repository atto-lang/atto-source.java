package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Host/runtime-only fair reader-writer state loans for one actor generation.
 *
 * The actor is the sole owner of its state. Logical handlers receive temporary
 * read or write capabilities, not raw ownership. These capabilities are NOT
 * exposed to guest code and do not authorize state access on their own:
 * compiler-generated handle_state MIR must verify the live loan on every use.
 *
 * No guest continuations, Future settlement or callbacks run under monitor.
 * Closing retires this generation and fences every outstanding loan, but leaves
 * active loans allocated until physical holder cleanup releases them.
 */
public final class ActorStateReadWriteLoans implements AutoCloseable {
    private final Object monitor = new Object();
    private final long generation;
    private final int maxPending;
    private final int maxActiveReaders;
    // This VM-only scalar layout is allocated once per actor generation and
    // never exposed as an array, reference or mutable field to guest code.
    private final Map<String, Integer> scalarFieldSlots;
    private final double[] scalarValues;
    private final boolean[] scalarInitialized;
    private boolean scalarStorageReclaimed;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final ArrayDeque<Runnable> notifications = new ArrayDeque<>();
    private final Set<Object> identities =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Loan> active =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private int activeReaders;
    private WriteLoan writer;
    private long nextEpoch;
    private boolean retired;
    private boolean publishing;

    public ActorStateReadWriteLoans(long generation, int maxPending) {
        this(generation, maxPending, 128, List.of());
    }

    /**
     * Create actor-owned scalar storage for an already type-checked state schema.
     * The compiler will eventually pass its private float field names here.
     * No guest-facing or supervisor-visible memory reference is created.
     */
    public ActorStateReadWriteLoans(long generation, int maxPending,
                                    List<String> scalarFieldNames) {
        this(generation, maxPending, 128, scalarFieldNames);
    }

    /**
     * A bounded active-reader budget is separate from the pending queue limit.
     * Without this limit immediately admitted readers can exhaust the heap
     * without ever consuming a pending queue slot.
     */
    public ActorStateReadWriteLoans(long generation, int maxPending,
                                    int maxActiveReaders, List<String> scalarFieldNames) {
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        if (maxPending < 0 || maxPending > 65_536)
            throw new IllegalArgumentException("pending capacity outside 0..65536");
        if (maxActiveReaders < 1 || maxActiveReaders > 65_536)
            throw new IllegalArgumentException("active reader capacity outside 1..65536");
        Objects.requireNonNull(scalarFieldNames, "scalarFieldNames");
        if (scalarFieldNames.size() > 128)
            throw new IllegalArgumentException("scalar state exceeds 128 fields");
        Map<String, Integer> slots = new HashMap<>();
        for (int index = 0; index < scalarFieldNames.size(); index++) {
            String name = Objects.requireNonNull(scalarFieldNames.get(index), "scalar field name");
            if (name.isBlank() || name.length() > 128 || name.startsWith("$")
                    || slots.putIfAbsent(name, index) != null)
                throw new IllegalArgumentException("invalid or duplicate scalar state field: " + name);
        }
        this.generation = generation;
        this.maxPending = maxPending;
        this.maxActiveReaders = maxActiveReaders;
        this.scalarFieldSlots = Map.copyOf(slots);
        this.scalarValues = new double[scalarFieldNames.size()];
        this.scalarInitialized = new boolean[scalarFieldNames.size()];
    }

    public long generation() { return generation; }
    public int activeReaderCount() {
        synchronized (monitor) { return activeReaders; }
    }
    public int activeReaderLimit() { return maxActiveReaders; }
    public boolean writerActive() {
        synchronized (monitor) { return writer != null; }
    }
    public int pendingCount() {
        synchronized (monitor) { return pending.size(); }
    }

    /** Diagnostics only: callbacks waiting for host-only publication. */
    public int pendingPublicationCount() {
        synchronized (monitor) { return notifications.size(); }
    }
    public int activeCount() {
        synchronized (monitor) { return active.size(); }
    }

    public int scalarFieldCount() { return scalarValues.length; }

    /**
     * Single-monitor validation + primitive access closes the ABA/check-use
     * gap between Loan.heldBy() and actual host state storage. It is critical
     * that these methods NEVER invoke guest-supplied callbacks under monitor.
     * Returned values are scalar copies, never guest-borrowable references.
     */
    public double readFloat(Loan loan, Object handler,
                            long expectedGeneration, String field) {
        synchronized (monitor) {
            int slot = checkedScalarSlotLocked(loan, handler, expectedGeneration, field, false);
            if (!scalarInitialized[slot])
                throw new IllegalStateException("uninitialized neuron state field: " + field);
            return scalarValues[slot];
        }
    }

    public void writeFloat(WriteLoan loan, Object handler,
                           long expectedGeneration, String field, double value) {
        synchronized (monitor) {
            int slot = checkedScalarSlotLocked(loan, handler, expectedGeneration, field, true);
            scalarValues[slot] = value;
            scalarInitialized[slot] = true;
        }
    }

    private int checkedScalarSlotLocked(Loan loan, Object handler, long expectedGeneration,
                                        String field, boolean requireWrite) {
        Objects.requireNonNull(loan, "state loan");
        Objects.requireNonNull(handler, "logical handler");
        Objects.requireNonNull(field, "state field");
        // The loan must originate from THIS arbiter. The active identity set
        // rejects forged, cross-actor and stale generation capabilities.
        if (retired || scalarStorageReclaimed || expectedGeneration != generation
                || loan.generation() != generation || loan.released
                || loan.handler != handler || !active.contains(loan)
                || (requireWrite && writer != loan)) {
            throw new IllegalStateException("invalid, stale or insufficient actor state loan");
        }
        Integer slot = scalarFieldSlots.get(field);
        if (slot == null)
            throw new IllegalArgumentException("unknown or inaccessible neuron state field: " + field);
        return slot;
    }

    /**
     * Only clear retired scalar storage after every physical holder has
     * released its loan. This is not an actor-wide turn/FFI quiescence receipt.
     * Callers must separately await any external scheduler/region pins.
     */
    public boolean tryClearRetiredScalarStorage() {
        synchronized (monitor) {
            if (!retired || !active.isEmpty()) return false;
            if (scalarStorageReclaimed) return true;
            Arrays.fill(scalarValues, 0.0);
            Arrays.fill(scalarInitialized, false);
            scalarStorageReclaimed = true;
            return true;
        }
    }

    /**
     * Trusted immediate-admission paths for physical actor turns.
     *
     * Unlike acquireReadAsync/acquireWriteAsync, these never register a
     * pending Future or park a carrier. null means a legitimate conflict or
     * queue fairness fence; invalid identity/generation remains an error.
     */
    ReadLoan tryAcquireReadNow(Object handler, long expectedGeneration) {
        Objects.requireNonNull(handler, "handler");
        synchronized (monitor) {
            String error = admissionError(handler, expectedGeneration);
            if (error != null) throw new IllegalStateException(error);
            try {
                if (writer != null || !pending.isEmpty()
                        || activeReaders >= maxActiveReaders) return null;
                return grantReaderLocked(handler);
            } finally {
                // Successful grants retain identity in the active ledger.
                // Rejected immediate admission must never reserve a turn.
                if (!identitiesHeldByActiveLocked(handler)) identities.remove(handler);
            }
        }
    }

    WriteLoan tryAcquireWriteNow(Object handler, long expectedGeneration) {
        Objects.requireNonNull(handler, "handler");
        synchronized (monitor) {
            String error = admissionError(handler, expectedGeneration);
            if (error != null) throw new IllegalStateException(error);
            try {
                if (writer != null || activeReaders != 0 || !pending.isEmpty())
                    return null;
                return grantWriterLocked(handler);
            } finally {
                if (!identitiesHeldByActiveLocked(handler)) identities.remove(handler);
            }
        }
    }

    private boolean identitiesHeldByActiveLocked(Object handler) {
        for (Loan loan : active) {
            if (loan.handler == handler && !loan.released) return true;
        }
        return false;
    }

    /**
     * Logical handler identity must be unique per in-flight turn. A handler
     * cannot reacquire either mode until its previous loan is closed.
     */
    public OresFuture<ReadLoan> acquireReadAsync(Object handler, long expectedGeneration) {
        Objects.requireNonNull(handler, "handler");
        synchronized (monitor) {
            String error = admissionError(handler, expectedGeneration);
            if (error != null) return OresFuture.failed(new IllegalStateException(error));
            if (writer == null && pending.isEmpty() && activeReaders < maxActiveReaders) {
                try {
                    return OresFuture.completed(grantReaderLocked(handler));
                } catch (IllegalStateException failure) {
                    identities.remove(handler);
                    return OresFuture.failed(failure);
                }
            }
            if (pending.size() == maxPending) {
                identities.remove(handler);
                return OresFuture.failed(new IllegalStateException("state loan queue full"));
            }
            ReadPending request = new ReadPending(handler);
            pending.addLast(request);
            return request.future;
        }
    }

    public OresFuture<WriteLoan> acquireWriteAsync(Object handler, long expectedGeneration) {
        Objects.requireNonNull(handler, "handler");
        synchronized (monitor) {
            String error = admissionError(handler, expectedGeneration);
            if (error != null) return OresFuture.failed(new IllegalStateException(error));
            if (writer == null && activeReaders == 0 && pending.isEmpty()) {
                try {
                    return OresFuture.completed(grantWriterLocked(handler));
                } catch (IllegalStateException failure) {
                    identities.remove(handler);
                    return OresFuture.failed(failure);
                }
            }
            if (pending.size() == maxPending) {
                identities.remove(handler);
                return OresFuture.failed(new IllegalStateException("state loan queue full"));
            }
            WritePending request = new WritePending(handler);
            pending.addLast(request);
            return request.future;
        }
    }

    private String admissionError(Object handler, long expectedGeneration) {
        if (retired) return "actor state generation retired";
        if (expectedGeneration != generation) return "stale actor state generation";
        if (!identities.add(handler)) return "handler already holds or awaits a state loan";
        return null;
    }

    private long allocateEpochLocked() {
        if (nextEpoch == Long.MAX_VALUE)
            throw new IllegalStateException("actor state loan epochs exhausted");
        return ++nextEpoch;
    }

    private ReadLoan grantReaderLocked(Object handler) {
        if (activeReaders >= maxActiveReaders || writer != null)
            throw new IllegalStateException("active reader loan budget exhausted");
        ReadLoan loan = new ReadLoan(handler, allocateEpochLocked());
        active.add(loan);
        activeReaders++;
        return loan;
    }

    private WriteLoan grantWriterLocked(Object handler) {
        if (writer != null || activeReaders != 0)
            throw new IllegalStateException("exclusive state loan overlaps active loan");
        WriteLoan loan = new WriteLoan(handler, allocateEpochLocked());
        active.add(loan);
        writer = loan;
        return loan;
    }

    private void cancelQueued(Pending request) {
        boolean removed;
        Loan reserved;
        synchronized (monitor) {
            removed = pending.remove(request);
            if (removed) {
                identities.remove(request.handler);
                // A cancelled head-of-line writer must not strand compatible
                // queued readers behind it until an unrelated loan closes.
                promoteLocked();
                reserved = null;
            } else {
                // Dequeued grants are RESERVED but not yet guest-visible.
                // Cancellation must release that reservation even when a
                // previous completion callback is stalled on another thread.
                // Do not wait for delayed publishGrant() to close the loan.
                reserved = request.reservation;
                if (reserved != null && request.publication != null) {
                    // Remove the not-yet-started callback. Without this,
                    // repeated cancel/reserve cycles could accumulate
                    // arbitrarily many cancelled notification closures while
                    // an earlier completion subscriber remains blocked.
                    notifications.remove(request.publication);
                    request.publication = null;
                }
                // The cancellation Future is terminal. Do not retain a
                // stale loan through its Pending/cancel-hook closure.
                request.reservation = null;
            }
        }
        if (reserved != null) reserved.close();
        if (removed) publish();
    }

    /**
     * Strict FIFO between modes; consecutive readers at the head may share,
     * but new readers cannot pass a waiting writer (writer starvation fence).
     */
    private void promoteLocked() {
        if (retired || writer != null) return;
        while (!pending.isEmpty()) {
            if (nextEpoch == Long.MAX_VALUE) {
                while (!pending.isEmpty()) {
                    Pending failed = pending.removeFirst();
                    identities.remove(failed.handler);
                    notifications.addLast(() -> failed.fail(
                            new IllegalStateException("actor state loan epochs exhausted")));
                }
                return;
            }
            Pending next = pending.peekFirst();
            if (next.cancelled()) {
                pending.removeFirst();
                identities.remove(next.handler);
                continue;
            }
            if (next instanceof WritePending && activeReaders != 0) return;
            if (next instanceof ReadPending && activeReaders >= maxActiveReaders) return;
            pending.removeFirst();
            Loan loan = next instanceof ReadPending
                    ? grantReaderLocked(next.handler)
                    : grantWriterLocked(next.handler);
            next.reservation = loan;
            Runnable notification = () -> publishGrant(next, loan);
            next.publication = notification;
            notifications.addLast(notification);
            if (loan instanceof WriteLoan) return;
        }
    }

    /**
     * Closing the generation can race grant publication; a retired grant
     * never settles successfully. A close just after settlement is fenced by
     * heldBy(), and the logical handler still owes a finally/cleanup close.
     */
    private void publishGrant(Pending request, Loan loan) {
        boolean invalid;
        synchronized (monitor) {
            invalid = retired;
        }
        if (invalid) {
            try {
                request.fail(new IllegalStateException(
                        "actor state generation retired before grant publication"));
            } finally {
                loan.close();
            }
        } else if (!request.complete(loan)) {
            // Cancellation won after reservation; never leak the reservation.
            loan.close();
        }
    }

    private void publish() {
        synchronized (monitor) {
            if (publishing) return;
            publishing = true;
        }
        Throwable firstFailure = null;
        for (;;) {
            Runnable notification;
            synchronized (monitor) {
                notification = notifications.pollFirst();
                if (notification == null) {
                    publishing = false;
                    break;
                }
            }
            try {
                notification.run();
            } catch (Throwable failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) {
            if (firstFailure instanceof Error fatal) throw fatal;
            if (firstFailure instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException("state loan notification failed", firstFailure);
        }
    }

    private abstract class Pending {
        final Object handler;
        // Assigned while holding monitor when removed from the pending FIFO.
        // Cancellation may revoke the reserved loan before Future delivery.
        Loan reservation;
        Runnable publication;
        Pending(Object handler) { this.handler = handler; }
        abstract boolean cancelled();
        abstract boolean complete(Loan loan);
        abstract void fail(Throwable failure);
    }

    private final class ReadPending extends Pending {
        final OresFuture<ReadLoan> future;
        ReadPending(Object handler) {
            super(handler);
            future = new OresFuture<>(() -> cancelQueued(this));
        }
        @Override boolean cancelled() { return future.isCancelled(); }
        @Override boolean complete(Loan loan) {
            return future.completeFromRuntime((ReadLoan) loan);
        }
        @Override void fail(Throwable failure) { future.failFromRuntime(failure); }
    }

    private final class WritePending extends Pending {
        final OresFuture<WriteLoan> future;
        WritePending(Object handler) {
            super(handler);
            future = new OresFuture<>(() -> cancelQueued(this));
        }
        @Override boolean cancelled() { return future.isCancelled(); }
        @Override boolean complete(Loan loan) {
            return future.completeFromRuntime((WriteLoan) loan);
        }
        @Override void fail(Throwable failure) { future.failFromRuntime(failure); }
    }

    public abstract class Loan implements AutoCloseable {
        private final Object handler;
        private final long epoch;
        private boolean released;
        Loan(Object handler, long epoch) {
            this.handler = handler;
            this.epoch = epoch;
        }
        public long epoch() { return epoch; }
        public long generation() { return generation; }
        /** Observational only; compiled critical scope must validate at use time. */
        public boolean heldBy(Object identity, long expectedGeneration) {
            synchronized (monitor) {
                return !retired && !released && generation == expectedGeneration
                        && handler == identity && active.contains(this);
            }
        }
        @Override public final void close() {
            synchronized (monitor) {
                if (released) return;
                released = true;
                if (!active.remove(this))
                    throw new IllegalStateException("actor state loan ledger corrupted");
                identities.remove(handler);
                if (this instanceof ReadLoan) {
                    activeReaders--;
                } else {
                    if (writer != this)
                        throw new IllegalStateException("actor state writer ledger corrupted");
                    writer = null;
                }
                promoteLocked();
            }
            publish();
        }
    }

    public final class ReadLoan extends Loan {
        private ReadLoan(Object handler, long epoch) { super(handler, epoch); }
    }
    public final class WriteLoan extends Loan {
        private WriteLoan(Object handler, long epoch) { super(handler, epoch); }
    }

    @Override public void close() {
        ArrayDeque<Pending> abandoned;
        synchronized (monitor) {
            if (retired) return;
            retired = true;
            abandoned = new ArrayDeque<>(pending);
            pending.clear();
            for (Pending next : abandoned) identities.remove(next.handler);
        }
        // Active loans survive until physical cleanup; heldBy() fences them now.
        // One broken terminal observer must never prevent retirement of the
        // rest of the pending queue. Futures settle outside the monitor.
        Throwable firstObserverFailure = null;
        for (Pending next : abandoned) {
            try {
                next.fail(new IllegalStateException("actor state generation retired"));
            } catch (Throwable failure) {
                if (firstObserverFailure == null) firstObserverFailure = failure;
                else if (firstObserverFailure != failure) {
                    firstObserverFailure.addSuppressed(failure);
                }
            }
        }
        if (firstObserverFailure != null) {
            if (firstObserverFailure instanceof Error fatal) throw fatal;
            if (firstObserverFailure instanceof RuntimeException failure) throw failure;
            throw new IllegalStateException(
                    "state loan retirement observer failed", firstObserverFailure);
        }
    }
}
