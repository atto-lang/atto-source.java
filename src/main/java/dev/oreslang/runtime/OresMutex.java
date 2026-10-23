package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * Oreslang synchronization primitives.
 *
 * <p>{@link Local} is an actor/private-domain mutex. It deliberately does not
 * use a JVM lock: the creating semantic execution domain owns it and recursive
 * acquisition is rejected. Shared actors may migrate JVM worker threads without
 * changing that domain. {@link Shared} is an explicit same-process
 * shared-memory capability backed by a JVM synchronizer with poisoning and
 * acquire/release ordering.</p>
 */
public final class OresMutex {
    private OresMutex() { }

    /** A logical scheduler Task owns its own lock permits even inside an actor. */
    private static Object currentLogicalLockOwner() {
        ExecutionContext task = OresScheduler.currentExecutionContext();
        return task == null ? ActorRuntime.currentExecutionDomain() : task;
    }

    /** Tracked Task permits outrank direct actor-incarnation ownership. */
    private static TaskOwnedLockLedger currentPermitLedger() {
        TaskOwnedLockLedger task = TaskHandle.currentLockLedger();
        return task != null ? task : ActorRuntime.currentActorLockLedger();
    }

    private static long saturatedNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    public static <T> Local<T> local(T value) {
        return new Local<>(value);
    }

