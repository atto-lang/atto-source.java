package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Strictly bounded structured host-side map-parallel composition.
 *
 * <p>The caller supplies lazy factory functions producing compiler-lowered
 * Tasks, NOT eagerly launched TaskHandles or already-running Futures.
 * A single coordinator Task admits at most concurrency TaskScope children;
 * slots are recycled only after each child's getDone() reports physical
 * quiescence. All user callbacks run through OresScheduler execution turns,
 * never on a producer notification thread or a dedicated Rx thread pool.</p>
 *
 * <p>Host runtime staging: this does not yet expose Oreslang source
 * Task.map_parallel and does not prove guest type/effect/ownership safety.</p>
 */
public final class TaskMapParallel {
    private static final int MAX_INPUTS = 65_536;
    private static final int MAX_ADMISSIONS_PER_TURN = 32;

    private TaskMapParallel() { }

    private record Running<R>(int index, TaskHandle<R> handle) { }

    /**
     * The task's result may settle before all children are physically retired
     * on error/cancellation. getDone() is a separate scope+coordinator
     * physical-quiescence barrier; cancelling it cannot forge completion.
     */
    public static final class MapHandle<R> {
        private final TaskHandle<List<R>> coordinator;
        private final TaskScope scope;
        private final OresFuture<Void> done = new OresFuture<>(() -> false, () -> { });

        private MapHandle(TaskHandle<List<R>> coordinator, TaskScope scope) {
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
            this.scope = Objects.requireNonNull(scope, "scope");

            coordinator.result().whenCompleteRuntime((value, failure) -> {
                try {
                    if (failure == null) {
                        scope.seal();
                    } else {
                        scope.cancel("map_parallel coordinator failed or cancelled");
                    }
                } finally {
                    publishIfQuiescent();
                }
            });
            coordinator.getDone().whenCompleteRuntime(
                    (outcome, failure) -> publishIfQuiescent());
            scope.getDone().whenCompleteRuntime(
                    (ignored, failure) -> publishIfQuiescent());
            publishIfQuiescent();
        }

        private void publishIfQuiescent() {
            if (coordinator.getDone().isDone() && scope.getDone().isDone()) {
                done.completeFromRuntime(null);
            }
        }

        public OresFuture<List<R>> result() {
            return coordinator.result();
        }

        /** Neither the parent nor its owned children are physically active. */
        public OresFuture<Void> getDone() {
            return done;
        }

        public boolean cancel(String reason) {
            boolean cancelled = coordinator.cancel(reason);
            if (cancelled) scope.cancel(reason);
            return cancelled;
        }

        public boolean cancel() {
            return cancel("map_parallel cancelled");
        }
    }

    /**
     * Run an ordered mapping of at most 65536 items with 1..maxConcurrent
     * physically active children. Factory creation runs during a coordinator
     * scheduler turn and should only CONSTRUCT a lazy OresScheduler.Task<R>;
     * its body executes when its scope admits/schedules that child.
     *
     * <p>Output order is INPUT order even if children finish out of order.
     * On first observed child failure/cancellation, stop admitting, cancel
     * siblings and retain their memory permits until physical retirement.
     * A cancelled child is currently surfaced as a Failed composition with
     * a CancellationException; guest typed cancellation classification
     * requires later compiler/TaskScope integration.</p>
     */
    public static <T, R> MapHandle<R> mapLimit(
            OresScheduler scheduler, List<T> inputs, int maxConcurrent,
            Function<? super T, ? extends OresScheduler.Task<R>> factory) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(factory, "factory");
        List<T> items = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (maxConcurrent < 1 || maxConcurrent > MAX_INPUTS) {
            throw new IllegalArgumentException(
                    "maxConcurrent must be between 1 and " + MAX_INPUTS);
        }
        if (items.size() > MAX_INPUTS) {
            throw new IllegalArgumentException(
                    "map_parallel input size exceeds " + MAX_INPUTS);
        }
        TaskScope scope = scheduler.newTaskScope();

