package dev.oreslang.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supervisor-backed source UNTRUSTED actors in exactly ONE shared Graal
 * polyglot Engine/isolate, not one isolate per actor.
 *
 * Each actor currently owns a separate bounded guest Context for independent
 * context retirement and cumulative CPU quotas. All those Contexts share the
 * same Engine, physical GC/JIT and aggregate isolate heap limit. This is not
 * hard per-actor native GC isolation or tenant-level crash independence.
 *
 * This explicit host API does not yet automatically lower source-level spawn.
 * A missing native Oreslang language-isolate image fails closed.
 */
public final class SharedUntrustedActorSupervisor implements AutoCloseable {
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_HEAP_BYTES = 128L * MIB;
    private static final long MAX_HEAP_BYTES = 1024L * MIB;
    private static final int MAX_ACTORS = 256;
    private static final int MAX_SOURCE_CHARS = 256 * 1024;
    // Parse/link/static initialization are not actor turns and would
    // otherwise inherit the Context's cumulative 12-hour CPU quota.
    private static final Duration MAX_ADMISSION_TIME = Duration.ofSeconds(30);

    private final Object lifecycle = new Object();
    private final long isolateHeapBytes;
    private final int maxActors;
    private final boolean ownsPool;
    private SharedUntrustedIsolatePool pool;
    private final Set<Actor> active = new HashSet<>();
    private int pendingSpawns;
    // Failed Context close cannot release its admission quota: a malicious
    // actor must not exceed maxActors by provoking guest teardown failures.
    private int failedRetirements;
    private boolean closed;

    /**
     * Lazy ownership: validates arguments without constructing a native
     * Engine. The first accepted spawn constructs the ONE UNTRUSTED Engine.
     */
    public SharedUntrustedActorSupervisor(long isolateHeapBytes, int maxActors) {
        validateConfiguration(isolateHeapBytes, maxActors);
        this.isolateHeapBytes = isolateHeapBytes;
        this.maxActors = maxActors;
        this.ownsPool = true;
    }

    /**
     * Share an already created application Engine, e.g. with other supervisor
     * components. Closing this supervisor closes only its Context leases.
     */
    public SharedUntrustedActorSupervisor(
            SharedUntrustedIsolatePool applicationPool, int maxActors) {
        Objects.requireNonNull(applicationPool, "applicationPool");
        if (maxActors < 1 || maxActors > MAX_ACTORS) {
            throw new IllegalArgumentException("untrusted actor capacity must be 1..256");
        }
        this.pool = applicationPool;
        this.isolateHeapBytes = applicationPool.isolateHeapBytes();
        this.maxActors = maxActors;
        this.ownsPool = false;
    }

    public static void validateConfiguration(long bytes, int maxActors) {
        if (bytes < MIN_HEAP_BYTES || bytes > MAX_HEAP_BYTES) {
            throw new IllegalArgumentException("shared isolate heap must be 128..1024 MiB");
        }
        if (maxActors < 1 || maxActors > MAX_ACTORS) {
            throw new IllegalArgumentException("untrusted actor capacity must be 1..256");
        }
    }

    public long isolateHeapBytes() { return isolateHeapBytes; }

    public int activeActors() {
        synchronized (lifecycle) { return active.size(); }
    }

    public int reservedActorSlots() {
        synchronized (lifecycle) {
            return active.size() + pendingSpawns + failedRetirements;
        }
    }

