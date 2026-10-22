package dev.oreslang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduler-owned lowering target for Future-aware value pipelines.
 *
 * <p>One OresScheduler task executes all stages in order. An ordinary result
 * stays in the same task turn; a Future result suspends the task, rather than
 * spawning another callback task per pipeline stage. This host/runtime API
 * is not the source-language Task.pipeline syntax or a typed-effect checker:
 * the compiler must prove port types, effects and actor ownership before
 * admitting a source pipeline here.</p>
 */
public final class TaskPipeline {
    private TaskPipeline() { }

    @FunctionalInterface
    public interface Stage {
        /** Called on the pipeline's scheduler only after upstream success. */
        Object apply(Object input) throws Exception;
    }

    /**
     * Only the pipeline's own newly started asynchronous work should be
     * wrapped as Owned. An input Future and externally shared stage Futures
     * are borrowed observations and are never cancelled by this pipeline.
     */
    public record Owned(OresFuture<?> future) {
        public Owned {
            Objects.requireNonNull(future, "future");
        }
    }

    public static Owned owned(OresFuture<?> future) {
        return new Owned(future);
    }

    private static final class UpstreamCancelled extends CancellationException {
        private static final long serialVersionUID = 1L;
        private UpstreamCancelled() {
            super("pipeline upstream Future was cancelled");
        }
    }

    /**
     * Execute at most 128 statically checked stages. Stage lambdas must delay
     * *all* stage arguments and other side effects until their invocation.
     *
     * <p>Cancellation revokes the single pipeline task and its currently
     * owned stage Future, never the shared input. The returned observation
     * Future is distinct from the scheduler-internal quiescence witness;
     * consumers requiring physical retirement must still use the runtime
     * task/actor lifetime barrier.</p>
     */
    @SuppressWarnings("unchecked")
    public static <T> OresFuture<T> start(
            OresScheduler scheduler,
            OresFuture<?> input,
            List<? extends Stage> stages) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(input, "input");
        List<Stage> chain = List.copyOf(Objects.requireNonNull(stages, "stages"));
        if (chain.size() > 128) {
            throw new IllegalArgumentException("pipeline exceeds 128 stages");
        }

        AtomicReference<OresFuture<?>> ownedStage = new AtomicReference<>();
        AtomicReference<OresFuture<T>> exposedRef = new AtomicReference<>();

        OresFuture<T> task = scheduler.start(new OresScheduler.Task<>() {
            private boolean started;
            private int next;
            private OresFuture<?> awaited;

            @Override
            public OresScheduler.Step<T> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (!started) {
                    if (!resume.initial()) {
                        throw new IllegalStateException("pipeline did not start on initial dispatch");
                    }
                    started = true;
                    awaited = input;
                    return OresScheduler.await(input);
                }
                if (resume.initial() || awaited == null) {
                    throw new IllegalStateException("pipeline resumed without an await");
                }

                OresFuture<?> previous = awaited;
                awaited = null;
                // An owned child is no longer current once its await resumes;
                // a concurrent pipeline cancellation may already have
                // cancelled it. No later stage may reuse its ownership token.
                ownedStage.compareAndSet(previous, null);

                if (resume.failure() != null) {
                    if (previous.isCancelled()) throw new UpstreamCancelled();
                    Throwable failure = OresFuture.unwrap(resume.failure());
                    if (failure instanceof Exception exception) throw exception;
                    if (failure instanceof Error error) throw error;
                    throw new IllegalStateException("pipeline stage failed", failure);
                }

                Object value = resume.value();
                while (next < chain.size()) {
                    OresFuture<T> exposed = exposedRef.get();
                    if (exposed != null && exposed.isCancelled()) {
                        throw new UpstreamCancelled();
                    }
                    Object produced = chain.get(next++).apply(value);
                    if (produced instanceof Owned owned) {
                        OresFuture<?> child = owned.future();
                        if (!ownedStage.compareAndSet(null, child)) {
                            throw new IllegalStateException(
                                    "pipeline already retains an owned stage Future");
                        }
                        exposed = exposedRef.get();
                        if (exposed != null && exposed.isCancelled()) {
                            if (ownedStage.compareAndSet(child, null)) child.cancel(false);
                            throw new UpstreamCancelled();
                        }
                        awaited = child;
                        return OresScheduler.await(child);
                    }
                    if (produced instanceof OresFuture<?> nextFuture) {
                        awaited = nextFuture;
                        return OresScheduler.await(nextFuture);
                    }
                    value = produced;
                }
                return OresScheduler.done((T) value);
            }
        });

        OresFuture<T> exposed = new OresFuture<>(() -> {
            task.cancel(false);
            OresFuture<?> currentOwned = ownedStage.getAndSet(null);
            if (currentOwned != null) currentOwned.cancel(false);
        });
        exposedRef.set(exposed);

        AtomicReference<OresFuture.RuntimeWaiterRegistration<T>> subscription =
                new AtomicReference<>();
        OresFuture.RuntimeWaiterRegistration<T> registration =
                task.whenCompleteRuntimeCancellable((value, failure) -> {
                    try {
                        if (exposed.isDone()) return;
                        if (failure == null) {
                            exposed.completeFromRuntime(value);
                        } else if (task.isCancelled()
                                || OresFuture.unwrap(failure) instanceof UpstreamCancelled) {
                            exposed.cancel(false);
                        } else {
                            exposed.failFromRuntime(OresFuture.unwrap(failure));
                        }
                    } finally {
                        ownedStage.set(null);
                        OresFuture.RuntimeWaiterRegistration<T> current =
                                subscription.getAndSet(null);
                        if (current != null) current.detach();
                    }
                });
        subscription.set(registration);
        if (exposed.isDone()) {
            OresFuture.RuntimeWaiterRegistration<T> raced =
                    subscription.getAndSet(null);
            if (raced != null) raced.detach();
            ownedStage.set(null);
        }
        return exposed;
    }
}
