package dev.oreslang.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Host-only terminal CONTROL bridge for physical virtual-actor incarnations.
 *
 * The VM injects its existing bounded CONTROL dispatcher; this bridge neither
 * creates carrier threads nor executes policy inline during actor retirement.
 * Exactly one immutable registration exists per physical ActorId, admitted
 * before the activation becomes visible. The runtime generation fence owns
 * every decision lease and the restart gate; this class owns no generations.
 *
 * These receipts are not yet durable and do not carry guest error messages,
 * payloads, actor heap references, or cancellation authority. A rejected
 * dispatch, closed group, or failed asynchronous policy quarantines the
 * logical identity until a separately reviewed recovery protocol exists.
 */
final class VirtualActorTerminalControl {
    enum Kind { TERMINAL_FAILURE, CANCELLED }

    record Notice(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            VirtualActorActivationRegistry.Identity identity,
            long generation,
            ActorRuntime.ActorId actorId,
            Kind kind,
            String failureClass) {
        Notice {
            Objects.requireNonNull(supervisorId, "supervisorId");
            Objects.requireNonNull(groupId, "groupId");
            VirtualActorRuntimeGenerationFence.validate(identity);
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(failureClass, "failureClass");
            if (generation <= 0 || failureClass.isBlank() || failureClass.length() > 256)
                throw new IllegalArgumentException("invalid virtual terminal receipt");
        }
    }

    private record Binding(
            ActorRuntime.ActorGroup group,
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId groupId,
            VirtualActorActivationRegistry.Identity identity,
            long generation,
            ActorRuntime.ActorId actorId,
            Function<Notice, CompletionStage<Void>> handler) { }

    private final Object monitor = new Object();
    private final int capacity;
    private final ActorRuntime.ControlDispatcher dispatcher;
    private final VirtualActorRuntimeGenerationFence fences;
    private final Map<ActorRuntime.ActorId, Binding> bindings = new HashMap<>();

    VirtualActorTerminalControl(
            int capacity,
            ActorRuntime.ControlDispatcher dispatcher,
            VirtualActorRuntimeGenerationFence fences) {
        if (capacity < 1) throw new IllegalArgumentException("terminal CONTROL capacity");
        this.capacity = capacity;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.fences = Objects.requireNonNull(fences, "fences");
    }

    void admit(ActorRuntime.ActorGroup group,
               ActorRuntime.SupervisorId supervisor,
               ActorRuntime.ActorGroupId groupId,
               VirtualActorActivationRegistry.Identity identity,
               long generation,
               ActorRuntime.ActorId actorId,
               Function<Notice, CompletionStage<Void>> handler) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(supervisor, "supervisor");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(handler, "handler");
        VirtualActorRuntimeGenerationFence.validate(identity);
        if (generation <= 0)
            throw new IllegalArgumentException("virtual actor generation must be positive");
        Binding binding = new Binding(
                group, supervisor, groupId, identity, generation, actorId, handler);
        synchronized (monitor) {
            if (bindings.size() >= capacity)
                throw new IllegalStateException("terminal CONTROL admission limit exceeded");
            if (bindings.putIfAbsent(actorId, binding) != null)
                throw new IllegalStateException("physical worker already bound to terminal CONTROL");
        }
    }

    /** Only for rollback when the generation bind failed before publication. */
    void discardUnbound(ActorRuntime.ActorId actorId) {
        synchronized (monitor) {
            bindings.remove(actorId);
        }
    }

    boolean registered(ActorRuntime.ActorId actorId) {
        synchronized (monitor) {
            return bindings.containsKey(actorId);
        }
    }

    /**
     * Called after exact physical retirement and generation-fence publication.
     * It only enqueues host CONTROL work; no guest handler executes here.
     * The registration is removed once, so duplicate retirement never
     * produces a second handler invocation.
     */
    void onRetired(ActorRuntime.ActorId actorId, Throwable cause) {
        Binding binding;
        synchronized (monitor) {
            binding = bindings.remove(actorId);
        }
        if (binding == null || cause == null) return; // Normal passivation.
        Kind kind = cause instanceof CancellationException
                ? Kind.CANCELLED : Kind.TERMINAL_FAILURE;
        Notice notice = new Notice(
                binding.supervisor(), binding.groupId(), binding.identity(),
                binding.generation(), actorId, kind, cause.getClass().getName());
        Thread retiringThread = Thread.currentThread();
        try {
            OresFuture<Void> enqueued = Objects.requireNonNull(
                    dispatcher.execute(() -> {
                        // An inline or actor-carrier dispatcher is not a
                        // CONTROL dispatcher: fail closed, do not invoke policy.
                        if (Thread.currentThread() == retiringThread
                                || ActorRuntime.isActorCarrierThread()
                                || ActorRuntime.inActorExecution()) {
                            quarantine(binding);
                            return;
                        }
                        executeOnControl(binding, notice);
                    }), "CONTROL dispatcher returned null Future");
            enqueued.whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) quarantine(binding);
            });
        } catch (RuntimeException | Error admissionFailure) {
            quarantine(binding);
        }
    }

    private void executeOnControl(Binding binding, Notice notice) {
        VirtualActorRuntimeGenerationFence.TerminalDecisionLease lease;
        try {
            lease = binding.group().beginVirtualActorTerminalDecision(
                    notice.identity(), notice.generation(), notice.actorId());
        } catch (RuntimeException | Error rejected) {
            quarantine(binding);
            return;
        }

        CompletionStage<Void> completion;
        try {
            completion = Objects.requireNonNull(
                    binding.handler().apply(notice), "terminal CONTROL handler returned null stage");
        } catch (RuntimeException | Error handlerFailure) {
            lease.quarantine();
            lease.close();
            return;
        }
        try {
            completion.whenComplete((ignored, error) -> {
                if (error != null) lease.quarantine();
                lease.close(); // Generation is fenced until async completion.
            });
        } catch (RuntimeException | Error registrationFailure) {
            lease.quarantine();
            lease.close();
        }
    }

    private void quarantine(Binding binding) {
        fences.failTerminalDelivery(
                binding.supervisor(), binding.groupId(), binding.identity(),
                binding.generation(), binding.actorId());
    }
}
