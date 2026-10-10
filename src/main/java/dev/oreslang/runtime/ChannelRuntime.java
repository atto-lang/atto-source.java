package dev.oreslang.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Oreslang-owned channel and atomic select substrate.
 *
 * <p>All pending channel operations are represented as select registrations;
 * direct read/write is simply a one-case SelectSet. One fair runtime lock owns
 * only the short admission/commit critical section. No actor carrier parks on
 * this lock waiting for channel readiness: an unready operation returns an
 * {@link OresFuture} and the owning Ores continuation suspends or keeps it
 * armed.</p>
 *
 * <p>This single commit coordinator deliberately favors semantic strength over
 * premature sharding in the reference runtime. It guarantees that multi-channel
 * selection, rendezvous, cancellation, close, and loser preservation share one
 * atomic arbitration point. A native backend may shard/lock-free this later as
 * long as it preserves the same observable contract.</p>
 */
public final class ChannelRuntime {
    private ChannelRuntime() { }

    private static final ReentrantLock COORDINATOR = new ReentrantLock(true);
    private static final Set<SelectRegistration> ACTIVE = new LinkedHashSet<>();
    private static long nextTicket;

    public enum SelectPolicy {
        /** Deterministic rotating fairness on a reusable SelectSet. */
        FAIR,
        /** Strict source/list order. May intentionally starve later cases. */
        PRIORITY,
        /** Explicit opt-in randomized case order; never the language default. */
        RANDOM
    }

    public enum SelectOperation {
        READ,
        WRITE,
        DEFAULT,
        AWAIT,
        CANCELLED,
        TIMEOUT,
        OBSERVE
    }

    public record SelectResult(
            int index,
            SelectOperation operation,
            Object value) {
        public SelectResult {
            if (index < 0) {
                throw new IllegalArgumentException(
                        "select result index must be non-negative");
            }
            Objects.requireNonNull(operation, "operation");
        }
    }

    public static final class ChannelClosedException extends IllegalStateException {
        public ChannelClosedException(String message) {
            super(message);
        }
    }

    /**
     * Bounded MPMC channel. Capacity zero is a rendezvous channel.
     *
     * <p>Null is deliberately rejected because Oreslang has no standalone null
     * value. Use Option<T> or an explicit signal/unit value instead.</p>
     */
    public static final class Channel<T> implements AutoCloseable {
        private final int capacity;
        private final java.util.ArrayDeque<T> buffer = new java.util.ArrayDeque<>();
        private final ArrayList<CaseRegistration> registrations = new ArrayList<>();
        private boolean closed;
        private Throwable closeCause;

        public Channel(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException(
                        "channel capacity cannot be negative");
            }
            this.capacity = capacity;
        }

        public int capacity() {
            return capacity;
        }

        public int size() {
            COORDINATOR.lock();
            try {
                return buffer.size();
            } finally {
                COORDINATOR.unlock();
            }
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public boolean isClosed() {
            COORDINATOR.lock();
            try {
                return closed;
            } finally {
                COORDINATOR.unlock();
            }
        }

        /**
         * Immediate receive probe. No waiter remains registered when this
         * method returns empty.
         */
        public Optional<T> tryRead() {
            Optional<SelectResult> result =
                    SelectSet.of(read(this)).trySelect(SelectPolicy.PRIORITY);
            if (result.isEmpty()) return Optional.empty();
            @SuppressWarnings("unchecked")
            T value = (T) result.get().value();
            return Optional.of(value);
        }

        /** Runtime-only drain of committed mailbox values, including after close. */
        boolean drainOne(java.util.function.Consumer<? super T> consumer) {
            Objects.requireNonNull(consumer, "consumer");
            T value;
            COORDINATOR.lock();
            try {
                if (buffer.isEmpty()) return false;
                value = buffer.removeFirst();
            } finally {
                COORDINATOR.unlock();
            }
            consumer.accept(value);
            return true;
        }

        /**
         * Immediate send probe. No waiter remains registered when this returns
         * false.
         */
        public boolean tryWrite(T value) {
            requireValue(value);
            return SelectSet.of(write(this, value))
                    .trySelect(SelectPolicy.PRIORITY)
                    .isPresent();
        }

        /**
         * Scheduler-friendly receive registration.
         */
        public OresFuture<T> readAsync() {
            OresFuture<SelectResult> selected =
                    SelectSet.of(read(this)).selectAsync(SelectPolicy.PRIORITY);
            return mapSelection(selected, result -> {
                @SuppressWarnings("unchecked")
                T value = (T) result.value();
                return value;
            });
        }

        /**
         * Scheduler-friendly send registration.
         */
        public OresFuture<Void> writeAsync(T value) {
            requireValue(value);
            OresFuture<SelectResult> selected =
                    SelectSet.of(write(this, value))
                            .selectAsync(SelectPolicy.PRIORITY);
            return mapSelection(selected, ignored -> null);
        }

        private void requireValue(T value) {
            Objects.requireNonNull(
                    value,
                    "Oreslang channels cannot carry null; use Option<T>");
        }

        private ChannelClosedException closedFailure() {
            ChannelClosedException failure =
                    new ChannelClosedException("channel is closed");
            if (closeCause != null) failure.initCause(closeCause);
            return failure;
        }

        @Override
        public void close() {
            close(null);
        }

        /**
         * Close preserves already-buffered values for readers. Once the buffer
         * is drained, reads fail; writes fail immediately. Pending selections
         * re-arbitrate atomically across all of their cases.
         */
        public void close(Throwable cause) {
            ArrayList<Runnable> completions = new ArrayList<>();
            COORDINATOR.lock();
            try {
                if (closed) return;
                closed = true;
                closeCause = cause;
                pumpLocked(completions);
            } finally {
                COORDINATOR.unlock();
            }
            runCompletions(completions);
        }
    }

