package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ores-owned channel substrate.
 *
 * <p>Channels never park an Oreslang carrier thread. A pending read or write is
 * represented by an {@link OresFuture}; source-level readch/writech/select
 * lower to scheduler suspension over those futures. The host-facing try*
 * methods are deliberately nonblocking.</p>
 *
 * <p>Selection is fair and deterministic by default. FAIR rotates the first
 * case for repeated selections through a reusable {@link SelectSet}. ORDERED
 * scans in lexical/list order for code that intentionally wants strict
 * priority. There is intentionally no hidden pseudo-random tie breaker.</p>
 */
public final class ChannelRuntime {
    private ChannelRuntime() { }

    public enum SelectPolicy {
        /** Deterministic rotating fairness. This is the Oreslang default. */
        FAIR,
        /** Strict lexical/list order. Earlier ready cases win and may starve later cases. */
        ORDERED
    }

    public enum Operation {
        READ,
        WRITE
    }

    public static final class ChannelClosedException extends IllegalStateException {
        public ChannelClosedException(String message) {
            super(message);
        }
    }

    /**
     * The winning select case. key is user/compiler metadata and is especially
     * useful for dynamic selects built from maps.
     */
    public record Selection(
            int index,
            Object key,
            Operation operation,
            Object value) { }

    public sealed interface SelectCase permits ReadCase, WriteCase {
        Object key();
        Channel<?> channel();
        Selection tryPerform(int index);
    }

    public record ReadCase<T>(Channel<T> channel, Object key) implements SelectCase {
        public ReadCase {
            Objects.requireNonNull(channel, "channel");
        }

        public ReadCase(Channel<T> channel) {
            this(channel, null);
        }

        @Override
        public Selection tryPerform(int index) {
            Optional<T> value = channel.tryRead();
            return value.map(item -> new Selection(index, key, Operation.READ, item)).orElse(null);
        }
    }

    public record WriteCase<T>(Channel<T> channel, T value, Object key) implements SelectCase {
        public WriteCase {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(value, "value");
        }

        public WriteCase(Channel<T> channel, T value) {
            this(channel, value, null);
        }

        @Override
        public Selection tryPerform(int index) {
            return channel.tryWrite(value)
                    ? new Selection(index, key, Operation.WRITE, value)
                    : null;
        }
    }

    /**
     * Reusable static/dynamic selection primitive. FAIR state belongs to this
     * logical select site/set rather than to a process-global random source.
     * Static source select sites should cache one SelectSet; dynamic code can
     * construct the same type from runtime list/map cases and reuse it.
     */
    public static final class SelectSet {
        private final List<SelectCase> cases;
        private final SelectPolicy policy;
        private final AtomicLong cursor = new AtomicLong();

        private SelectSet(List<? extends SelectCase> cases, SelectPolicy policy) {
            this.cases = checkedCases(cases);
            this.policy = Objects.requireNonNull(policy, "policy");
        }

        public OresFuture<Selection> next() {
            int start = policy == SelectPolicy.FAIR
                    ? (int) Math.floorMod(cursor.getAndIncrement(), cases.size())
                    : 0;
            return ActorRuntime.trackCurrentFuture(
                    new Selector(cases, policy, start).arm());
        }

        public List<SelectCase> cases() {
            return cases;
        }

        public SelectPolicy policy() {
            return policy;
        }
    }

    public static SelectSet selectSet(List<? extends SelectCase> cases) {
        return selectSet(cases, SelectPolicy.FAIR);
    }

    public static SelectSet selectSet(
            List<? extends SelectCase> cases,
            SelectPolicy policy) {
        return new SelectSet(cases, policy);
    }

    /**
     * Blocking source-level select lowers to await(selectSet(...).next()).
     * This host primitive itself is always nonblocking and returns an OresFuture.
     */
    public static OresFuture<Selection> select(
            List<? extends SelectCase> cases) {
        return selectSet(cases).next();
    }

    public static OresFuture<Selection> select(
            List<? extends SelectCase> cases,
            SelectPolicy policy) {
        return selectSet(cases, policy).next();
    }

    /**
     * Source-level `nb select` lowering. It is intentionally the exact same
     * runtime operation as select without the source-level await/suspension.
     */
    public static OresFuture<Selection> nbSelect(
            List<? extends SelectCase> cases) {
        return select(cases);
    }

    public static OresFuture<Selection> nbSelect(
            List<? extends SelectCase> cases,
            SelectPolicy policy) {
        return select(cases, policy);
    }

