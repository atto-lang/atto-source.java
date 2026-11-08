package dev.oreslang.runtime;

import java.util.List;
import java.text.Normalizer;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A bounded, supervisor-owned HOST CONTROL notification lane.
 *
 * The owning runtime supplies its existing shared dispatcher. Child-carrier
 * code only enqueues immutable value notices; a failure handler never runs
 * inline on that carrier, and handlers are strictly single-flight.
 *
 * This is a host substrate, not parsed Oreslang supervisor/on_panic syntax.
 * Terminal actor failures are not guest recoverable; policy and restart
 * decisions are left to the trusted supervisor.
 */
public final class SupervisorFailureControl {
    public enum Kind { TERMINAL_FAILURE, CANCELLED }

    /** Version-neutral logical identity carried by supervisor terminal receipts. */
    public record VirtualActorLogicalId(String domain, String declaration, String key) {
        public VirtualActorLogicalId {
            domain = identityPart(domain, "domain", 128);
            declaration = identityPart(declaration, "declaration", 128);
            key = identityPart(key, "key", 256);
        }
    }

    /** Logical identity plus the exact activation generation that terminated. */
    public record VirtualActorTerminalIdentity(
            VirtualActorLogicalId identity,
            long activationGeneration) {
        public VirtualActorTerminalIdentity {
            Objects.requireNonNull(identity, "identity");
            if (activationGeneration <= 0)
                throw new IllegalArgumentException("virtual activation generation must be positive");
        }
    }

