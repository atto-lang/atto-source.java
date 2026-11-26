package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host-only immutable i64 counted selection. Not compiler lowering. */
final class CountedSelectReadCursor {
    static final int MAX_COUNT = 1024;
    enum State { READY, WAITING, STOPPING, SUCCESS, CANCELLED, FAILED }
    static final class SelectExhausted extends IllegalStateException {
        SelectExhausted() { super("all counted selection sources exhausted"); }
    }
    private final Thread owner;
    private java.util.function.LongUnaryOperator projection = value -> value;
    private ArrayList<java.util.function.LongUnaryOperator> armProjections;
    private final Object taskDomain;
    private final ActorRuntime taskActorRuntime;
    private final ActorRuntime.ContinuationTarget taskActorTarget;
    private TaskOwnedLeases.Lease taskRoundLease;
    private final ActorRuntime actorRuntime;
    private final ActorRuntime.ContinuationTarget actorTarget;
    private final int count;
    private final ChannelRuntime.SelectPolicy policy;
    private final ArrayList<Long> values;
    private final ArrayList<ChannelRuntime.SelectCase> live = new ArrayList<>();
    private int cursor;
    private State state;
    private OresFuture<ChannelRuntime.SelectResult> active;
    private AtomicBoolean drained;
    private Throwable failure;
    private boolean driving;

    CountedSelectReadCursor(int count, List<ChannelRuntime.ReadChannel<Long>> sources,
            ChannelRuntime.SelectPolicy policy) {
        this(count, sources, List.of(), policy);
    }

    CountedSelectReadCursor(int count, List<ChannelRuntime.ReadChannel<Long>> sources,
            List<OresFuture<Long>> futures, ChannelRuntime.SelectPolicy policy) {
        this(count, sources, futures, policy, null);
    }

    static CountedSelectReadCursor actorOwned(ActorRuntime runtime, int count,
            List<ChannelRuntime.ReadChannel<Long>> sources,
            List<OresFuture<Long>> futures, ChannelRuntime.SelectPolicy policy) {
        return new CountedSelectReadCursor(count, sources, futures, policy,
                Objects.requireNonNull(runtime));
    }

    private CountedSelectReadCursor(int count, List<ChannelRuntime.ReadChannel<Long>> sources,
            List<OresFuture<Long>> futures, ChannelRuntime.SelectPolicy policy,
            ActorRuntime runtime) {
        this(count, policy, runtime);
        if (count == 0) return;
        Objects.requireNonNull(sources);
        Objects.requireNonNull(futures);
        long total = (long)sources.size() + futures.size();
        if (total == 0 || total > ChannelRuntime.MAX_SELECT_CASES)
            throw new IllegalArgumentException("invalid counted source count");
        for (var source : sources) live.add(ChannelRuntime.read(source));
        for (var future : futures) live.add(ChannelRuntime.await(future));
    }

    // Mixed cases must retain their original lexical order; splitting them
    // into channel and Future lists changes PRIORITY and FAIR arbitration.
    static CountedSelectReadCursor ordered(int count,
            List<? extends ChannelRuntime.SelectCase> cases,
            ChannelRuntime.SelectPolicy policy) {
        return new CountedSelectReadCursor(count, policy, null, cases);
    }

    static CountedSelectReadCursor actorOwnedOrdered(ActorRuntime runtime, int count,
            List<? extends ChannelRuntime.SelectCase> cases,
            ChannelRuntime.SelectPolicy policy) {
        return new CountedSelectReadCursor(count, policy, Objects.requireNonNull(runtime), cases);
    }

    private CountedSelectReadCursor(int count, ChannelRuntime.SelectPolicy policy,
            ActorRuntime runtime, List<? extends ChannelRuntime.SelectCase> cases) {
        this(count, policy, runtime, cases, false);
    }

    static CountedSelectReadCursor taskOwnedOrdered(int count,
            List<? extends ChannelRuntime.SelectCase> cases,
            ChannelRuntime.SelectPolicy policy) {
        return new CountedSelectReadCursor(count, policy, null, cases, true);
    }

