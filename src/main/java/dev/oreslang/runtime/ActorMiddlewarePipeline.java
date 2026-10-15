package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Trusted host-side reference engine for supervisor/actor middleware ordering.
 *
 * Not a guest ActorMiddleware API and not wired to a source-actor receive/run
 * dispatcher. The guest compiler must continue to reject @ActorMiddleware
 * until its own actor-turn continuation, ownership and capability gates exist.
 *
 * A supervisor proof is held only in this invocation's private state, never in
 * a message/header supplied by a client or guest actor. Skips require an
 * explicit host-provided equivalence allowlist and exact middleware version.
 */
public final class ActorMiddlewarePipeline {
    public enum Phase { SUPERVISOR, ACTOR }

    public record Key(String id, String version) {
        public Key {
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
                    || version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("invalid middleware identity/version");
            }
        }
    }

    @FunctionalInterface
    public interface Middleware {
        CompletionStage<Outcome> next(Envelope request, Next next);
    }

    /** A bounded one-shot continuation. Reentry cannot dispatch twice. */
    @FunctionalInterface
    public interface Next {
        CompletionStage<Outcome> proceed(Envelope request);
    }

    @FunctionalInterface
    public interface Terminal {
        CompletionStage<Outcome> receive(Envelope request);
    }

    public record Stage(Key key, Phase phase, Middleware middleware, boolean maySkipAfterSupervisor) {
        public Stage {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(middleware, "middleware");
            if (phase == Phase.SUPERVISOR && maySkipAfterSupervisor) {
                throw new IllegalArgumentException("skip eligibility belongs to the actor phase");
            }
        }
    }

    public record Outcome(int status, String body) {
        public Outcome {
            if (status < 100 || status > 599) throw new IllegalArgumentException("invalid response status");
            Objects.requireNonNull(body, "body");
        }
    }

    /** Immutable application-message metadata, not raw HTTP framing headers. */
    public record Envelope(String message, Map<String, String> headers) {
        private static final int MAX_HEADERS = 64;
        private static final int MAX_HEADER_BYTES = 32 * 1024;

        public Envelope {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(headers, "headers");
            Map<String, String> normalized = new LinkedHashMap<>();
            int bytes = 0;
            for (var entry : headers.entrySet()) {
                String name = normalizedName(entry.getKey());
                String value = validValue(entry.getValue());
                if (isProtected(name)) {
                    throw new SecurityException("reserved message metadata header " + name);
                }
                if (normalized.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException("duplicate normalized header " + name);
                }
                bytes += name.length() + value.length();
                if (normalized.size() > MAX_HEADERS || bytes > MAX_HEADER_BYTES) {
                    throw new IllegalArgumentException("middleware header budget exceeded");
                }
            }
            headers = Map.copyOf(normalized);
        }

        public Envelope withHeader(String name, String value) {
            Map<String, String> changed = new LinkedHashMap<>(headers);
            changed.put(normalizedName(name), validValue(value));
            return new Envelope(message, changed);
        }

        public Envelope withMessage(String newMessage) {
            return new Envelope(Objects.requireNonNull(newMessage), headers);
        }

        private static String normalizedName(String raw) {
            if (raw == null || !raw.matches("[A-Za-z][A-Za-z0-9-]{0,127}")) {
                throw new IllegalArgumentException("invalid message metadata header");
            }
            return raw.toLowerCase(Locale.ROOT);
        }

        private static String validValue(String value) {
            if (value == null || value.length() > 8192) {
                throw new IllegalArgumentException("invalid middleware metadata value");
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c == 0x7f) {
                    throw new IllegalArgumentException("control character in metadata header");
                }
            }
            return value;
        }

        private static boolean isProtected(String name) {
            return name.equals("authorization") || name.equals("cookie")
                    || name.equals("set-cookie") || name.equals("host")
                    || name.equals("content-length") || name.equals("connection")
                    || name.equals("transfer-encoding") || name.equals("upgrade")
                    || name.equals("te") || name.equals("trailer")
                    || name.equals("proxy-authorization")
                    || name.startsWith("proxy-") || name.startsWith("x-forwarded-")
                    || name.startsWith("x-oreslang-");
        }
    }

    private final List<Stage> supervisor;
    private final List<Stage> actor;
    private final Set<Key> verifiedEquivalence;
    private final Terminal terminal;

    /**
     * The equivalence set is a trusted supervisor/configuration allowlist, NOT
     * a guest-supplied request field. Matching names alone never grant skips.
     */
    public ActorMiddlewarePipeline(List<Stage> supervisor, List<Stage> actor,
                                   Set<Key> verifiedEquivalence, Terminal terminal) {
        this.supervisor = validateStages(supervisor, Phase.SUPERVISOR);
        this.actor = validateStages(actor, Phase.ACTOR);
        this.verifiedEquivalence = Set.copyOf(Objects.requireNonNull(verifiedEquivalence));
        this.terminal = Objects.requireNonNull(terminal);
        Set<Key> supervisorKeys = new HashSet<>();
        for (Stage stage : this.supervisor) supervisorKeys.add(stage.key());
        for (Key key : this.verifiedEquivalence) {
            if (!supervisorKeys.contains(key)) {
                throw new IllegalArgumentException("equivalence allowance without supervisor stage " + key);
            }
        }
    }

    private static List<Stage> validateStages(List<Stage> stages, Phase phase) {
        Objects.requireNonNull(stages, "stages");
        if (stages.size() > 16) throw new IllegalArgumentException("middleware chain exceeds 16 stages");
        List<Stage> copied = List.copyOf(stages);
        Set<Key> seen = new HashSet<>();
        for (Stage stage : copied) {
            if (stage.phase() != phase) throw new IllegalArgumentException("middleware phase mismatch");
            if (!seen.add(stage.key())) throw new IllegalArgumentException("duplicate middleware stage " + stage.key());
        }
        return copied;
    }

    /** Cancellation/deadline observation is supplied by the trusted owner. */
    public CompletionStage<Outcome> invoke(Envelope request, BooleanSupplier cancelled) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancelled, "cancelled");
        return run(0, request, cancelled, new ConcurrentHashMap<>());
    }

    private CompletionStage<Outcome> run(int position, Envelope request,
                                         BooleanSupplier cancelled, Map<Key, Envelope> supervisorReceipts) {
        if (cancelled.getAsBoolean()) {
            return CompletableFuture.failedFuture(new IllegalStateException("middleware request cancelled"));
        }
        int supervisorCount = supervisor.size();
        int size = supervisorCount + actor.size();
        if (position == size) {
            try {
                CompletionStage<Outcome> result = Objects.requireNonNull(
                        terminal.receive(request), "null actor terminal future");
                return result.thenCompose(value -> cancelled.getAsBoolean()
                        ? CompletableFuture.failedFuture(new IllegalStateException("middleware request cancelled"))
                        : CompletableFuture.completedFuture(Objects.requireNonNull(value)));
            } catch (Throwable error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        boolean inSupervisor = position < supervisorCount;
        Stage stage = inSupervisor ? supervisor.get(position) : actor.get(position - supervisorCount);
        // No client-header proof is examined. The current invocation created
        // this receipt when the supervisor stage invoked its trusted next.
        // Subsequent context mutation invalidates the exact-envelope proof.
        if (!inSupervisor && stage.maySkipAfterSupervisor()
                && verifiedEquivalence.contains(stage.key())
                && supervisorReceipts.get(stage.key()) == request) {
            return run(position + 1, request, cancelled, supervisorReceipts);
        }
        AtomicBoolean advanced = new AtomicBoolean();
        AtomicBoolean settled = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<CompletionStage<Outcome>> downstream =
                new java.util.concurrent.atomic.AtomicReference<>();
        Next next = updated -> {
            if (settled.get()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("middleware next after completion"));
            }
            if (!advanced.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("middleware next is single-use"));
            }
            if (cancelled.getAsBoolean()) {
                return CompletableFuture.failedFuture(new IllegalStateException("middleware request cancelled"));
            }
            if (inSupervisor && verifiedEquivalence.contains(stage.key())) {
                // Proof becomes usable only after the trusted supervisor
                // middleware reaches its continuation, not at admission.
                supervisorReceipts.put(stage.key(), updated);
            }
            CompletionStage<Outcome> invoked = run(
                    position + 1, Objects.requireNonNull(updated), cancelled, supervisorReceipts);
            downstream.set(invoked);
            return invoked;
        };
        CompletionStage<Outcome> result;
        try {
            result = Objects.requireNonNull(
                    stage.middleware().next(request, next), "null middleware completion");
        } catch (Throwable error) {
            result = CompletableFuture.failedFuture(error);
        }
        // Even if middleware fails after calling next, the outer response
        // must not settle before downstream finishes (or is cancelled by its
        // trusted owner). Otherwise a late handler can write after rejection.
        return result.handle((value, failure) -> {
            settled.set(true);
            CompletionStage<Outcome> forwarded = downstream.get();
            CompletionStage<Outcome> drain = forwarded == null
                    ? CompletableFuture.completedFuture(null) : forwarded;
            return drain.handle((ignored, downstreamFailure) -> {
                if (failure != null) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
                if (downstreamFailure != null) {
                    throw new java.util.concurrent.CompletionException(downstreamFailure);
                }
                if (cancelled.getAsBoolean()) {
                    throw new IllegalStateException("middleware request cancelled");
                }
                return Objects.requireNonNull(value, "null middleware outcome");
            });
        }).thenCompose(completion -> completion);
    }
}
