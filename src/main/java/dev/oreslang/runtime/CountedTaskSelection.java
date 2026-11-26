package dev.oreslang.runtime;

import java.util.List;
import java.util.function.LongUnaryOperator;

/**
 * Compiler-owned, tracked scheduler bridge for scalar counted selection.
 * Projection is invoked only by the authenticated owner retiring a drained
 * round; producer threads cannot invoke source code. Not a guest API.
 */
public final class CountedTaskSelection {
    private final CountedSelectReadCursor cursor;

    public CountedTaskSelection(int count, List<? extends ChannelRuntime.SelectCase> cases,
            ChannelRuntime.SelectPolicy policy, LongUnaryOperator projection) {
        cursor = CountedSelectReadCursor.taskOwnedOrdered(count, cases, policy);
        if (count != 0) cursor.setTaskProjection(projection);
    }

    /** One checked projection for each lexical arm; alias retirement preserves alignment. */
    public CountedTaskSelection(int count, List<? extends ChannelRuntime.SelectCase> cases,
            ChannelRuntime.SelectPolicy policy, List<? extends LongUnaryOperator> projections) {
        cursor = CountedSelectReadCursor.taskOwnedOrdered(count, cases, policy);
        if (count != 0) cursor.setTaskArmProjections(projections);
    }

    public OresFuture<Void> begin() { return cursor.beginTaskRound(); }

    public boolean retire() {
        if (!cursor.retireRound())
            throw new IllegalStateException("counted round has not physically retired");
        return switch (cursor.state()) {
            case READY -> false;
            case SUCCESS -> true;
            case FAILED -> throwFailure(cursor.failure());
            default -> throw new IllegalStateException("counted selection cancelled");
        };
    }

    private static boolean throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new java.util.concurrent.CompletionException(failure);
    }

    public List<Long> result() { return cursor.result(); }
}
