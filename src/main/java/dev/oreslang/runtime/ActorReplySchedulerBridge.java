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

    /**
     * First host-executable actor->actor unary path: reserve an opaque slot,
     * bind its nonblocking Future to the CALLER's logical actor lane, and
     * enqueue the data-only message with a VM-owned sidecar reply binding.
     *
     * Handler-side response uses ActorContext.replyCurrentCall(value). The
     * current source compiler still lacks run()/ref.request() lowering.
     */
    /** Idempotent caller/callee-incarnation teardown hook for an admitted
     * service actor. Retiring the endpoint fails all outstanding local calls.
     * This hook runs in runtime plumbing, never as guest code. */
    static void retireWhenDone(
            GenerationalReplyRegistry registry,
            GenerationalReplyRegistry.Endpoint endpoint,
            ActorRuntime.ActorRef<?> actor) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(actor, "actor");
        actor.done().whenCompleteRuntime((ignored, failure) -> {
            try {
                registry.retire(endpoint);
            } catch (SecurityException alreadyRetired) {
                // A registry shutdown or earlier incarnation retirement
                // already resolved all affected call futures.
            }
        });
    }

    static <M, R> OresFuture<R> requestCurrentActor(
            ActorRuntime runtime,
            GenerationalReplyRegistry registry,
            GenerationalReplyRegistry.Endpoint caller,
            GenerationalReplyRegistry.Endpoint callee,
            ActorRuntime.ActorRef<M> destination,
            M message,
            Class<R> responseType,
            Duration timeout,
            ScheduledExecutorService timer) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(caller, "caller");
        if (caller.role() != GenerationalReplyRegistry.Role.ACTOR) {
            throw new SecurityException("a supervisor application endpoint cannot impersonate an actor caller");
        }
        if (runtime.currentActorId().filter(destination.id()::equals).isPresent()) {
            throw new IllegalArgumentException("actor cannot synchronously request itself");
        }
        // captureCurrentContinuationTarget also proves the caller currently
        // executes inside this runtime's admitted actor lease.
        ActorRuntime.ContinuationTarget owner = runtime.captureCurrentContinuationTarget();
        if (!runtime.currentActorId().orElseThrow().equals(owner.actorId())) {
            throw new SecurityException("caller actor execution identity changed");
        }
        GenerationalReplyRegistry.Ticket<R> ticket =
                registry.reserve(caller, callee, responseType);
        OresFuture<R> future = bindCurrentActor(
                runtime, ticket, caller, timeout, timer, (value, failure) -> { });
        if (ticket.state() != GenerationalReplyRegistry.State.PENDING) {
            return future;
        }
        try {
            runtime.sendCall(destination, message, ticket, callee);
        } catch (RuntimeException | Error admissionFailure) {
            ticket.failAdmission(caller, admissionFailure);
        }
        return future;
    }

    /**
     * Supervisor APPLICATION service -> ordinary actor, using the same
     * data-plane call/reply protocol. It does NOT have supervisor CONTROL
     * authority. The supplied app actor handle is checked against the actor
     * currently holding the physical logical execution lease.
     */
    static <M, R> OresFuture<R> requestCurrentSupervisorApplication(
            ActorRuntime runtime,
            GenerationalReplyRegistry registry,
            GenerationalReplyRegistry.Endpoint supervisorApplication,
            ActorRuntime.ActorRef<?> supervisorServiceActor,
            GenerationalReplyRegistry.Endpoint callee,
            ActorRuntime.ActorRef<M> destination,
            M message,
            Class<R> responseType,
            Duration timeout,
            ScheduledExecutorService timer) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(supervisorApplication, "supervisor application endpoint");
        Objects.requireNonNull(supervisorServiceActor, "supervisor application service");
        if (supervisorApplication.role() !=
                GenerationalReplyRegistry.Role.SUPERVISOR_APPLICATION) {
            throw new SecurityException("supervisor application role required");
        }
        ActorRuntime.ContinuationTarget active = runtime.captureCurrentContinuationTarget();
        if (!supervisorServiceActor.id().equals(active.actorId())) {
            throw new SecurityException("supervisor app call attempted outside owning service actor");
        }
        if (destination.id().equals(supervisorServiceActor.id())) {
            throw new IllegalArgumentException("supervisor application cannot call itself");
        }
        GenerationalReplyRegistry.Ticket<R> ticket =
                registry.reserve(supervisorApplication, callee, responseType);
        OresFuture<R> future = bindCurrentActor(
                runtime, ticket, supervisorApplication, timeout, timer, (value, failure) -> { });
        if (ticket.state() != GenerationalReplyRegistry.State.PENDING) return future;
        try {
            runtime.sendCall(destination, message, ticket, callee);
        } catch (RuntimeException | Error admissionFailure) {
            ticket.failAdmission(supervisorApplication, admissionFailure);
        }
        return future;
    }

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
        final boolean invalidTimeout = timeout.isZero() || timeout.isNegative();

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

        if (invalidTimeout) {
            ticket.failAdmission(caller,
                    new IllegalArgumentException("request deadline must be positive"));
            return future;
        }
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
