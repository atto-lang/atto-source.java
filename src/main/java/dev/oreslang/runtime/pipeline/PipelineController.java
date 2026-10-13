package dev.oreslang.runtime.pipeline;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Trusted host control-plane for sequential actor-pipeline generations.
 *
 * It never rewires a live ActorRuntime.ActorStream. Pausing fences new DATA,
 * waits for the one admitted round trip, then requires an engine-owned
 * physical STOP/DONE barrier. Resume/restart creates fresh stage capabilities.
 *
 * This is a reference coordinator. The production ActorRuntime engine
 * adapter and guest syntax are intentionally separate release gates.
 */
public final class PipelineController {
    public enum State { NEW, OPENING, RUNNING, PAUSING, PAUSED, FAILED, CLOSED }

    /**
     * Implementations MUST verify registered AOT-compatible providers,
     * spawn fresh actors, check the exact nominal domain, and atomically bind
     * the entire route before allowing any DATA ingress.
     */
    @FunctionalInterface
    public interface Engine {
        Session open(PipelinePlan plan);

        /** Schedule reopening on the trusted root/CONTROL lane, NEVER a guest
         * actor finalizer or arbitrary CompletionStage callback carrier. */
        default void scheduleControl(Runnable task) {
            throw new IllegalStateException("pipeline engine has no trusted control scheduler");
        }
    }

    public interface Session {
        /** One authorized ingress, completed or exceptionally settled. */
        CompletionStage<Object> send(Object value);

        /**
         * Trusted, side-effect-free pre-admission check against the concrete
         * compiler-bound mailbox domain. The default fails closed so an
         * engine that cannot prove the expected type cannot admit DATA.
         * Must not allocate actors, publish DATA, or invoke guest code.
         */
        default void requireExpectedReplyCase(String qualifiedCase) {
            throw new UnsupportedOperationException(
                    "pipeline engine does not attest nominal reply cases");
        }

        /**
         * No DATA may be admitted after this call starts. Complete only after
         * all admitted messages and every stage's physical DONE has settled.
         * Must be safe to call after a failed send or actor termination.
         */
        CompletionStage<Void> stopAfterDrain();

        /** Non-cancellable, engine-authenticated whole-route DONE observer. */
        CompletionStage<Void> getDone();
    }

    private final Object gate = new Object();
    private final Engine engine;
    private PipelinePlan plan;
    private Session active;
    private State state = State.NEW;
    private long generation;
    private boolean inFlight;
    private boolean closeRequested;
    private boolean quiesceStarted;
    private CompletableFuture<Void> pauseFuture;
    private CompletableFuture<Void> closeFuture;

    public PipelineController(PipelinePlan plan, Engine engine) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public State state() {
        synchronized (gate) { return state; }
    }

    public PipelinePlan plan() {
        synchronized (gate) { return plan; }
    }

    public long generation() {
        synchronized (gate) { return generation; }
    }

    /** Opening invokes registered providers outside the lifecycle mutex. */
    public PipelineRunner start() {
        PipelinePlan snapshot;
        synchronized (gate) {
            if (closeRequested || (state != State.NEW && state != State.PAUSED)) {
                throw new IllegalStateException("pipeline start requires NEW or quiescent PAUSED");
            }
            if (generation == Long.MAX_VALUE) {
                state = State.FAILED;
                throw new IllegalStateException("pipeline generation space exhausted");
            }
            state = State.OPENING;
            snapshot = plan;
        }
        Session opened = null;
        CompletionStage<Void> wholeDone;
        try {
            opened = Objects.requireNonNull(engine.open(snapshot), "engine session");
            wholeDone = Objects.requireNonNull(opened.getDone(), "engine physical DONE observer");
        } catch (Throwable failure) {
            // A session might have spawned several actors before its DONE
            // observer failed. Retain it for verified close() cleanup.
            synchronized (gate) {
                active = opened;
                state = State.FAILED;
            }
            throw asRuntime(failure);
        }
        final long activatedGeneration;
        final Session activatedSession = opened;
        final PipelineRunner runner;
        synchronized (gate) {
            // The only allowed concurrent operation during OPENING is
            // observation. A reentrant start, pause, swap or close fails.
            if (state != State.OPENING) {
                throw new IllegalStateException("pipeline opening state corrupted");
            }
            active = opened;
            ++generation;
            activatedGeneration = generation;
            inFlight = false;
            quiesceStarted = false;
            pauseFuture = null;
            state = State.RUNNING;
            runner = new PipelineRunner(this, generation, wholeDone);
        }
        // The scheduler can report fatal actor completion before the caller
        // submits DATA. Fence the entire generation, rather than leaving a
        // RUNNING controller containing a physically dead route. Ignore
        // successful completion (normal after DATA), and do not supersede a
        // STOP already in progress (PAUSING) or a subsequent generation.
        wholeDone.whenComplete((ignored, failure) -> {
            if (failure == null) return;
            synchronized (gate) {
                if (active == activatedSession
                        && generation == activatedGeneration
                        && state == State.RUNNING) {
                    state = State.FAILED;
                }
            }
        });
        synchronized (gate) {
            if (state == State.FAILED) {
                throw new IllegalStateException(
                        "actor pipeline failed during startup; close for physical cleanup");
            }
        }
        return runner;
    }

