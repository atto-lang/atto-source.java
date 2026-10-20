package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;

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
        DEFAULT
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

    public sealed interface SelectCase permits ReadCase, WriteCase, DefaultCase { }

    public record ReadCase<T>(Channel<T> channel) implements SelectCase {
        public ReadCase {
            Objects.requireNonNull(channel, "channel");
        }
    }

    public record WriteCase<T>(Channel<T> channel, T value)
            implements SelectCase {
        public WriteCase {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(
                    value,
                    "Oreslang channels cannot carry null; use Option<T>");
        }
    }

    public record DefaultCase() implements SelectCase { }

    public static <T> ReadCase<T> read(Channel<T> channel) {
        return new ReadCase<>(channel);
    }

    public static <T> WriteCase<T> write(Channel<T> channel, T value) {
        return new WriteCase<>(channel, value);
    }

    public static DefaultCase defaultCase() {
        return new DefaultCase();
    }

    /**
     * Reusable select descriptor. Static and dynamic source select lower to
     * this same primitive.
     */
    public static final class SelectSet {
        private final List<SelectCase> cases;
        private final AtomicLong fairCursor;
        private final int defaultIndex;

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
            this(cases, new AtomicLong(initialFairCursor));
        }

        private SelectSet(
                Collection<? extends SelectCase> cases,
                AtomicLong sharedFairCursor) {
            Objects.requireNonNull(cases, "cases");
            this.fairCursor = Objects.requireNonNull(sharedFairCursor, "sharedFairCursor");
            if (cases.isEmpty()) {
                throw new IllegalArgumentException(
                        "select requires at least one case");
            }
            this.cases = List.copyOf(cases);
            int foundDefault = -1;
            for (int i = 0; i < this.cases.size(); i++) {
                if (!(this.cases.get(i) instanceof DefaultCase)) continue;
                if (foundDefault >= 0) {
                    throw new IllegalArgumentException(
                            "select permits at most one default case");
                }
                foundDefault = i;
            }
            this.defaultIndex = foundDefault;
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

        private void selected(int index, SelectPolicy policy) {
            if (policy == SelectPolicy.FAIR && !cases.isEmpty()
                    && !(cases.get(index) instanceof DefaultCase)) {
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
        private final SelectSet set;

        public SelectPlan(Collection<? extends SelectCase> cases) {
            this(new SelectSet(cases));
        }

        private SelectPlan(SelectSet set) {
            this.set = Objects.requireNonNull(set, "set");
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

        public List<SelectCase> cases() {
            return set.cases();
        }

        /** Expose an immutable descriptor for language-level dynamic select. */
        public SelectSet selectSetSnapshot() {
            return set;
        }

        public OresFuture<SelectResult> selectAsync() {
            return set.selectAsync();
        }

        public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
            return set.selectAsync(policy);
        }

        public Optional<SelectResult> trySelect() {
            return set.trySelect();
        }

        public Optional<SelectResult> trySelect(SelectPolicy policy) {
            return set.trySelect(policy);
        }
    }

    /**
     * Mutation-friendly reusable select plan. Mutations publish an immutable
     * snapshot for subsequent select operations. Already-armed operations retain
     * their original case list and can still win after a removal or replacement.
     *
     * <p>Each invocation allocates its own registration. No channel waiter or
     * winner state is recycled. Case indexes refer to the operation's snapshot,
     * not to a potentially changed current plan. Use the keyed result API when
     * the application needs stable dispatch under concurrent changes.</p>
     */
    public static final class DynamicSelectPlan<K> {
        private final LinkedHashMap<K, SelectCase> entries = new LinkedHashMap<>();
        // New structural generations get their own atomic cursor. A winner
        // from an old registration can only update the old generation, and
        // arbitration never takes the plan monitor while holding COORDINATOR.
        private AtomicLong fairness = new AtomicLong();
        private long version;
        // Volatile publication permits lock-free repeated selections while
        // synchronized mutations invalidate the generation.
        private volatile Snapshot<K> cached;

        private record Snapshot<K>(long version, List<K> keys, SelectSet set) { }

        /** The case key remains stable even when snapshot indexes change. */
        public record PlannedResult<K>(long version, K key, SelectResult selection) {
            public PlannedResult {
                Objects.requireNonNull(key, "key");
                Objects.requireNonNull(selection, "selection");
            }
        }

        public DynamicSelectPlan() { }

        public DynamicSelectPlan(Map<? extends K, ? extends SelectCase> initial) {
            Objects.requireNonNull(initial, "initial");
            for (Map.Entry<? extends K, ? extends SelectCase> entry : initial.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }

        /** Add or replace a keyed case, preserving the key's insertion position. */
        public synchronized void put(K key, SelectCase selectCase) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(selectCase, "selectCase");
            if (selectCase instanceof DefaultCase) {
                for (Map.Entry<K, SelectCase> entry : entries.entrySet()) {
                    if (!entry.getKey().equals(key) && entry.getValue() instanceof DefaultCase) {
                        throw new IllegalArgumentException("select permits at most one default case");
                    }
                }
            }
            long nextVersion = Math.addExact(version, 1L);
            entries.put(key, selectCase);
            fairness = new AtomicLong(fairness.get());
            version = nextVersion;
            cached = null;
        }

        /** Remove a case for future invocations. Pending selections are unaffected. */
        public synchronized boolean remove(K key) {
            Objects.requireNonNull(key, "key");
            if (!entries.containsKey(key)) return false;
            long nextVersion = Math.addExact(version, 1L);
            entries.remove(key);
            fairness = new AtomicLong(fairness.get());
            version = nextVersion;
            cached = null;
            return true;
        }

        public synchronized long version() {
            return version;
        }

        public synchronized int size() {
            return entries.size();
        }

        public synchronized List<K> keys() {
            return List.copyOf(entries.keySet());
        }

        /**
         * A single immutable snapshot per mutation generation. The fast path
         * does not take the mutable plan monitor on every loop iteration.
         * Observing the previous snapshot concurrently with a put/remove is
         * valid: that selection linearizes before the generation change.
         */
        private Snapshot<K> snapshot() {
            Snapshot<K> published = cached;
            if (published != null) return published;
            synchronized (this) {
                published = cached;
                if (published != null) return published;
                if (entries.isEmpty()) {
                    throw new IllegalStateException("cannot select from an empty dynamic plan");
                }
                published = new Snapshot<>(
                        version,
                        List.copyOf(entries.keySet()),
                        new SelectSet(entries.values(), fairness));
                cached = published;
                return published;
            }
        }

        public OresFuture<SelectResult> selectAsync() {
            return selectAsync(SelectPolicy.FAIR);
        }

        public OresFuture<SelectResult> selectAsync(SelectPolicy policy) {
            return snapshot().set().selectAsync(policy);
        }

        public Optional<SelectResult> trySelect() {
            return trySelect(SelectPolicy.FAIR);
        }

        public Optional<SelectResult> trySelect(SelectPolicy policy) {
            return snapshot().set().trySelect(policy);
        }

        public OresFuture<PlannedResult<K>> selectKeyedAsync(SelectPolicy policy) {
            Snapshot<K> selectedSnapshot = snapshot();
            return mapSelection(
                    selectedSnapshot.set().selectAsync(policy),
                    result -> keyed(selectedSnapshot, result));
        }

        public Optional<PlannedResult<K>> trySelectKeyed(SelectPolicy policy) {
            Snapshot<K> selectedSnapshot = snapshot();
            return selectedSnapshot.set().trySelect(policy)
                    .map(result -> keyed(selectedSnapshot, result));
        }

        private PlannedResult<K> keyed(Snapshot<K> selectedSnapshot, SelectResult result) {
            return new PlannedResult<>(
                    selectedSnapshot.version(),
                    selectedSnapshot.keys().get(result.index()),
                    result);
        }

        /** Snapshot for source-language dynamic select; never a mutable waiter list. */
        public SelectSet selectSetSnapshot() {
            return snapshot().set();
        }
    }

    private static final class CaseRegistration {
        private final SelectRegistration selection;
        private final int index;
        private final SelectCase selectCase;
        private final long ticket;

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
                    "default case is never channel-registered");
        }

        private SelectOperation operation() {
            if (selectCase instanceof ReadCase<?>) return SelectOperation.READ;
            if (selectCase instanceof WriteCase<?>) return SelectOperation.WRITE;
            return SelectOperation.DEFAULT;
        }

        private Object writeValue() {
            return ((WriteCase<?>) selectCase).value();
        }
    }

    private static final class SelectRegistration {
        private final SelectSet set;
        private final SelectPolicy policy;
        // The cursor is captured under COORDINATOR on admission. FAIR and
        // PRIORITY use ordinal arithmetic without materializing an int[] of
        // arm indexes. RANDOM is the only mode requiring a shuffled array.
        private int fairStart;
        private int[] randomOrder;
        // Indexed registration storage makes readiness O(1) by arm index and
        // avoids an ArrayList backing buffer plus a detach-time copy.
        private final CaseRegistration[] registrations;
        private final int defaultIndex;
        private final OresFuture<SelectResult> future;
        private boolean decided;
        private boolean registered;

        private SelectRegistration(SelectSet set, SelectPolicy policy) {
            this.set = set;
            this.policy = policy;
            this.defaultIndex = set.defaultIndex;
            this.registrations = new CaseRegistration[set.cases.size()];
            this.future = new OresFuture<>(
                    this::cancelAdmission,
                    () -> { });
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
        }

        private Optional<SelectResult> tryNow() {
            start(true);
            if (!future.isDone()) return Optional.empty();
            return Optional.of(future.join());
        }

        private void captureOrderLocked() {
            if (policy == SelectPolicy.FAIR) {
                fairStart = Math.floorMod(set.fairCursor.get(), set.cases.size());
            } else if (policy == SelectPolicy.RANDOM) {
                int count = set.cases.size();
                randomOrder = new int[count];
                for (int i = 0; i < count; i++) randomOrder[i] = i;
                for (int i = count - 1; i > 0; i--) {
                    int j = ThreadLocalRandom.current().nextInt(i + 1);
                    int temp = randomOrder[i];
                    randomOrder[i] = randomOrder[j];
                    randomOrder[j] = temp;
                }
            }
        }

        private int orderedIndex(int ordinal) {
            if (policy == SelectPolicy.PRIORITY) return ordinal;
            if (policy == SelectPolicy.RANDOM) return randomOrder[ordinal];
            // Avoid overflow when the descriptor count approaches int limits.
            int remaining = set.cases.size() - fairStart;
            return ordinal < remaining
                    ? fairStart + ordinal
                    : ordinal - remaining;
        }

        private CaseRegistration caseOrDefault(int index) {
            CaseRegistration registration = registrations[index];
            return registration != null
                    ? registration
                    : new CaseRegistration(this, index, set.cases.get(index));
        }
    }

    private static void registerLocked(SelectRegistration selection) {
        if (selection.decided || selection.registered) return;
        selection.captureOrderLocked();
        selection.registered = true;
        ACTIVE.add(selection);

        for (int step = 0; step < selection.set.cases.size(); step++) {
            int index = selection.orderedIndex(step);
            SelectCase selectCase = selection.set.cases.get(index);
            if (selectCase instanceof DefaultCase) continue;

            CaseRegistration registration =
                    new CaseRegistration(selection, index, selectCase);
            selection.registrations[index] = registration;
            channelOf(selectCase).registrations.add(registration);
        }
    }

    private static void unregisterLocked(SelectRegistration selection) {
        if (!selection.registered) return;
        selection.registered = false;
        ACTIVE.remove(selection);
        for (int i = 0; i < selection.registrations.length; i++) {
            CaseRegistration registration = selection.registrations[i];
            if (registration == null) continue;
            registration.channel().registrations.remove(registration);
            selection.registrations[i] = null;
        }
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

        for (int step = 0; step < selection.set.cases.size(); step++) {
            int index = selection.orderedIndex(step);
            SelectCase selectCase = selection.set.cases.get(index);
            if (selectCase instanceof DefaultCase) continue;

            CaseRegistration registration =
                    findRegistration(selection, index);
            if (registration == null) continue;

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

        for (int step = 0; step < selection.set.cases.size(); step++) {
            int index = selection.orderedIndex(step);
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
        return selection.registrations[index];
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
