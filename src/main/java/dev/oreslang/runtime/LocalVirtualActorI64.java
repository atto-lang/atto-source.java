package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Local, manually pumped i64 virtual-actor execution slice. Bounded mailbox,
 * stable logical references, fenced activation, and one turn per identity.
 *
 * This is a VM-only execution substrate, NOT a guest `define virtual actor`
 * compiler nor a substitute for ActorRuntime's priority supervisor CONTROL.
 * It has no durable storage, remote placement, or general Sendable payloads.
 * No user factory, actor turn, or supervisor callback runs under a VM monitor.
 */
public final class LocalVirtualActorI64 implements AutoCloseable {
    /**
     * A handler may own a guest state region. Its close hook is invoked once,
     * after every admitted turn settles and its activation is fenced.
     * No host disposal callback runs while a VM monitor is held.
     * Existing stateless handlers remain valid functional implementations.
     */
    @FunctionalInterface public interface Turn extends AutoCloseable {
        void on_message(long message) throws Exception;
        @Override default void close() { }
    }

    public enum AdmissionKind { ADMITTED, BACKPRESSURE, ACTIVATING, CLOSED, UNAVAILABLE }
    public enum Outcome { PROCESSED, FAILED_CONTROL_NOTIFIED }
    public enum NoticeKind { PANIC }

    public record Notice(NoticeKind kind, VirtualActorActivationRegistry.Identity identity,
                         long incarnation, int failedAcceptedCount,
                         String errorClass) { }

    public record SendResult(AdmissionKind kind, CompletableFuture<Outcome> completion) {
        public SendResult {
            Objects.requireNonNull(kind);
            if ((kind == AdmissionKind.ADMITTED) != (completion != null))
                throw new IllegalArgumentException("only admitted turns have completion futures");
        }
        public boolean admitted() { return kind == AdmissionKind.ADMITTED; }
    }

    private static final class Envelope {
        final long value;
        VirtualActorActivationRegistry.Admission receipt;
        final CompletableFuture<Outcome> result = new CompletableFuture<>();
        Envelope(long value) {
            this.value = value;
        }
    }

    private static final class Slot {
        final VirtualActorActivationRegistry.LogicalRef logical;
        final ArrayDeque<Envelope> pending = new ArrayDeque<>();
        VirtualActorActivationRegistry.Activation activation;
        Turn handler;
        boolean initializing;
        boolean processing;
        boolean failed;
        boolean retiring;
        Slot(VirtualActorActivationRegistry.LogicalRef logical) { this.logical = logical; }
    }

    private record RetiredTurn(Slot slot, Turn handler) { }

    public final class Ref {
        private final Slot slot;
        private Ref(Slot slot) { this.slot = slot; }
        public VirtualActorActivationRegistry.Identity identity() { return slot.logical.identity(); }
    }

    private final Object directoryLock = new Object();
    private final VirtualActorActivationRegistry registry;
    private final String declaration;
    private final Function<VirtualActorActivationRegistry.Identity, Turn> factory;
    private final Consumer<Notice> control;
    private final Map<VirtualActorActivationRegistry.Identity, Slot> slots = new HashMap<>();
    private volatile boolean closed;
    private volatile boolean closing;
    private volatile boolean shutdownDisposing;
    private volatile Throwable shutdownFailure;

    public LocalVirtualActorI64(String domain, String declaration,
                               int maxIdentities, int mailboxCapacity,
                               Function<VirtualActorActivationRegistry.Identity, Turn> factory,
                               Consumer<Notice> control) {
        this.registry = new VirtualActorActivationRegistry(domain, maxIdentities, mailboxCapacity);
        this.declaration = Objects.requireNonNull(declaration);
        this.factory = Objects.requireNonNull(factory);
        this.control = Objects.requireNonNull(control);
    }

    public Ref resolve(String key) {
        synchronized (directoryLock) {
            if (closed) throw new IllegalStateException("virtual actor directory closed");
            var logical = registry.resolve(declaration, key);
            Slot slot = slots.computeIfAbsent(logical.identity(), ignored -> new Slot(logical));
            return new Ref(slot);
        }
    }

    private Slot requireLocal(Ref reference) {
        Objects.requireNonNull(reference, "virtual actor reference");
        if (reference.getClass() != Ref.class)
            throw new IllegalArgumentException("foreign virtual actor reference");
        Slot slot = reference.slot;
        synchronized (directoryLock) {
            if (slots.get(slot.logical.identity()) != slot)
                throw new IllegalArgumentException("foreign virtual actor reference");
        }
        return slot;
    }