        OresScheduler.Task<List<R>> driver = new OresScheduler.Task<>() {
            private final Object[] ordered = new Object[items.size()];
            private final ArrayList<Running<R>> active = new ArrayList<>();
            private int nextIndex;
            private int completed;
            private boolean started;

            private void inspectRetiredChildren() throws Exception {
                for (int i = active.size() - 1; i >= 0; i--) {
                    Running<R> running = active.get(i);
                    OresFuture<TaskHandle.TaskOutcome<R>> physical =
                            running.handle().getDone();
                    if (!physical.isDone()) continue;
                    // Settled-read only: TaskHandle.getDone() has already
                    // verified that the source Task and descendants retired.
                    TaskHandle.TaskOutcome<R> outcome = physical.join();
                    active.remove(i);
                    if (outcome instanceof TaskHandle.Success<?> success) {
                        ordered[running.index()] = success.value();
                        completed++;
                    } else if (outcome instanceof TaskHandle.Failed<?> failed) {
                        throwOriginal(failed.failure());
                    } else if (outcome instanceof TaskHandle.Cancelled<?> cancelled) {
                        throw new java.util.concurrent.CancellationException(
                                "map_parallel child cancelled: " + cancelled.reason());
                    } else {
                        throw new IllegalStateException("unrecognized TaskOutcome");
                    }
                }
            }

            private OresScheduler.Step<List<R>> dispatch() throws Exception {
                OresScheduler.cancellationCheckpoint();
                inspectRetiredChildren();
                int admitted = 0;
                while (nextIndex < items.size() && active.size() < maxConcurrent) {
                    if (admitted++ == MAX_ADMISSIONS_PER_TURN) {
                        // Preserve cooperative fairness on a large fan-out.
                        return OresScheduler.cooperate();
                    }
                    int at = nextIndex++;
                    OresScheduler.Task<R> task = Objects.requireNonNull(
                            factory.apply(items.get(at)), "map factory returned null Task");
                    TaskHandle<R> child = scope.startTask(task);
                    active.add(new Running<>(at, child));
                }

                if (completed == items.size()) {
                    ArrayList<R> result = new ArrayList<>(ordered.length);
                    for (Object raw : ordered) {
                        @SuppressWarnings("unchecked")
                        R item = (R) raw;
                        result.add(Objects.requireNonNull(item,
                                "map_parallel child produced null; use Option<T>"));
                    }
                    return OresScheduler.done(List.copyOf(result));
                }

                if (active.isEmpty()) {
                    throw new IllegalStateException("map_parallel lost all live children");
                }

                ArrayList<OresFuture<? extends TaskHandle.TaskOutcome<R>>> pending =
                        new ArrayList<>(active.size());
                for (Running<R> running : active) {
                    pending.add(running.handle().getDone());
                }
                return OresScheduler.await(FutureCombinators.race(pending));
            }

            @Override
            public OresScheduler.Step<List<R>> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (!started) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "map_parallel started with noninitial resume");
                    }
                    started = true;
                } else if (resume.initial()) {
                    throw new IllegalStateException(
                            "map_parallel resumed with duplicate initial resume");
                }
                if (resume.failure() != null) {
                    throwOriginal(OresFuture.unwrap(resume.failure()));
                }
                return dispatch();
            }
        };

        final TaskHandle<List<R>> coordinator;
        try {
            coordinator = scheduler.startTracked(driver);
        } catch (RuntimeException | Error rejected) {
            try {
                scope.cancel("map_parallel coordinator admission rejected");
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != rejected) rejected.addSuppressed(cleanup);
            }
            throw rejected;
        }
        return new MapHandle<>(coordinator, scope);
    }

    /** Direct sequential mapping uses the same physical retirement semantics. */
    public static <T, R> MapHandle<R> mapSeries(
            OresScheduler scheduler, List<T> inputs,
            Function<? super T, ? extends OresScheduler.Task<R>> factory) {
        return mapLimit(scheduler, inputs, 1, factory);
    }

    private static void throwOriginal(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("nonexceptional Throwable in child Task", failure);
    }
}
