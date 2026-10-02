package dev.oreslang.runtime;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * Oreslang actor state is owned by one actor. Messages pass through freeze(),
 * which only accepts values that can be made deeply immutable without leaving
 * a writable alias in the receiver. Cross-isolate transports should serialize
 * Frozen values rather than sharing Java object references.
 */
public final class ActorRuntime implements AutoCloseable {
    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final int MAX_FREEZE_DEPTH = 256;
    private static final int MAX_FREEZE_NODES = 100_000;

    private final AtomicLong actorGcRequests = new AtomicLong();
    private final AtomicLong processGcRequests = new AtomicLong();
    private final AtomicBoolean processGcInProgress = new AtomicBoolean();
    private final ThreadLocal<ActorCell<?>> currentActor = new ThreadLocal<>();
    private final IsolatePolicy policyCeiling;
    private final GarbageCollector garbageCollector;

    public ActorRuntime() {
        this(IsolatePolicy.developer());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this(policyCeiling, GarbageCollector.hostDefault());
    }

    public ActorRuntime(IsolatePolicy policyCeiling, GarbageCollector garbageCollector) {
        this.policyCeiling = java.util.Objects.requireNonNull(policyCeiling);
        this.garbageCollector = java.util.Objects.requireNonNull(garbageCollector);
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }

    /**
     * Effective policy for the current execution. Actor code inherits the
     * actor's stricter policy; non-actor execution uses the runtime ceiling.
     */
    public IsolatePolicy effectivePolicy() {
        ActorCell<?> cell = currentActor.get();
        return cell == null ? policyCeiling : cell.policy;
    }

    public record ActorId(UUID value) {
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    public static final class Shared<T> {
        private final T value;

        private Shared(T value) {
            this.value = value;
        }

        public T value() {
            return value;
        }

        @Override
        public String toString() {
            return "Shared[" + value + "]";
        }
    }

    /**
     * Backend hook for explicit GC requests.
     *
     * The language contract distinguishes actor-local collection from
     * process-wide collection. A backend must never implement collectActor by
     * silently escalating it to process-wide GC.
     */
    public interface GarbageCollector {
        /**
         * Allocate/register backend memory for a new actor. Native backends can
         * create an arena/private heap here and index it by actor id.
         */
        default void actorStarted(ActorId actorId, IsolatePolicy policy) { }

        void collectActor(ActorId actorId);

        /**
         * Release all backend-owned memory for the actor. Implementations must
         * be idempotent because startup failure cleanup can call this hook.
         */
        default void actorExited(ActorId actorId) { }

        void collectProcess();

        static GarbageCollector hostDefault() {
            return HostGarbageCollector.INSTANCE;
        }
    }

    private enum HostGarbageCollector implements GarbageCollector {
        INSTANCE;

        @Override
        public void collectActor(ActorId actorId) {
            /*
             * HotSpot/Graal JVM GC has no API for collecting one actor's Java
             * object graph. Preserve the actor-local semantic boundary: this is
             * a cooperative collection point only. Native/isolate backends plug
             * in a collector that can reclaim the actor heap physically.
             */
            Thread.yield();
        }

        @Override
        public void collectProcess() {
            // Explicit process.gc() is a request/hint; the host VM may ignore it.
            System.gc();
        }
    }

    public record GcStats(long actorRequests, long processRequests) { }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();

        /** Request collection of this actor's private heap only. */
        void gc();
    }

    public final class ActorRef<M> {
        private final ActorId id;

        private ActorRef(ActorId id) {
            this.id = id;
        }

        public ActorId id() { return id; }

        private ActorRuntime owner() { return ActorRuntime.this; }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        @Override
        public String toString() {
            return "ActorRef[" + id.value() + "]";
        }
    }

