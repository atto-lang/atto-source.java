package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An executable source-code unit INSIDE the pooled untrusted polyglot
 * isolate. The host communicates exclusively via bounded, copied scalar
 * arguments and results. Polyglot Value references never escape this adapter.
 *
 * Contains a scalar-only control plane for guest-side source UNTRUSTED
 * actors, identified by opaque local IDs (never ActorRef or polyglot Value
 * across the boundary). This does NOT yet automatically redirect host-side
 * source-language "spawn" expressions. The host must explicitly use this
 * adapter; host JVM spawnCodeActor is not physical isolation.
 */
public final class UntrustedJitCodeUnit implements AutoCloseable {
    private static final int MAX_SOURCE_CHARS = 256 * 1024;
    private static final int MAX_ARGS = 16;
    private final SharedUntrustedIsolatePool.Group group;
    private final UUID generation = UUID.randomUUID();
    private final Value parsedRoot;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Parse, link and initialize a source unit strictly inside the group's
     * sandboxed Context. Never parse hostile source in the AOT host.
     */
    public UntrustedJitCodeUnit(SharedUntrustedIsolatePool.Group group,
                                String source, String sourceId) {
        this.group = Objects.requireNonNull(group, "group");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceId, "sourceId");
        if (source.isEmpty() || source.length() > MAX_SOURCE_CHARS) {
            throw new IllegalArgumentException("untrusted source must be 1..262144 chars");
        }
        if (sourceId.isBlank() || sourceId.length() > 128
                || !sourceId.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("untrusted source identity must be a bounded identifier");
        }

