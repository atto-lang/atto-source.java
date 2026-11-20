package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Trusted-host keyed lookup for real ActorRuntime mailboxes. This is a local
 * physical-actor directory, not a distributed virtual actor or a persistence
 * service. Uniqueness is scoped to ONE registry instance; a broker must use
 * one directory for its namespace and enforce durable ownership separately.
 * In particular, resolving a key does not create a broker queue.
 *
 * <p>The encoder must produce a deterministic, collision-free representation
 * of a stable immutable K. It runs outside every directory monitor. Guest code
 * must use compiler-admitted capabilities rather than importing this host API.</p>
 */
public final class ActorRegistry<K, M> implements AutoCloseable {
    /** Distinguish an unknown key from an actor that is still initializing or retiring. */
    public enum LookupPhase { ABSENT, INITIALIZING, ACTIVE, RETIRING }

    /** Snapshot only; mailbox admission independently validates actor lifetime. */
    public record LookupSnapshot<M>(LookupPhase phase, ActorRuntime.ActorRef<M> actor) {
        public LookupSnapshot {
            Objects.requireNonNull(phase, "phase");
            if ((phase == LookupPhase.ACTIVE) != (actor != null))
                throw new IllegalArgumentException("only an active actor can be returned");
        }
    }
    private static final long CREATION_WAIT_SECONDS = 5L;

    private static final class Pending {
        final Thread owner = Thread.currentThread();
        final CountDownLatch completed = new CountDownLatch(1);
    }

    /** A key handle, not a promise that an actor or durable entity exists. */
    public final class LogicalRef {
        private final VirtualActorActivationRegistry.Identity identity;

        private LogicalRef(VirtualActorActivationRegistry.Identity identity) {
            this.identity = identity;
        }

        public VirtualActorActivationRegistry.Identity identity() { return identity; }

        public Optional<ActorRuntime.ActorRef<M>> resolve_existing() {
            return lookupIdentity(identity);
        }

        public LookupSnapshot<M> inspect() {
            return inspectIdentity(identity);
        }
    }

    private final ActorRuntime runtime;
    private final String domain;
    private final String declaration;
    private final Function<? super K, String> encodeKey;
    private final int capacity;
    private final Object lock = new Object();
    private final Map<VirtualActorActivationRegistry.Identity, ActorRuntime.ActorRef<M>> active =
            new HashMap<>();
    private final Map<VirtualActorActivationRegistry.Identity, Pending> creating =
            new HashMap<>();
    /** Identity comparison is intentional: no aliasing two keys to one physical actor. */
    private final Map<ActorRuntime.ActorRef<M>, VirtualActorActivationRegistry.Identity> byRef =
            new IdentityHashMap<>();
    private boolean closed;