    public CompletionStage<Void> pause() {
        Session toQuiesce = null;
        CompletableFuture<Void> result;
        synchronized (gate) {
            if (state == State.PAUSED) return CompletableFuture.completedFuture(null);
            if (state == State.PAUSING) return pauseFuture.minimalCompletionStage();
            if (state != State.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "pipeline pause requires RUNNING"));
            }
            state = State.PAUSING;
            pauseFuture = new CompletableFuture<>();
            result = pauseFuture;
            if (!inFlight) {
                quiesceStarted = true;
                toQuiesce = active;
            }
        }
        if (toQuiesce != null) beginQuiesce(toQuiesce, result);
        return result.minimalCompletionStage();
    }

    private void beginQuiesce(Session session, CompletableFuture<Void> observer) {
        CompletionStage<Void> stopped;
        try {
            stopped = Objects.requireNonNull(session.stopAfterDrain(), "physical STOP barrier");
        } catch (Throwable failure) {
            stopped = CompletableFuture.failedFuture(failure);
        }
        stopped.whenComplete((unused, failure) -> {
            synchronized (gate) {
                if (failure == null) {
                    active = null; // the stage generations have physically retired
                    state = State.PAUSED;
                } else {
                    state = State.FAILED; // preserve session for explicit failed cleanup
                }
            }
            if (failure == null) observer.complete(null);
            else observer.completeExceptionally(failure);
        });
    }

    CompletionStage<Object> submitExpected(
            long expectedGeneration, Object value, String expectedCase) {
        Objects.requireNonNull(value, "pipeline message");
        Objects.requireNonNull(expectedCase, "expected nominal result case");
        Session selected;
        synchronized (gate) {
            if (closeRequested || state != State.RUNNING
                    || generation != expectedGeneration || inFlight) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "stale runner, paused ingress, or outstanding pipeline flow"));
            }
            selected = active;
        }
        // Call external session code without holding the controller gate.
        // A rejected case MUST NOT consume the single-flow admission token.
        // submit() below rechecks the generation after this validation:
        // a concurrent pause/restart cannot publish on a stale session.
        try {
            selected.requireExpectedReplyCase(expectedCase);
        } catch (RuntimeException | Error rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
        return submit(expectedGeneration, value);
    }

    CompletionStage<Object> submit(long expectedGeneration, Object value) {
        Objects.requireNonNull(value, "pipeline message");
        Session selected;
        synchronized (gate) {
            if (closeRequested || state != State.RUNNING
                    || generation != expectedGeneration || inFlight) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "stale runner, paused ingress, or outstanding pipeline flow"));
            }
            inFlight = true;
            selected = active;
        }
        CompletionStage<Object> execution;
        try {
            execution = Objects.requireNonNull(selected.send(value), "engine send completion");
        } catch (Throwable failure) {
            execution = CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<Object> result = new CompletableFuture<>();
        execution.whenComplete((answer, failure) -> {
            Session toQuiesce = null;
            CompletableFuture<Void> observer = null;
            CompletableFuture<Void> failedPause = null;
            synchronized (gate) {
                inFlight = false;
                if (failure != null) {
                    state = State.FAILED;
                    if (closeRequested && pauseFuture != null
                            && !quiesceStarted) {
                        // close() was already waiting on pause; it MUST
                        // drain physical actors even when DATA fails.
                        quiesceStarted = true;
                        toQuiesce = active;
                        observer = pauseFuture;
                    } else if (pauseFuture != null && !pauseFuture.isDone()) {
                        failedPause = pauseFuture;
                    }
                } else if (state == State.PAUSING && !quiesceStarted) {
                    quiesceStarted = true;
                    toQuiesce = active;
                    observer = pauseFuture;
                }
            }
            // No external continuations or completion callbacks under gate.
            if (failedPause != null) failedPause.completeExceptionally(failure);
            if (toQuiesce != null) beginQuiesce(toQuiesce, observer);
            if (failure == null) result.complete(answer);
            else result.completeExceptionally(failure);
        });
        // Never hand out producer settlement authority: cancellation or
        // completion of the caller's observation cannot change real DATA.
        return result.minimalCompletionStage();
    }

    public void install(PipelinePlan replacement) {
        Objects.requireNonNull(replacement, "replacement plan");
        synchronized (gate) {
            if (closeRequested || state != State.PAUSED || active != null || inFlight) {
                throw new IllegalStateException(
                        "pipeline topology updates require a fully quiescent PAUSED generation");
            }
            if (replacement.revision() <= plan.revision()) {
                throw new IllegalArgumentException("plan installation requires a newer revision");
            }
            if (!replacement.nominalDomain().equals(plan.nominalDomain())) {
                throw new IllegalArgumentException("pipeline plan cannot change its closed nominal domain");
            }
            plan = replacement;
        }
    }

    public void replace(String stageName, PipelinePlan.Stage replacement) {
        synchronized (gate) {
            if (closeRequested || state != State.PAUSED || active != null || inFlight) {
                throw new IllegalStateException("actor hot replacement requires PAUSED quiescence");
            }
            plan = plan.replace(stageName, replacement);
        }
    }

    public void insert(int position, PipelinePlan.Stage stage) {
        synchronized (gate) {
            if (closeRequested || state != State.PAUSED || active != null || inFlight) {
                throw new IllegalStateException("actor hot insertion requires PAUSED quiescence");
            }
            plan = plan.insert(position, stage);
        }
    }

    public void remove(String stageName) {
        synchronized (gate) {
            if (closeRequested || state != State.PAUSED || active != null || inFlight) {
                throw new IllegalStateException("actor hot removal requires PAUSED quiescence");
            }
            plan = plan.remove(stageName);
        }
    }

    /** Same paused plan starts a fresh generation with fresh actors. */
    public PipelineRunner resume() { return start(); }

    /**
     * A RUNNING restart drains old actors first. A FAILED restart is not
     * silently retried: the host must explicitly close and reconstruct
     * after investigating whether the previous publication was ambiguous.
     */
    public CompletionStage<PipelineRunner> restart() {
        State observed = state();
        if (observed == State.PAUSED) {
            CompletableFuture<PipelineRunner> restarted = new CompletableFuture<>();
            try {
                engine.scheduleControl(() -> {
                    try { restarted.complete(start()); }
                    catch (Throwable error) { restarted.completeExceptionally(error); }
                });
            } catch (Throwable error) { restarted.completeExceptionally(error); }
            return restarted.minimalCompletionStage();
        }
        if (observed != State.RUNNING && observed != State.PAUSING) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "pipeline restart requires RUNNING or PAUSED"));
        }
        CompletableFuture<PipelineRunner> restarted = new CompletableFuture<>();
        pause().whenComplete((ignored, failure) -> {
            if (failure != null) {
                restarted.completeExceptionally(failure);
                return;
            }
            try {
                engine.scheduleControl(() -> {
                    try { restarted.complete(start()); }
                    catch (Throwable problem) { restarted.completeExceptionally(problem); }
                });
            } catch (Throwable problem) {
                restarted.completeExceptionally(problem);
            }
        });
        return restarted.minimalCompletionStage();
    }

    /** Close fences admission immediately, then waits for physical DONE. */
    public CompletionStage<Void> close() {
        State observed;
        Session failedSession;
        CompletableFuture<Void> completion;
        synchronized (gate) {
            if (closeFuture != null) return closeFuture.minimalCompletionStage();
            if (state == State.OPENING) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "cannot close during in-progress provider activation"));
            }
            closeRequested = true;
            closeFuture = new CompletableFuture<>();
            completion = closeFuture;
            observed = state;
            failedSession = state == State.FAILED ? active : null;
        }
        CompletionStage<Void> shutdown;
        if (observed == State.RUNNING || observed == State.PAUSING) {
            shutdown = pause();
        } else if (failedSession != null) {
            try {
                shutdown = Objects.requireNonNull(failedSession.stopAfterDrain(), "failure drain");
            } catch (Throwable failure) {
                shutdown = CompletableFuture.failedFuture(failure);
            }
        } else {
            shutdown = CompletableFuture.completedFuture(null);
        }
        shutdown.whenComplete((ignored, failure) -> {
            synchronized (gate) {
                if (failure == null) {
                    active = null;
                    state = State.CLOSED;
                } else {
                    state = State.FAILED;
                }
            }
            if (failure == null) completion.complete(null);
            else completion.completeExceptionally(failure);
        });
        return completion.minimalCompletionStage();
    }

    private static RuntimeException asRuntime(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("pipeline provider activation failed", failure);
    }
}
