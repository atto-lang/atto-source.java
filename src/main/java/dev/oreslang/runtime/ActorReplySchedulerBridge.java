package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Runtime-only bridge from a generational unary reply ticket to an actor's
 * single-writer continuation mailbox. No guest callback executes on the
 * responding actor's carrier, the timeout thread, or the host completion thread.
 *
 * This layer intentionally does NOT perform mailbox enqueue/correlation:
 * the compiler/VM must pair the ticket with the exact admitted message
 * envelope, outside the actor's ordinary transport value graph.
 */
public final class ActorReplySchedulerBridge {
    private ActorReplySchedulerBridge() { }

    static <R> OresFuture<R> bindCurrentActor(
            ActorRuntime runtime,
            GenerationalReplyRegistry.Ticket<R> ticket,
            GenerationalReplyRegistry.Endpoint caller,
            Duration timeout,
            ScheduledExecutorService timer,
            BiConsumer<? super R, ? super Throwable> continuation) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(continuation, "continuation");
        if (timeout.isZero() || timeout.isNegative()) {
            ticket.failAdmission(caller,
                    new IllegalArgumentException("request deadline must be positive"));
            return OresFuture.from(ticket.result());
        }

        final ActorRuntime.ContinuationTarget owner = runtime.captureCurrentContinuationTarget();
        // Atomically claim the *producer* ticket before reporting cancellation
        // on the language Future. publishCancellation() runs after Future CAS.
        OresFuture<R> future = new OresFuture<>(
                () -> ticket.claimCancel(caller), ticket::publishCancellation);
        runtime.enqueueOnCompletion(future, owner, continuation);

        final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        // Register settlement BEFORE deadline admission: even timer rejection
        // and immediate cancellation must complete the outer Future.
        ticket.result().whenComplete((value, failure) -> {
            ScheduledFuture<?> deadline = scheduled.get();
            if (deadline != null) deadline.cancel(false);
            if (failure == null) {
                future.completeFromRuntime(value);
            } else {
                Throwable cause = OresFuture.unwrap(failure);
                future.failFromRuntime(cause == null ? new CompletionException(failure) : cause);
            }
        });

        final long delay;
        try {
            delay = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            ticket.failAdmission(caller, new IllegalArgumentException("deadline overflow", overflow));
            return future;
        }
        try {
            ScheduledFuture<?> deadline =
                    timer.schedule(() -> ticket.timeout(caller), delay, TimeUnit.NANOSECONDS);
            scheduled.set(deadline);
            if (ticket.state() != GenerationalReplyRegistry.State.PENDING) {
                deadline.cancel(false);
            }
        } catch (RuntimeException schedulingFailed) {
            ticket.failAdmission(caller, schedulingFailed);
        }
        return future;
    }
}