    public ActorRegistry(ActorRuntime runtime, String domain, String declaration,
                         int capacity, Function<? super K, String> encodeKey) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.encodeKey = Objects.requireNonNull(encodeKey, "encodeKey");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        VirtualActorRuntimeGenerationFence.validate(
                new VirtualActorActivationRegistry.Identity(domain, declaration, "probe"));
        this.capacity = capacity;
    }

    private static void requireHost() {
        if (ActorRuntime.currentExecutionContext() != null ||
                ActorRuntime.inActorGroupMailmanExecution())
            throw new SecurityException("host ActorRegistry is not a guest actor capability");
    }

    private VirtualActorActivationRegistry.Identity identity(K key) {
        requireHost();
        String encoded = Objects.requireNonNull(encodeKey.apply(
                Objects.requireNonNull(key, "key")), "encoded key");
        var result = new VirtualActorActivationRegistry.Identity(domain, declaration, encoded);
        VirtualActorRuntimeGenerationFence.validate(result);
        return result;
    }

    /** Creating a logical reference neither spawns an actor nor declares a queue. */
    public LogicalRef ref_for(K key) {
        var id = identity(key);
        synchronized (lock) {
            ensureOpen();
            return new LogicalRef(id);
        }
    }

    public Optional<ActorRuntime.ActorRef<M>> lookup(K key) {
        return lookupIdentity(identity(key));
    }

    public LookupSnapshot<M> lookup_state(K key) {
        return inspectIdentity(identity(key));
    }

    private Optional<ActorRuntime.ActorRef<M>> lookupIdentity(
            VirtualActorActivationRegistry.Identity id) {
        return Optional.ofNullable(inspectIdentity(id).actor());
    }

    private LookupSnapshot<M> inspectIdentity(
            VirtualActorActivationRegistry.Identity id) {
        requireHost();
        // Retry after cleaning an already-finalized activation: a successor may
        // have been published between reading the old slot and cleanup.
        for (;;) {
            ActorRuntime.ActorRef<M> ref;
            synchronized (lock) {
                ensureOpen();
                ref = active.get(id);
                if (ref == null) {
                    return new LookupSnapshot<>(creating.containsKey(id)
                            ? LookupPhase.INITIALIZING : LookupPhase.ABSENT, null);
                }
            }
            if (runtime.isAlive(ref)) return new LookupSnapshot<>(LookupPhase.ACTIVE, ref);
            // A stopped actor may still be executing. This state MUST NOT be
            // mistaken for missing durable queue metadata or safe reactivation.
            if (!ref.done().isDone())
                return new LookupSnapshot<>(LookupPhase.RETIRING, null);
            removeIfCurrent(id, ref);
        }
    }

    /**
     * One physical actor is registered per key. A creator runs outside the
     * global lock, and contenders wait only on the same key's completion.
     * This host-only method must not park an actor carrier.
     */
    public ActorRuntime.ActorRef<M> get_or_create(
            K key, Function<? super K, ? extends ActorRuntime.ActorRef<M>> factory) {
        requireHost();
        Objects.requireNonNull(factory, "factory");
        var id = identity(key);
        for (;;) {
            ActorRuntime.ActorRef<M> existing;
            Pending pending;
            boolean creator = false;
            synchronized (lock) {
                ensureOpen();
                existing = active.get(id);
                pending = creating.get(id);
                if (existing == null && pending == null) {
                    if (active.size() + creating.size() >= capacity)
                        throw new IllegalStateException("actor registry capacity exceeded");
                    pending = new Pending();
                    creating.put(id, pending);
                    creator = true;
                } else if (existing == null && pending.owner == Thread.currentThread()) {
                    throw new IllegalStateException("recursive same-key actor creation");
                }
            }
            if (existing != null) {
                if (runtime.isAlive(existing)) return existing;
                if (!existing.done().isDone())
                    throw new IllegalStateException("actor retirement still in progress");
                removeIfCurrent(id, existing);
                continue;
            }
            if (!creator) {
                try {
                    if (!pending.completed.await(CREATION_WAIT_SECONDS, TimeUnit.SECONDS))
                        throw new IllegalStateException("actor creation still in progress");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while resolving actor", interrupted);
                }
                continue;
            }
            try {
                ActorRuntime.ActorRef<M> created = Objects.requireNonNull(
                        factory.apply(key), "actor factory returned null");
                if (!runtime.isAlive(created))
                    throw new IllegalArgumentException("factory returned stopped or foreign actor");
                synchronized (lock) {
                    ensureOpen();
                    if (byRef.containsKey(created))
                        throw new IllegalArgumentException("one physical actor cannot own two keys");
                    byRef.put(created, id);
                    try {
                        active.put(id, created);
                    } catch (RuntimeException | Error failure) {
                        byRef.remove(created);
                        throw failure;
                    }
                }
                // Runtime-owned completion: never hold the directory monitor
                // while attaching a callback or executing a host factory.
                created.done().whenCompleteRuntime((ignored, failure) ->
                        removeIfCurrent(id, created));
                if (!runtime.isAlive(created) && created.done().isDone())
                    removeIfCurrent(id, created);
                return created;
            } finally {
                synchronized (lock) {
                    creating.remove(id, pending);
                    pending.completed.countDown();
                }
            }
        }
    }

    /** Removes this exact incarnation only; stale callbacks cannot erase a successor. */
    public boolean unregister(K key, ActorRuntime.ActorRef<M> expected) {
        var id = identity(key);
        Objects.requireNonNull(expected, "expected");
        if (runtime.isAlive(expected) || !expected.done().isDone())
            throw new IllegalStateException("cannot unregister before actor finalization");
        return removeIfCurrent(id, expected);
    }

    private boolean removeIfCurrent(VirtualActorActivationRegistry.Identity id,
                                    ActorRuntime.ActorRef<M> expected) {
        synchronized (lock) {
            if (active.get(id) != expected) return false;
            active.remove(id);
            byRef.remove(expected);
            return true;
        }
    }

    public int registered_count() {
        requireHost();
        synchronized (lock) {
            ensureOpen();
            return active.size();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("actor registry closed");
    }

    /** Does not stop actors; their owning supervisor controls their lifetime. */
    @Override public void close() {
        requireHost();
        synchronized (lock) {
            if (!creating.isEmpty())
                throw new IllegalStateException("cannot close registry during actor initialization");
            // Abandoning an active registration would allow another directory
            // to create a second owner while this actor still executes.
            for (var ref : active.values()) {
                if (!ref.done().isDone())
                    throw new IllegalStateException(
                            "cannot close registry before actor finalization");
            }
            closed = true;
            active.clear();
            byRef.clear();
        }
    }
}
