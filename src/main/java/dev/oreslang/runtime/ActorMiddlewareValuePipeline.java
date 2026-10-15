package dev.oreslang.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/**
 * Typed unary middleware reference executor. This host component is not the
 * Oreslang guest actor scheduler. Annotated guest actors remain gated until
 * the compiler can lower middleware into actor-owned continuations.
 *
 * next_val returns Continue/Reply/Reject and never owns a response writer.
 * next is an around stage with a one-shot continuation.
 */
public final class ActorMiddlewareValuePipeline<T> {
    public sealed interface Decision<R> permits Continue, Reply, Reject {}
    public sealed interface Outcome<R> permits Reply, Reject {}

    /**
     * Immutable HTTP reply *metadata*, not an HttpResponseWriter. No headers
     * or status are committed until the single trusted terminal owner encodes
     * the typed reply. A generic ActorMail adapter may ignore HTTP options.
     */
    public record ResponseDraft(int status, Map<String, String> headers) {
        public ResponseDraft {
            if (status < 200 || status > 599 || status == 204 || status == 205
                    || status == 304) throw new IllegalArgumentException("status cannot carry a typed reply");
            headers = new ActorMiddlewarePipeline.Envelope("", headers).headers();
        }
        public static ResponseDraft defaults() { return new ResponseDraft(200, Map.of()); }
        public ResponseDraft withStatus(int value) { return new ResponseDraft(value, headers); }
        public ResponseDraft withHeader(String name, String value) {
            var changed = new HashMap<>(headers);
            changed.put(name, value);
            return new ResponseDraft(status, changed);
        }
    }

    public record Continue<R>(ActorMiddlewarePipeline.Envelope request, ResponseDraft response)
            implements Decision<R> {
        public Continue {
            Objects.requireNonNull(request);
            Objects.requireNonNull(response);
        }
        public Continue(ActorMiddlewarePipeline.Envelope request) {
            this(request, ResponseDraft.defaults());
        }
    }
    public record Reply<R>(R value, ResponseDraft response) implements Decision<R>, Outcome<R> {
        public Reply {
            Objects.requireNonNull(value, "reply must be nonvoid");
            Objects.requireNonNull(response);
        }
        public Reply(R value) { this(value, ResponseDraft.defaults()); }
    }
    public record Reject<R>(int status, String reason) implements Decision<R>, Outcome<R> {
        public Reject {
            if (status < 400 || status > 599) throw new IllegalArgumentException("invalid error status");
            Objects.requireNonNull(reason);
        }
    }

    @FunctionalInterface public interface Next<R> {
        CompletionStage<Outcome<R>> proceed(
                ActorMiddlewarePipeline.Envelope request, ResponseDraft response);
        default CompletionStage<Outcome<R>> proceed(ActorMiddlewarePipeline.Envelope request) {
            return proceed(request, ResponseDraft.defaults());
        }
    }
    @FunctionalInterface public interface ValueHook<R> {
        CompletionStage<Decision<R>> nextVal(
                ActorMiddlewarePipeline.Envelope request, ResponseDraft response);
    }
    @FunctionalInterface public interface AroundHook<R> {
        CompletionStage<Outcome<R>> next(
                ActorMiddlewarePipeline.Envelope request, ResponseDraft response, Next<R> next);
    }
    @FunctionalInterface public interface Terminal<R> {
        CompletionStage<R> run(ActorMiddlewarePipeline.Envelope request);
    }

    public record Stage<R>(
            ActorMiddlewarePipeline.Key key, ActorMiddlewarePipeline.Phase phase,
            ValueHook<R> valueHook, AroundHook<R> aroundHook, boolean skipIfEquivalent) {
        public Stage {
            Objects.requireNonNull(key);
            Objects.requireNonNull(phase);
            if ((valueHook == null) == (aroundHook == null))
                throw new IllegalArgumentException("stage must select exactly one hook");
            if (phase == ActorMiddlewarePipeline.Phase.SUPERVISOR && skipIfEquivalent)
                throw new IllegalArgumentException("skip option belongs to actor middleware");
        }
        public static <R> Stage<R> value(ActorMiddlewarePipeline.Key key,
                ActorMiddlewarePipeline.Phase phase, ValueHook<R> hook, boolean skip) {
            return new Stage<>(key, phase, Objects.requireNonNull(hook), null, skip);
        }
        public static <R> Stage<R> around(ActorMiddlewarePipeline.Key key,
                ActorMiddlewarePipeline.Phase phase, AroundHook<R> hook, boolean skip) {
            return new Stage<>(key, phase, null, Objects.requireNonNull(hook), skip);
        }
    }

    private final List<Stage<T>> supervisor;
    private final List<Stage<T>> actor;
    private final Set<ActorMiddlewarePipeline.Key> trustedEquivalent;
    private final Terminal<T> terminal;

    public ActorMiddlewareValuePipeline(List<Stage<T>> supervisor,
            List<Stage<T>> actor,
            Set<ActorMiddlewarePipeline.Key> trustedEquivalent,
            Terminal<T> terminal) {
        this.supervisor = validate(supervisor, ActorMiddlewarePipeline.Phase.SUPERVISOR);
        this.actor = validate(actor, ActorMiddlewarePipeline.Phase.ACTOR);
        this.trustedEquivalent = Set.copyOf(Objects.requireNonNull(trustedEquivalent));
        this.terminal = Objects.requireNonNull(terminal);
        Set<ActorMiddlewarePipeline.Key> supervisorKeys = new HashSet<>();
        for (Stage<T> stage : this.supervisor) supervisorKeys.add(stage.key());
        if (!supervisorKeys.containsAll(this.trustedEquivalent))
            throw new IllegalArgumentException("unknown supervisor equivalence key");
    }