    /**
     * Returns false if another initializer is in progress. Callers never wait
     * on a monitor while guest initialization executes.
     */
    public boolean activate(Ref reference) {
        Slot slot = requireLocal(reference);
        VirtualActorActivationRegistry.Activation incarnation;
        synchronized (slot) {
            if (closed || closing || slot.failed || slot.retiring) return false;
            if (slot.activation != null) return slot.activation.active();
            if (slot.initializing) return false;
            slot.initializing = true;
            try {
                incarnation = registry.activate(slot.logical);
            } catch (RuntimeException | Error failure) {
                slot.initializing = false;
                throw failure;
            }
            if (!incarnation.initializer()) {
                slot.initializing = false;
                return false;
            }
        }
        Turn newHandler;
        try {
            newHandler = Objects.requireNonNull(factory.apply(slot.logical.identity()),
                    "virtual actor factory returned null");
        } catch (Throwable failure) {
            synchronized (slot) {
                registry.abort(incarnation);
                slot.initializing = false;
            }
            if (failure instanceof Error fatal) throw fatal;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("virtual actor initialization failed", failure);
        }
        synchronized (slot) {
            // close() refuses an initializer in flight, so publication cannot
            // race a successful directory retirement.
            registry.publish(incarnation);
            slot.handler = newHandler;
            slot.activation = incarnation;
            slot.initializing = false;
            return true;
        }
    }

    public SendResult try_send(Ref reference, long value) {
        Slot slot = requireLocal(reference);
        if (closed || closing) return new SendResult(AdmissionKind.CLOSED, null);
        if (!activate(reference)) {
            synchronized (slot) {
                return new SendResult(
                        slot.initializing ? AdmissionKind.ACTIVATING : AdmissionKind.UNAVAILABLE,
                        null);
            }
        }
        synchronized (slot) {
            if (closed || closing || slot.failed || slot.retiring
                    || slot.activation == null || !slot.activation.active())
                return new SendResult(AdmissionKind.UNAVAILABLE, null);
            // Allocate the envelope, future and returned handle BEFORE the
            // registry accepts the turn. Queue the provisional envelope under
            // the slot monitor, then atomically acquire its admission ticket.
            // No worker can observe it until this monitor is released.
            // There must be no allocation/queue-growth failure after acceptance
            // that can silently strand an outstanding registry credit.
            var envelope = new Envelope(value);
            var accepted = new SendResult(AdmissionKind.ADMITTED, envelope.result);
            slot.pending.addLast(envelope);
            VirtualActorActivationRegistry.Admission ticket;
            try {
                ticket = registry.tryAdmit(slot.activation);
            } catch (RuntimeException | Error failure) {
                if (slot.pending.removeLast() != envelope)
                    throw new IllegalStateException("virtual actor admission rollback corrupted", failure);
                throw failure;
            }
            if (ticket == null) {
                if (slot.pending.removeLast() != envelope)
                    throw new IllegalStateException("virtual actor backpressure rollback corrupted");
                return new SendResult(AdmissionKind.BACKPRESSURE, null);
            }
            envelope.receipt = ticket;
            return accepted;
        }
    }

    /**
     * Execute one admitted turn on the current runtime-controlled worker.
     * A parallel caller for the same logical actor cannot enter the handler.
     * Returns false if the mailbox is empty, faulted or already processing.
     */
    public boolean run_one(Ref reference) {
        Slot slot = requireLocal(reference);
        Envelope next;
        Turn target;
        long incarnation;
        synchronized (slot) {
            if (closed || slot.failed || slot.processing || slot.pending.isEmpty()) return false;
            slot.processing = true;
            next = slot.pending.peekFirst(); // retained until explicit settlement
            target = slot.handler;
            incarnation = slot.activation.incarnation();
        }
        Throwable problem = null;
        try {
            target.on_message(next.value);
        } catch (Throwable failure) {
            problem = failure;
        }

        if (problem == null) {
            synchronized (slot) {
                if (slot.pending.peekFirst() != next)
                    throw new IllegalStateException("virtual actor FIFO corrupted");
                slot.pending.removeFirst();
                // Keep processing true until ticket settlement.
            }
            try {
                next.receipt.settle(VirtualActorActivationRegistry.Disposition.PROCESSED);
                next.result.complete(Outcome.PROCESSED);
                return true;
            } finally {
                synchronized (slot) { slot.processing = false; }
            }
        }

        List<Envelope> abandoned;
        Notice notice;
        synchronized (slot) {
            slot.failed = true;
            abandoned = List.copyOf(slot.pending);
            notice = new Notice(NoticeKind.PANIC, slot.logical.identity(),
                    incarnation, abandoned.size(), problem.getClass().getName());
            // The failed turn stays queued until the supervisor acknowledges
            // notification. If notification fails, it remains retained and
            // close/deactivation fail closed with the outstanding credits.
        }
        try {
            control.accept(notice);
        } catch (Throwable notifierFailure) {
            synchronized (slot) { slot.processing = false; }
            if (notifierFailure instanceof Error fatal) throw fatal;
            throw new IllegalStateException("supervisor CONTROL notification failed; accepted messages retained",
                    notifierFailure);
        }
        synchronized (slot) {
            if (slot.pending.size() != abandoned.size())
                throw new IllegalStateException("failed virtual actor mailbox accounting changed");
            slot.pending.clear();
        }
        for (Envelope envelope : abandoned) {
            envelope.receipt.settle(VirtualActorActivationRegistry.Disposition.FAILED_CONTROL_NOTIFIED);
            envelope.result.complete(Outcome.FAILED_CONTROL_NOTIFIED);
        }
        Turn retired;
        synchronized (slot) {
            registry.deactivate(slot.activation);
            retired = slot.handler;
            slot.activation = null;
            slot.handler = null;
            slot.failed = false;
            slot.processing = false;
            slot.retiring = true;
        }
        dispose(slot, retired);
        return true;
    }