    /**
     * Creates actor-local behavior inside the actor thread. The Supplier should
     * be generated by Oreslang lowering, not supplied from untrusted guest code.
     */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawn(policyCeiling, behaviorFactory);
    }

    public <M> ActorRef<M> spawn(IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        requireWithinCeiling(policy);
        ActorId id = ActorId.create();
        ActorRef<M> ref = new ActorRef<>(id);
        ActorCell<M> cell = new ActorCell<>(ref, policy, behaviorFactory);
        boolean backendStarted = false;
        try {
            garbageCollector.actorStarted(id, policy);
            backendStarted = true;
            actors.put(id, cell);
            cell.start();
            return ref;
        } catch (Throwable failure) {
            actors.remove(id, cell);
            if (backendStarted) safeActorExit(id);
            throw failure;
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
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null) throw new IllegalStateException("unknown actor " + ref.id());
        Object frozen = freezeForRuntime(message);
        if (!cell.mailbox.offer(frozen)) {
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }
    }

    /**
     * Request collection of the current actor's private heap.
     *
     * This method is valid only while running actor behavior. It deliberately
     * does not fall back to process-wide System.gc(), because doing so would
     * violate the actor isolation/latency contract.
     */
    public void gcCurrentActor() {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        ActorCell<?> cell = currentActor.get();
        if (cell == null || actors.get(cell.ref.id()) != cell) {
            throw new IllegalStateException("actor.gc() requires a live current actor");
        }
        actorGcRequests.incrementAndGet();
        cell.gcRequests.incrementAndGet();
        garbageCollector.collectActor(cell.ref.id());
    }

    /**
     * Request process-wide collection. Concurrent requests are coalesced so a
     * group of actors cannot fan out into simultaneous full-heap collections.
     */
    public void gcProcess() {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        processGcRequests.incrementAndGet();
        if (!processGcInProgress.compareAndSet(false, true)) return;
        try {
            if (!closed.get()) garbageCollector.collectProcess();
        } finally {
            processGcInProgress.set(false);
        }
    }

    public GcStats gcStats() {
        return new GcStats(actorGcRequests.get(), processGcRequests.get());
    }

    private void safeActorExit(ActorId actorId) {
        try {
            garbageCollector.actorExited(actorId);
        } catch (Throwable ignored) {
            // Actor/runtime cleanup must not be prevented by a backend release
            // failure. Production backends should surface this through their
            // own telemetry and make actorExited idempotent.
        }
    }

    /**
     * Creates an explicitly read-only shared value. The returned graph is a
     * frozen representation; no mutable source object itself is exposed.
     */
    /**
     * Cooperative scheduler hook used by compiler-injected loop safepoints.
     * It observes runtime shutdown/interruption and yields the carrier so
     * supervisor/control-plane work can run. This is intentionally a runtime
     * hook rather than guest-accessible thread control.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("actor execution interrupted");
        Thread.yield();
    }

    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        return new Shared<>((T) freezeForRuntime(value));
    }

    private Object freezeForRuntime(Object value) {
        return freeze(value, new FreezeState(this), 0);
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     *
     * Freezing is bounded and cycle-aware because this is a trust boundary:
     * malformed host graphs must fail admission rather than overflow the stack
     * or allocate without bound before the mailbox capacity check runs.
     */
    public static Object freeze(Object value) {
        return freeze(value, new FreezeState(null), 0);
    }

    private static Object freeze(Object value, FreezeState state, int depth) {
        if (depth > MAX_FREEZE_DEPTH) {
            throw new IllegalArgumentException("sendable graph exceeds maximum freeze depth " + MAX_FREEZE_DEPTH);
        }
        if (++state.nodes > MAX_FREEZE_NODES) {
            throw new IllegalArgumentException("sendable graph exceeds maximum freeze node count " + MAX_FREEZE_NODES);
        }

        if (value == null) {
            throw new IllegalArgumentException("null is not Sendable; use Option<T> instead");
        }

        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId) {
            return value;
        }
        if (value instanceof ActorRef<?> ref) {
            if (state.runtime == null) {
                throw new IllegalArgumentException("ActorRef values require an ActorRuntime send boundary");
            }
            if (ref.owner() != state.runtime) {
                throw new IllegalArgumentException("ActorRef belongs to a different actor runtime/isolate");
            }
            return ref;
        }

        if (value instanceof Shared<?> shared) {
            return new Shared<>(freeze(shared.value(), state, depth + 1));
        }

        if (value instanceof List<?> list) {
            enterContainer(value, state);
            try {
                List<Object> frozen = new ArrayList<>(list.size());
                for (Object item : list) frozen.add(freeze(item, state, depth + 1));
                return List.copyOf(frozen);
            } finally {
                state.visiting.remove(value);
            }
        }
        if (value instanceof Set<?> set) {
            enterContainer(value, state);
            try {
                java.util.HashSet<Object> frozen = new java.util.HashSet<>(Math.max(16, set.size() * 2));
                for (Object item : set) frozen.add(freeze(item, state, depth + 1));
                return Set.copyOf(frozen);
            } finally {
                state.visiting.remove(value);
            }
        }
        if (value instanceof Map<?, ?> map) {
            enterContainer(value, state);
            try {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object frozenKey = freeze(entry.getKey(), state, depth + 1);
                    Object frozenValue = freeze(entry.getValue(), state, depth + 1);
                    if (frozen.putIfAbsent(frozenKey, frozenValue) != null) {
                        throw new IllegalArgumentException("distinct map keys collapse to the same frozen key");
                    }
                }
                return Map.copyOf(frozen);
            } finally {
                state.visiting.remove(value);
            }
        }
        if (value.getClass().isArray()) {
            enterContainer(value, state);
            try {
                int length = Array.getLength(value);
                List<Object> frozen = new ArrayList<>(length);
                for (int i = 0; i < length; i++) frozen.add(freeze(Array.get(value, i), state, depth + 1));
                return List.copyOf(frozen);
            } finally {
                state.visiting.remove(value);
            }
        }
        if (value instanceof Sendable sendable) {
            Object representation = sendable.freezeForSend();
            if (representation == value) {
                throw new IllegalArgumentException("Sendable.freezeForSend() must return a detached representation");
            }
            return freeze(representation, state, depth + 1);
        }
        throw new IllegalArgumentException("value of type " + value.getClass().getName()
                + " is not Sendable; mutable host objects cannot cross actor boundaries");
    }

    private static void enterContainer(Object value, FreezeState state) {
        if (state.visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic mutable graphs are not Sendable");
        }
    }

    private static final class FreezeState {
        private final ActorRuntime runtime;
        private final IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
        private int nodes;

        private FreezeState(ActorRuntime runtime) {
            this.runtime = runtime;
        }
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
    }

    private final class ActorCell<M> {
        private static final Object STOP = new Object();
        private final ActorRef<M> ref;
        private final IsolatePolicy policy;
        private final Supplier<? extends Behavior<M>> behaviorFactory;
        private final BlockingQueue<Object> mailbox;
        private final AtomicLong gcRequests = new AtomicLong();
        private volatile Thread thread;

        private ActorCell(ActorRef<M> ref, IsolatePolicy policy, Supplier<? extends Behavior<M>> behaviorFactory) {
            this.ref = ref;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
        }

        private void start() {
            thread = Thread.ofVirtual().name("ores-actor-" + ref.id().value()).start(this::run);
        }

        @SuppressWarnings("unchecked")
        private void run() {
            if (currentActor.get() != null) {
                throw new IllegalStateException("nested actor execution on one carrier is not allowed");
            }
            currentActor.set(this);
            try {
                final Behavior<M> behavior = behaviorFactory.get();
                final ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                    @Override public void gc() { ActorRuntime.this.gcCurrentActor(); }
                };
                while (true) {
                    Object message = mailbox.take();
                    if (message == STOP) return;
                    behavior.onMessage((M) message, context);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                // v0 fail-stop supervision policy. A later supervisor layer will
                // expose restart/escalation strategies as typed Oreslang APIs.
            } finally {
                mailbox.clear();
                currentActor.remove();
                actors.remove(ref.id(), this);
                safeActorExit(ref.id());
            }
        }

        private void stop() {
            mailbox.offer(STOP);
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }
}