    private static <T> List<Stage<T>> validate(
            List<Stage<T>> stages, ActorMiddlewarePipeline.Phase phase) {
        List<Stage<T>> copy = List.copyOf(stages);
        if (copy.size() > 16) throw new IllegalArgumentException("too many middleware stages");
        Set<ActorMiddlewarePipeline.Key> seen = new HashSet<>();
        for (Stage<T> s : copy) {
            if (s.phase() != phase || !seen.add(s.key()))
                throw new IllegalArgumentException("invalid middleware stage phase or duplicate key");
        }
        return copy;
    }

    public CompletionStage<Outcome<T>> invoke(
            ActorMiddlewarePipeline.Envelope request, BooleanSupplier cancelled) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(cancelled);
        return dispatch(0, request, ResponseDraft.defaults(), cancelled, new ConcurrentHashMap<>());
    }

    private record Receipt(ActorMiddlewarePipeline.Envelope request, ResponseDraft response) {}

    private CompletionStage<Outcome<T>> dispatch(int slot,
            ActorMiddlewarePipeline.Envelope request, ResponseDraft response,
            BooleanSupplier cancelled,
            Map<ActorMiddlewarePipeline.Key, Receipt> receipts) {
        if (cancelled.getAsBoolean())
            return CompletableFuture.failedFuture(new IllegalStateException("request cancelled"));
        int superCount = supervisor.size();
        if (slot == superCount + actor.size()) {
            try {
                return Objects.requireNonNull(terminal.run(request)).thenApply(v -> {
                    if (cancelled.getAsBoolean()) throw new IllegalStateException("request cancelled");
                    return new Reply<T>(v);
                });
            } catch (Throwable t) { return CompletableFuture.failedFuture(t); }
        }
        boolean privileged = slot < superCount;
        Stage<T> stage = privileged ? supervisor.get(slot) : actor.get(slot - superCount);
        // Receipt matches the *exact immutable object*, not equal-looking
        // reconstructed headers. Mutate-then-revert invalidates the receipt.
        if (!privileged && stage.skipIfEquivalent()
                && trustedEquivalent.contains(stage.key())
                && receipts.get(stage.key()) != null
                && receipts.get(stage.key()).request() == request
                && receipts.get(stage.key()).response() == response)
            return dispatch(slot + 1, request, response, cancelled, receipts);
        AtomicBoolean advanced = new AtomicBoolean();
        AtomicBoolean settled = new AtomicBoolean();
        AtomicReference<CompletionStage<Outcome<T>>> forwarded = new AtomicReference<>();
        Next<T> next = (updated, draft) -> {
            if (settled.get()) return CompletableFuture.failedFuture(
                    new IllegalStateException("next after middleware completion"));
            if (!advanced.compareAndSet(false, true)) return CompletableFuture.failedFuture(
                    new IllegalStateException("next called more than once"));
            if (cancelled.getAsBoolean()) return CompletableFuture.failedFuture(
                    new IllegalStateException("request cancelled"));
            ActorMiddlewarePipeline.Envelope checked = Objects.requireNonNull(updated);
            ResponseDraft checkedDraft = Objects.requireNonNull(draft);
            if (privileged && trustedEquivalent.contains(stage.key()))
                receipts.put(stage.key(), new Receipt(checked, checkedDraft));
            CompletionStage<Outcome<T>> down = dispatch(
                    slot + 1, checked, checkedDraft, cancelled, receipts);
            forwarded.set(down);
            return down;
        };
        CompletionStage<Outcome<T>> current;
        try {
            if (stage.valueHook() != null) {
                current = Objects.requireNonNull(stage.valueHook().nextVal(request, response))
                        .thenCompose(decision -> {
                            if (decision instanceof Continue<?> pass)
                                return next.proceed(pass.request(), pass.response());
                            if (decision instanceof Reply<?> reply) {
                                if (privileged) return CompletableFuture.failedFuture(
                                        new SecurityException("supervisor cannot return actor's typed value"));
                                @SuppressWarnings("unchecked") Outcome<T> cast = (Outcome<T>) reply;
                                return CompletableFuture.completedFuture(cast);
                            }
                            if (decision instanceof Reject<?> reject) {
                                @SuppressWarnings("unchecked") Outcome<T> cast = (Outcome<T>) reject;
                                return CompletableFuture.completedFuture(cast);
                            }
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("invalid next_val decision"));
                        });
            } else current = Objects.requireNonNull(stage.aroundHook().next(request, response, next));
        } catch (Throwable error) { current = CompletableFuture.failedFuture(error); }

        // One observable terminal result, even if around-stage code fails
        // after invoking its child. No detached child may publish late output.
        return current.handle((outcome, failure) -> {
            settled.set(true);
            CompletionStage<Outcome<T>> child = forwarded.get();
            CompletionStage<Outcome<T>> drained = child == null
                    ? CompletableFuture.completedFuture(null) : child;
            return drained.handle((ignored, childFailure) -> {
                if (failure != null) throw new CompletionException(failure);
                if (childFailure != null) throw new CompletionException(childFailure);
                if (cancelled.getAsBoolean()) throw new IllegalStateException("request cancelled");
                return Objects.requireNonNull(outcome, "null middleware outcome");
            });
        }).thenCompose(x -> x);
    }
}