    private CountedSelectReadCursor(int count, ChannelRuntime.SelectPolicy policy,
            ActorRuntime runtime, List<? extends ChannelRuntime.SelectCase> cases,
            boolean taskOwned) {
        this(count, policy, runtime, taskOwned);
        if (count == 0) return;
        Objects.requireNonNull(cases);
        int length = cases.size();
        if (length < 1 || length > ChannelRuntime.MAX_SELECT_CASES)
            throw new IllegalArgumentException("invalid counted source count");
        for (int i = 0; i < length; i++) {
            var candidate = cases.get(i);
            if (!(candidate instanceof ChannelRuntime.ReadCase<?> ||
                    candidate instanceof ChannelRuntime.FutureCase<?>))
                throw new IllegalArgumentException("counted selection requires read/Future cases");
            live.add(candidate);
        }
    }

    private CountedSelectReadCursor(int count, ChannelRuntime.SelectPolicy policy,
            ActorRuntime runtime) {
        this(count, policy, runtime, false);
    }

    private CountedSelectReadCursor(int count, ChannelRuntime.SelectPolicy policy,
            ActorRuntime runtime, boolean taskOwned) {
        if (count < 0 || count > MAX_COUNT)
            throw new IllegalArgumentException("SelectCountInvalid");
        if (taskOwned && (runtime != null || !TaskHandle.isCurrentTrackedTaskTurn()))
            throw new IllegalStateException("counted task cursor requires tracked Task turn");
        taskDomain = taskOwned ? Objects.requireNonNull(OresScheduler.currentTaskDomain()) : null;
        taskActorRuntime = taskOwned ? ActorRuntime.currentActorRuntime() : null;
        taskActorTarget = taskActorRuntime == null ? null : taskActorRuntime.captureCurrentContinuationTarget();
        actorRuntime = runtime;
        actorTarget = runtime == null ? null : runtime.captureCurrentContinuationTarget();
        owner = runtime == null && !taskOwned ? Thread.currentThread() : null;
        checkOwner();
        this.count = count;
        this.policy = Objects.requireNonNull(policy);
        values = new ArrayList<>(count);
        if (count == 0) {
            state = State.SUCCESS;
            return;
        }
        state = State.READY;
    }

    private void checkOwner() {
        if (driving || (taskDomain != null
                ? taskDomain != OresScheduler.currentTaskDomain() || !TaskHandle.isCurrentTrackedTaskTurn()
                    || ActorRuntime.currentActorRuntime() != taskActorRuntime
                    || (taskActorRuntime != null && !taskActorRuntime.ownsCountedCursorTurn(taskActorTarget))
                : actorRuntime == null ? Thread.currentThread() != owner
                : !actorRuntime.ownsCountedCursorTurn(actorTarget)))
            throw new IllegalStateException("counted read cursor requires its serialized owner");
    }

    State state() { checkOwner(); return state; }

    OresFuture<ChannelRuntime.SelectResult> beginRound() {
        if (actorRuntime != null || taskDomain != null)
            throw new IllegalStateException("owned cursor requires its retirement continuation");
        return beginRoundInternal();
    }

    void setTaskProjection(java.util.function.LongUnaryOperator projector) {
        checkOwner();
        if (taskDomain == null || state != State.READY || !values.isEmpty() || active != null)
            throw new IllegalStateException("counted projection requires initial tracked cursor");
        projection = Objects.requireNonNull(projector);
    }

    void setTaskArmProjections(List<? extends java.util.function.LongUnaryOperator> projectors) {
        checkOwner();
        if (taskDomain == null || state != State.READY || !values.isEmpty() || active != null
                || projectors.size() != live.size())
            throw new IllegalStateException("counted arm projections require initial tracked cursor and one projector per lexical arm");
        armProjections = new ArrayList<>(List.copyOf(projectors));
    }

