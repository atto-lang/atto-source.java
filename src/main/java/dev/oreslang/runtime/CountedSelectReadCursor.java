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
        if (count < 0 || count > MAX_COUNT)
            throw new IllegalArgumentException("SelectCountInvalid");
        actorRuntime = runtime;
        actorTarget = runtime == null ? null : runtime.captureCurrentContinuationTarget();
        owner = runtime == null ? Thread.currentThread() : null;
        checkOwner();
        this.count = count;
        this.policy = Objects.requireNonNull(policy);
        values = new ArrayList<>(count);
        if (count == 0) {
            state = State.SUCCESS;
            return;
        }
        Objects.requireNonNull(sources);
        Objects.requireNonNull(futures);
        long total = (long)sources.size() + futures.size();
        if (total == 0 || total > ChannelRuntime.MAX_SELECT_CASES)
            throw new IllegalArgumentException("invalid counted source count");
        for (var source : sources) live.add(ChannelRuntime.read(source));
        for (var future : futures) live.add(ChannelRuntime.await(future));
        state = State.READY;
    }

    private void checkOwner() {
        if (driving || (actorRuntime == null
                ? Thread.currentThread() != owner
                : !actorRuntime.ownsCountedCursorTurn(actorTarget)))
            throw new IllegalStateException("counted read cursor requires its serialized owner");
    }

    State state() { checkOwner(); return state; }

    OresFuture<ChannelRuntime.SelectResult> beginRound() {
        if (actorRuntime != null)
            throw new IllegalStateException("actor-owned cursor requires retirement continuation");
        return beginRoundInternal();
    }

    void beginActorRound(Runnable retirement) {
        checkOwner();
        if (actorRuntime == null)
            throw new IllegalStateException("not an actor-owned cursor");
        Objects.requireNonNull(retirement);
        var round = beginRoundInternal();
        actorRuntime.enqueueCountedRoundRetirement(round, actorTarget, retirement);
    }

    private OresFuture<ChannelRuntime.SelectResult> beginRoundInternal() {
        checkOwner();
        if (state != State.READY) throw new IllegalStateException("round not ready");
        driving = true;
        try {
            var set = ChannelRuntime.SelectSet.countedReads(live, cursor);
            drained = new AtomicBoolean();
            active = set.selectAsync(policy);
            AtomicBoolean receipt = drained;
            active.whenNotificationsDrainedRuntime(() -> receipt.set(true));
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
        ChannelRuntime.SelectCase nextCase = null;
        for (int k = 0; k < oldSize; k++) {
            var candidate = live.get((next + k) % oldSize);
            if (!sameSource(candidate, winner)) { nextCase = candidate; break; }
        }
        for (var candidate : live)
            if (!sameSource(candidate, winner)) remaining.add(candidate);
        live.clear();
        live.addAll(remaining);
        cursor = 0;
        for (int i = 0; i < live.size(); i++)
            if (live.get(i) == nextCase) { cursor = i; break; }
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
        try {
            if (stopping) {
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
                    values.add(number);
                    advance(result.index(), result.operation() == ChannelRuntime.SelectOperation.FUTURE);
                }
                if (values.size() == count) {
                    live.clear();
                    state = State.SUCCESS;
                } else if (live.isEmpty()) {
                    fail(new SelectExhausted());
                } else state = State.READY;
            }
        } catch (RuntimeException exception) {
            fail(exception);
        } finally {
            active = null;
            drained = null;
        }
        return true;
    }

    boolean cancel() {
        checkOwner();
        if (state == State.READY) {
            values.clear();
            live.clear();
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
