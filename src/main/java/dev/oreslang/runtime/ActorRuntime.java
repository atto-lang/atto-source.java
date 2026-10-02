package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * Oreslang follows the core Akka-style execution invariant: actors are
 * multiplexed over dispatcher threads, but one actor processes its mailbox
 * serially. Carrier-thread identity is never actor identity.
 *
 * PRIVATE and SHARED actors are deliberately bulkheaded onto different
 * dispatchers. Private actors also receive a confined logical memory slice;
 * shared actors may coordinate through explicitly synchronized shared cells.
 */
public final class ActorRuntime implements AutoCloseable {
    public enum ActorKind { PRIVATE, SHARED }

    public record DispatcherConfig(int privateParallelism, int sharedParallelism, int throughput) {
        public DispatcherConfig {
            if (privateParallelism <= 0) throw new IllegalArgumentException("privateParallelism must be > 0");
            if (sharedParallelism <= 0) throw new IllegalArgumentException("sharedParallelism must be > 0");
            if (throughput <= 0) throw new IllegalArgumentException("throughput must be > 0");
        }

        public static DispatcherConfig defaults() {
            int cpus = Math.max(2, Runtime.getRuntime().availableProcessors());
            return new DispatcherConfig(cpus, cpus, 64);
        }
    }

    public record ActorId(UUID value) {
        public ActorId { Objects.requireNonNull(value); }
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    /** Deeply immutable value that shared actors may reference without copying. */
    public record Shared<T>(T value) { }

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong privateMemoryBytes = new AtomicLong();
    private final IsolatePolicy policyCeiling;
    private final DispatcherConfig dispatcherConfig;
    private final ExecutorService privateDispatcher;
    private final ExecutorService sharedDispatcher;
    private final ThreadLocal<ActorCell<?>> currentActor = new ThreadLocal<>();

    public ActorRuntime() {
        this(IsolatePolicy.developer(), DispatcherConfig.defaults());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this(policyCeiling, DispatcherConfig.defaults());
    }

    public ActorRuntime(IsolatePolicy policyCeiling, DispatcherConfig dispatcherConfig) {
        this.policyCeiling = Objects.requireNonNull(policyCeiling);
        this.dispatcherConfig = Objects.requireNonNull(dispatcherConfig);
        this.privateDispatcher = Executors.newFixedThreadPool(
                dispatcherConfig.privateParallelism(), namedFactory("ores-private-actor-dispatcher-"));
        this.sharedDispatcher = Executors.newFixedThreadPool(
                dispatcherConfig.sharedParallelism(), namedFactory("ores-shared-actor-dispatcher-"));
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }
    public DispatcherConfig dispatcherConfig() { return dispatcherConfig; }
    public long privateMemoryBytes() { return privateMemoryBytes.get(); }

    /**
     * Logical actor-confined memory slice for one private actor.
     *
     * This is independent of carrier threads. Mailbox payloads and persistent
     * actor-state allocations share one budget. The current JVM backend uses
     * accounting plus alias isolation; a native/polyglot-isolate backend can map
     * this same contract to a physically separate heap/arena.
     */
    public final class ActorMemorySlice implements AutoCloseable {
        private final ActorId owner;
        private final long limitBytes;
        private final AtomicLong usedBytes = new AtomicLong();
        private final AtomicBoolean sliceClosed = new AtomicBoolean();

        private ActorMemorySlice(ActorId owner, long limitBytes) {
            this.owner = Objects.requireNonNull(owner);
            this.limitBytes = limitBytes;
        }

        public ActorId owner() { return owner; }
        public long limitBytes() { return limitBytes; }
        public long usedBytes() { return usedBytes.get(); }
        public long remainingBytes() { return Math.max(0L, limitBytes - usedBytes.get()); }
        public boolean closed() { return sliceClosed.get(); }

        /**
         * Reserve persistent private-actor heap. Compiler/interpreter lowering
         * should retain the reservation for as long as the state allocation is
         * live and close it when that allocation dies.
         */
        public MemoryReservation reserveHeap(long bytes) {
            requireCurrentOwner();
            return reserve(bytes, "private actor heap");
        }