    public static <T> Shared<T> shared(T value) {
        if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.PRIVATE) {
            throw new SecurityException("private actors cannot create SharedMutex<T>");
        }
        IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
        if (actorPolicy != null) {
            actorPolicy.require(
                    IsolatePolicy.Capability.SHARED_MEMORY,
                    "SharedMutex.new");
        }
        return new Shared<>(value);
    }

    /**
     * Runtime-owned future for a lexical MutexGuard.
     *
     * <p>Callers may observe or cancel it, but may not forge completion,
     * timeout-complete it, or obtrude a value. Otherwise a queued lock request
     * could be detached from the mutex's domain/permit accounting.</p>
     */
    public static final class GuardFuture<T> extends CompletableFuture<Guard<T>> {
        // Physical wait/graph revocation must precede public cancellation and
        // timeout publication. If a handoff already claimed the waiter, its
        // grant or explicit failure wins rather than forging an early cancel.
        private volatile java.util.function.BooleanSupplier revokeQueuedWaiter;
        // Each accepted async request reserves one notification slot before
        // its Future becomes visible. The slot remains live while arbitrary
        // synchronous host subscribers are executing on a notifier worker.
        private volatile Runnable releaseNotificationAdmission;
        /** Signals only after public Future completion subscribers drain.
         * The queue-unlink receipt alone is not Task quiescence evidence. */
        private volatile Runnable afterNotificationDrained;
        private final AtomicBoolean notificationReleased = new AtomicBoolean();
        /** This private receipt is not the public Future terminal state.
         * It settles only after already-registered inline callbacks drain. */
        private final CompletableFuture<Void> notificationDrained =
                new CompletableFuture<>();
        // One scheduled terminal publication per Future, even when timeout,
        // client cancellation, owner death and claimed handoff race.
        private final AtomicBoolean publicationClaimed = new AtomicBoolean();

        private GuardFuture() { }

        private void notificationComplete() {
            try {
                Runnable release = releaseNotificationAdmission;
                if (release != null && notificationReleased.compareAndSet(false, true)) {
                    releaseNotificationAdmission = null;
                    release.run();
                }
                Runnable after = afterNotificationDrained;
                afterNotificationDrained = null;
                if (after != null) after.run();
                notificationDrained.complete(null);
            } catch (RuntimeException | Error failure) {
                // A quota or runtime callback invariant failed. Never
                // manufacture physical settlement from an unsafe receipt.
                notificationDrained.completeExceptionally(failure);
                throw failure;
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (super.isDone()) return false;
            java.util.function.BooleanSupplier revoke = revokeQueuedWaiter;
            if (revoke != null && !revoke.getAsBoolean()) return false;
            if (!publicationClaimed.compareAndSet(false, true)) return false;
            if (ActorRuntime.inActorExecution() || OresScheduler.currentTaskDomain() != null) {
                Shared.executeNotification(
                        () -> completeCancellationFromRuntime(mayInterruptIfRunning));
                return true;
            }
            return completeCancellationFromRuntime(mayInterruptIfRunning);
        }

        private boolean completeCancellationFromRuntime(boolean mayInterruptIfRunning) {
            try {
                return super.cancel(mayInterruptIfRunning);
            } finally {
                if (super.isDone()) revokeQueuedWaiter = null;
                notificationComplete();
            }
        }

        private boolean failQueuedFromRuntime(Throwable failure) {
            java.util.function.BooleanSupplier revoke = revokeQueuedWaiter;
            if (super.isDone() || revoke == null || !revoke.getAsBoolean()) return false;
            if (!publicationClaimed.compareAndSet(false, true)) return false;
            Shared.executeNotification(() -> failFromRuntime(failure));
            return true;
        }

        /** Scheduler-only, after physical Task quiescence. A queued request
         * must be unlinked first; a claimed, unpublished handoff is allowed
         * to lose to owner death and must discard its eventual guard. */
        private void cancelAfterOwnerQuiescence() {
            if (super.isDone() || !publicationClaimed.compareAndSet(false, true)) return;
            // The private queued receipt is settled by notificationComplete()
            // after CF subscriber drain, not when the queue entry is unlinked.
            Shared.executeNotification(() -> completeCancellationFromRuntime(false));
        }

        private void completeHandoffOnNotifier(
                Guard<T> guard, Runnable rejected, Runnable physicalSettled) {
            if (!publicationClaimed.compareAndSet(false, true)) {
                // Task-death cancellation can claim the public Future after
                // dequeue, before this handoff publishes. Reclaim the guard,
                // but do not certify physical retirement while cancellation
                // subscribers are still running on the notifier.
                try { rejected.run(); }
                finally { notificationDrained.thenRun(physicalSettled); }
                return;
            }
            Shared.executeNotification(() -> {
                boolean published = false;
                try {
                    // Task revocation can release the granted guard while
                    // this notification is queued. Never publish a stale
                    // capability to an already-retired logical Task.
                    if (guard.released()) {
                        failFromRuntime(new java.util.concurrent.CancellationException(
                                "lock owner retired before async grant publication"));
                    } else {
                        published = completeFromRuntime(guard);
                    }
                } finally {
                    try {
                        if (!published) rejected.run();
                    } finally {
                        physicalSettled.run();
                    }
                }
            });
        }

        private void failHandoffOnNotifier(Throwable failure, Runnable physicalSettled) {
            if (!publicationClaimed.compareAndSet(false, true)) {
                // The competing cancellation still owns the final callback
                // drain; Future.isDone() is not a physical stop receipt.
                notificationDrained.thenRun(physicalSettled);
                return;
            }
            Shared.executeNotification(() -> {
                try {
                    failFromRuntime(failure);
                } finally {
                    physicalSettled.run();
                }
            });
        }

        private boolean completeFromRuntime(Guard<T> guard) {
            try {
                return super.complete(guard);
            } finally {
                if (super.isDone()) revokeQueuedWaiter = null;
                notificationComplete();
            }
        }

        private boolean failFromRuntime(Throwable failure) {
            try {
                return super.completeExceptionally(failure);
            } finally {
                if (super.isDone()) revokeQueuedWaiter = null;
                notificationComplete();
            }
        }

        @Override
        public boolean complete(Guard<T> value) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> completeAsync(
                java.util.function.Supplier<? extends Guard<T>> supplier) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> completeAsync(
                java.util.function.Supplier<? extends Guard<T>> supplier,
                java.util.concurrent.Executor executor) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public CompletableFuture<Guard<T>> orTimeout(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException("use Oreslang timed lock acquisition, not GuardFuture.orTimeout");
        }

        @Override
        public CompletableFuture<Guard<T>> completeOnTimeout(
                Guard<T> value,
                long timeout,
                TimeUnit unit) {
            throw new UnsupportedOperationException("use Oreslang timed lock acquisition, not GuardFuture.completeOnTimeout");
        }

        @Override
        public void obtrudeValue(Guard<T> value) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }

        @Override
        public void obtrudeException(Throwable ex) {
            throw new UnsupportedOperationException("Mutex GuardFuture completion is runtime-owned");
        }
    }

    /**
     * Runtime-owned aggregate contract used to inspect values protected by
     * SharedMutex at actor-transport boundaries. Implementations must expose
     * every transitively reachable child that can carry capabilities/state.
     */
    public interface SharedState {
        Iterable<?> sharedStateChildren();
    }

    public sealed interface Lock<T> permits Local, Shared {
        Guard<T> lock();
        Optional<Guard<T>> tryLock();
        Optional<Guard<T>> lockFor(Duration timeout);
        CompletableFuture<Guard<T>> lockAsync();
        CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout);
        <R> R withLock(Function<? super T, ? extends R> body);
        boolean isPoisoned();
    }

    /**
     * Linear lock capability. Oreslang lowering owns guard release; guest code
     * may release early, but the mutex itself has no public unlock operation.
     */
    public interface Guard<T> extends AutoCloseable {
        T value();
        boolean released();
        void release();

        /**
         * Releases after an abnormal critical-section exit. Shared mutexes are
         * poisoned; actor-local mutexes simply release because their state is
         * confined to the failing actor/private domain.
         */
        void fail();

        @Override
        default void close() {
            release();
        }
    }

    public static final class RecursiveLockException extends IllegalStateException {
        public RecursiveLockException(String kind) {
            super(kind + " is non-reentrant; recursive acquisition is forbidden");
        }
    }

    /** A different actor execution segment already holds a nonparking local mutex. */
    public static final class MutexContendedException extends IllegalStateException {
        public MutexContendedException() {
            super("Mutex<T> is held by another execution segment; use try_lock() or scheduler-owned logical admission");
        }
    }

    public static final class WrongMutexDomainException extends IllegalStateException {
        public WrongMutexDomainException(String message) {
            super(message);
        }
    }

    public static final class PoisonedMutexException extends IllegalStateException {
        public PoisonedMutexException() {
            super("SharedMutex is poisoned because a previous critical section exited abnormally; call recover(...)");
        }
    }

    public static final class LockTimeoutException extends IllegalStateException {
        public LockTimeoutException(Duration timeout) {
            super("mutex acquisition timed out after " + timeout);
        }
    }

    public static final class DeadlockDetectedException extends IllegalStateException {
        public DeadlockDetectedException() {
            super("SharedMutex wait would create a cross-mutex deadlock cycle");
        }
    }

    /**
     * Actor/private-domain mutex. The stable actor domain authorizes access,
     * but each physical dispatch has a distinct execution-segment identity.
     * Atomic admission is required even for actor-local state: multiple
     * carrier threads may execute the same actor when explicitly configured.
     *
     * <p>No JVM thread parks on contention. Local lock_async() retains its
     * existing immediate-acquisition contract and fails closed when busy;
     * queued logical admission must be provided by the actor scheduler.</p>
     */
    public static final class Local<T> implements Lock<T> {
        private final T value;
        private final Object ownerDomain;
        private final AtomicReference<Object> holderSegment = new AtomicReference<>();

        private Local(T value) {
            this.value = value;
            this.ownerDomain = ActorRuntime.currentExecutionDomain();
        }

        private void requireOwnerDomain() {
            if (!Objects.equals(ActorRuntime.currentExecutionDomain(), ownerDomain)) {
                throw new WrongMutexDomainException(
                        "Mutex<T> is actor/private-domain state and cannot be accessed from another actor/execution domain; use SharedMutex<T>");
            }
        }

        @Override
        public Guard<T> lock() {
            requireOwnerDomain();
            Object segment = ActorRuntime.currentMutexExecutionSegment();
            if (!holderSegment.compareAndSet(null, segment)) {
                if (holderSegment.get() == segment) {
                    throw new RecursiveLockException("Mutex<T>");
                }
                throw new MutexContendedException();
            }
            return new LocalGuard(segment);
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            requireOwnerDomain();
            Object segment = ActorRuntime.currentMutexExecutionSegment();
            if (!holderSegment.compareAndSet(null, segment)) return Optional.empty();
            return Optional.of(new LocalGuard(segment));
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            return tryLock();
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            GuardFuture<T> future = new GuardFuture<>();
            try {
                future.completeFromRuntime(lock());
            } catch (Throwable failure) {
                future.failFromRuntime(failure);
            }
            return future;
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            return lockAsync();
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        @Override
        public boolean isPoisoned() {
            return false;
        }

        private final class LocalGuard implements Guard<T>, TaskOwnedLockLedger.Lease {
            private final Object owningSegment;
            private final AtomicBoolean released = new AtomicBoolean();
            private final TaskOwnedLockLedger taskLocks;

            private LocalGuard(Object owningSegment) {
                this.owningSegment = owningSegment;
                TaskOwnedLockLedger ledger = currentPermitLedger();
                if (ledger != null) {
                    try {
                        ledger.register(this);
                    } catch (RuntimeException | Error failure) {
                        holderSegment.compareAndSet(owningSegment, null);
                        throw failure;
                    }
                }
                this.taskLocks = ledger;
            }

            private void requireOwningSegment() {
                requireOwnerDomain();
                // Identity is intentional: record value equality would merge
                // separate physical actor turns in the same execution domain.
                if (ActorRuntime.currentMutexExecutionSegment() != owningSegment) {
                    throw new WrongMutexDomainException(
                            "MutexGuard belongs to another physical actor execution segment; state borrows cannot cross a turn boundary");
                }
            }

            @Override
            public T value() {
                requireOwningSegment();
                if (released.get() || holderSegment.get() != owningSegment) {
                    throw new IllegalStateException("MutexGuard has been released");
                }
                return value;
            }

            @Override public boolean released() { return released.get(); }

            @Override
            public void release() {
                requireOwningSegment();
                if (!released.compareAndSet(false, true)) return;
                if (!holderSegment.compareAndSet(owningSegment, null)) {
                    throw new IllegalStateException("MutexGuard owner segment changed before release");
                }
                if (taskLocks != null) taskLocks.forget(this);
            }

            @Override
            public void fail() {
                release();
            }

            @Override
            public void revokeAfterQuiescence() {
                // Trusted scheduler retirement only: do not unlock a host
                // thread mutex or let a foreign physical carrier access T.
                if (!released.compareAndSet(false, true)) return;
                if (!holderSegment.compareAndSet(owningSegment, null)) {
                    throw new IllegalStateException("actor-local permit owner changed during Task retirement");
                }
            }
        }
    }

    /**
     * Explicit same-process shared-memory mutex. This is intentionally not a
     * distributed lock and must not be serialized across OS-process/Graal
     * isolate boundaries.
     */
    public static final class Shared<T> implements Lock<T> {
        private static final int MAX_ASYNC_WAITERS = 8_192;
        private static final int MAX_GLOBAL_ASYNC_WAITERS = 32_768;
        private static final AtomicInteger GLOBAL_ASYNC_WAITERS = new AtomicInteger();
        private static final int MAX_ASYNC_NOTIFICATIONS = 32_768;
        private static final AtomicInteger NOTIFICATION_ADMISSIONS = new AtomicInteger();
        private static void executeNotification(Runnable action) {
            // Each Future has exactly one claimed notification with one
            // reserved capacity slot. A blocking subscriber cannot starve
            // unrelated notices, and the releaser never invokes host code.
            // Virtual threads, unlike a four-worker fixed pool, allow an
            // otherwise independent mutex handoff to make progress.
            Thread.ofVirtual().name("ores-lock-host-notifier").start(action);
        }
        private static final ConcurrentHashMap<Object, Shared<?>> WAITING_ON =
                new ConcurrentHashMap<>();
        private static final ScheduledExecutorService ASYNC_TIMEOUTS =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform()
                                .daemon(true)
                                .name("ores-shared-mutex-timeouts")
                                .factory());

        private final T value;
        private final Semaphore permit = new Semaphore(1, true);
        private final AtomicBoolean poisoned = new AtomicBoolean();
        private final AtomicInteger asyncWaiters = new AtomicInteger();
        private final AtomicReference<Object> currentOwnerDomain = new AtomicReference<>();
        private final Object asyncQueueLock = new Object();
        private final ArrayDeque<AsyncWaiter> asyncQueue = new ArrayDeque<>();
        private boolean preferAsyncHandoff = true;
        /*
         * Runtime ownership is publication-aware. A send may need to reserve
         * ownership before mailbox admission so a receiver can never observe an
         * unbound SharedMutex, but a failed admission must not permanently bind
         * the handle. pendingPublications + publishedToRuntime provide that
         * two-phase contract.
         */
        private ActorRuntime owningRuntime;
        private int pendingPublications;
        private boolean publishedToRuntime;
        private final Set<Object> activeDomains = ConcurrentHashMap.newKeySet();

        private Shared(T value) {
            this.value = value;
        }

        synchronized boolean bindToRuntime(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime == null) {
                owningRuntime = runtime;
            } else if (owningRuntime != runtime) {
                return false;
            }
            publishedToRuntime = true;
            return true;
        }

        synchronized boolean reserveRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime == null) {
                owningRuntime = runtime;
            } else if (owningRuntime != runtime) {
                return false;
            }
            pendingPublications++;
            return true;
        }

        synchronized void commitRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime != runtime || pendingPublications <= 0) {
                throw new IllegalStateException("SharedMutex publication commit without matching reservation");
            }
            pendingPublications--;
            publishedToRuntime = true;
        }

        synchronized void abortRuntimePublication(ActorRuntime runtime) {
            Objects.requireNonNull(runtime, "runtime");
            if (owningRuntime != runtime || pendingPublications <= 0) {
                throw new IllegalStateException("SharedMutex publication abort without matching reservation");
            }
            pendingPublications--;
            if (pendingPublications == 0 && !publishedToRuntime) {
                owningRuntime = null;
            }
        }

        void inspectForTransport(java.util.function.Consumer<? super T> inspection) {
            Objects.requireNonNull(inspection, "inspection");
            final boolean acquired;
            try {
                acquired = permit.tryAcquire(0L, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "SharedMutex transport inspection interrupted");
            }
            if (!acquired) {
                throw new IllegalStateException(
                        "SharedMutex cannot be published while locked or contended; retry after the current critical section completes");
            }
            try {
                inspection.accept(value);
            } finally {
                releasePermitOrHandoff();
            }
        }

        private void requireActorAccess() {
            if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.PRIVATE) {
                throw new SecurityException("private actors cannot access SharedMutex<T>");
            }
            IsolatePolicy actorPolicy = ActorRuntime.currentActorPolicy();
            if (actorPolicy != null) {
                actorPolicy.require(
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        "SharedMutex operation");
            }

            ActorRuntime current = ActorRuntime.currentActorRuntime();
            if (current != null && !bindToRuntime(current)) {
                throw new WrongMutexDomainException(
                        "SharedMutex belongs to another ActorRuntime");
            }
        }

        private void rejectBlockingActorAcquisition() {
            requireActorAccess();
            if (ActorRuntime.inActorExecution()) {
                throw new WrongMutexDomainException(
                        "blocking SharedMutex.lock()/lock_for()/with_lock() is forbidden during actor execution; use try_lock() or await lock_async()");
            }
        }

        /**
         * Reserve the semantic execution domain before waiting. This prevents a
         * single actor from queueing a second acquisition behind itself and
         * deadlocking, even when the actor migrates JVM workers.
         */
        private Object reserveDomain(boolean tryOnly) {
            requireActorAccess();
            Object domain = currentLogicalLockOwner();
            if (activeDomains.add(domain)) return domain;
            if (tryOnly) return null;
            throw new RecursiveLockException("SharedMutex<T>");
        }

        private void releaseDomain(Object domain) {
            if (domain != null) activeDomains.remove(domain);
        }

        private void beginWait(Object domain) {
            Shared<?> existing = WAITING_ON.putIfAbsent(domain, this);
            if (existing != null) {
                if (existing == this) {
                    throw new RecursiveLockException("SharedMutex<T>");
                }
                throw new IllegalStateException(
                        "execution domain is already waiting on another SharedMutex");
            }

            if (wouldCreateDeadlock(domain)) {
                WAITING_ON.remove(domain, this);
                throw new DeadlockDetectedException();
            }
        }

        private void endWait(Object domain) {
            if (domain != null) WAITING_ON.remove(domain, this);
        }

        private boolean wouldCreateDeadlock(Object requesterDomain) {
            Object owner = currentOwnerDomain.get();
            Set<Object> seen = new HashSet<>();
            while (owner != null) {
                if (Objects.equals(owner, requesterDomain)) return true;
                if (!seen.add(owner)) return false;
                Shared<?> ownerWait = WAITING_ON.get(owner);
                if (ownerWait == null) return false;
                owner = ownerWait.currentOwnerDomain.get();
            }
            return false;
        }

        private void markOwner(Object domain) {
            if (!currentOwnerDomain.compareAndSet(null, domain)) {
                throw new IllegalStateException("SharedMutex permit acquired while another owner is recorded");
            }
        }

        private void clearOwner(Object domain) {
            if (domain == null) return;
            if (!currentOwnerDomain.compareAndSet(domain, null)) {
                Object recorded = currentOwnerDomain.get();
                if (recorded != null) {
                    throw new IllegalStateException(
                            "SharedMutex owner-domain accounting mismatch");
                }
            }
        }

        private int asyncWaiterLimit() {
            IsolatePolicy policy = ActorRuntime.currentActorPolicy();
            return policy == null
                    ? MAX_ASYNC_WAITERS
                    : Math.min(MAX_ASYNC_WAITERS, policy.maxMailboxMessages());
        }

        private static boolean reserveWaiter(AtomicInteger counter, int limit) {
            while (true) {
                int current = counter.get();
                if (current >= limit) return false;
                if (counter.compareAndSet(current, current + 1)) return true;
            }
        }

        private boolean reserveAsyncWaiter(int limit) {
            return reserveWaiter(asyncWaiters, limit);
        }

        private static void releaseWaiter(AtomicInteger counter, String scope) {
            int remaining = counter.decrementAndGet();
            if (remaining < 0) {
                counter.incrementAndGet();
                throw new IllegalStateException("SharedMutex " + scope + " async waiter accounting underflow");
            }
        }

        private void releaseAsyncWaiter() {
            releaseWaiter(asyncWaiters, "per-mutex");
        }

        private static void releaseGlobalAsyncWaiter() {
            releaseWaiter(GLOBAL_ASYNC_WAITERS, "global");
        }

        private static <T> GuardFuture<T> failedGuardFuture(Throwable failure) {
            GuardFuture<T> future = new GuardFuture<>();
            future.failFromRuntime(failure);
            return future;
        }

        /**
         * One pending Task-owned request. Register before publication so Task
         * retirement can cancel a suspended waiter without relying on a guest
         * finally/defer. Once granted, a separate SharedGuard becomes the
         * authoritative held-permit entry in the same Task ledger.
         */
        private final class AsyncWaiter implements TaskOwnedLockLedger.Lease {
            private final Object ownerDomain;
            private final boolean enforceOwnerDomain;
            private final GuardFuture<T> future;
            private final TaskOwnedLockLedger taskLocks;
            private final AtomicBoolean quotaSettled = new AtomicBoolean();
            private final AtomicBoolean queueRevoked = new AtomicBoolean();
            // A dequeued handoff still owns a physical permit until its
            // guard has been published or discarded, including synchronous
            // Java Future subscribers. Do not use Future.isDone() as proof.
            private final CompletableFuture<Void> physicalSettlement =
                    new CompletableFuture<>();

            private AsyncWaiter(
                    Object ownerDomain,
                    boolean enforceOwnerDomain,
                    GuardFuture<T> future,
                    TaskOwnedLockLedger taskLocks) {
                this.ownerDomain = ownerDomain;
                this.enforceOwnerDomain = enforceOwnerDomain;
                this.future = future;
                this.taskLocks = taskLocks;
            }

            @Override
            public void revokeAfterQuiescence() {
                // Task quiescence fences the Future even if a releasing carrier
                // claimed the queue entry. The waiter remains in the ledger
                // until the physical handoff receipt settles.
                revokeQueuedWaiter(this);
                // The queue is unlinked now, but subscribers may still be
                // running. A Future that already won publication will complete
                // its own queued receipt from notificationComplete().
                future.cancelAfterOwnerQuiescence();
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> settleAfterQuiescence() {
                revokeAfterQuiescence();
                return physicalSettlement;
            }
        }

        /** Physically unlink a queued waiter before its Future becomes terminal. */
        private boolean revokeQueuedWaiter(AsyncWaiter waiter) {
            synchronized (asyncQueueLock) {
                if (!asyncQueue.remove(waiter)) return false;
                waiter.queueRevoked.set(true);
                endWait(waiter.ownerDomain);
                releaseDomain(waiter.ownerDomain);
                settleAsyncWaiterQuota(waiter);
            }
            // Do not complete physicalSettlement here: a queued request's
            // Future may still have synchronous host callbacks executing.
            return true;
        }

        private void settleAsyncWaiterQuota(AsyncWaiter waiter) {
            if (!waiter.quotaSettled.compareAndSet(false, true)) return;
            releaseAsyncWaiter();
            releaseGlobalAsyncWaiter();
        }

        /**
         * Releases the physical permit or directly transfers it to a queued
         * async waiter. Direct handoff avoids one virtual thread per waiter.
         *
         * <p>When both blocking host waiters and async waiters exist, alternate
         * preference so neither class can monopolize release handoff.</p>
         */
        private void releasePermitOrHandoff() {
            while (true) {
                AsyncWaiter waiter = null;
                synchronized (asyncQueueLock) {
                    while (!asyncQueue.isEmpty()) {
                        AsyncWaiter candidate = asyncQueue.removeFirst();
                        if (!candidate.future.isDone()) {
                            waiter = candidate;
                            break;
                        }
                        // Cancellation/completion can race with dequeue. Once
                        // removed from the queue, this path owns wait/domain cleanup.
                        candidate.queueRevoked.set(true);
                        endWait(candidate.ownerDomain);
                        releaseDomain(candidate.ownerDomain);
                        settleAsyncWaiterQuota(candidate);
                        // Future.isDone() can become true before its
                        // synchronous completion listeners have unwound.
                        // This branch runs under asyncQueueLock: use an async
                        // continuation so DONE observers never run under the
                        // queue monitor, even if notification already drained.
                        candidate.future.notificationDrained.thenRunAsync(
                                () -> candidate.physicalSettlement.complete(null));
                    }

                    if (waiter == null) {
                        permit.release();
                        return;
                    }

                    if (permit.hasQueuedThreads() && !preferAsyncHandoff) {
                        asyncQueue.addFirst(waiter);
                        preferAsyncHandoff = true;
                        permit.release();
                        return;
                    }
                    if (permit.hasQueuedThreads()) preferAsyncHandoff = false;
                    // This dequeue wins over timeout/cancel. Return quota
                    // capacity before publishing a grant or failure.
                    settleAsyncWaiterQuota(waiter);
                }

                // The selected waiter is stable after leaving the queue lock.
                // Lambdas must capture this final token, not the loop variable.
                final AsyncWaiter claimed = waiter;
                if (poisoned.get()) {
                    endWait(claimed.ownerDomain);
                    releaseDomain(claimed.ownerDomain);
                    try {
                        claimed.future.failHandoffOnNotifier(
                                new PoisonedMutexException(),
                                () -> claimed.physicalSettlement.complete(null));
                    } catch (RuntimeException | Error notifierFailure) {
                        claimed.physicalSettlement.completeExceptionally(notifierFailure);
                        throw notifierFailure;
                    }
                    continue;
                }

                endWait(claimed.ownerDomain);
                markOwner(claimed.ownerDomain);
                final SharedGuard guard;
                try {
                    // Registration may fail if the origin Task terminated
                    // after dequeue. The physical permit remains ours.
                    guard = new SharedGuard(
                            claimed.ownerDomain, claimed.enforceOwnerDomain,
                            claimed.taskLocks);
                } catch (RuntimeException | Error failedRegistration) {
                    clearOwner(claimed.ownerDomain);
                    releaseDomain(claimed.ownerDomain);
                    try {
                        claimed.future.failHandoffOnNotifier(
                                failedRegistration,
                                () -> claimed.physicalSettlement.complete(null));
                    } catch (RuntimeException | Error notifierFailure) {
                        claimed.physicalSettlement.completeExceptionally(notifierFailure);
                        throw notifierFailure;
                    }
                    continue;
                }
                try {
                    claimed.future.completeHandoffOnNotifier(
                            guard,
                            // A cancelling Task may win while publication is
                            // queued. Reject the abandoned guard and arrange a
                            // fresh physical permit handoff on the host notifier.
                            () -> guard.releaseFromRuntime(),
                            () -> claimed.physicalSettlement.complete(null));
                } catch (RuntimeException | Error notifierFailure) {
                    // Do not manufacture a successful Task retirement while
                    // an unpublished guard still physically owns the mutex.
                    // Supervisor recovery must quarantine the failed grant.
                    claimed.physicalSettlement.completeExceptionally(notifierFailure);
                    throw notifierFailure;
                }
                return;
            }
        }

        private Guard<T> checkedGuardAfterAcquire(Object ownerDomain, boolean enforceOwnerDomain) {
            endWait(ownerDomain);
            if (poisoned.get()) {
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
                throw new PoisonedMutexException();
            }
            try {
                markOwner(ownerDomain);
            } catch (RuntimeException | Error failure) {
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
                throw failure;
            }
            try {
                return new SharedGuard(ownerDomain, enforceOwnerDomain,
                        currentPermitLedger());
            } catch (RuntimeException | Error failure) {
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                releasePermitOrHandoff();
                throw failure;
            }
        }

        @Override
        public Guard<T> lock() {
            rejectBlockingActorAcquisition();
            Object ownerDomain = reserveDomain(false);
            try {
                beginWait(ownerDomain);
                permit.acquire();
            } catch (InterruptedException interrupted) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            } catch (RuntimeException | Error failure) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                throw failure;
            }
            return checkedGuardAfterAcquire(ownerDomain, true);
        }

        @Override
        public Optional<Guard<T>> tryLock() {
            Object ownerDomain = reserveDomain(true);
            if (ownerDomain == null) return Optional.empty();
            try {
                if (!permit.tryAcquire(0L, TimeUnit.NANOSECONDS)) {
                    releaseDomain(ownerDomain);
                    return Optional.empty();
                }
                return Optional.of(checkedGuardAfterAcquire(ownerDomain, true));
            } catch (InterruptedException interrupted) {
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "SharedMutex try_lock interrupted");
            }
        }

        @Override
        public Optional<Guard<T>> lockFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
            rejectBlockingActorAcquisition();
            if (timeout.isZero()) return tryLock();
            Object ownerDomain = reserveDomain(true);
            if (ownerDomain == null) return Optional.empty();
            try {
                beginWait(ownerDomain);
                if (!permit.tryAcquire(saturatedNanos(timeout), TimeUnit.NANOSECONDS)) {
                    endWait(ownerDomain);
                    releaseDomain(ownerDomain);
                    return Optional.empty();
                }
                return Optional.of(checkedGuardAfterAcquire(ownerDomain, true));
            } catch (InterruptedException interrupted) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("SharedMutex lock wait interrupted");
            } catch (RuntimeException | Error failure) {
                endWait(ownerDomain);
                releaseDomain(ownerDomain);
                throw failure;
            }
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsync() {
            Object ownerDomain = reserveDomain(false);
            int waiterLimit = asyncWaiterLimit();
            if (!reserveAsyncWaiter(waiterLimit)) {
                releaseDomain(ownerDomain);
                return failedGuardFuture(new IllegalStateException(
                        "SharedMutex async waiter limit exceeded: " + waiterLimit));
            }
            if (!reserveWaiter(GLOBAL_ASYNC_WAITERS, MAX_GLOBAL_ASYNC_WAITERS)) {
                releaseAsyncWaiter();
                releaseDomain(ownerDomain);
                return failedGuardFuture(new IllegalStateException(
                        "SharedMutex process-wide async waiter limit exceeded: " + MAX_GLOBAL_ASYNC_WAITERS));
            }
            if (!reserveWaiter(NOTIFICATION_ADMISSIONS, MAX_ASYNC_NOTIFICATIONS)) {
                releaseGlobalAsyncWaiter();
                releaseAsyncWaiter();
                releaseDomain(ownerDomain);
                return failedGuardFuture(new IllegalStateException(
                        "SharedMutex host notification capacity exceeded"));
            }

            TaskOwnedLockLedger ledger = currentPermitLedger();
            boolean enforceOwnerDomain = ActorRuntime.inActorExecution() || ledger != null;
            GuardFuture<T> future = new GuardFuture<>();
            future.releaseNotificationAdmission =
                    () -> releaseWaiter(NOTIFICATION_ADMISSIONS, "notification");
            AsyncWaiter waiter = new AsyncWaiter(
                    ownerDomain, enforceOwnerDomain, future, ledger);
            future.revokeQueuedWaiter = () -> revokeQueuedWaiter(waiter);
            future.afterNotificationDrained = () -> {
                if (waiter.queueRevoked.get()) waiter.physicalSettlement.complete(null);
            };
            if (ledger != null) {
                try {
                    ledger.register(waiter);
                } catch (RuntimeException | Error rejected) {
                    releaseAsyncWaiter();
                    releaseGlobalAsyncWaiter();
                    releaseDomain(ownerDomain);
                    releaseWaiter(NOTIFICATION_ADMISSIONS, "notification");
                    return failedGuardFuture(rejected);
                }
            }

            future.whenComplete((ignored, failure) -> {
                boolean removed;
                synchronized (asyncQueueLock) {
                    removed = asyncQueue.remove(waiter);
                }
                if (removed) {
                    waiter.queueRevoked.set(true);
                    endWait(ownerDomain);
                    releaseDomain(ownerDomain);
                }
                // Physical receipt is published by GuardFuture after the
                // entire synchronous completion callback drain finishes.
                settleAsyncWaiterQuota(waiter);
            });
            // Public Future settlement is not a carrier/handoff completion
            // witness. Retain Task ownership until this private receipt.
            waiter.physicalSettlement.whenComplete((ignored, failure) -> {
                if (ledger != null && failure == null) ledger.forget(waiter);
            });

            boolean acquired = false;
            boolean poisonedNow = false;
            Throwable waitFailure = null;
            synchronized (asyncQueueLock) {
                if (poisoned.get()) {
                    poisonedNow = true;
                } else {
                    boolean waitRegistered = false;
                    try {
                        // The timed zero-duration form honors a fair
                        // Semaphore's queue order. Untimed tryAcquire() is
                        // explicitly allowed to barge ahead of queued host
                        // waiters, which would violate our mixed-waiter
                        // fairness contract.
                        if (permit.tryAcquire(0L, TimeUnit.NANOSECONDS)) {
                            acquired = true;
                        } else {
                            beginWait(ownerDomain);
                            waitRegistered = true;
                            asyncQueue.addLast(waiter);
                        }
                    } catch (InterruptedException interrupted) {
                        if (waitRegistered) endWait(ownerDomain);
                        Thread.currentThread().interrupt();
                        waitFailure = new java.util.concurrent.CancellationException(
                                "SharedMutex async acquisition interrupted");
                    } catch (RuntimeException | Error failure) {
                        if (waitRegistered) endWait(ownerDomain);
                        waitFailure = failure;
                    }
                }
            }

            if (waitFailure != null) {
                releaseDomain(ownerDomain);
                future.failHandoffOnNotifier(waitFailure,
                        () -> waiter.physicalSettlement.complete(null));
            } else if (poisonedNow) {
                releaseDomain(ownerDomain);
                future.failHandoffOnNotifier(new PoisonedMutexException(),
                        () -> waiter.physicalSettlement.complete(null));
            } else if (acquired) {
                try {
                    markOwner(ownerDomain);
                } catch (RuntimeException | Error ownershipFailure) {
                    // Permit ownership cannot be proven. Keep its physical
                    // permit quarantined and fail the Task's private receipt.
                    waiter.physicalSettlement.completeExceptionally(ownershipFailure);
                    future.failHandoffOnNotifier(ownershipFailure, () -> { });
                    return future;
                }
                final SharedGuard guard;
                try {
                    guard = new SharedGuard(ownerDomain, enforceOwnerDomain, ledger);
                } catch (RuntimeException | Error failedRegistration) {
                    clearOwner(ownerDomain);
                    releaseDomain(ownerDomain);
                    releasePermitOrHandoff();
                    future.failHandoffOnNotifier(failedRegistration,
                            () -> waiter.physicalSettlement.complete(null));
                    return future;
                }
                // An immediately available mutex must be just as safe as a
                // queued grant: never execute a non-Async Future callback
                // inline on the actor or scheduler carrier.
                try {
                    future.completeHandoffOnNotifier(
                            guard,
                            () -> guard.releaseFromRuntime(),
                            () -> waiter.physicalSettlement.complete(null));
                } catch (RuntimeException | Error notifierFailure) {
                    // No notification thread accepted this physical grant.
                    // Retain the unresolved Task lease for quarantine instead
                    // of manufacturing a successful retirement receipt.
                    waiter.physicalSettlement.completeExceptionally(notifierFailure);
                    throw notifierFailure;
                }
            }

            return future;
        }

        @Override
        public CompletableFuture<Guard<T>> lockAsyncFor(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");

            CompletableFuture<Guard<T>> pending = lockAsync();
            if (!(pending instanceof GuardFuture<?>)) return pending;

            @SuppressWarnings("unchecked")
            GuardFuture<T> future = (GuardFuture<T>) pending;
            if (future.isDone()) return future;

            long timeoutNanos = saturatedNanos(timeout);
            if (timeoutNanos == 0L) {
                future.failQueuedFromRuntime(new LockTimeoutException(timeout));
                return future;
            }

            final java.util.concurrent.ScheduledFuture<?> timeoutTask;
            try {
                timeoutTask = ASYNC_TIMEOUTS.schedule(
                        () -> future.failQueuedFromRuntime(new LockTimeoutException(timeout)),
                        timeoutNanos,
                        TimeUnit.NANOSECONDS);
            } catch (RuntimeException schedulingFailure) {
                future.failQueuedFromRuntime(schedulingFailure);
                return future;
            }
            future.whenComplete((ignored, failure) -> timeoutTask.cancel(false));
            return future;
        }

        @Override
        public <R> R withLock(Function<? super T, ? extends R> body) {
            Objects.requireNonNull(body, "body");
            Guard<T> guard = lock();
            try {
                R result = body.apply(value);
                guard.release();
                return result;
            } catch (RuntimeException | Error failure) {
                guard.fail();
                throw failure;
            }
        }

        public <R> R recover(Function<? super T, ? extends R> repair) {
            Objects.requireNonNull(repair, "repair");
            Object ownerDomain = reserveDomain(false);
            boolean acquired = false;
            boolean waiting = false;
            try {
                if (ActorRuntime.inActorExecution()) {
                    // Recovery is expected to happen after the poisoning guard
                    // released its permit. Never block an actor if another
                    // recovery attempt is already in progress.
                    acquired = permit.tryAcquire();
                    if (!acquired) {
                        throw new WrongMutexDomainException(
                                "SharedMutex recovery is busy; actor recovery never blocks, retry from a later mailbox turn");
                    }
                } else {
                    try {
                        beginWait(ownerDomain);
                        waiting = true;
                        permit.acquire();
                        acquired = true;
                        endWait(ownerDomain);
                        waiting = false;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.util.concurrent.CancellationException(
                                "SharedMutex recovery wait interrupted");
                    }
                }

                markOwner(ownerDomain);
                if (!poisoned.get()) {
                    throw new IllegalStateException(
                            "SharedMutex is not poisoned; recover(...) is only for repairing poisoned state");
                }
                try {
                    R result = repair.apply(value);
                    poisoned.set(false);
                    return result;
                } catch (RuntimeException | Error failure) {
                    poisoned.set(true);
                    throw failure;
                }
            } finally {
                if (waiting) endWait(ownerDomain);
                if (acquired) {
                    clearOwner(ownerDomain);
                    releasePermitOrHandoff();
                }
                releaseDomain(ownerDomain);
            }
        }

        @Override
        public boolean isPoisoned() {
            requireActorAccess();
            return poisoned.get();
        }

        /** Runtime transport inspects the protected payload for explicit capability/reference validation. */
        Object transportValue() {
            return value;
        }

        private final class SharedGuard implements Guard<T>, TaskOwnedLockLedger.Lease {
            private final Object ownerDomain;
            private final boolean enforceOwnerDomain;
            private final AtomicBoolean released = new AtomicBoolean();
            private final TaskOwnedLockLedger taskLocks;

            private SharedGuard(Object ownerDomain, boolean enforceOwnerDomain) {
                this(ownerDomain, enforceOwnerDomain, null);
            }

            private SharedGuard(Object ownerDomain, boolean enforceOwnerDomain,
                                TaskOwnedLockLedger ledger) {
                this.ownerDomain = ownerDomain;
                this.enforceOwnerDomain = enforceOwnerDomain;
                if (ledger != null) ledger.register(this);
                this.taskLocks = ledger;
            }

            private void requireOwnerDomain() {
                if (enforceOwnerDomain
                        && !Objects.equals(currentLogicalLockOwner(), ownerDomain)) {
                    throw new WrongMutexDomainException(
                            "SharedMutex guard belongs to another actor/execution domain");
                }
            }

            @Override
            public T value() {
                requireOwnerDomain();
                if (released()) throw new IllegalStateException("MutexGuard has been released");
                return value;
            }

            @Override public boolean released() { return released.get(); }

            @Override
            public void release() {
                requireOwnerDomain();
                releaseFromRuntime();
            }

            private void releaseFromRuntime() {
                if (!released.compareAndSet(false, true)) return;
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                if (taskLocks != null) taskLocks.forget(this);
                releasePermitOrHandoff();
            }

            private void discardUnpublishedWithoutHandoff() {
                if (!released.compareAndSet(false, true)) return;
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                if (taskLocks != null) taskLocks.forget(this);
                // The current handoff loop still owns the physical permit.
            }

            @Override
            public void fail() {
                requireOwnerDomain();
                failFromRuntime();
            }

            private void failFromRuntime() {
                if (!released.compareAndSet(false, true)) return;
                // A live guard surviving its Task's lexical scope is an
                // abnormal critical-section exit, even on Task success.
                poisoned.set(true);
                clearOwner(ownerDomain);
                releaseDomain(ownerDomain);
                if (taskLocks != null) taskLocks.forget(this);
                releasePermitOrHandoff();
            }

            @Override
            public void revokeAfterQuiescence() {
                failFromRuntime();
            }
        }
    }
}