    /**
     * Host-only admission. Only bounded source text/identifiers are inspected
     * before native Engine admission; guest syntax is parsed INSIDE the
     * isolated Context. Capacity reserves a slot before guest code runs.
     */
    public Actor spawn(String source, String sourceId, String actorType) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(actorType, "actorType");
        if (source.isEmpty() || source.length() > MAX_SOURCE_CHARS) {
            throw new IllegalArgumentException("untrusted source must be 1..262144 chars");
        }
        if (sourceId.length() < 1 || sourceId.length() > 128
                || !sourceId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid bounded source identity");
        }
        if (actorType.length() < 1 || actorType.length() > 128
                || !actorType.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid untrusted actor type");
        }
        // Cheap input-only check: fail before native isolate construction,
        // never interpret or validate hostile source in the trusted JVM.
        UntrustedSourceImageCache.validateCandidate(sourceId, source);

        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("untrusted supervisor is closed");
            if (active.size() + pendingSpawns + failedRetirements >= maxActors) {
                throw new IllegalStateException("shared untrusted actor capacity exceeded");
            }
            pendingSpawns++;
        }

        SharedUntrustedIsolatePool.Group group = null;
        UntrustedJitCodeUnit code = null;
        GuestAdmissionDeadline admission = null;
        try {
            // Each actor gets a Context, NOT a new Engine. Every group shares
            // the singleton pool's physical isolate collector and JIT.
            group = applicationPool().createGroup();
            SharedUntrustedIsolatePool.Group leasedGroup = group;
            admission = new GuestAdmissionDeadline(
                    MAX_ADMISSION_TIME, leasedGroup::close);
            code = new UntrustedJitCodeUnit(group, source, sourceId);
            UntrustedJitCodeUnit.RemoteUntrustedActor remote =
                    code.spawnUntrustedActor(code.generation(), actorType);
            synchronized (lifecycle) {
                if (closed) throw new IllegalStateException(
                        "untrusted supervisor retired during actor admission");
                if (!admission.commitIfLive()) {
                    throw new IllegalStateException(
                            "untrusted source admission timed out and guest Context was revoked");
                }
                Actor actor = new Actor(group, code, remote);
                active.add(actor);
                return actor;
            }
        } catch (RuntimeException | Error failure) {
            if (group != null) {
                try { group.close(); }
                catch (RuntimeException | Error cleanup) {
                    // The pool retains the failed Group; supervisor capacity
                    // must also remain reserved even though spawn failed.
                    synchronized (lifecycle) { failedRetirements++; }
                    failure.addSuppressed(new IllegalStateException(
                            "untrusted spawn teardown incomplete; reservation retained"));
                }
            }
            if (code != null) code.fenceAfterContextClose();
            throw failure;
        } finally {
            if (admission != null) admission.close();
            synchronized (lifecycle) {
                pendingSpawns--;
                lifecycle.notifyAll();
            }
        }
    }

    private SharedUntrustedIsolatePool applicationPool() {
        synchronized (lifecycle) {
            if (closed) throw new IllegalStateException("untrusted supervisor is closed");
            if (pool == null) {
                pool = new SharedUntrustedIsolatePool(isolateHeapBytes, maxActors);
            }
            return pool;
        }
    }

    public final class Actor implements AutoCloseable {
        private final UUID identity = UUID.randomUUID();
        private final SharedUntrustedIsolatePool.Group group;
        private final UntrustedJitCodeUnit code;
        private final UntrustedJitCodeUnit.RemoteUntrustedActor remote;
        private final AtomicBoolean revoked = new AtomicBoolean();
        private final VerifiedCloseFence closeFence;

        private Actor(SharedUntrustedIsolatePool.Group group,
                      UntrustedJitCodeUnit code,
                      UntrustedJitCodeUnit.RemoteUntrustedActor remote) {
            this.group = group;
            this.code = code;
            this.remote = remote;
            this.closeFence = new VerifiedCloseFence(() -> {
                try {
                    // Physical revocation must not take guest execution monitor.
                    this.group.close();
                    synchronized (lifecycle) { active.remove(this); }
                } finally {
                    // Keep a failed close's slot reserved and every proxy fenced.
                    this.code.fenceAfterContextClose();
                }
            });
        }

        public UUID identity() { return identity; }

        /** There is no independent per-actor native GC inside this pool. */
        public boolean independentlyCollectable() { return false; }

        /** Contexts are in the sandboxed Engine, not the trusted host Engine. */
        public boolean sandboxedInUntrustedIsolate() { return true; }

        /** Host-only test proof of shared physical engine/collector identity. */
        boolean sharesPhysicalCollectorWith(Actor other) {
            Objects.requireNonNull(other, "other");
            return group.contextForSupervisor().getEngine()
                    == other.group.contextForSupervisor().getEngine();
        }

        /** Host-only verification for a caller-owned hot-reload Context. */
        boolean sharesPhysicalCollectorWith(org.graalvm.polyglot.Context other) {
            Objects.requireNonNull(other, "other");
            return group.contextForSupervisor().getEngine() == other.getEngine();
        }

        /** Different actor contexts allow targeted context retirement. */
        boolean sharesGuestContextWith(Actor other) {
            Objects.requireNonNull(other, "other");
            return group.contextForSupervisor() == other.group.contextForSupervisor();
        }

        public boolean closed() { return revoked.get(); }

        private void requireActive() {
            if (revoked.get()) throw new IllegalStateException("untrusted actor lease is closed");
        }

        public void send(Object data) {
            requireActive();
            remote.send(data);
        }

        public boolean ready() {
            requireActive();
            return remote.ready();
        }

        public boolean done() {
            requireActive();
            return remote.done();
        }

        /**
         * Cooperative guest cancellation. This requires entry to the
         * serialized guest Context and is NOT a preemption mechanism.
         * For hostile or noncooperating guest turns, use forceCancel().
         */
        public boolean cancel() {
            requireActive();
            return remote.cancel();
        }

        /**
         * Supervisor priority revocation: physically close only this actor's
         * guest Context without entering its guest-execution monitor.
         * Sibling Contexts and the shared Engine remain alive.
         *
         * A successful return means Context.close(true) was confirmed.
         * Failure leaves the actor revoked and its slot permanently reserved.
         */
        public boolean forceCancel() {
            if (!revoked.compareAndSet(false, true)) return false;
            closeFence.awaitConfirmedClose("force-cancelled untrusted actor");
            return true;
        }

        @Override public void close() {
            revoked.set(true);
            closeFence.awaitConfirmedClose("untrusted actor lease");
        }
    }

    private final VerifiedCloseFence supervisorCloseFence =
            new VerifiedCloseFence(this::retireSupervisor);

    @Override public void close() {
        synchronized (lifecycle) { closed = true; }
        supervisorCloseFence.awaitConfirmedClose("untrusted actor supervisor");
    }

    private void retireSupervisor() {
        ArrayList<Actor> snapshot;
        SharedUntrustedIsolatePool owned;
        synchronized (lifecycle) {
            // A pending spawn already reserved a slot and may be executing
            // hostile initialization. Its deadline/revoker must settle before
            // the supervisor may report fully closed, especially when this
            // supervisor does not own its application's shared Engine.
            while (pendingSpawns != 0) {
                try {
                    lifecycle.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "untrusted supervisor has unfinished actor admissions");
                }
            }
            snapshot = new ArrayList<>(active);
            owned = ownsPool ? pool : null;
        }
        Throwable failure = null;
        for (Actor actor : snapshot) {
            try { actor.close(); }
            catch (RuntimeException | Error error) {
                // One failing Context must not prevent other actor retirement.
                failure = VerifiedCloseFence.mergeRetirementFailure(failure, error);
            }
        }
        if (owned != null) {
            try { owned.close(); }
            catch (RuntimeException | Error error) {
                // Always attempt physical Engine teardown after every actor.
                failure = VerifiedCloseFence.mergeRetirementFailure(failure, error);
            }
        }
        VerifiedCloseFence.rethrowRetirementFailure(failure);
    }
}