        private MemoryReservation reserveMailbox(Object isolatedMessage) {
            return reserve(estimateFrozenBytes(isolatedMessage), "private actor mailbox");
        }

        private MemoryReservation reserve(long bytes, String purpose) {
            if (bytes < 0) throw new IllegalArgumentException("memory reservation cannot be negative");
            if (sliceClosed.get()) throw new IllegalStateException("private actor memory slice is closed");
            if (bytes == 0) return new MemoryReservation(this, 0);

            while (true) {
                long current = usedBytes.get();
                long next;
                try {
                    next = Math.addExact(current, bytes);
                } catch (ArithmeticException overflow) {
                    throw new IllegalStateException(purpose + " accounting overflow");
                }
                if (next > limitBytes) {
                    throw new IllegalStateException(purpose + " limit exceeded for " + owner
                            + ": requested=" + bytes + " used=" + current + " limit=" + limitBytes);
                }
                if (usedBytes.compareAndSet(current, next)) break;
            }

            try {
                reservePrivateRuntimeBytes(bytes, owner, purpose);
            } catch (RuntimeException failure) {
                usedBytes.addAndGet(-bytes);
                throw failure;
            }
            return new MemoryReservation(this, bytes);
        }

        private void requireCurrentOwner() {
            ActorCell<?> cell = currentActor.get();
            if (cell == null || cell.kind != ActorKind.PRIVATE || !cell.ref.id().equals(owner)) {
                throw new IllegalStateException(
                        "private actor memory slice may only be reserved by its owning actor");
            }
        }

        private void release(long bytes) {
            if (bytes == 0 || sliceClosed.get()) return;
            long remaining = usedBytes.addAndGet(-bytes);
            if (remaining < 0) {
                usedBytes.addAndGet(bytes);
                throw new IllegalStateException("private actor memory accounting underflow for " + owner);
            }
            long aggregate = privateMemoryBytes.addAndGet(-bytes);
            if (aggregate < 0) {
                privateMemoryBytes.addAndGet(bytes);
                usedBytes.addAndGet(bytes);
                throw new IllegalStateException("private actor aggregate memory accounting underflow");
            }
        }

        @Override
        public void close() {
            if (!sliceClosed.compareAndSet(false, true)) return;
            long bytes = usedBytes.getAndSet(0);
            if (bytes != 0) {
                long aggregate = privateMemoryBytes.addAndGet(-bytes);
                if (aggregate < 0) privateMemoryBytes.set(0);
            }
        }
    }

    public final class MemoryReservation implements AutoCloseable {
        private final ActorMemorySlice slice;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private MemoryReservation(ActorMemorySlice slice, long bytes) {
            this.slice = Objects.requireNonNull(slice);
            this.bytes = bytes;
        }