        // All accesses to the shared polyglot Context are serialized on the
        // group monitor. An untrusted guest cannot obtain Context or Value.
        synchronized (group) {
            Context context = group.contextForSupervisor();
            Source guestSource = Source.newBuilder(
                    OresLanguage.ID, source, "untrusted-" + generation + "-" + sourceId)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();
            try {
                Value candidate = context.parse(guestSource);
                candidate.execute(OresEvalRootNode.LINK_ONLY_COMMAND);
                candidate.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
                this.parsedRoot = candidate;
            } catch (PolyglotException guestFailure) {
                // Do not expose guest exception objects or foreign references
                // to the trusted supervisor through a causal chain.
                throw new IllegalStateException(
                        "untrusted source parsing, linking or initialization failed");
            }
        }
    }

    public UUID generation() { return generation; }

    /**
     * Generation fencing prevents delayed invocations from using a newer
     * code image following hot reload or actor-group replacement.
     */
    public Object invoke(UUID expectedGeneration, String publicFunction, Object... arguments) {
        Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        if (!generation.equals(expectedGeneration) || closed.get()) {
            throw new IllegalStateException("stale or retired untrusted code-unit generation");
        }
        if (publicFunction == null || !publicFunction.matches("[a-zA-Z_][a-zA-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid exported untrusted function identifier");
        }
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length > MAX_ARGS) {
            throw new IllegalArgumentException("untrusted function argument quota exceeded");
        }

        Object[] guestArguments = new Object[arguments.length + 2];
        guestArguments[0] = OresEvalRootNode.INVOKE_PUBLIC_COMMAND;
        guestArguments[1] = publicFunction;
        for (int i = 0; i < arguments.length; i++) {
            guestArguments[i + 2] = copyScalar(arguments[i]);
        }
        synchronized (group) {
            if (closed.get() || !generation.equals(expectedGeneration)) {
                throw new IllegalStateException("untrusted code unit retired during admission");
            }
            // Do not return a Value, host guest object or reference to
            // foreign-managed memory, including exceptional guest objects.
            group.contextForSupervisor();
            return copyGuestScalar(executeGuest(guestArguments));
        }
    }

    /**
     * Host-side invalidation after the supervisor has closed this actor's
     * Context. Does not acquire the guest Context monitor or execute code.
     * A malicious guest cannot prevent its host-issued revocation.
     */
    void fenceAfterContextClose() {
        closed.set(true);
    }

    /** No object graphs, handles, byte buffers or Java callbacks may enter. */
    public static Object copyScalar(Object value) {
        return UntrustedJitScalarTransport.copyScalar(value);
    }

    private Value executeGuest(Object... command) {
        try {
            return parsedRoot.execute(command);
        } catch (PolyglotException guestFailure) {
            // PolyglotException exposes guest objects through getGuestObject().
            // The public adapter boundary must not leak it to an AOT caller.
            throw new IllegalStateException(
                    "untrusted guest operation failed or isolate was revoked");
        }
    }

    private void requireActive(UUID expectedGeneration) {
        if (closed.get() || !generation.equals(expectedGeneration)) {
            throw new IllegalStateException("stale or retired untrusted actor code generation");
        }
    }

    /** Launch source UNTRUSTED actor inside this code unit's guest isolate. */
    public RemoteUntrustedActor spawnUntrustedActor(UUID expectedGeneration, String actorType) {
        Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        if (actorType == null || !actorType.matches("[a-zA-Z_][a-zA-Z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid untrusted actor type name");
        }
        synchronized (group) {
            requireActive(expectedGeneration);
            group.contextForSupervisor();
            Value answer = executeGuest(
                    OresEvalRootNode.ISOLATED_ACTOR_SPAWN_COMMAND, actorType);
            if (!answer.fitsInLong() || answer.asLong() <= 0) {
                throw new SecurityException("guest did not supply a valid opaque actor ID");
            }
            return new RemoteUntrustedActor(expectedGeneration, answer.asLong());
        }
    }

    /**
     * Host-owned proxy. It holds only a generation and numeric actor ID;
     * actual ActorRef, actor state and mailbox remain INSIDE the guest isolate.
     */
    public final class RemoteUntrustedActor implements AutoCloseable {
        private final UUID unitGeneration;
        private final long remoteId;
        private final AtomicBoolean revoked = new AtomicBoolean();

        private RemoteUntrustedActor(UUID unitGeneration, long remoteId) {
            this.unitGeneration = unitGeneration;
            this.remoteId = remoteId;
        }

        public long id() { return remoteId; }

        public void send(Object payload) {
            Object copy = copyScalar(payload);
            synchronized (group) {
                requireActive(unitGeneration);
                if (revoked.get()) throw new IllegalStateException("remote actor canceled");
                group.contextForSupervisor();
                executeGuest(OresEvalRootNode.ISOLATED_ACTOR_SEND_COMMAND, remoteId, copy);
            }
        }

        public boolean ready() {
            synchronized (group) {
                requireActive(unitGeneration);
                group.contextForSupervisor();
                return executeGuest(OresEvalRootNode.ISOLATED_ACTOR_READY_COMMAND,
                        remoteId).asBoolean();
            }
        }

        public boolean done() {
            synchronized (group) {
                requireActive(unitGeneration);
                group.contextForSupervisor();
                return executeGuest(OresEvalRootNode.ISOLATED_ACTOR_DONE_COMMAND,
                        remoteId).asBoolean();
            }
        }

        public boolean cancel() {
            synchronized (group) {
                requireActive(unitGeneration);
                group.contextForSupervisor();
                if (!revoked.compareAndSet(false, true)) return false;
                return executeGuest(OresEvalRootNode.ISOLATED_ACTOR_CANCEL_COMMAND,
                        remoteId).asBoolean();
            }
        }

        @Override public void close() {
            synchronized (group) {
                if (closed.get() || revoked.get()) return;
                cancel();
            }
        }
    }

    private static Object copyGuestScalar(Value value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.asBoolean();
        if (value.isString()) return copyScalar(value.asString());
        if (value.fitsInInt()) return value.asInt();
        if (value.fitsInLong()) return value.asLong();
        if (value.isNumber() && value.fitsInDouble()) return copyScalar(value.asDouble());
        throw new SecurityException("guest returned non-scalar data across isolate boundary");
    }

    @Override
    public void close() {
        synchronized (group) {
            if (closed.get()) return;
            try {
                group.contextForSupervisor();
                executeGuest(OresEvalRootNode.ISOLATED_ACTOR_CANCEL_ALL_COMMAND);
            } finally {
                // Fence every existing proxy even if group has been closed.
                closed.set(true);
            }
        }
    }
}