    public sealed interface SelectCase permits ReadCase, WriteCase, DefaultCase,
            AwaitCase, CancelCase, TimeoutCase, ObserveCase { }

    public record ReadCase<T>(Channel<T> channel) implements SelectCase {
        public ReadCase {
            Objects.requireNonNull(channel, "channel");
        }
    }

    /**
     * Affine payload commits must be tracked by referential identity, not
     * equals/hashCode (which may be mutable, reentrant, or adversarial).
     *
     * All lookups/claims run under COORDINATOR. Weak keys ensure that a
     * committed object is not kept alive merely because it was sent once.
     * This is a defensive host-interop backstop; it cannot prevent a foreign
     * Java caller from mutating an object after giving it away.
     */
    private static final class TransferredPayloads {
        private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();
        private static final Map<PayloadIdentity, Boolean> CLAIMS = new java.util.HashMap<>();

        private static final class PayloadIdentity extends WeakReference<Object> {
            private final int identityHash;

            private PayloadIdentity(Object value, ReferenceQueue<Object> queue) {
                super(value, queue);
                identityHash = System.identityHashCode(value);
            }

            @Override public int hashCode() { return identityHash; }

            @Override public boolean equals(Object candidate) {
                if (this == candidate) return true;
                if (!(candidate instanceof PayloadIdentity other)) return false;
                Object value = get();
                return value != null && value == other.get();
            }
        }

        private static void reclaimCollected() {
            java.lang.ref.Reference<? extends Object> dead;
            while ((dead = COLLECTED.poll()) != null) CLAIMS.remove(dead);
        }

        private static boolean alreadyClaimed(Object value) {
            reclaimCollected();
            return CLAIMS.containsKey(new PayloadIdentity(value, null));
        }

        private static boolean claim(Object value) {
            reclaimCollected();
            return CLAIMS.putIfAbsent(new PayloadIdentity(value, COLLECTED),
                    Boolean.TRUE) == null;
        }
    }

    /**
     * A write descriptor owns its payload. Immutable scalar payloads are
     * Copy-like and may be sent repeatedly; object/collection payloads are
     * affine and may be committed at most once by object identity across
     * distinct descriptors, SelectSets and Plans (including concurrent revisions).
     */
    public static final class WriteCase<T> implements SelectCase {
        private final Channel<T> channel;
        private final T value;
        private final boolean repeatable;
        private final AtomicBoolean transferred = new AtomicBoolean();

        public WriteCase(Channel<T> channel, T value) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.value = Objects.requireNonNull(value,
                    "Oreslang channels cannot carry null; use Option<T>");
            this.repeatable = value instanceof String
                    || value instanceof Boolean || value instanceof Character
                    || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double
                    || value.getClass() == java.math.BigInteger.class
                    || value.getClass() == java.math.BigDecimal.class;
        }

        public Channel<T> channel() { return channel; }
        public T value() { return value; }

        private boolean exhausted() {
            return !repeatable
                    && (transferred.get() || TransferredPayloads.alreadyClaimed(value));
        }