    private static String identityPart(String value, String field, int maxBytes) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maxBytes
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes
                || !Normalizer.isNormalized(value, Normalizer.Form.NFC))
            throw new IllegalArgumentException("invalid virtual actor " + field);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch))
                throw new IllegalArgumentException("control character in virtual actor " + field);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    throw new IllegalArgumentException("unpaired surrogate in virtual actor " + field);
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired surrogate in virtual actor " + field);
            }
        }
        return value;
    }

    public record Notice(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            ActorRuntime.ActorId actorId,
            Optional<ActorRuntime.ActorId> spawnedBy,
            Kind kind,
            String failureType,
            String diagnostic,
            Optional<VirtualActorTerminalIdentity> virtualActor) {
        public Notice {
            Objects.requireNonNull(supervisorId);
            Objects.requireNonNull(groupId);
            Objects.requireNonNull(actorId);
            spawnedBy = Objects.requireNonNull(spawnedBy);
            Objects.requireNonNull(kind);
            failureType = bounded(Objects.requireNonNull(failureType));
            diagnostic = bounded(Objects.requireNonNull(diagnostic));
            virtualActor = Objects.requireNonNull(virtualActor);
        }

        /** Source-compatible constructor for ordinary non-virtual actors. */
        public Notice(ActorRuntime.SupervisorId supervisorId,
                      ActorRuntime.ActorGroupId groupId,
                      ActorRuntime.ActorId actorId,
                      Optional<ActorRuntime.ActorId> spawnedBy,
                      Kind kind, String failureType, String diagnostic) {
            this(supervisorId, groupId, actorId, spawnedBy, kind,
                    failureType, diagnostic, Optional.empty());
        }
    }

    public record PendingTerminal(long sequence, Notice notice) {
        public PendingTerminal {
            if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
            Objects.requireNonNull(notice);
        }
    }

    private static String bounded(String text) {
        // A Java-char limit is not a UTF-8 wire budget: 1024 supplementary
        // characters can encode to 4096 bytes. Also reject log-line/control
        // injection and invalid isolated surrogate code units.
        StringBuilder safe = new StringBuilder(Math.min(text.length(), 1024));
        int bytes = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp >= 0xd800 && cp <= 0xdfff
                    || Character.isISOControl(cp)
                    || Character.getType(cp) == Character.LINE_SEPARATOR
                    || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR) {
                cp = ' ';
            }
            int width = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + width > 1024) break;
            safe.appendCodePoint(cp);
            bytes += width;
        }
        return safe.toString();
    }

    private final ActorRuntime.SupervisorId supervisorId;
    private final ActorRuntime.ActorGroupId groupId;
    private final Executor dispatcher;
    private final SupervisorTerminalLedger ledger;
    private final AtomicLong undelivered = new AtomicLong();
    private Function<Notice, CompletionStage<Void>> handler;
    /** Once claimed by an actor, this CONTROL stream can never change consumers. */
    private boolean actorPollingClaimed;
    /** One logical delivery lease, including across asynchronous acknowledgement. */
    private boolean draining;
    private Throwable handlerFailure;

    public SupervisorFailureControl(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            Executor dispatcher,
            int capacity) {
        this.supervisorId = Objects.requireNonNull(supervisorId);
        this.groupId = Objects.requireNonNull(groupId);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.ledger = new SupervisorTerminalLedger(capacity);
    }

    public ActorRuntime.SupervisorId supervisorId() { return supervisorId; }
    public int capacity() { return ledger.capacity(); }

    synchronized void reserve(ActorRuntime.ActorId actorId) {
        if (handlerFailure != null) {
            throw new IllegalStateException(
                    "supervisor CONTROL failed; refusing new managed actor", handlerFailure);
        }
        ledger.reserve(actorId);
    }

    synchronized void releaseReservation(ActorRuntime.ActorId actorId) {
        ledger.releaseUnpublished(actorId);
    }

    /** Install exactly once; pre-handler terminal state stays durable until installed. */
    public synchronized void install(Consumer<Notice> handler) {
        Objects.requireNonNull(handler);
        installLocked(notice -> {
            handler.accept(notice);
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Install an async consumer. Completion is the acknowledgement boundary;
     * until it succeeds the actor's pre-reserved terminal slot remains held.
     */
    synchronized void installAsync(Function<Notice, CompletionStage<Void>> handler) {
        Objects.requireNonNull(handler);
        installLocked(handler);
    }

    private void installLocked(Function<Notice, CompletionStage<Void>> nextHandler) {
        if (this.handler != null || actorPollingClaimed) {
            throw new IllegalStateException(
                    "supervisor CONTROL already has an exclusive consumer");
        }
        if (handlerFailure != null) {
            throw new IllegalStateException("supervisor control lane failed", handlerFailure);
        }
        this.handler = nextHandler;
        scheduleLocked();
        if (handlerFailure != null) {
            throw new IllegalStateException(
                    "supervisor CONTROL handler dispatch failed", handlerFailure);
        }
    }

    /**
     * Runtime-only publication. The actor already owns a terminal slot, so
     * ordinary mailbox pressure cannot make this lifecycle state disappear.
     */
    synchronized void publish(Notice notice) {
        Objects.requireNonNull(notice);
        if (!supervisorId.equals(notice.supervisorId())
                || !groupId.equals(notice.groupId())) {
            throw new SecurityException("foreign supervisor CONTROL notice");
        }
        ledger.publish(notice);
        if (handlerFailure != null) {
            undelivered.incrementAndGet();
            return;
        }
        scheduleLocked();
    }

    /**
     * The owning actor polls immutable notices on its own single-flight turn.
     * Poll is the acknowledgement boundary for actor-owned supervision.
     */
    synchronized Optional<Notice> poll() {
        if (handler != null) {
            throw new IllegalStateException(
                    "supervisor CONTROL has a registered process/handler");
        }
        if (handlerFailure != null) {
            throw new IllegalStateException("supervisor CONTROL lane failed", handlerFailure);
        }
        actorPollingClaimed = true;
        SupervisorTerminalLedger.Record record = ledger.pollFirst();
        return record == null ? Optional.empty() : Optional.of(record.notice());
    }

    public synchronized int queued() { return ledger.published(); }
    public synchronized int reserved() { return ledger.reserved(); }
    public synchronized int availableReservations() { return ledger.available(); }
    public long undelivered() { return undelivered.get(); }
    public long lastPublishedSequence() { return ledger.lastSequence(); }

    public synchronized List<PendingTerminal> pendingSnapshot() {
        return ledger.snapshot().stream()
                .map(record -> new PendingTerminal(record.sequence(), record.notice()))
                .toList();
    }

    public synchronized Optional<String> failure() {
        return Optional.ofNullable(handlerFailure).map(Throwable::toString);
    }

    public synchronized boolean healthy() {
        return handlerFailure == null && undelivered.get() == 0;
    }

    private void scheduleLocked() {
        if (draining || handler == null || ledger.published() == 0 || handlerFailure != null) {
            return;
        }
        draining = true;
        try {
            SupervisorDispatchGuard.enqueue(dispatcher, this::beginDelivery);
        } catch (Throwable dispatchFailure) {
            failLocked(dispatchFailure);
        }
    }

    private void beginDelivery() {
        final SupervisorTerminalLedger.Record record;
        final Function<Notice, CompletionStage<Void>> callback;
        synchronized (this) {
            if (handlerFailure != null) {
                draining = false;
                return;
            }
            try {
                record = ledger.leaseFirst();
            } catch (Throwable leaseFailure) {
                failLocked(leaseFailure);
                return;
            }
            if (record == null) {
                draining = false;
                return;
            }
            callback = handler;
        }

        final CompletionStage<Void> acknowledgement;
        try {
            acknowledgement = Objects.requireNonNull(
                    callback.apply(record.notice()),
                    "supervisor CONTROL handler returned null acknowledgement");
        } catch (Throwable failure) {
            synchronized (this) {
                ledger.retry(record.sequence(), record.notice());
                failLocked(failure);
            }
            return;
        }

        try {
            acknowledgement.whenComplete((ignored, failure) -> {
                try {
                    SupervisorDispatchGuard.enqueue(
                            dispatcher,
                            () -> finishDelivery(record, failure));
                } catch (Throwable schedulingFailure) {
                    synchronized (SupervisorFailureControl.this) {
                        ledger.retry(record.sequence(), record.notice());
                        failLocked(schedulingFailure);
                    }
                }
            });
        } catch (Throwable subscriptionFailure) {
            synchronized (this) {
                ledger.retry(record.sequence(), record.notice());
                failLocked(subscriptionFailure);
            }
        }
    }

    private void finishDelivery(
            SupervisorTerminalLedger.Record record,
            Throwable failure) {
        synchronized (this) {
            if (failure != null) {
                ledger.retry(record.sequence(), record.notice());
                failLocked(failure);
                return;
            }
            try {
                ledger.acknowledge(record.sequence(), record.notice());
            } catch (Throwable acknowledgementFailure) {
                failLocked(acknowledgementFailure);
                return;
            }
            draining = false;
            scheduleLocked();
        }
    }

    private void failLocked(Throwable failure) {
        if (handlerFailure != null) return;
        handlerFailure = Objects.requireNonNull(failure);
        draining = false;
        undelivered.addAndGet(ledger.published());
        // Retain every published terminal record. A failed wakeup or consumer
        // path must not erase the authoritative state needed for escalation.
    }
}
