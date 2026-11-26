package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Host-only counted-selection driver for immutable i64 reads. A serialized,
 * non-migrating owner drives rounds; the existing SelectSet is the sole
 * channel arbiter. A scheduler may await the round Future and then retire it.
 * No guest execution, arbitrary callbacks, implicit ownership conversion or
 * Future<Array<T>> source lowering is admitted by this class.
 */
final class CountedSelectReadCursor {
    static final int MAX_COUNT = 1024;
    enum State { READY, WAITING, STOPPING, SUCCESS, CANCELLED, FAILED }
    private final Thread owner = Thread.currentThread();
    private final int count;
    private final ChannelRuntime.SelectSet set;
    private final ChannelRuntime.SelectPolicy policy;
    private final ArrayList<Long> values;
    private State state;
    private OresFuture<ChannelRuntime.SelectResult> active;
    private AtomicBoolean drained;
    private Throwable failure;
    private boolean driving;

    CountedSelectReadCursor(int count, List<ChannelRuntime.ReadChannel<Long>> sources,
            ChannelRuntime.SelectPolicy policy) {
        if (count < 0 || count > MAX_COUNT)
            throw new IllegalArgumentException("SelectCountInvalid");
        this.count = count;
        this.policy = Objects.requireNonNull(policy);
        values = new ArrayList<>(count);
        if (count == 0) {
            set = null;
            state = State.SUCCESS;
        } else {
            Objects.requireNonNull(sources);
            if (sources.isEmpty() || sources.size() > ChannelRuntime.MAX_SELECT_CASES)
                throw new IllegalArgumentException("invalid counted read source count");
            var cases = new ArrayList<ChannelRuntime.SelectCase>(sources.size());
            for (var source : sources) cases.add(ChannelRuntime.read(source));
            set = new ChannelRuntime.SelectSet(cases);
            state = State.READY;
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner || driving)
            throw new IllegalStateException("counted read cursor requires its serialized host owner");
    }

    State state() { checkOwner(); return state; }

    // Never call until the previous round has been physically retired.
    OresFuture<ChannelRuntime.SelectResult> beginRound() {
        checkOwner();
        if (state != State.READY) throw new IllegalStateException("round not ready");
        driving = true;
        try {
            state = State.WAITING;
            drained = new AtomicBoolean();
            active = set.selectAsync(policy);
            AtomicBoolean receipt = drained;
            active.whenNotificationsDrainedRuntime(() -> receipt.set(true));
            return active;
        } finally { driving = false; }
    }

    // Returns false if the owner has not yet observed physical notification
    // retirement. The owner must schedule/resume, not spin. No join on a pending
    // Future and no callback execution under an application lock.
    boolean retireRound() {
        checkOwner();
        if (state != State.WAITING && state != State.STOPPING)
            throw new IllegalStateException("no active round");
        if (!drained.get() || !active.isDone()) return false;
        boolean stopping = state == State.STOPPING || active.isCancelled();
        try {
            if (stopping) {
                values.clear();
                state = State.CANCELLED;
            } else {
                var result = active.join();
                if (result.operation() != ChannelRuntime.SelectOperation.READ
                        || !(result.value() instanceof Long number))
                    throw new IllegalStateException("counted scalar read received non-i64 result");
                values.add(number);
                state = values.size() == count ? State.SUCCESS : State.READY;
            }
        } catch (RuntimeException exception) {
            // The underlying single-round arbiter reports a selected closed
            // source as failure. This restricted profile fails the whole batch
            // rather than falsely claiming all-source retirement/exhaustion.
            failure = exception instanceof CompletionException && exception.getCause() != null
                    ? exception.getCause() : exception;
            values.clear();
            state = State.FAILED;
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
            state = State.CANCELLED;
            return true;
        }
        if (state != State.WAITING) return false;
        state = State.STOPPING;
        driving = true;
        try {
            // Cancellation may lose to a committed read. In that case the
            // committed scalar is discarded only after physical retirement;
            // channel ownership is never rolled back.
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