        /** Called only under COORDINATOR, at the actual send commit. */
        private boolean claimCommit() {
            if (repeatable) return true;
            if (transferred.get() || !TransferredPayloads.claim(value)) return false;
            transferred.set(true);
            return true;
        }
    }

    public record DefaultCase() implements SelectCase { }

    /** Observe one Future outcome without taking cancellation authority over it. */
    public record AwaitCase<T>(OresFuture<T> future) implements SelectCase {
        public AwaitCase { Objects.requireNonNull(future, "future"); }
    }

    /** Ready only when the observed Future is cancelled, not when it succeeds. */
    public record CancelCase(OresFuture<?> signal) implements SelectCase {
        public CancelCase { Objects.requireNonNull(signal, "signal"); }
    }

    /** A monotonic relative deadline, measured from registration admission. */
    public record TimeoutCase(long delayNanos) implements SelectCase {
        public TimeoutCase {
            if (delayNanos < 0) throw new IllegalArgumentException("select timeout cannot be negative");
        }
    }

    /** Each selection owns its own one-shot reactive subscription. */
    public record ObserveCase<T>(Flow.Publisher<T> publisher) implements SelectCase {
        public ObserveCase { Objects.requireNonNull(publisher, "publisher"); }
    }

    public static <T> ReadCase<T> read(Channel<T> channel) {
        return new ReadCase<>(channel);
    }

    public static <T> WriteCase<T> write(Channel<T> channel, T value) {
        return new WriteCase<>(channel, value);
    }

    public static DefaultCase defaultCase() {
        return new DefaultCase();
    }

    public static <T> AwaitCase<T> await(OresFuture<T> future) {
        return new AwaitCase<>(future);
    }

    public static CancelCase cancelled(OresFuture<?> signal) {
        return new CancelCase(signal);
    }

    /** Select the next emitted value; unsubscribe as soon as any case wins. */
    public static <T> ObserveCase<T> observeOnce(Flow.Publisher<T> publisher) {
        return new ObserveCase<>(publisher);
    }

    public static TimeoutCase timeout(long delay, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (delay < 0) throw new IllegalArgumentException("select timeout cannot be negative");
        return new TimeoutCase(unit.toNanos(delay));
    }

    /** Lazily activated by an actually armed deadline, never by a try-select. */
    private static final class TimeoutClock {
        private static final ScheduledThreadPoolExecutor EXECUTOR = newClock();
        private static ScheduledThreadPoolExecutor newClock() {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
                Thread thread = new Thread(r, "ores-select-deadline");
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        }
    }

    /**
     * Reusable select descriptor. Static and dynamic source select lower to
     * this same primitive.
     */
    public static final class SelectSet {
        private final List<SelectCase> cases;
        private final AtomicLong fairCursor = new AtomicLong();

        public SelectSet(Collection<? extends SelectCase> cases) {
            this(cases, 0L);
        }

        /**
         * Compiler/runtime hook for static select sites that rebuild evaluated
         * case values each execution but retain one deterministic fairness
         * ticket.
         */
        public SelectSet(
                Collection<? extends SelectCase> cases,
                long initialFairCursor) {
            Objects.requireNonNull(cases, "cases");
            if (cases.isEmpty()) {
                throw new IllegalArgumentException(
                        "select requires at least one case");
            }
            this.cases = List.copyOf(cases);
            this.fairCursor.set(initialFairCursor);
            long defaults = this.cases.stream()
                    .filter(DefaultCase.class::isInstance)
                    .count();
            if (defaults > 1) {
                throw new IllegalArgumentException(
                        "select permits at most one default case");
            }
        }

        public static SelectSet of(SelectCase... cases) {
            return new SelectSet(List.of(cases));
        }

        public static SelectSet from(Iterable<? extends SelectCase> cases) {
            ArrayList<SelectCase> copy = new ArrayList<>();
            for (SelectCase selectCase : cases) {
                copy.add(Objects.requireNonNull(selectCase));
            }
            return new SelectSet(copy);
        }

        /**
         * Map insertion/value iteration order defines the case index/order.
         * The language may add keyed SelectResult metadata later without
         * changing selection arbitration.
         */
        public static SelectSet fromMap(Map<?, ? extends SelectCase> cases) {
            Objects.requireNonNull(cases, "cases");
            return new SelectSet(cases.values());
        }

        public List<SelectCase> cases() {
            return cases;
        }

        public OresFuture<SelectResult> selectAsync() {
            return selectAsync(SelectPolicy.FAIR);
        }

        public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
            SelectRegistration registration =
                    new SelectRegistration(
                            this,
                            Objects.requireNonNull(policy, "policy"));
            registration.start(false);
            return registration.future;
        }

        public Optional<SelectResult> trySelect() {
            return trySelect(SelectPolicy.FAIR);
        }

        public Optional<SelectResult> trySelect(SelectPolicy policy) {
            SelectRegistration registration =
                    new SelectRegistration(
                            this,
                            Objects.requireNonNull(policy, "policy"));
            return registration.tryNow();
        }

        private int[] initialOrder(SelectPolicy policy) {
            int count = cases.size();
            int[] order = new int[count];

            if (policy == SelectPolicy.PRIORITY) {
                for (int i = 0; i < count; i++) order[i] = i;
                return order;
            }

            if (policy == SelectPolicy.FAIR) {
                int start = Math.floorMod(fairCursor.get(), count);
                for (int i = 0; i < count; i++) {
                    order[i] = (start + i) % count;
                }
                return order;
            }

            for (int i = 0; i < count; i++) order[i] = i;
            for (int i = count - 1; i > 0; i--) {
                int j = ThreadLocalRandom.current().nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            return order;
        }

        private void selected(int index, SelectPolicy policy) {
            if (policy == SelectPolicy.FAIR && !cases.isEmpty()) {
                fairCursor.set((index + 1L) % cases.size());
            }
        }
    }

    /**
     * Optional reusable execution plan for repeated or dynamically assembled
     * selections.
     *
     * <p>A SelectPlan is deliberately a layer over {@link SelectSet}, not a
     * replacement for it. Existing select lowering may continue constructing
     * and executing SelectSet directly. Plans only retain immutable case
     * descriptors plus the SelectSet fairness cursor; every invocation still
     * creates a fresh SelectRegistration and therefore preserves the existing
     * atomic arbitration, cancellation, loser-detachment, close, and Future
     * semantics.</p>
     *
     * <p>This split gives the compiler a safe optimization target for static
     * select sites and gives dynamic select code an explicit reusable value.
     * It intentionally does not keep live channel waiters armed between
     * invocations. Persistent waiter reuse is a separate optimization that
     * would require a stronger generation/winner protocol.</p>
     */
    public static final class SelectPlan {
        // A mutation publishes an immutable descriptor *snapshot*. Registrations
        // already armed on the previous revision retain their own SelectSet.
        // This avoids removing/retargeting live waiters while still supporting
        // dynamic add/remove/replace on one stable plan handle.
        private record Snapshot(long revision, SelectSet set) { }
        private final AtomicReference<Snapshot> current;

        public SelectPlan(Collection<? extends SelectCase> cases) {
            this(new SelectSet(cases));
        }

        private SelectPlan(SelectSet set) {
            this.current = new AtomicReference<>(
                    new Snapshot(0L, Objects.requireNonNull(set, "set")));
        }

        public static SelectPlan of(SelectCase... cases) {
            return new SelectPlan(SelectSet.of(cases));
        }

        public static SelectPlan from(Iterable<? extends SelectCase> cases) {
            return new SelectPlan(SelectSet.from(cases));
        }

        public static SelectPlan fromMap(Map<?, ? extends SelectCase> cases) {
            return new SelectPlan(SelectSet.fromMap(cases));
        }

        /** A stable generation number for debugging and stale-plan detection. */
        public long revision() {
            return current.get().revision();
        }

        /** Immutable view of the current descriptor generation. */
        public List<SelectCase> cases() {
            return current.get().set().cases();
        }

        /** Captures one complete generation for a source-level dynamic select. */
        public SelectSet snapshot() {
            return current.get().set();
        }

        /**
         * Appends a descriptor for FUTURE invocations. Pending selections
         * continue observing their original descriptor generation.
         */
        public SelectPlan addCase(SelectCase selectCase) {
            Objects.requireNonNull(selectCase, "selectCase");
            revise(next -> next.add(selectCase));
            return this;
        }

        public SelectPlan removeCase(int index) {
            revise(next -> next.remove(index));
            return this;
        }

        public SelectPlan replaceCase(int index, SelectCase selectCase) {
            Objects.requireNonNull(selectCase, "selectCase");
            revise(next -> next.set(index, selectCase));
            return this;
        }

        public SelectPlan replaceCases(Collection<? extends SelectCase> cases) {
            Objects.requireNonNull(cases, "cases");
            List<SelectCase> copy = List.copyOf(cases);
            revise(next -> {
                next.clear();
                next.addAll(copy);
            });
            return this;
        }

        private void revise(java.util.function.Consumer<ArrayList<SelectCase>> mutation) {
            while (true) {
                Snapshot before = current.get();
                ArrayList<SelectCase> next = new ArrayList<>(before.set().cases());
                mutation.accept(next);
                // Validate before publication (nonempty, nonnull, one default).
                // New revisions inherit the current FAIR cursor position but
                // have their own cursor thereafter: old in-flight completions
                // must not reorder cases in the new descriptor generation.
                SelectSet validated = new SelectSet(next, before.set().fairCursor.get());
                Snapshot after = new Snapshot(
                        Math.addExact(before.revision(), 1L), validated);
                if (current.compareAndSet(before, after)) return;
            }
        }

        public OresFuture<SelectResult> selectAsync() {
            return current.get().set().selectAsync();
        }

        public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
            return current.get().set().selectAsync(policy);
        }

        public Optional<SelectResult> trySelect() {
            return current.get().set().trySelect();
        }

        public Optional<SelectResult> trySelect(SelectPolicy policy) {
            return current.get().set().trySelect(policy);
        }
    }

    /** Flow-compatible single-value adapter; no guest code runs in callbacks. */
    private static final class OncePublisher<T> implements Flow.Subscriber<T> {
        private static final Flow.Subscription STOPPED = new Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { }
        };
        private final Flow.Publisher<T> publisher;
        private final OresFuture<T> result = new OresFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();

        private OncePublisher(Flow.Publisher<T> publisher) {
            this.publisher = publisher;
        }

        private void start() {
            if (!started.compareAndSet(false, true) || terminal.get()) return;
            // Foreign subscribe implementations may block or invoke onNext
            // synchronously. Never execute them on an actor's native carrier.
            try {
                Thread.ofVirtual().name("ores-select-observable-once").start(() -> {
                    if (terminal.get()) return;
                    try {
                        publisher.subscribe(this);
                    } catch (Throwable failure) {
                        onError(failure);
                    }
                });
            } catch (RuntimeException | Error admissionFailure) {
                // The selection is already armed. A host thread-admission
                // failure must settle the observed case so other channel/
                // Future registrations do not remain stranded indefinitely.
                onError(admissionFailure);
            }
        }

        private static void cancelSubscription(Flow.Subscription prior) {
            // Neither an untrusted Publisher's cancel() nor its exceptions may
            // block a native guest carrier or poison Future delivery.
            Runnable cleanup = () -> {
                try {
                    prior.cancel();
                } catch (Throwable ignored) {
                    // Selection settlement is already authoritative. A broken
                    // host cancellation hook cannot roll back its winner.
                }
            };
            try {
                Thread.ofVirtual().name("ores-select-unsubscribe").start(cleanup);
            } catch (RuntimeException | Error launchFailure) {
                // Last-resort asynchronous fallback: do not run arbitrary
                // foreign cancel code inline on the caller's actor carrier.
                try {
                    java.util.concurrent.CompletableFuture.runAsync(cleanup);
                } catch (RuntimeException | Error ignored) {
                    // Host thread exhaustion can prevent best-effort remote
                    // cancellation; admission/selection remain fully detached.
                }
            }
        }

        private void stop() {
            terminal.set(true);
            Flow.Subscription prior = subscription.getAndSet(STOPPED);
            if (prior != null && prior != STOPPED) cancelSubscription(prior);
        }

        @Override
        public void onSubscribe(Flow.Subscription next) {
            // Malformed foreign publishers may deliver this asynchronously,
            // outside the subscribe() call's exception boundary. Throwing here
            // would strand the selection if no other arm can become ready.
            if (next == null) {
                onError(new NullPointerException("publisher supplied null subscription"));
                return;
            }
            if (!subscription.compareAndSet(null, next)) {
                // A Publisher that sends onSubscribe twice violates Flow's
                // contract. If it repeats the exact same Subscription object,
                // cancelling the "extra" would actually cancel the active
                // subscription and could strand this selection forever.
                // Different duplicate subscriptions must still be cancelled.
                if (subscription.get() != next) cancelSubscription(next);
                return;
            }
            if (terminal.get()) {
                stop();
                return;
            }
            try {
                next.request(1);
            } catch (Throwable failure) {
                onError(failure);
            }
        }

        @Override
        public void onNext(T item) {
            if (!terminal.compareAndSet(false, true)) return;
            stop();
            if (item == null) result.failFromRuntime(new NullPointerException(
                    "observable emitted null; use Option<T>"));
            else result.completeFromRuntime(item);
        }

        @Override
        public void onError(Throwable failure) {
            if (!terminal.compareAndSet(false, true)) return;
            stop();
            result.failFromRuntime(failure == null
                    ? new NullPointerException("observable failed with null")
                    : failure);
        }

        @Override
        public void onComplete() {
            if (!terminal.compareAndSet(false, true)) return;
            stop();
            result.failFromRuntime(new java.util.NoSuchElementException(
                    "observable completed without emitting a value"));
        }
    }

    private static final class CaseRegistration {
        private final SelectRegistration selection;
        private final int index;
        private final SelectCase selectCase;
        private final long ticket;
        private OncePublisher<?> oneShot;

        private CaseRegistration(
                SelectRegistration selection,
                int index,
                SelectCase selectCase) {
            this.selection = selection;
            this.index = index;
            this.selectCase = selectCase;
            this.ticket = nextTicket++;
        }

        private Channel<?> channel() {
            if (selectCase instanceof ReadCase<?> read) return read.channel();
            if (selectCase instanceof WriteCase<?> write) return write.channel();
            throw new IllegalStateException(
                    "non-channel select case has no channel registration");
        }

        private SelectOperation operation() {
            if (selectCase instanceof ReadCase<?>) return SelectOperation.READ;
            if (selectCase instanceof WriteCase<?>) return SelectOperation.WRITE;
            if (selectCase instanceof AwaitCase<?>) return SelectOperation.AWAIT;
            if (selectCase instanceof CancelCase) return SelectOperation.CANCELLED;
            if (selectCase instanceof TimeoutCase) return SelectOperation.TIMEOUT;
            if (selectCase instanceof ObserveCase<?>) return SelectOperation.OBSERVE;
            return SelectOperation.DEFAULT;
        }

        private Object writeValue() {
            return ((WriteCase<?>) selectCase).value();
        }
    }

    private static final class SelectRegistration {
        private final SelectSet set;
        private final SelectPolicy policy;
        private final int[] order;
        private final ArrayList<CaseRegistration> registrations =
                new ArrayList<>();
        private final int defaultIndex;
        private final OresFuture<SelectResult> future;
        private final long startNanos = System.nanoTime();
        private final ArrayList<OresFuture.RuntimeWaiterRegistration<?>> observers = new ArrayList<>();
        private final ArrayList<ScheduledFuture<?>> alarms = new ArrayList<>();
        private final ArrayList<OncePublisher<?>> oneShots = new ArrayList<>();
        private boolean decided;
        private boolean registered;
        private boolean armingExternal;

        private SelectRegistration(SelectSet set, SelectPolicy policy) {
            this.set = set;
            this.policy = policy;
            this.order = set.initialOrder(policy);

            int foundDefault = -1;
            for (int i = 0; i < set.cases.size(); i++) {
                if (set.cases.get(i) instanceof DefaultCase) {
                    foundDefault = i;
                    break;
                }
            }
            this.defaultIndex = foundDefault;
            this.future = new OresFuture<>(
                    this::cancelAdmission,
                    () -> { });
            // Selection settlement is published only AFTER the coordinator
            // unlocks. Subscription.cancel() is external code and MUST NOT
            // execute in a channel arbitration critical section.
            this.future.whenCompleteRuntime((ignored, failure) -> {
                for (OncePublisher<?> subscription : List.copyOf(oneShots)) {
                    subscription.stop();
                }
            });
        }

        private boolean cancelAdmission() {
            COORDINATOR.lock();
            try {
                if (decided) return false;
                decided = true;
                unregisterLocked(this);
                return true;
            } finally {
                COORDINATOR.unlock();
            }
        }

        private void start(boolean immediateOnly) {
            ArrayList<Runnable> completions = new ArrayList<>();
            COORDINATOR.lock();
            try {
                registerLocked(this);
                pumpLocked(completions);

                if (!decided && defaultIndex >= 0) {
                    commitSingleLocked(
                            caseOrDefault(defaultIndex),
                            new SelectResult(
                                    defaultIndex,
                                    SelectOperation.DEFAULT,
                                    null),
                            completions);
                }

                if (!decided && immediateOnly) {
                    decided = true;
                    unregisterLocked(this);
                }
            } finally {
                COORDINATOR.unlock();
            }
            runCompletions(completions);
            if (!immediateOnly) armExternal();
        }

        /** Install only after initial channel arbitration/default handling. */
        private void armExternal() {
            ArrayList<Runnable> completions = new ArrayList<>();
            ArrayList<OncePublisher<?>> toSubscribe = new ArrayList<>();
            COORDINATOR.lock();
            try {
                if (decided || !registered) return;
                armingExternal = true;
                for (int index : order) {
                    SelectCase candidate = set.cases.get(index);
                    OresFuture<?> observed = candidate instanceof AwaitCase<?> awaited
                            ? awaited.future()
                            : candidate instanceof CancelCase cancelled ? cancelled.signal() : null;
                    if (candidate instanceof ObserveCase<?> observe) {
                        OncePublisher<?> bridge = new OncePublisher<>(observe.publisher());
                        CaseRegistration ownedCase = findRegistration(this, index);
                        ownedCase.oneShot = bridge;
                        oneShots.add(bridge);
                        toSubscribe.add(bridge);
                        observers.add(bridge.result.whenCompleteRuntimeCancellable(
                                (value, failure) -> externalReady()));
                    } else if (observed != null) {
                        OresFuture.RuntimeWaiterRegistration<?> registration =
                                observed.whenCompleteRuntimeCancellable((value, failure) -> externalReady());
                        observers.add(registration);
                    } else if (candidate instanceof TimeoutCase timeout && timeout.delayNanos() > 0) {
                        long elapsed = Math.max(0L, System.nanoTime() - startNanos);
                        long remaining = Math.max(0L, timeout.delayNanos() - elapsed);
                        alarms.add(TimeoutClock.EXECUTOR.schedule(
                                this::externalReady, remaining, TimeUnit.NANOSECONDS));
                    }
                }
                armingExternal = false;
                // Includes completions racing with observer registration.
                pumpLocked(completions);
            } catch (RuntimeException | Error admissionFailure) {
                // Timer registration and external waiter attachment are host
                // admission steps. A partial failure must never strand this
                // selection in ACTIVE with a live channel read/write.
                if (!decided) {
                    decided = true;
                    unregisterLocked(this);
                    completions.add(() -> future.failFromRuntime(admissionFailure));
                }
            } finally {
                armingExternal = false;
                COORDINATOR.unlock();
            }
            runCompletions(completions);
            // Flow subscribe is arbitrary external code; never run it under
            // COORDINATOR or directly on an actor/supervisor carrier.
            for (OncePublisher<?> subscription : toSubscribe) subscription.start();
        }

        private void externalReady() {
            ArrayList<Runnable> completions = new ArrayList<>();
            COORDINATOR.lock();
            try {
                if (!decided && registered && !armingExternal) pumpLocked(completions);
            } finally {
                COORDINATOR.unlock();
            }
            runCompletions(completions);
        }

        private Optional<SelectResult> tryNow() {
            start(true);
            if (!future.isDone()) return Optional.empty();
            return Optional.of(future.join());
        }

        private CaseRegistration caseOrDefault(int index) {
            for (CaseRegistration registration : registrations) {
                if (registration.index == index) return registration;
            }
            return new CaseRegistration(this, index, set.cases.get(index));
        }
    }

    private static void registerLocked(SelectRegistration selection) {
        if (selection.decided || selection.registered) return;
        selection.registered = true;
        ACTIVE.add(selection);

        for (int index : selection.order) {
            SelectCase selectCase = selection.set.cases.get(index);
            if (selectCase instanceof DefaultCase) continue;

            CaseRegistration registration =
                    new CaseRegistration(selection, index, selectCase);
            selection.registrations.add(registration);
            if (selectCase instanceof ReadCase<?> || selectCase instanceof WriteCase<?>) {
                channelOf(selectCase).registrations.add(registration);
            }
        }
    }

    private static void unregisterLocked(SelectRegistration selection) {
        if (!selection.registered) return;
        selection.registered = false;
        ACTIVE.remove(selection);
        for (CaseRegistration registration :
                List.copyOf(selection.registrations)) {
            if (registration.selectCase instanceof ReadCase<?>
                    || registration.selectCase instanceof WriteCase<?>) {
                registration.channel().registrations.remove(registration);
            }
        }
        selection.registrations.clear();
        for (OresFuture.RuntimeWaiterRegistration<?> observer : selection.observers) {
            observer.detach();
        }
        selection.observers.clear();
        for (ScheduledFuture<?> alarm : selection.alarms) alarm.cancel(false);
        selection.alarms.clear();
    }

    /**
     * Drive all registrations until no further atomic commit is possible.
     * Completion callbacks are queued and run only after COORDINATOR is
     * released, preventing channel-lock -> actor-mailbox lock inversion.
     */
    private static void pumpLocked(List<Runnable> completions) {
        boolean progressed;
        do {
            progressed = false;

            for (SelectRegistration selection : List.copyOf(ACTIVE)) {
                if (selection.decided) continue;

                CaseRegistration chosen =
                        preferredCommittableCaseLocked(selection);
                if (chosen == null) continue;

                if (chosen.selectCase instanceof AwaitCase<?> awaited) {
                    Object terminal = awaited.future().runtimeTerminalStateOrNull();
                    Throwable failure = OresFuture.runtimeTerminalFailure(terminal);
                    if (failure != null) commitFailureLocked(chosen, failure, completions);
                    else commitSingleLocked(chosen,
                            new SelectResult(chosen.index, SelectOperation.AWAIT,
                                    OresFuture.runtimeTerminalValue(terminal)), completions);
                    progressed = true;
                    break;
                }
                if (chosen.selectCase instanceof CancelCase) {
                    commitSingleLocked(chosen,
                            new SelectResult(chosen.index, SelectOperation.CANCELLED, null), completions);
                    progressed = true;
                    break;
                }
                if (chosen.selectCase instanceof TimeoutCase) {
                    commitSingleLocked(chosen,
                            new SelectResult(chosen.index, SelectOperation.TIMEOUT, null), completions);
                    progressed = true;
                    break;
                }
                if (chosen.selectCase instanceof ObserveCase<?>) {
                    Object terminal = chosen.oneShot.result.runtimeTerminalStateOrNull();
                    Throwable failure = OresFuture.runtimeTerminalFailure(terminal);
                    if (failure != null) commitFailureLocked(chosen, failure, completions);
                    else commitSingleLocked(chosen,
                            new SelectResult(chosen.index, SelectOperation.OBSERVE,
                                    OresFuture.runtimeTerminalValue(terminal)), completions);
                    progressed = true;
                    break;
                }
                if (chosen.selectCase instanceof WriteCase<?> outgoing
                        && outgoing.exhausted()) {
                    commitFailureLocked(chosen,
                            new IllegalStateException(
                                    "select write payload was already transferred; "
                                            + "replace the WriteCase for another send"),
                            completions);
                    progressed = true;
                    break;
                }
                Channel<?> channel = chosen.channel();

                if (chosen.operation() == SelectOperation.READ) {
                    if (!channel.buffer.isEmpty()) {
                        Object value = channel.buffer.removeFirst();
                        commitSingleLocked(
                                chosen,
                                new SelectResult(
                                        chosen.index,
                                        SelectOperation.READ,
                                        value),
                                completions);
                        progressed = true;
                        break;
                    }

                    if (channel.closed) {
                        commitFailureLocked(
                                chosen,
                                channel.closedFailure(),
                                completions);
                        progressed = true;
                        break;
                    }

                    CaseRegistration writer =
                            findMutualPeerLocked(
                                    chosen,
                                    SelectOperation.WRITE);
                    if (writer != null) {
                        commitPairLocked(chosen, writer, completions);
                        progressed = true;
                        break;
                    }
                } else {
                    if (channel.closed) {
                        commitFailureLocked(
                                chosen,
                                channel.closedFailure(),
                                completions);
                        progressed = true;
                        break;
                    }

                    CaseRegistration reader =
                            findMutualPeerLocked(
                                    chosen,
                                    SelectOperation.READ);
                    if (reader != null) {
                        commitPairLocked(reader, chosen, completions);
                        progressed = true;
                        break;
                    }

                    if (channel.capacity > channel.buffer.size()) {
                        if (!((WriteCase<?>) chosen.selectCase).claimCommit()) {
                            commitFailureLocked(chosen,
                                    new IllegalStateException("select write payload already transferred"),
                                    completions);
                            progressed = true;
                            break;
                        }
                        @SuppressWarnings("unchecked")
                        Channel<Object> writable =
                                (Channel<Object>) channel;
                        writable.buffer.addLast(chosen.writeValue());
                        commitSingleLocked(
                                chosen,
                                new SelectResult(
                                        chosen.index,
                                        SelectOperation.WRITE,
                                        null),
                                completions);
                        progressed = true;
                        break;
                    }
                }
            }
        } while (progressed);
    }

    private static CaseRegistration preferredCommittableCaseLocked(
            SelectRegistration selection) {
        if (selection.decided) return null;

        for (int index : selection.order) {
            SelectCase selectCase = selection.set.cases.get(index);
            if (selectCase instanceof DefaultCase) continue;

            CaseRegistration registration =
                    findRegistration(selection, index);
            if (registration == null) continue;
            if (externalReadyLocked(registration)) return registration;
            if (selectCase instanceof WriteCase<?> write && write.exhausted()) {
                return registration;
            }
            if (!(selectCase instanceof ReadCase<?> || selectCase instanceof WriteCase<?>)) continue;

            Channel<?> channel = registration.channel();
            if (registration.operation() == SelectOperation.READ) {
                if (!channel.buffer.isEmpty() || channel.closed) {
                    return registration;
                }
                if (findMutualPeerLocked(
                        registration,
                        SelectOperation.WRITE) != null) {
                    return registration;
                }
            } else {
                if (channel.closed
                        || channel.capacity > channel.buffer.size()) {
                    return registration;
                }
                if (findMutualPeerLocked(
                        registration,
                        SelectOperation.READ) != null) {
                    return registration;
                }
            }
        }
        return null;
    }

    private static CaseRegistration preferredReadyCaseLocked(
            SelectRegistration selection) {
        if (selection.decided) return null;

        for (int index : selection.order) {
            SelectCase selectCase = selection.set.cases.get(index);
            if (selectCase instanceof DefaultCase) continue;

            CaseRegistration registration =
                    findRegistration(selection, index);
            if (registration != null && basicReadyLocked(registration)) {
                return registration;
            }
        }
        return null;
    }

    private static boolean basicReadyLocked(CaseRegistration registration) {
        if (!(registration.selectCase instanceof ReadCase<?>
                || registration.selectCase instanceof WriteCase<?>)) {
            return externalReadyLocked(registration);
        }
        if (registration.selectCase instanceof WriteCase<?> write
                && write.exhausted()) return true;
        Channel<?> channel = registration.channel();

        if (registration.operation() == SelectOperation.READ) {
            if (!channel.buffer.isEmpty()) return true;
            if (channel.closed) return true;
            return hasOppositePeerLocked(
                    registration,
                    SelectOperation.WRITE);
        }

        if (channel.closed) return true;
        if (hasOppositePeerLocked(registration, SelectOperation.READ)) {
            return true;
        }
        return channel.capacity > channel.buffer.size();
    }

    private static boolean hasOppositePeerLocked(
            CaseRegistration registration,
            SelectOperation operation) {
        for (CaseRegistration candidate :
                registration.channel().registrations) {
            if (candidate.selection == registration.selection
                    || candidate.selection.decided
                    || candidate.operation() != operation) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean externalReadyLocked(CaseRegistration registration) {
        SelectCase item = registration.selectCase;
        if (item instanceof AwaitCase<?> awaited) return awaited.future().isDone();
        if (item instanceof CancelCase cancelled) return cancelled.signal().isCancelled();
        if (item instanceof TimeoutCase timeout) {
            return Math.max(0L, System.nanoTime() - registration.selection.startNanos)
                    >= timeout.delayNanos();
        }
        if (item instanceof ObserveCase<?>) {
            return registration.oneShot != null && registration.oneShot.result.isDone();
        }
        return false;
    }

    /**
     * A rendezvous commits only when each selection currently prefers the
     * matching case under its own FAIR/PRIORITY/RANDOM order. This prevents a
     * peer's lower-priority arm from being stolen merely because it is present
     * on the same rendezvous channel.
     */
    private static CaseRegistration findMutualPeerLocked(
            CaseRegistration registration,
            SelectOperation opposite) {
        // Buffered values precede pending writers. Pairing a writer directly
        // with a reader here would let a newer value overtake committed data.
        if (!registration.channel().buffer.isEmpty()) return null;
        CaseRegistration best = null;

        for (CaseRegistration candidate :
                registration.channel().registrations) {
            if (candidate.selection == registration.selection
                    || candidate.selection.decided
                    || candidate.operation() != opposite) {
                continue;
            }

            CaseRegistration peerPreferred =
                    preferredReadyCaseLocked(candidate.selection);
            if (peerPreferred != candidate) continue;

            if (best == null || candidate.ticket < best.ticket) {
                best = candidate;
            }
        }
        return best;
    }

    private static CaseRegistration findRegistration(
            SelectRegistration selection,
            int index) {
        for (CaseRegistration registration : selection.registrations) {
            if (registration.index == index) return registration;
        }
        return null;
    }

    private static void commitPairLocked(
            CaseRegistration reader,
            CaseRegistration writer,
            List<Runnable> completions) {
        if (reader.selection == writer.selection
                || reader.selection.decided
                || writer.selection.decided) {
            return;
        }

        if (!((WriteCase<?>) writer.selectCase).claimCommit()) {
            commitFailureLocked(writer,
                    new IllegalStateException("select write payload already transferred"),
                    completions);
            return;
        }
        Object value = writer.writeValue();

        reader.selection.decided = true;
        writer.selection.decided = true;
        reader.selection.set.selected(reader.index, reader.selection.policy);
        writer.selection.set.selected(writer.index, writer.selection.policy);
        unregisterLocked(reader.selection);
        unregisterLocked(writer.selection);

        SelectResult readResult = new SelectResult(
                reader.index,
                SelectOperation.READ,
                value);
        SelectResult writeResult = new SelectResult(
                writer.index,
                SelectOperation.WRITE,
                null);

        completions.add(() ->
                reader.selection.future.completeFromRuntime(readResult));
        completions.add(() ->
                writer.selection.future.completeFromRuntime(writeResult));
    }

    private static void commitSingleLocked(
            CaseRegistration registration,
            SelectResult result,
            List<Runnable> completions) {
        SelectRegistration selection = registration.selection;
        if (selection.decided) return;

        selection.decided = true;
        selection.set.selected(result.index(), selection.policy);
        unregisterLocked(selection);
        completions.add(() ->
                selection.future.completeFromRuntime(result));
    }

    private static void commitFailureLocked(
            CaseRegistration registration,
            Throwable failure,
            List<Runnable> completions) {
        SelectRegistration selection = registration.selection;
        if (selection.decided) return;

        // A terminally ready case still wins selection. FAIR reuse must rotate
        // past it exactly as it does after a successful read/write; otherwise a
        // permanently closed arm can monopolize a reusable SelectSet forever.
        selection.decided = true;
        selection.set.selected(registration.index, selection.policy);
        unregisterLocked(selection);
        completions.add(() ->
                selection.future.failFromRuntime(failure));
    }

    private static Channel<?> channelOf(SelectCase selectCase) {
        if (selectCase instanceof ReadCase<?> read) return read.channel();
        if (selectCase instanceof WriteCase<?> write) return write.channel();
        throw new IllegalArgumentException(
                "default select case has no channel");
    }

    private static <T> OresFuture<T> mapSelection(
            OresFuture<SelectResult> source,
            Function<SelectResult, T> mapper) {
        OresFuture<T> result = new OresFuture<>(
                () -> source.cancel(false),
                () -> { });

        source.whenCompleteRuntime((selected, failure) -> {
            if (failure == null) {
                try {
                    result.completeFromRuntime(mapper.apply(selected));
                } catch (Throwable mappingFailure) {
                    result.failFromRuntime(mappingFailure);
                }
            } else if (source.isCancelled()) {
                // source is private to this mapping. Its cancellation was
                // initiated by result.cancel(); allow the outer cancellation
                // call to perform the authoritative Cancelled settlement.
                return;
            } else if (!result.isDone()) {
                result.failFromRuntime(failure);
            }
        });
        return result;
    }

    private static void runCompletions(List<Runnable> completions) {
        for (Runnable completion : completions) completion.run();
    }
}