        public ActorId owner() { return slice.owner(); }
        public long bytes() { return bytes; }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) slice.release(bytes);
        }
    }

    private record MessageEnvelope(Object value, MemoryReservation reservation) implements AutoCloseable {
        @Override
        public void close() {
            if (reservation != null) reservation.close();
        }
    }

    /**
     * Explicit synchronized shared-memory cell.
     *
     * Actor fields do not use this: a mailbox turn already provides exclusive
     * mutation of actor-owned state. SyncCell is for state intentionally shared
     * by multiple SHARED actors.
     */
    public final class SyncCell<T> {
        private final ReentrantLock lock = new ReentrantLock();
        private T value;

        @SuppressWarnings("unchecked")
        private SyncCell(T initialValue) {
            this.value = (T) freeze(initialValue);
        }

        public T snapshot() {
            lock.lock();
            try {
                return value;
            } finally {
                lock.unlock();
            }
        }

        public <R> R read(Function<? super T, ? extends R> reader) {
            Objects.requireNonNull(reader);
            lock.lock();
            try {
                @SuppressWarnings("unchecked")
                R result = (R) freeze(reader.apply(value));
                return result;
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        public T update(UnaryOperator<T> updater) {
            Objects.requireNonNull(updater);
            requireSharedActorTurn();
            lock.lock();
            try {
                value = (T) freeze(updater.apply(value));
                return value;
            } finally {
                lock.unlock();
            }
        }
    }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    /**
     * Compiler-facing actor constructor. The actor context is available before
     * state initialization, so private actor fields can reserve/allocate in the
     * actor's confined memory slice rather than being captured from the caller.
     */
    @FunctionalInterface
    public interface BehaviorFactory<M> {
        Behavior<M> create(ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
        ActorKind kind();
        Optional<ActorMemorySlice> privateMemory();
    }

    public final class ActorRef<M> {
        private final ActorId id;
        private final ActorKind kind;

        private ActorRef(ActorId id, ActorKind kind) {
            this.id = id;
            this.kind = kind;
        }

        public ActorId id() { return id; }
        public ActorKind kind() { return kind; }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        @Override
        public String toString() {
            return "ActorRef[" + kind + ":" + id.value() + "]";
        }
    }

    /** Backward-compatible default: an unqualified runtime actor is private. */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policy, behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policyCeiling, behaviorFactory);
    }

    /**
     * Trusted-host compatibility path. Compiler-generated actors should prefer
     * the context-aware BehaviorFactory overload.
     */
    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        Objects.requireNonNull(behaviorFactory);
        return spawn(ActorKind.PRIVATE, policy, context -> behaviorFactory.get());
    }

    public <M> ActorRef<M> spawnPrivate(BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, policy, behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnShared(policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        Objects.requireNonNull(behaviorFactory);
        return spawn(ActorKind.SHARED, policy, context -> behaviorFactory.get());
    }

    public <M> ActorRef<M> spawnShared(BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.SHARED, policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.SHARED, policy, behaviorFactory);
    }

    /** Compatibility path for trusted host callers. */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        Objects.requireNonNull(behaviorFactory);
        return spawn(kind, policy, context -> behaviorFactory.get());
    }

    /**
     * Creates the actor identity and private memory slice immediately. Behavior
     * initialization later runs on that actor's dispatcher with the actor
     * context already installed.
     */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        Objects.requireNonNull(kind);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(behaviorFactory);
        requireWithinCeiling(policy);

        ActorId id = ActorId.create();
        ActorRef<M> ref = new ActorRef<>(id, kind);
        ActorCell<M> cell = new ActorCell<>(ref, kind, policy, behaviorFactory);
        actors.put(id, cell);
        return ref;
    }

    public <T> SyncCell<T> syncCell(T initialValue) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        return new SyncCell<>(initialValue);
    }

    private void requireSharedActorTurn() {
        ActorCell<?> cell = currentActor.get();
        if (cell == null || cell.kind != ActorKind.SHARED) {
            throw new IllegalStateException("shared state mutation requires a shared actor mailbox turn");
        }
    }

    private void reservePrivateRuntimeBytes(long bytes, ActorId owner, String purpose) {
        while (true) {
            long current = privateMemoryBytes.get();
            long next;
            try {
                next = Math.addExact(current, bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " aggregate accounting overflow");
            }
            if (next > policyCeiling.maxHeapBytes()) {
                throw new IllegalStateException(purpose + " aggregate runtime limit exceeded for " + owner
                        + ": requested=" + bytes + " used=" + current
                        + " runtimeLimit=" + policyCeiling.maxHeapBytes());
            }
            if (privateMemoryBytes.compareAndSet(current, next)) return;
        }
    }

    private void requireWithinCeiling(IsolatePolicy child) {
        if (!policyCeiling.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(policyCeiling.capabilities());
            throw new SecurityException("child actor policy exceeds parent capabilities: " + excess);
        }
        if (child.maxHeapBytes() > policyCeiling.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds parent policy");
        }
        if (child.maxMailboxMessages() > policyCeiling.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds parent policy");
        }
        if (child.maxWallTime().compareTo(policyCeiling.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds parent policy");
        }
        if (policyCeiling.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial parent policy");
        }
    }

    @SuppressWarnings("unchecked")
    public <M> void send(ActorRef<M> ref, M message) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        Objects.requireNonNull(ref);
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null || cell.stopped.get()) {
            throw new IllegalStateException("unknown or stopped actor " + ref.id());
        }

        Object prepared = cell.kind == ActorKind.PRIVATE ? isolateCopy(message) : freeze(message);
        MemoryReservation reservation = null;
        if (cell.memorySlice != null) {
            try {
                reservation = cell.memorySlice.reserveMailbox(prepared);
            } catch (IllegalStateException exceeded) {
                throw new IllegalStateException(
                        "private actor mailbox limit exceeded for " + ref.id() + ": " + exceeded.getMessage(),
                        exceeded);
            }
        }

        MessageEnvelope envelope = new MessageEnvelope(prepared, reservation);
        if (!cell.mailbox.offer(envelope)) {
            envelope.close();
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }
        cell.schedule();
    }

    /**
     * Cooperative scheduler hook used by compiler-injected loop safepoints.
     * Carrier threads remain an implementation detail.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        ActorCell<?> cell = currentActor.get();
        if (cell != null && cell.stopped.get()) {
            throw new CancellationException("actor execution stopped");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("actor execution interrupted");
        }
        Thread.yield();
    }

    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        return new Shared<>((T) freeze(value));
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     */
    public static Object freeze(Object value) {
        return freeze(value, new IdentityHashMap<>());
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (isScalar(value)) return value;
        if (value instanceof Shared<?> shared) return shared;
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;
        if (value instanceof ActorRuntime.SyncCell<?> cell) return cell;

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                List<Object> frozen = new ArrayList<>(list.size());
                for (Object item : list) frozen.add(freeze(item, visiting));
                return List.copyOf(frozen);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> frozen = new LinkedHashSet<>();
                for (Object item : set) frozen.add(freeze(item, visiting));
                return Collections.unmodifiableSet(frozen);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    frozen.put(freeze(entry.getKey(), visiting), freeze(entry.getValue(), visiting));
                }
                return Collections.unmodifiableMap(frozen);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> frozen = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    frozen.add(freeze(Array.get(value, i), visiting));
                }
                return List.copyOf(frozen);
            }
            if (value instanceof Sendable sendable) {
                Object frozen = sendable.freezeForSend();
                if (frozen == value) {
                    throw new IllegalArgumentException(
                            "Sendable.freezeForSend() must not return the mutable source object");
                }
                return freeze(frozen, visiting);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    /**
     * Private transport never retains a shared mutable reference. Immutable
     * shared wrappers are unwrapped and copied into the private message graph.
     */
    private static Object isolateCopy(Object value) {
        return isolateCopy(value, new IdentityHashMap<>());
    }

    private static Object isolateCopy(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (isScalar(value)) return value;
        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("private actors cannot receive shared SyncCell values");
        }
        if (value instanceof Shared<?> shared) return isolateCopy(shared.value(), visiting);
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross private actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) copy.add(isolateCopy(item, visiting));
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> copy = new LinkedHashSet<>();
                for (Object item : set) copy.add(isolateCopy(item, visiting));
                return Collections.unmodifiableSet(copy);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(isolateCopy(entry.getKey(), visiting), isolateCopy(entry.getValue(), visiting));
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> copy = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    copy.add(isolateCopy(Array.get(value, i), visiting));
                }
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Sendable sendable) {
                Object frozen = sendable.freezeForSend();
                if (frozen == value) {
                    throw new IllegalArgumentException(
                            "Sendable.freezeForSend() must not return the mutable source object");
                }
                return isolateCopy(frozen, visiting);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    /**
     * Conservative language-level footprint estimate. This is a quota metric,
     * not a promise about HotSpot/Graal object layout.
     */
    private static long estimateFrozenBytes(Object value) {
        return estimateFrozenBytes(value, new IdentityHashMap<>());
    }

    private static long estimateFrozenBytes(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return 8L;
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character || value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double) return 24L;
        if (value instanceof BigInteger integer) return 32L + integer.toByteArray().length;
        if (value instanceof BigDecimal decimal) return 48L + decimal.unscaledValue().toByteArray().length;
        if (value instanceof String string) return 40L + (long) string.length() * 2L;
        if (value instanceof UUID || value instanceof ActorId) return 40L;
        if (value instanceof Enum<?>) return 24L;
        if (value instanceof ActorRuntime.ActorRef<?>) return 48L;
        if (value instanceof ActorRuntime.SyncCell<?>) return 64L;
        if (value instanceof Shared<?> shared) return estimateFrozenBytes(shared.value(), seen);

        if (seen.put(value, Boolean.TRUE) != null) return 0L;

        long bytes = 24L;
        if (value instanceof List<?> list) {
            bytes = Math.addExact(bytes, 8L * list.size());
            for (Object item : list) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen));
            return bytes;
        }
        if (value instanceof Set<?> set) {
            bytes = Math.addExact(bytes, 16L * set.size());
            for (Object item : set) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen));
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            bytes = Math.addExact(bytes, 32L * map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getKey(), seen));
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getValue(), seen));
            }
            return bytes;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            bytes = Math.addExact(bytes, 8L * length);
            for (int i = 0; i < length; i++) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(Array.get(value, i), seen));
            }
            return bytes;
        }
        return 64L;
    }

    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId;
    }

    /** Implemented by generated immutable Oreslang aggregate values. */
    public interface Sendable {
        Object freezeForSend();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (ActorCell<?> cell : actors.values()) cell.stop();
        actors.clear();
        privateDispatcher.shutdownNow();
        sharedDispatcher.shutdownNow();
    }

    private ExecutorService dispatcherFor(ActorKind kind) {
        return kind == ActorKind.PRIVATE ? privateDispatcher : sharedDispatcher;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger next = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class ActorCell<M> {
        private final ActorRef<M> ref;
        private final ActorKind kind;
        private final IsolatePolicy policy;
        private final BehaviorFactory<M> behaviorFactory;
        private final BlockingQueue<MessageEnvelope> mailbox;
        private final ActorMemorySlice memorySlice;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private Behavior<M> behavior;

        private ActorCell(
                ActorRef<M> ref,
                ActorKind kind,
                IsolatePolicy policy,
                BehaviorFactory<M> behaviorFactory) {
            this.ref = ref;
            this.kind = kind;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
            this.memorySlice = kind == ActorKind.PRIVATE
                    ? new ActorMemorySlice(ref.id(), policy.maxHeapBytes())
                    : null;
        }

        private void schedule() {
            if (stopped.get() || closed.get()) return;
            if (!scheduled.compareAndSet(false, true)) return;
            try {
                dispatcherFor(kind).execute(this::runBatch);
            } catch (RejectedExecutionException rejected) {
                scheduled.set(false);
                stop();
                if (!closed.get()) throw rejected;
            }
        }

        @SuppressWarnings("unchecked")
        private void runBatch() {
            currentActor.set(this);
            try {
                if (stopped.get()) return;

                ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                    @Override public ActorKind kind() { return kind; }
                    @Override public Optional<ActorMemorySlice> privateMemory() {
                        return Optional.ofNullable(memorySlice);
                    }
                };

                if (behavior == null) {
                    behavior = Objects.requireNonNull(
                            behaviorFactory.create(context),
                            "actor behaviorFactory returned null");
                }

                int processed = 0;
                while (processed < dispatcherConfig.throughput() && !stopped.get()) {
                    MessageEnvelope envelope = mailbox.poll();
                    if (envelope == null) break;
                    try (envelope) {
                        behavior.onMessage((M) envelope.value(), context);
                    }
                    processed++;
                }
            } catch (Throwable failure) {
                // v0 fail-stop supervision. A later supervisor layer can expose
                // restart/escalation strategies without weakening serialization.
                stopped.set(true);
                drainMailboxReservations();
                if (memorySlice != null) memorySlice.close();
                actors.remove(ref.id(), this);
            } finally {
                currentActor.remove();
                scheduled.set(false);

                if (stopped.get() || closed.get()) {
                    actors.remove(ref.id(), this);
                } else if (!mailbox.isEmpty()) {
                    // Bounded batch/throughput handoff for dispatcher fairness.
                    schedule();
                }
            }
        }

        private void drainMailboxReservations() {
            MessageEnvelope envelope;
            while ((envelope = mailbox.poll()) != null) envelope.close();
        }

        private void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            drainMailboxReservations();
            if (memorySlice != null) memorySlice.close();
            actors.remove(ref.id(), this);
        }
    }
}
