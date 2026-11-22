package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private record Binding(
            ActorRuntime.ActorGroup group,
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId groupId,
            VirtualActorActivationRegistry.Identity identity,
            long generation,
            ActorRuntime.ActorId actorId,
            Function<ActorRuntime.VirtualActorTerminalNotice, CompletionStage<Void>> handler) { }

    private static final class Delivery {
        final Binding binding;
        final ActorRuntime.VirtualActorTerminalNotice notice;
        boolean dispatched;

        Delivery(Binding binding, ActorRuntime.VirtualActorTerminalNotice notice) {
            this.binding = binding;
            this.notice = notice;
        }
    }

    private static final class Lane {
        final ArrayDeque<Delivery> waiting = new ArrayDeque<>();
        Delivery current;
        boolean active;
    }

    private final Object monitor = new Object();
    private final int capacity;
    private final ActorRuntime.ControlDispatcher dispatcher;
    private final VirtualActorRuntimeGenerationFence fences;
    private final Map<ActorRuntime.ActorId, Binding> bindings = new HashMap<>();
    private final Map<ActorRuntime.SupervisorId, Lane> lanes = new HashMap<>();
    // Bounds queued + executing CONTROL receipts, including suspended stages.
    private int outstanding;
    private final ThreadLocal<Boolean> submitting = new ThreadLocal<>();

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
               Function<ActorRuntime.VirtualActorTerminalNotice, CompletionStage<Void>> handler) {
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

    /**
     * A failed physical cleanup is not a terminal receipt. Discard any
     * captured host callback to avoid retaining its closure indefinitely;
     * the ledger deliberately keeps the old physical binding quarantined.
     */
    void discardIncomplete(ActorRuntime.ActorId actorId) {
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
     * No handler executes here; registrations are consumed exactly once.
     * A single-flight lane per supervisor identity prevents callback
     * reentrancy across different ActorGroups and suspended CompletionStages.
     * Unrelated supervisors have independent bounded lanes.
     */
    void onRetired(ActorRuntime.ActorId actorId, Throwable cause) {
        Binding binding;
        synchronized (monitor) {
            binding = bindings.remove(actorId);
        }
        if (binding == null || cause == null) return; // Graceful passivation.
        ActorRuntime.VirtualActorTerminalKind kind = cause instanceof CancellationException
                ? ActorRuntime.VirtualActorTerminalKind.CANCELLED : ActorRuntime.VirtualActorTerminalKind.TERMINAL_FAILURE;
        final ActorRuntime.VirtualActorTerminalNotice notice;
        try {
            notice = new ActorRuntime.VirtualActorTerminalNotice(
                    binding.supervisor(), binding.groupId(), binding.identity(),
                    binding.generation(), actorId, kind, cause.getClass().getName());
        } catch (VirtualMachineError | ThreadDeath fatal) {
            quarantine(binding);
            throw fatal;
        } catch (RuntimeException | Error invalidNotice) {
            quarantine(binding);
            return;
        }
        Lane toSchedule = null;
        boolean rejected = false;
        synchronized (monitor) {
            if (outstanding >= capacity) {
                rejected = true;
            } else {
                Lane lane = lanes.computeIfAbsent(binding.supervisor(), ignored -> new Lane());
                lane.waiting.addLast(new Delivery(binding, notice));
                outstanding++;
                if (!lane.active) {
                    lane.active = true;
                    toSchedule = lane;
                }
            }
        }
        if (rejected) quarantine(binding);
        else if (toSchedule != null) scheduleNext(binding.supervisor(), toSchedule);
    }

    private void scheduleNext(ActorRuntime.SupervisorId supervisorId, Lane lane) {
        Delivery delivery;
        synchronized (monitor) {
            if (lanes.get(supervisorId) != lane) return; // Already failed/quarantined.
            delivery = lane.waiting.pollFirst();
            if (delivery == null) {
                lane.active = false;
                lanes.remove(supervisorId, lane);
                return;
            }
            lane.current = delivery;
        }

        try {
            submitting.set(Boolean.TRUE);
            OresFuture<Void> accepted = Objects.requireNonNull(
                    dispatcher.execute(() -> {
                        synchronized (monitor) {
                            // Failed/cancelled admission may still race a late
                            // runnable. A reused or duplicate invocation
                            // cannot execute policy after its lane moved on.
                            if (lanes.get(supervisorId) != lane
                                    || lane.current != delivery || delivery.dispatched)
                                return;
                            delivery.dispatched = true;
                        }
                        // A VM CONTROL dispatcher cannot inline the policy on
                        // the retiring child or calling host's stack.
                        if (Boolean.TRUE.equals(submitting.get())
                                || ActorRuntime.isActorCarrierThread()
                                || ActorRuntime.inActorExecution()) {
                            quarantine(delivery.binding);
                            finish(supervisorId, lane);
                            return;
                        }
                        executeOnControl(supervisorId, lane, delivery);
                    }), "CONTROL dispatcher returned null Future");
            accepted.whenCompleteRuntime((ignored, failure) -> {
                if (failure != null) {
                    quarantine(delivery.binding);
                    boolean ran;
                    synchronized (monitor) { ran = delivery.dispatched; }
                    if (!ran) failLane(supervisorId, lane, delivery);
                }
            });
        } catch (VirtualMachineError | ThreadDeath fatal) {
            quarantine(delivery.binding);
            failLane(supervisorId, lane, delivery);
            throw fatal;
        } catch (RuntimeException | Error admissionFailure) {
            quarantine(delivery.binding);
            failLane(supervisorId, lane, delivery);
        } finally {
            submitting.remove();
        }
    }

    private void executeOnControl(
            ActorRuntime.SupervisorId supervisorId, Lane lane, Delivery delivery) {
        Binding binding = delivery.binding;
        ActorRuntime.VirtualActorTerminalNotice notice = delivery.notice;
        VirtualActorRuntimeGenerationFence.TerminalDecisionLease lease;
        try {
            lease = binding.group().beginVirtualActorTerminalDecision(
                    notice.identity(), notice.generation(), notice.actorId());
        } catch (VirtualMachineError | ThreadDeath fatal) {
            quarantine(binding);
            finish(supervisorId, lane);
            throw fatal;
        } catch (RuntimeException | Error rejected) {
            quarantine(binding);
            finish(supervisorId, lane);
            return;
        }

        CompletionStage<Void> completion;
        try {
            completion = Objects.requireNonNull(
                    binding.handler().apply(notice), "terminal CONTROL handler returned null stage");
        } catch (VirtualMachineError | ThreadDeath fatal) {
            try {
                lease.quarantine();
                lease.close();
            } finally {
                finish(supervisorId, lane);
            }
            throw fatal;
        } catch (RuntimeException | Error handlerFailure) {
            try {
                lease.quarantine();
                lease.close();
            } finally {
                finish(supervisorId, lane);
            }
            return;
        }
        try {
            completion.whenComplete((ignored, error) -> {
                try {
                    if (error != null) lease.quarantine();
                    lease.close();
                } finally {
                    finish(supervisorId, lane);
                }
            });
        } catch (RuntimeException | Error callbackRegistrationFailure) {
            try {
                lease.quarantine();
                lease.close();
            } finally {
                finish(supervisorId, lane);
            }
        }
    }

    private void finish(ActorRuntime.SupervisorId supervisorId, Lane lane) {
        boolean more;
        synchronized (monitor) {
            if (lanes.get(supervisorId) != lane) return;
            if (outstanding < 1)
                throw new IllegalStateException("terminal CONTROL outstanding accounting underflow");
            if (lane.current == null)
                throw new IllegalStateException("terminal CONTROL active delivery missing");
            lane.current = null;
            outstanding--;
            more = !lane.waiting.isEmpty();
            if (!more) {
                lane.active = false;
                lanes.remove(supervisorId, lane);
            }
        }
        if (more) scheduleNext(supervisorId, lane);
    }

    /**
     * If dispatcher admission itself fails, quarantine every queued receipt
     * in this group instead of losing notices or incorrectly admitting the
     * next generation. No callback runs while holding monitor.
     */
    private void failLane(
            ActorRuntime.SupervisorId supervisorId, Lane lane, Delivery active) {
        List<Binding> dropped = new ArrayList<>();
        synchronized (monitor) {
            if (lanes.get(supervisorId) != lane) return;
            dropped.add(active.binding);
            for (Delivery queued : lane.waiting) dropped.add(queued.binding);
            int count = dropped.size();
            if (count > outstanding)
                throw new IllegalStateException("terminal CONTROL outstanding accounting underflow");
            outstanding -= count;
            lane.waiting.clear();
            lane.current = null;
            lane.active = false;
            lanes.remove(supervisorId, lane);
        }
        for (Binding binding : dropped) quarantine(binding);
    }

    private void quarantine(Binding binding) {
        fences.failTerminalDelivery(
                binding.supervisor(), binding.groupId(), binding.identity(),
                binding.generation(), binding.actorId());
    }
}
