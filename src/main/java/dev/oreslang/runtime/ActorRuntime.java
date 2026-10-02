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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * The execution model follows the important Akka dispatcher invariant: actors
 * are multiplexed over executor threads, but a given actor drains exactly one
 * mailbox serially. A carrier thread is not actor identity and may change from
 * one mailbox turn to the next.
 *
 * PRIVATE actors and SHARED actors are deliberately bulkheaded onto separate
 * dispatchers. Private messages are isolation-copied so private actors never
 * receive shared-memory cells. Shared actors may additionally exchange
 * explicitly shareable values: deeply immutable Shared values and SyncCell
 * values whose updates are serialized by the cell itself.
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

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final IsolatePolicy policyCeiling;
    private final DispatcherConfig dispatcherConfig;
    private final ExecutorService privateDispatcher;
    private final ExecutorService sharedDispatcher;
    private final ThreadLocal<ActorCell<?>> currentActor = new ThreadLocal<>();
    private final AtomicLong privateMemoryBytes = new AtomicLong();

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

    public record ActorId(UUID value) {
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    /** Deeply immutable value that shared actors may reference without copying. */
    public record Shared<T>(T value) { }

    /**
     * Actor-local memory accounting boundary. The Java reference heap is still
     * the current storage backend, but all lowered actor-local allocations can
     * be charged to this slice. PRIVATE slices additionally enforce that public
     * reservations are made only while their owning actor is executing.
     *
     * This abstraction is intentionally backend-neutral: a future Native Image
     * or polyglot-isolate backend can map the same slice contract to a true
     * isolate heap without changing Oreslang actor semantics.
     */
    public interface MemorySlice {
        UUID id();
        ActorKind kind();
        long limitBytes();
        long usedBytes();
        boolean closed();
        MemoryReservation reserve(long bytes);
    }

    public static final class MemoryLimitExceededException extends IllegalStateException {
        public MemoryLimitExceededException(String message) { super(message); }
    }

    public static final class MemoryReservation implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        private final Runnable releaser;
        private final long bytes;

        private MemoryReservation(long bytes, Runnable releaser) {
            this.bytes = bytes;
            this.releaser = releaser;
        }

        public long bytes() { return bytes; }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) releaser.run();
        }
    }

    private abstract class AbstractMemorySlice implements MemorySlice, AutoCloseable {
        private final UUID id = UUID.randomUUID();
        private final ActorKind kind;
        private final long limitBytes;
        private final AtomicLong usedBytes = new AtomicLong();
        private final AtomicBoolean sliceClosed = new AtomicBoolean();

        private AbstractMemorySlice(ActorKind kind, long limitBytes) {
            this.kind = kind;
            this.limitBytes = limitBytes;
        }

        @Override public UUID id() { return id; }
        @Override public ActorKind kind() { return kind; }
        @Override public long limitBytes() { return limitBytes; }
        @Override public long usedBytes() { return usedBytes.get(); }
        @Override public boolean closed() { return sliceClosed.get(); }

        protected abstract void requireAccess();

        @Override
        public MemoryReservation reserve(long bytes) {
            requireAccess();
            return reserveInternal(bytes);
        }

        private MemoryReservation reserveInternal(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("memory reservation cannot be negative");
            if (sliceClosed.get()) throw new IllegalStateException("actor memory slice is closed");
            if (bytes == 0) return new MemoryReservation(0, () -> { });

            while (true) {
                long current = usedBytes.get();
                long next;
                try {
                    next = Math.addExact(current, bytes);
                } catch (ArithmeticException overflow) {
                    throw new MemoryLimitExceededException("actor memory reservation overflow");
                }
                if (next > limitBytes) {
                    throw new MemoryLimitExceededException(
                            "actor memory slice exceeded: requested=" + bytes
                                    + " used=" + current + " limit=" + limitBytes);
                }
                if (usedBytes.compareAndSet(current, next)) {
                    return new MemoryReservation(bytes, () -> {
                        if (sliceClosed.get()) return;
                        long remaining = usedBytes.addAndGet(-bytes);
                        if (remaining < 0) {
                            usedBytes.set(0);
                            throw new IllegalStateException("actor memory accounting underflow");
                        }
                    });
                }
            }
        }

        private MemoryReservation reserveTransport(long bytes) {
            return reserveInternal(bytes);
        }

        @Override
        public void close() {
            if (sliceClosed.compareAndSet(false, true)) usedBytes.set(0);
        }
    }

    public final class PrivateMemorySlice extends AbstractMemorySlice {
        private final ActorId owner;

        private PrivateMemorySlice(ActorId owner, long limitBytes) {
            super(ActorKind.PRIVATE, limitBytes);
            this.owner = owner;
        }

        public ActorId owner() { return owner; }

        @Override
        protected void requireAccess() {
            ActorCell<?> cell = currentActor.get();
            if (cell == null || !cell.ref.id().equals(owner)) {
                throw new IllegalStateException("private actor memory slice may only be accessed by its owning actor");
            }
        }
    }

    public final class SharedMemorySlice extends AbstractMemorySlice {
        private SharedMemorySlice(long limitBytes) {
            super(ActorKind.SHARED, limitBytes);
        }

        @Override
        protected void requireAccess() {
            ActorCell<?> cell = currentActor.get();
            if (cell == null || cell.kind != ActorKind.SHARED) {
                throw new IllegalStateException("shared actor memory slice may only be reserved during shared-actor execution");
            }
        }
    }

    /**
     * Explicit synchronized shared-memory cell.
     *
     * The stored state is always frozen. Readers only observe frozen snapshots,
     * and updates run under one lock and must produce a replacement value. This
     * keeps arbitrary mutable aliases from escaping while still allowing shared
     * actors to coordinate through memory when message passing is not suitable.
     */
    public static final class SyncCell<T> {
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
            lock.lock();
            try {
                value = (T) freeze(updater.apply(value));
                return value;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Logical, actor-confined heap budget for one private actor.
     *
     * This is deliberately independent of carrier threads. Every allocation is
     * charged both to this slice and to the parent ActorRuntime aggregate
     * budget. A production backend may map the same abstraction to a Graal
     * isolate/arena for physical heap separation.
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
         * Reserve persistent actor-state memory. Generated Oreslang actor
         * storage should retain this allocation for as long as the state lives.
         */
        public MemoryReservation reserveHeap(long bytes) {
            return reserve(bytes, "private actor heap");
        }

        private MemoryReservation reserveMailbox(Object frozenMessage) {
            return reserve(estimateFrozenBytes(frozenMessage), "private actor mailbox");
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

        private void release(long bytes) {
            if (bytes == 0 || sliceClosed.get()) return;
            long remaining = usedBytes.addAndGet(-bytes);
            if (remaining < 0) {
                usedBytes.addAndGet(bytes);
                throw new IllegalStateException("private actor memory accounting underflow for " + owner);
            }
            privateMemoryBytes.addAndGet(-bytes);
        }

        @Override
        public void close() {
            if (!sliceClosed.compareAndSet(false, true)) return;
            long bytes = usedBytes.getAndSet(0);
            if (bytes != 0) privateMemoryBytes.addAndGet(-bytes);
        }
    }

    public final class MemoryReservation implements AutoCloseable {
        private final ActorMemorySlice slice;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private MemoryReservation(ActorMemorySlice slice, long bytes) {
            this.slice = slice;
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

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
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

    /**
     * Backward-compatible default: an unqualified actor is private.
     */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policy, behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, policy, behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnShared(policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(ActorKind.SHARED, policy, behaviorFactory);
    }

    /**
     * Creates actor-local behavior on its dispatcher the first time that actor
     * is scheduled. The Supplier should be generated by Oreslang lowering, not
     * supplied from untrusted guest code.
     */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        Objects.requireNonNull(kind);
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

    public long privateMemoryBytes() {
        return privateMemoryBytes.get();
    }

    private void requireWithinCeiling(IsolatePolicy child) {
        Objects.requireNonNull(child);
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
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null || cell.stopped.get()) throw new IllegalStateException("unknown or stopped actor " + ref.id());

        Object prepared = cell.kind == ActorKind.PRIVATE ? isolateCopy(message) : freeze(message);
        MemoryReservation reservation = cell.memorySlice == null ? null : cell.memorySlice.reserveMailbox(prepared);
        MessageEnvelope envelope = new MessageEnvelope(prepared, reservation);
        if (!cell.mailbox.offer(envelope)) {
            envelope.close();
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }
        cell.schedule();
    }

    /**
     * Cooperative scheduler hook used by compiler-injected loop safepoints.
     * It checks both whole-runtime cancellation and the currently executing
     * actor's cancellation token. It never exposes a carrier Thread to guest
     * code because a carrier is shared by many actor identities.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        ActorCell<?> cell = currentActor.get();
        if (cell != null && cell.stopped.get()) throw new CancellationException("actor execution stopped");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("actor execution interrupted");
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
        if (isScalar(value)) return value;
        if (value instanceof Shared<?> shared) return shared;
        if (value instanceof SyncCell<?> cell) return cell;
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;
        if (value instanceof List<?> list) {
            List<Object> frozen = new ArrayList<>(list.size());
            for (Object item : list) frozen.add(freeze(item));
            return List.copyOf(frozen);
        }
        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> frozen = new LinkedHashSet<>();
            for (Object item : set) frozen.add(freeze(item));
            return Collections.unmodifiableSet(frozen);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) frozen.put(freeze(entry.getKey()), freeze(entry.getValue()));
            return Collections.unmodifiableMap(frozen);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> frozen = new ArrayList<>(length);
            for (int i = 0; i < length; i++) frozen.add(freeze(Array.get(value, i)));
            return List.copyOf(frozen);
        }
        if (value instanceof Sendable sendable) {
            Object frozen = sendable.freezeForSend();
            if (frozen == value) {
                throw new IllegalArgumentException("Sendable.freezeForSend() must not return the mutable source object");
            }
            return freeze(frozen);
        }
        throw new IllegalArgumentException("value of type " + value.getClass().getName()
                + " is not Sendable; mutable host objects cannot cross actor boundaries");
    }

    /**
     * Isolation-copy transport for private actors. Explicit shared-memory cells
     * are rejected; deeply immutable shared values are copied into the private
     * actor's message graph rather than retaining the Shared wrapper.
     */
    private static Object isolateCopy(Object value) {
        if (isScalar(value)) return value;
        if (value instanceof SyncCell<?>) {
            throw new IllegalArgumentException("private actors cannot receive shared SyncCell values");
        }
        if (value instanceof Shared<?> shared) return isolateCopy(shared.value());
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(isolateCopy(item));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            LinkedHashSet<Object> copy = new LinkedHashSet<>();
            for (Object item : set) copy.add(isolateCopy(item));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(isolateCopy(entry.getKey()), isolateCopy(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) copy.add(isolateCopy(Array.get(value, i)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Sendable sendable) {
            Object frozen = sendable.freezeForSend();
            if (frozen == value) {
                throw new IllegalArgumentException("Sendable.freezeForSend() must not return the mutable source object");
            }
            return isolateCopy(frozen);
        }
        if (value == null) return null;
        throw new IllegalArgumentException("value of type " + value.getClass().getName()
                + " is not Sendable; mutable host objects cannot cross actor boundaries");
    }

    /**
     * Conservative logical-size estimate for already frozen Oreslang values.
     * This is a language heap accounting metric, not a claim about HotSpot/Graal
     * object layout. Physical isolation requires a separate VM/polyglot isolate.
     */
    private static long estimateFrozenBytes(Object value) {
        return estimateFrozenBytes(value, new IdentityHashMap<>());
    }

    private static long estimateFrozenBytes(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return 0L;
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character || value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double) return 24L;
        if (value instanceof BigInteger integer) return 32L + integer.toByteArray().length;
        if (value instanceof BigDecimal decimal) return 48L + decimal.unscaledValue().toByteArray().length;
        if (value instanceof String string) return 40L + (long) string.length() * 2L;
        if (value instanceof UUID || value instanceof ActorId) return 40L;
        if (value instanceof Enum<?>) return 24L;
        if (value instanceof ActorRuntime.ActorRef<?>) return 48L;
        if (value instanceof Shared<?> shared) return estimateFrozenBytes(shared.value(), seen);
        if (value instanceof SyncCell<?>) return 64L;

        if (seen.put(value, Boolean.TRUE) != null) {
            // Frozen graphs should be trees/DAGs. Count a repeated immutable
            // reference once rather than recursively double-counting it.
            return 0L;
        }

        long bytes = 24L;
        if (value instanceof List<?> list) {
            bytes += 8L * list.size();
            for (Object item : list) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen));
            return bytes;
        }
        if (value instanceof Set<?> set) {
            bytes += 16L * set.size();
            for (Object item : set) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen));
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            bytes += 32L * map.size();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getKey(), seen));
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getValue(), seen));
            }
            return bytes;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            bytes += 8L * length;
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

    private static long estimateFootprint(Object value) {
        return estimateFootprint(value, new java.util.IdentityHashMap<>());
    }

    private static long estimateFootprint(Object value, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (value == null) return 8;
        if (value instanceof Byte || value instanceof Boolean) return 16;
        if (value instanceof Short || value instanceof Character) return 16;
        if (value instanceof Integer || value instanceof Float) return 16;
        if (value instanceof Long || value instanceof Double) return 24;
        if (value instanceof BigInteger big) return 32L + Math.max(8, big.bitLength() / 8L);
        if (value instanceof BigDecimal decimal) return 40L + estimateFootprint(decimal.unscaledValue(), seen);
        if (value instanceof String text) return 24L + (long) text.length() * 2L;
        if (value instanceof UUID || value instanceof ActorId || value instanceof ActorRuntime.ActorRef<?>) return 32;
        if (value instanceof Enum<?>) return 16;
        if (value instanceof SyncCell<?>) return 32;

        if (seen.put(value, Boolean.TRUE) != null) return 8;
        if (value instanceof Shared<?> shared) return 24L + estimateFootprint(shared.value(), seen);
        if (value instanceof List<?> list) {
            long bytes = 24L + 8L * list.size();
            for (Object item : list) bytes += estimateFootprint(item, seen);
            return bytes;
        }
        if (value instanceof Set<?> set) {
            long bytes = 32L + 16L * set.size();
            for (Object item : set) bytes += estimateFootprint(item, seen);
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            long bytes = 48L + 32L * map.size();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes += estimateFootprint(entry.getKey(), seen);
                bytes += estimateFootprint(entry.getValue(), seen);
            }
            return bytes;
        }
        return 64;
    }

    private record Envelope(Object message, MemoryReservation reservation) { }

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
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final BlockingQueue<MessageEnvelope> mailbox;
        private final ActorMemorySlice memorySlice;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private Behavior<M> behavior;

        private ActorCell(
                ActorRef<M> ref,
                ActorKind kind,
                IsolatePolicy policy,
                Supplier<? extends Behavior<M>> behaviorFactory) {
            this.ref = ref;
            this.kind = kind;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
            this.memory = kind == ActorKind.PRIVATE
                    ? new PrivateMemorySlice(ref.id(), policy.maxHeapBytes())
                    : new SharedMemorySlice(policy.maxHeapBytes());
            this.memorySlice = kind == ActorKind.PRIVATE ? new ActorMemorySlice(ref.id(), policy.maxHeapBytes()) : null;
        }

        private void schedule() {
            if (stopped.get() || closed.get()) return;
            if (!scheduled.compareAndSet(false, true)) return;
            try {
                dispatcherFor(kind).execute(this::runBatch);
            } catch (RejectedExecutionException rejected) {
                scheduled.set(false);
                if (!closed.get()) throw rejected;
            }
        }

        @SuppressWarnings("unchecked")
        private void runBatch() {
            currentActor.set(this);
            try {
                if (stopped.get()) return;
                if (behavior == null) {
                    behavior = Objects.requireNonNull(behaviorFactory.get(), "actor behaviorFactory returned null");
                }

                ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                    @Override public ActorKind kind() { return kind; }
                    @Override public MemorySlice memory() { return memory; }
                    @Override public Optional<ActorMemorySlice> privateMemory() { return Optional.ofNullable(memorySlice); }
                };

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
                // v0 fail-stop supervision policy. Never allow a failing actor
                // to poison a shared dispatcher worker or execute concurrently.
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
                    // Akka-style throughput/fairness handoff: after a bounded
                    // batch, resubmit this actor so another mailbox can run.
                    schedule();
                }
            }
        }

        private void drainMailboxReservations() {
            MessageEnvelope envelope;
            while ((envelope = mailbox.poll()) != null) envelope.close();
        }

        private void drainMailboxReservations() {
            Envelope envelope;
            while ((envelope = mailbox.poll()) != null) envelope.reservation().close();
        }

        private void stop() {
            if (!stopped.compareAndSet(false, true)) return;
            drainMailboxReservations();
            if (memorySlice != null) memorySlice.close();
            actors.remove(ref.id(), this);
        }
    }
}