    /**
     * Source-level `nb readch channel`. The read registration is armed now,
     * but the owning actor is not suspended unless/ until the returned Future
     * is explicitly awaited.
     */
    public static <T> OresFuture<T> nbRead(Channel<T> channel) {
        return Objects.requireNonNull(channel, "channel").read();
    }

    /**
     * Source-level `nb writech channel, value`. The write registration is
     * armed now and completion is represented by the returned Future.
     */
    public static <T> OresFuture<Void> nbWrite(Channel<T> channel, T value) {
        return Objects.requireNonNull(channel, "channel").write(value);
    }

    /**
     * Dynamic-select helper for a list of channels. Case keys are list indexes.
     */
    public static <T> List<SelectCase> readCases(
            List<? extends Channel<T>> channels) {
        Objects.requireNonNull(channels, "channels");
        List<SelectCase> cases = new ArrayList<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            cases.add(new ReadCase<>(Objects.requireNonNull(channels.get(i), "channel"), i));
        }
        return List.copyOf(cases);
    }

    /**
     * Dynamic-select helper for a map of channels. Map keys become Selection.key.
     * Iteration order is preserved, so LinkedHashMap gives explicit priority.
     */
    public static <K, T> List<SelectCase> readCases(
            Map<K, ? extends Channel<T>> channels) {
        Objects.requireNonNull(channels, "channels");
        List<SelectCase> cases = new ArrayList<>(channels.size());
        for (Map.Entry<K, ? extends Channel<T>> entry : channels.entrySet()) {
            cases.add(new ReadCase<>(
                    Objects.requireNonNull(entry.getValue(), "channel"),
                    entry.getKey()));
        }
        return List.copyOf(cases);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<SelectCase> checkedCases(List<? extends SelectCase> cases) {
        Objects.requireNonNull(cases, "cases");
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("select requires at least one case");
        }
        List<SelectCase> copy = new ArrayList<>(cases.size());
        for (SelectCase selectCase : cases) {
            copy.add(Objects.requireNonNull(selectCase, "select case"));
        }
        return List.copyOf(copy);
    }

    public static <T> Channel<T> channel(int capacity) {
        return new Channel<>(capacity);
    }

    public static <T> Channel<T> rendezvous() {
        return new Channel<>(0);
    }

    /**
     * A bounded Oreslang channel. capacity==0 is a rendezvous channel.
     */
    public static final class Channel<T> implements AutoCloseable {
        private record ReadWaiter<T>(OresFuture<T> future) { }
        private record WriteWaiter<T>(T value, OresFuture<Void> future) { }
        private record Poll<T>(boolean ready, T value) {
            private static <T> Poll<T> empty() { return new Poll<>(false, null); }
            private static <T> Poll<T> ready(T value) { return new Poll<>(true, value); }
        }

        private final int capacity;
        private final ArrayDeque<T> buffer = new ArrayDeque<>();
        private final ArrayDeque<ReadWaiter<T>> readers = new ArrayDeque<>();
        private final ArrayDeque<WriteWaiter<T>> writers = new ArrayDeque<>();
        private final Set<Runnable> readinessSubscribers = new LinkedHashSet<>();

        private boolean closed;

        private Channel(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("channel capacity cannot be negative");
            }
            this.capacity = capacity;
        }

        public int capacity() {
            return capacity;
        }

        public synchronized int size() {
            return buffer.size();
        }

        public synchronized boolean isEmpty() {
            return buffer.isEmpty() && writers.isEmpty();
        }

        public synchronized boolean isClosed() {
            return closed;
        }

        /**
         * Nonblocking receive. Empty means no value is ready now.
         */
        public Optional<T> tryRead() {
            List<Runnable> completions = new ArrayList<>();
            Poll<T> polled;
            synchronized (this) {
                polled = pollLocked(completions);
                if (polled.ready()) {
                    snapshotReadinessLocked(completions);
                }
            }
            runCompletions(completions);
            return polled.ready() ? Optional.of(polled.value()) : Optional.empty();
        }

        /**
         * Nonblocking send. false means the write would suspend now.
         */
        public boolean tryWrite(T value) {
            Objects.requireNonNull(value, "channel values cannot be null");
            List<Runnable> completions = new ArrayList<>();
            boolean written;
            synchronized (this) {
                requireOpenLocked();
                written = writeLocked(value, completions);
                if (written) snapshotReadinessLocked(completions);
            }
            runCompletions(completions);
            return written;
        }

        /**
         * Scheduler-suspending receive primitive. The returned Future owns only
         * the registration; cancelling it removes the pending read.
         */
        public OresFuture<T> read() {
            List<Runnable> completions = new ArrayList<>();
            AtomicReference<ReadWaiter<T>> holder = new AtomicReference<>();
            OresFuture<T> pending = new OresFuture<>(
                    () -> {
                        ReadWaiter<T> waiter = holder.get();
                        if (waiter == null) return false;
                        List<Runnable> wake = new ArrayList<>();
                        boolean removed;
                        synchronized (Channel.this) {
                            removed = readers.remove(waiter);
                            if (removed) snapshotReadinessLocked(wake);
                        }
                        runCompletions(wake);
                        return removed;
                    },
                    () -> { });
            ReadWaiter<T> waiter = new ReadWaiter<>(pending);
            holder.set(waiter);

            Poll<T> polled;
            synchronized (this) {
                polled = pollLocked(completions);
                if (polled.ready()) {
                    completions.add(() -> pending.completeFromRuntime(polled.value()));
                    snapshotReadinessLocked(completions);
                } else if (closed) {
                    completions.add(() -> pending.failFromRuntime(
                            new ChannelClosedException("channel is closed")));
                } else {
                    readers.addLast(waiter);
                    snapshotReadinessLocked(completions);
                }
            }
            runCompletions(completions);
            return ActorRuntime.trackCurrentFuture(pending);
        }

        /**
         * Scheduler-suspending send primitive. The Future completes only when
         * the value has been committed to the channel/rendezvous receiver.
         */
        public OresFuture<Void> write(T value) {
            Objects.requireNonNull(value, "channel values cannot be null");
            List<Runnable> completions = new ArrayList<>();
            AtomicReference<WriteWaiter<T>> holder = new AtomicReference<>();
            OresFuture<Void> pending = new OresFuture<>(
                    () -> {
                        WriteWaiter<T> waiter = holder.get();
                        if (waiter == null) return false;
                        List<Runnable> wake = new ArrayList<>();
                        boolean removed;
                        synchronized (Channel.this) {
                            removed = writers.remove(waiter);
                            if (removed) snapshotReadinessLocked(wake);
                        }
                        runCompletions(wake);
                        return removed;
                    },
                    () -> { });
            WriteWaiter<T> waiter = new WriteWaiter<>(value, pending);
            holder.set(waiter);

            synchronized (this) {
                if (closed) {
                    completions.add(() -> pending.failFromRuntime(
                            new ChannelClosedException("channel is closed")));
                } else if (writeLocked(value, completions)) {
                    completions.add(() -> pending.completeFromRuntime(null));
                    snapshotReadinessLocked(completions);
                } else {
                    writers.addLast(waiter);
                    snapshotReadinessLocked(completions);
                }
            }
            runCompletions(completions);
            return ActorRuntime.trackCurrentFuture(pending);
        }

        /**
         * Internal readiness subscription used by select. It observes only
         * state changes and never executes guest code.
         */
        private ReadinessRegistration subscribeReadiness(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            synchronized (this) {
                readinessSubscribers.add(callback);
            }
            return new ReadinessRegistration(this, callback);
        }

        private void unsubscribeReadiness(Runnable callback) {
            synchronized (this) {
                readinessSubscribers.remove(callback);
            }
        }

        @Override
        public void close() {
            List<Runnable> completions = new ArrayList<>();
            synchronized (this) {
                if (closed) return;
                closed = true;

                ChannelClosedException failure =
                        new ChannelClosedException("channel is closed");
                ReadWaiter<T> reader;
                while ((reader = readers.pollFirst()) != null) {
                    OresFuture<T> future = reader.future();
                    if (!future.isDone()) {
                        completions.add(() -> future.failFromRuntime(failure));
                    }
                }
                WriteWaiter<T> writer;
                while ((writer = writers.pollFirst()) != null) {
                    OresFuture<Void> future = writer.future();
                    if (!future.isDone()) {
                        completions.add(() -> future.failFromRuntime(failure));
                    }
                }
                snapshotReadinessLocked(completions);
            }
            runCompletions(completions);
        }

        private Poll<T> pollLocked(List<Runnable> completions) {
            if (!buffer.isEmpty()) {
                T value = buffer.removeFirst();
                promoteWriterLocked(completions);
                return Poll.ready(value);
            }

            WriteWaiter<T> writer;
            while ((writer = writers.pollFirst()) != null) {
                if (writer.future().isDone()) continue;
                OresFuture<Void> future = writer.future();
                completions.add(() -> future.completeFromRuntime(null));
                return Poll.ready(writer.value());
            }
            return Poll.empty();
        }

        private boolean writeLocked(T value, List<Runnable> completions) {
            ReadWaiter<T> reader;
            while ((reader = readers.pollFirst()) != null) {
                if (reader.future().isDone()) continue;
                OresFuture<T> future = reader.future();
                completions.add(() -> future.completeFromRuntime(value));
                return true;
            }

            if (capacity > buffer.size()) {
                buffer.addLast(value);
                return true;
            }
            return false;
        }

        private void promoteWriterLocked(List<Runnable> completions) {
            if (capacity == 0 || buffer.size() >= capacity) return;
            WriteWaiter<T> writer;
            while ((writer = writers.pollFirst()) != null) {
                if (writer.future().isDone()) continue;
                buffer.addLast(writer.value());
                OresFuture<Void> future = writer.future();
                completions.add(() -> future.completeFromRuntime(null));
                return;
            }
        }

        private void requireOpenLocked() {
            if (closed) throw new ChannelClosedException("channel is closed");
        }

        private void snapshotReadinessLocked(List<Runnable> completions) {
            completions.addAll(readinessSubscribers);
        }
    }

    private static final class ReadinessRegistration implements AutoCloseable {
        private final Channel<?> channel;
        private final Runnable callback;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ReadinessRegistration(Channel<?> channel, Runnable callback) {
            this.channel = channel;
            this.callback = callback;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                channel.unsubscribeReadiness(callback);
            }
        }
    }

    /**
     * Select is implemented as readiness subscription + deterministic re-probe.
     * Losing cases never perform a read/write, avoiding the common "race a set
     * of Futures and cancel the losers" bug where a losing read already consumes
     * a value.
     */
    private static final class Selector {
        private final List<SelectCase> cases;
        private final SelectPolicy policy;
        private final int start;
        private final AtomicBoolean probing = new AtomicBoolean();
        private final AtomicBoolean reprobe = new AtomicBoolean();
        private final List<ReadinessRegistration> registrations = new ArrayList<>();
        private final Object arbitrationLock = new Object();
        private boolean cancellationClaimed;
        private boolean operationCommitted;
        private final OresFuture<Selection> future;

        private Selector(List<SelectCase> cases, SelectPolicy policy, int start) {
            this.cases = cases;
            this.policy = policy;
            this.start = start;
            this.future = new OresFuture<>(this::admitCancellation, this::detach);
        }

        private OresFuture<Selection> arm() {
            probe();
            if (future.isDone()) return future;

            synchronized (registrations) {
                if (future.isDone()) return future;
                Set<Channel<?>> unique = new LinkedHashSet<>();
                for (SelectCase selectCase : cases) unique.add(selectCase.channel());
                for (Channel<?> channel : unique) {
                    registrations.add(channel.subscribeReadiness(this::signal));
                }
            }

            // Close the probe/subscribe race: if a channel became ready between
            // the first scan and subscription, this second scan observes it.
            probe();
            return future;
        }

        private void signal() {
            if (future.isDone()) return;
            if (!probing.compareAndSet(false, true)) {
                reprobe.set(true);
                return;
            }

            try {
                do {
                    reprobe.set(false);
                    Selection selected = scan();
                    if (selected != null) {
                        if (future.completeFromRuntime(selected)) detach();
                        return;
                    }
                } while (reprobe.get() && !future.isDone());
            } finally {
                probing.set(false);
            }

            if (reprobe.get() && !future.isDone()) signal();
        }

        private void probe() {
            signal();
        }

        private Selection scan() {
            int size = cases.size();
            int first = policy == SelectPolicy.FAIR ? start : 0;
            for (int offset = 0; offset < size; offset++) {
                int index = (first + offset) % size;

                // A select cancellation and a committed channel operation are
                // mutually exclusive. Hold this small arbitration lock only
                // around a nonblocking tryRead/tryWrite; it never spans a wait.
                synchronized (arbitrationLock) {
                    if (cancellationClaimed || operationCommitted || future.isDone()) {
                        return null;
                    }
                    Selection selected = cases.get(index).tryPerform(index);
                    if (selected != null) {
                        operationCommitted = true;
                        return selected;
                    }
                }
            }
            return null;
        }

        private boolean admitCancellation() {
            synchronized (arbitrationLock) {
                if (operationCommitted) return false;
                cancellationClaimed = true;
                return true;
            }
        }

        private void detach() {
            synchronized (registrations) {
                for (ReadinessRegistration registration : registrations) {
                    registration.close();
                }
                registrations.clear();
            }
        }
    }

    private static void runCompletions(List<Runnable> completions) {
        for (Runnable completion : completions) {
            try {
                completion.run();
            } catch (RuntimeException | Error ignored) {
                // Runtime waiter/readiness plumbing is best-effort notification.
                // State has already committed and guest code is never run here.
            }
        }
    }
}
