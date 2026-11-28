package dev.oreslang.runtime;

import java.util.List;

/**
 * Trusted root-host aggregate bridge over the scalar counted cursor.
 * One serialized host owner pumps; no guest code or actor continuation is
 * invoked by a producer. Not an Array<T> guest lowering or actor STOP API.
 */
final class CountedSelectBatch {
    private final Thread owner = Thread.currentThread();
    private final CountedSelectReadCursor cursor;
    private final OresFuture<List<Long>> completion;
    private boolean driving;

    CountedSelectBatch(int count, List<? extends ChannelRuntime.SelectCase> cases,
            ChannelRuntime.SelectPolicy policy) {
        cursor = CountedSelectReadCursor.ordered(count, cases, policy);
        completion = new OresFuture<>(this::mayCancel, this::cancelOnOwner, () -> {}, true);
        publishIfTerminal();
    }

    private boolean mayCancel() {
        return Thread.currentThread() == owner && !driving;
    }

    private void enter() {
        if (!mayCancel())
            throw new IllegalStateException("counted batch requires serialized host owner");
        driving = true;
    }

    OresFuture<List<Long>> completion() { return completion; }

    // At most one registration/retirement per owner step. A false return
    // means no progress, not terminal success; owner must drive again after
    // readiness. Never spin or await on an IO notification thread.
    boolean pump() {
        enter();
        try {
            if (completion.isDone()) return false;
            return switch (cursor.state()) {
                case READY -> {
                    try {
                        cursor.beginRound();
                    } catch (RuntimeException failure) {
                        // beginRound has already recorded the failed cursor.
                        publishIfTerminal();
                        if (!completion.isDone()) throw failure;
                    }
                    yield true;
                }
                case WAITING, STOPPING -> {
                    boolean retired = cursor.retireRound();
                    if (retired) publishIfTerminal();
                    yield retired;
                }
                case SUCCESS, CANCELLED, FAILED -> {
                    publishIfTerminal();
                    yield true;
                }
            };
        } finally { driving = false; }
    }

    private void cancelOnOwner() {
        enter();
        try {
            cursor.cancel();
            // If a round was in flight, its drain must be observed before
            // publishing cancellation. READY cancellation has no round.
            if (cursor.state() == CountedSelectReadCursor.State.STOPPING)
                cursor.retireRound();
            publishIfTerminal();
        } finally { driving = false; }
    }

    private void publishIfTerminal() {
        switch (cursor.state()) {
            case SUCCESS -> completion.completeFromRuntime(cursor.result());
            case CANCELLED -> {
                if (completion.deferredCancellationCause() == null)
                    completion.failFromRuntime(new IllegalStateException("counted round cancelled without batch request"));
                else completion.completeDeferredCancellationFromRuntime();
            }
            case FAILED -> {
                if (completion.deferredCancellationCause() != null)
                    completion.failDeferredCleanupFromRuntime(cursor.failure());
                else completion.failFromRuntime(cursor.failure());
            }
            default -> { }
        }
    }
}
