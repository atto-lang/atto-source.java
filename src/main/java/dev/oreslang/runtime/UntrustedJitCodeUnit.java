package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.nodes.OresEvalRootNode;
import org.graalvm.polyglot.Context;
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
 * This is a narrow, real guest-execution substrate. It is NOT a remote actor
 * mailbox, ActorRef implementation, continuation transport, or source
 * spawnCodeActor router. Host callers must not execute malicious Oreslang
 * through ActorRuntime.spawnCodeActor as a replacement for this boundary.
 */
public final class UntrustedJitCodeUnit implements AutoCloseable {
    private static final int MAX_SOURCE_CHARS = 256 * 1024;
    private static final int MAX_VALUE_CHARS = 16 * 1024;
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
            Value candidate = context.parse(guestSource);
            candidate.execute(OresEvalRootNode.LINK_ONLY_COMMAND);
            candidate.execute(OresEvalRootNode.INIT_ONLY_COMMAND);
            this.parsedRoot = candidate;
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
            return copyGuestScalar(parsedRoot.execute(guestArguments));
        }
    }

    /** No object graphs, handles, byte buffers or Java callbacks may enter. */
    public static Object copyScalar(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Integer
                || value instanceof Long) return value;
        if (value instanceof String text && text.length() <= MAX_VALUE_CHARS) return text;
        if (value instanceof Double number && Double.isFinite(number)) return number;
        throw new SecurityException(
                "untrusted isolate transport permits only bounded strings, integers, finite doubles, bool or null");
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
            closed.set(true);
            // Guest code state is destroyed when the owning GROUP closes;
            // marking this unit closed prevents further host admissions.
        }
    }
}