    // Register cleanup and its physical receipt BEFORE publishing the round.
    // Abandoned Task retirement revokes only this selection, not its sources.
    // Producer callbacks publish host receipts, never execute guest code.
    OresFuture<Void> beginTaskRound() {
        checkOwner();
        if (taskDomain == null || state != State.READY)
            throw new IllegalStateException("task-owned cursor round not ready");
        var physical = new java.util.concurrent.CompletableFuture<Void>();
        var published = new java.util.concurrent.atomic.AtomicReference<OresFuture<ChannelRuntime.SelectResult>>();
        var lease = TaskOwnedLeases.register(() -> {
            var round = published.get();
            if (round != null) round.cancel(false);
        });
        try {
            TaskHandle.registerPhysicalSettlement(physical);
            var round = beginRoundInternal();
            published.set(round);
            taskRoundLease = lease;
            // Actor STOP also owns the physical round receipt. Task getDone
            // alone is not the actor arena finalization fence.
            if (taskActorRuntime != null)
                taskActorRuntime.ownTrackedCountedRound(taskActorTarget, round);
            var ready = new OresFuture<Void>(() -> false, () -> {});
            AtomicBoolean observed = drained;
            round.whenNotificationsDrainedRuntime(() -> {
                observed.set(true);
                Throwable failure = round.cancellationHookFailureFromRuntime();
                if (failure == null) physical.complete(null);
                else physical.completeExceptionally(failure);
                // Retire on the logical Task, not on this producer.
                ready.completeFromRuntime(null);
            });
            return ready;
        } catch (RuntimeException | Error failed) {
            var round = published.get();
            if (round == null) physical.complete(null);
            else {
                round.whenNotificationsDrainedRuntime(() -> {
                    Throwable hookFailure = round.cancellationHookFailureFromRuntime();
                    if (hookFailure == null) physical.complete(null);
                    else physical.completeExceptionally(hookFailure);
                });
                round.cancel(false);
            }
            lease.disarmWithoutRelease();
            throw failed;
        }
    }

    void beginActorRound(Runnable retirement) {
        checkOwner();
        if (actorRuntime == null)
            throw new IllegalStateException("not an actor-owned cursor");
        Objects.requireNonNull(retirement);
        var round = beginRoundInternal();
        AtomicBoolean receipt = drained;
        actorRuntime.enqueueCountedRoundRetirement(round, actorTarget, () -> {
            // Only this authenticated retirement turn may observe the actor
            // receipt. Never depend on notification listener ordering.
            receipt.set(true);
            retirement.run();
        });
    }

    private OresFuture<ChannelRuntime.SelectResult> beginRoundInternal() {
        checkOwner();
        if (state != State.READY) throw new IllegalStateException("round not ready");
        driving = true;
        try {
            var set = ChannelRuntime.SelectSet.countedReads(live, cursor);
            drained = new AtomicBoolean();
            active = set.selectAsync(policy);
            if (actorRuntime == null) {
                AtomicBoolean receipt = drained;
                active.whenNotificationsDrainedRuntime(() -> receipt.set(true));
            }
            state = State.WAITING;
            return active;
        } catch (RuntimeException exception) {
            fail(exception);
            throw exception;
        } finally { driving = false; }
    }

