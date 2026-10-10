package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.SandboxPolicy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A supervisor-side, executable path to ONE physical Graal polyglot isolate
 * and GC heap PER untrusted Oreslang actor.
 *
 * Unlike SharedUntrustedIsolatePool, a fresh Engine is constructed for every
 * actor. Graal's isolated-engine collector is physically independent from the
 * JVM host heap and every other engine's collector. This requires an actual
 * Oreslang language-isolate native image: if it is missing, spawn fails
 * without ever running guest source on the host.
 *
 * Source actor declarations are not automatically redirected here. The
 * supervisor must explicitly call spawn(...). Host ActorRuntime.spawnUntrusted
 * remains a logical actor and MUST NOT be described as an isolated engine.
 */
public final class DedicatedUntrustedActorIsolates implements AutoCloseable {
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_ISOLATE_BYTES = 128L * MIB;
    private static final long MAX_ISOLATE_BYTES = 1024L * MIB;
    private static final int MAX_ACTORS = 256;

    private final long perActorIsolateBytes;
    private final int maxActors;
    private final Set<Actor> active = new HashSet<>();
    private boolean closed;

    public DedicatedUntrustedActorIsolates(long perActorIsolateBytes, int maxActors) {
        validateConfiguration(perActorIsolateBytes, maxActors);
        this.perActorIsolateBytes = perActorIsolateBytes;
        this.maxActors = maxActors;
    }

    /** Fail before allocating VM resources when limits are unreasonable. */
    public static void validateConfiguration(long bytes, int maxActors) {
        if (bytes < MIN_ISOLATE_BYTES || bytes > MAX_ISOLATE_BYTES) {
            throw new IllegalArgumentException(
                    "dedicated actor isolate heap must be 128..1024 MiB");
        }
        if (maxActors < 1 || maxActors > MAX_ACTORS) {
            throw new IllegalArgumentException(
                    "dedicated actor isolate capacity must be 1..256");
        }
    }

    public long perActorIsolateBytes() { return perActorIsolateBytes; }

    public synchronized int activeActors() { return active.size(); }

    /**
     * Parse, initialize and spawn exactly one source UNTRUSTED actor in a
     * fresh physically isolated Engine. No raw polyglot Context or Value is
     * exposed in the returned actor proxy.
     */
    public synchronized Actor spawn(
            String source, String sourceId, String actorType) {
        if (closed) throw new IllegalStateException("dedicated actor pool is closed");
        if (active.size() >= maxActors) {
            throw new IllegalStateException("dedicated actor isolate capacity exceeded");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(actorType, "actorType");

        Scope scope = null;
        UntrustedJitCodeUnit code = null;
        try {
            // Must create Engine anew on EVERY call. Do not cache, reuse or
            // inherit the parent Oreslang Context's Engine.
            scope = new Scope(perActorIsolateBytes);
            code = new UntrustedJitCodeUnit(scope, source, sourceId);
            UntrustedJitCodeUnit.RemoteUntrustedActor remote =
                    code.spawnUntrustedActor(code.generation(), actorType);
            Actor actor = new Actor(scope, code, remote);
            active.add(actor);
            return actor;
        } catch (RuntimeException | Error failure) {
            if (code != null) {
                try { code.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            if (scope != null) {
                try { scope.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    /**
     * Engine and Context have the same lifetime as the single remote actor.
     * The engine's distinct Native Image isolate owns its own GC.
     */
    private static final class Scope implements UntrustedGuestIsolateScope, AutoCloseable {
        private final Engine engine;
        private final Context context;
        private boolean admitted;
        private boolean closed;

        private Scope(long bytes) {
            Engine created = Engine.newBuilder(OresLanguage.ID)
                    .sandbox(SandboxPolicy.UNTRUSTED)
                    .spawnIsolate(true)
                    .option("engine.MaxIsolateMemory", bytes + "B")
                    .build();
            try {
                // The context has a cumulative CPU budget (12 hours) separate
                // from the guest actor's 250 ms per-turn untrusted quota.
                // It does not create or share another engine.
                context = IsolatePolicy.untrustedActorGroup()
                        .sharedUntrustedIsolateContextBuilder(
                                ExecutionProfile.serverHybrid(), created)
                        .build();
            } catch (RuntimeException | Error failure) {
                try { created.close(true); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            engine = created;
            if (context.getEngine() != engine) {
                close();
                throw new IllegalStateException("guest context did not retain dedicated engine");
            }
        }

        @Override public synchronized Context contextForSupervisor() {
            if (closed) throw new IllegalStateException("actor isolate is closed");
            return context;
        }

        @Override public synchronized void admitActorSpawn() {
            if (closed) throw new IllegalStateException("actor isolate is closed");
            if (admitted) {
                throw new SecurityException(
                        "one physical actor isolate may contain only one remote actor");
            }
            admitted = true;
        }

        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            try { context.close(true); }
            catch (RuntimeException error) { failure = error; }
            try { engine.close(true); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    /**
     * Opaque host-side proxy. No guest object pointer, Context or Engine
     * escapes to source code. Data transport is scalar-copy only.
     */
    public final class Actor implements AutoCloseable {
        private final UUID hostIdentity = UUID.randomUUID();
        private final Scope scope;
        private final UntrustedJitCodeUnit code;
        private final UntrustedJitCodeUnit.RemoteUntrustedActor remote;
        private final AtomicBoolean actorClosed = new AtomicBoolean();

        private Actor(Scope scope, UntrustedJitCodeUnit code,
                      UntrustedJitCodeUnit.RemoteUntrustedActor remote) {
            this.scope = scope;
            this.code = code;
            this.remote = remote;
        }

        public UUID identity() { return hostIdentity; }

        /**
         * The sole actor in this lease has a distinct Graal isolate Engine
         * (and collector). This flag is only available after engine/context
         * construction succeeds; there is no fallback host-JVM actor.
         */
        public boolean independentlyCollectable() { return true; }

        /** Package-private verification, never exposed as a guest handle. */
        boolean sharesPhysicalCollectorWith(Actor other) {
            Objects.requireNonNull(other, "other");
            return scope.engine == other.scope.engine;
        }
        public boolean closed() { return actorClosed.get(); }

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

        public boolean cancel() {
            requireActive();
            return remote.cancel();
        }

        private void requireActive() {
            if (actorClosed.get()) {
                throw new IllegalStateException("dedicated actor isolate is closed");
            }
        }

        @Override public void close() {
            synchronized (DedicatedUntrustedActorIsolates.this) {
                if (!actorClosed.compareAndSet(false, true)) return;
                RuntimeException failure = null;
                try { code.close(); }
                catch (RuntimeException error) { failure = error; }
                try { scope.close(); }
                catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                } finally {
                    active.remove(this);
                }
                if (failure != null) throw failure;
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Actor actor : new ArrayList<>(active)) {
            try { actor.close(); }
            catch (RuntimeException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }
}