    /**
     * Never run guest-owned state disposal under directory or slot monitors.
     * A failed disposer quarantines the slot: reactivation and silent shutdown
     * are forbidden until a higher-level runtime supplies explicit recovery.
     */
    private static void dispose(Slot slot, Turn handler) {
        try {
            handler.close();
        } catch (Throwable failure) {
            synchronized (slot) {
                slot.failed = true;
                slot.retiring = false;
            }
            if (failure instanceof Error fatal) throw fatal;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("virtual actor state disposal failed", failure);
        }
        synchronized (slot) { slot.retiring = false; }
    }

    /** Ephemeral deactivation is forbidden until all accepted work retires. */
    public void deactivate(Ref reference) {
        Slot slot = requireLocal(reference);
        Turn retired = null;
        synchronized (slot) {
            if (closed || closing || slot.initializing || slot.processing
                    || slot.failed || slot.retiring || !slot.pending.isEmpty())
                throw new IllegalStateException("virtual actor has outstanding work or is shutting down");
            if (slot.activation != null) {
                registry.deactivate(slot.activation);
                retired = slot.handler;
                slot.activation = null;
                slot.handler = null;
                slot.retiring = true;
            }
        }
        if (retired != null) dispose(slot, retired);
    }

    /**
     * Shutdown first fences all directory admission and accepted turns.
     * Only after successful registry closure are handler resources detached
     * and disposed outside every monitor. All disposers are attempted.
     */
    @Override public void close() {
        List<RetiredTurn> retired = new ArrayList<>();
        synchronized (directoryLock) {
            if (closed) {
                if (shutdownDisposing)
                    throw new IllegalStateException("virtual actor shutdown disposal still in progress");
                if (shutdownFailure != null) propagateShutdownFailure(shutdownFailure);
                return;
            }
            if (closing) throw new IllegalStateException("virtual actor directory already closing");
            closing = true;
            try {
                for (Slot slot : slots.values()) {
                    synchronized (slot) {
                        if (slot.initializing || slot.processing || slot.failed || slot.retiring
                                || !slot.pending.isEmpty())
                            throw new IllegalStateException("virtual actor directory has outstanding work or disposal");
                    }
                }
                registry.close();
                closed = true;
                shutdownDisposing = true;
                for (Slot slot : slots.values()) {
                    synchronized (slot) {
                        if (slot.handler != null) {
                            retired.add(new RetiredTurn(slot, slot.handler));
                            slot.handler = null;
                            slot.activation = null;
                            slot.retiring = true;
                        }
                    }
                }
            } finally {
                closing = false;
            }
        }
        Throwable firstFailure = null;
        for (RetiredTurn item : retired) {
            try {
                dispose(item.slot(), item.handler());
            } catch (Throwable failure) {
                if (firstFailure == null) firstFailure = failure;
                else if (firstFailure != failure) firstFailure.addSuppressed(failure);
            }
        }
        shutdownFailure = firstFailure;
        shutdownDisposing = false;
        if (firstFailure != null) propagateShutdownFailure(firstFailure);
    }

    private static void propagateShutdownFailure(Throwable failure) {
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException("virtual actor directory disposal failed", failure);
    }
}