    // A graceful EOF is a retirement receipt, not an event. A completed
    // Future contributes exactly one event across all identity aliases.
    // Preserve the next lexical FAIR source even when aliases disappear.
    private void advance(int index, boolean removeAliases) {
        var winner = live.get(index);
        int oldSize = live.size();
        int next = (index + 1) % oldSize;
        if (!removeAliases) {
            cursor = next;
            return;
        }
        var remaining = new ArrayList<ChannelRuntime.SelectCase>(oldSize);
        var remainingProjections = armProjections == null ? null :
                new ArrayList<java.util.function.LongUnaryOperator>(oldSize);
        int nextIndex = -1;
        for (int k = 0; k < oldSize; k++) {
            int indexAfterWinner = (next + k) % oldSize;
            if (!sameSource(live.get(indexAfterWinner), winner)) {
                nextIndex = indexAfterWinner;
                break;
            }
        }
        // The same case object may appear more than once. Preserve its
        // lexical occurrence, not the first matching object identity.
        cursor = 0;
        for (int i = 0; i < oldSize; i++) {
            var candidate = live.get(i);
            if (!sameSource(candidate, winner)) {
                if (i < nextIndex) cursor++;
                remaining.add(candidate);
                if (remainingProjections != null) remainingProjections.add(armProjections.get(i));
            }
        }
        live.clear();
        live.addAll(remaining);
        armProjections = remainingProjections;
    }

    private static boolean sameSource(ChannelRuntime.SelectCase a, ChannelRuntime.SelectCase b) {
        if (a instanceof ChannelRuntime.ReadCase<?> x && b instanceof ChannelRuntime.ReadCase<?> y)
            return x.channel() == y.channel();
        if (a instanceof ChannelRuntime.FutureCase<?> x && b instanceof ChannelRuntime.FutureCase<?> y)
            return x.future() == y.future();
        return false;
    }

    private void fail(Throwable exception) {
        failure = exception instanceof CompletionException && exception.getCause() != null
                ? exception.getCause() : exception;
        values.clear();
        live.clear();
        state = State.FAILED;
    }

    boolean retireRound() {
        checkOwner();
        if (state != State.WAITING && state != State.STOPPING)
            throw new IllegalStateException("no active round");
        if (!drained.get() || !active.isDone()) return false;
        boolean stopping = state == State.STOPPING || active.isCancelled();
        driving = true;
        try {
            Throwable physicalFailure = active.cancellationHookFailureFromRuntime();
            if (physicalFailure != null) {
                fail(physicalFailure);
            } else if (stopping) {
                values.clear();
                live.clear();
                state = State.CANCELLED;
            } else {
                var result = active.join();
                if (result.operation() == ChannelRuntime.SelectOperation.READ
                        && result.value() == ChannelRuntime.CountedReadEnd.EOF) {
                    advance(result.index(), true);
                } else {
                    if ((result.operation() != ChannelRuntime.SelectOperation.READ
                            && result.operation() != ChannelRuntime.SelectOperation.FUTURE)
                            || !(result.value() instanceof Long number))
                        throw new IllegalStateException("counted scalar selection received non-i64 result");
                    // Projections track lexical occurrences, not source identity.
                    var projector = armProjections == null ? projection : armProjections.get(result.index());
                    values.add(projector.applyAsLong(number));
                    advance(result.index(), result.operation() == ChannelRuntime.SelectOperation.FUTURE);
                }
                if (values.size() == count) {
                    live.clear();
                    state = State.SUCCESS;
                } else if (live.isEmpty()) {
                    fail(new SelectExhausted());
                } else state = State.READY;
            }
        } catch (RuntimeException | Error exception) {
            fail(exception);
        } finally {
            driving = false;
            if (state != State.READY) armProjections = null;
            active = null;
            drained = null;
            if (taskRoundLease != null) {
                taskRoundLease.disarmWithoutRelease();
                taskRoundLease = null;
            }
        }
        return true;
    }

    boolean cancel() {
        checkOwner();
        if (state == State.READY) {
            values.clear();
            live.clear();
            armProjections = null;
            state = State.CANCELLED;
            return true;
        }
        if (state != State.WAITING) return false;
        state = State.STOPPING;
        driving = true;
        try {
            active.cancel(false);
        } finally { driving = false; }
        return true;
    }

    List<Long> result() {
        checkOwner();
        if (state != State.SUCCESS) throw new IllegalStateException("batch not successful");
        return List.copyOf(values);
    }

    Throwable failure() {
        checkOwner();
        if (state != State.FAILED) throw new IllegalStateException("batch not failed");
        return failure;
    }
}
