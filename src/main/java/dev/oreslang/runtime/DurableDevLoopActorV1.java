package dev.oreslang.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Host-reference actor wrapper for one durable development workflow run.
 *
 * <p>The actor holds only one mutable execution state object (this behavior)
 * and treats SQLite as the durable authority. Each delivered workflow event is
 * reduced from a freshly replayed journal; no in-memory reducer mutation is
 * trusted across a failed persistence operation.</p>
 *
 * <p>This is the Java/Graal reference shape for the future source-level
 * DevLoopActor. UI and activity workers interact by messages and immutable
 * actor outputs rather than direct method calls or shared mutable state.</p>
 */
public final class DurableDevLoopActorV1 {

    public sealed interface Message permits OpenRun, Deliver,
            ClaimEffect, CompleteEffect, ReleaseEffect, ExpireClaims,
            ReconcileCompleted, ReconcileRetry, QueryProjection, Shutdown { }

    public record OpenRun(
            String databasePath,
            String runId,
            int maxIterations,
            String startEventId,
            String goal) implements Message {
        public OpenRun {
            databasePath = requireText(databasePath, "databasePath");
            runId = requireText(runId, "runId");
            startEventId = requireText(startEventId, "startEventId");
            goal = requireText(goal, "goal");
            if (maxIterations <= 0 || maxIterations > 10_000) {
                throw new IllegalArgumentException(
                        "maxIterations must be in [1, 10000]");
            }
        }
    }

    public record Deliver(
            String eventId,
            DurableDevLoopV1.Event event) implements Message {
        public Deliver {
            eventId = requireText(eventId, "eventId");
            event = Objects.requireNonNull(event, "event");
        }
    }

    public record ClaimEffect(
            String workerId,
            long nowMillis,
            long leaseMillis) implements Message {
        public ClaimEffect {
            workerId = requireText(workerId, "workerId");
        }
    }

    public record CompleteEffect(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis) implements Message {
        public CompleteEffect {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            receiptRef = requireText(receiptRef, "receiptRef");
        }
    }

    public record ReleaseEffect(
            String effectKey,
            String claimToken,
            String reasonRef,
            long nowMillis) implements Message {
        public ReleaseEffect {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            reasonRef = requireText(reasonRef, "reasonRef");
        }
    }

    public record ExpireClaims(long nowMillis) implements Message { }

    public record ReconcileCompleted(
            String effectKey,
            String receiptRef,
            String reconciliationRef) implements Message {
        public ReconcileCompleted {
            effectKey = requireText(effectKey, "effectKey");
            receiptRef = requireText(receiptRef, "receiptRef");
            reconciliationRef = requireText(
                    reconciliationRef, "reconciliationRef");
        }
    }

    public record ReconcileRetry(
            String effectKey,
            String reconciliationRef) implements Message {
        public ReconcileRetry {
            effectKey = requireText(effectKey, "effectKey");
            reconciliationRef = requireText(
                    reconciliationRef, "reconciliationRef");
        }
    }

    public record QueryProjection() implements Message { }

    public record Shutdown(String reason) implements Message {
        public Shutdown {
            reason = requireText(reason, "reason");
        }
    }

    public sealed interface Output permits Opened, Transitioned,
            EffectClaimed, NoEffectAvailable, EffectUpdated,
            ProjectionPublished, Stopping { }

    public record Opened(
            WorkflowRunProjectionV1 projection) implements Output {
        public Opened {
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record Transitioned(
            DurableDevLoopV1.JournalEntry journalEntry,
            WorkflowRunProjectionV1 projection) implements Output {
        public Transitioned {
            journalEntry = Objects.requireNonNull(
                    journalEntry, "journalEntry");
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record EffectClaimed(
            WorkflowJournalV1.Claim claim,
            WorkflowRunProjectionV1 projection) implements Output {
        public EffectClaimed {
            claim = Objects.requireNonNull(claim, "claim");
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record NoEffectAvailable(
            WorkflowRunProjectionV1 projection) implements Output {
        public NoEffectAvailable {
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record EffectUpdated(
            String effectKey,
            WorkflowRunProjectionV1 projection) implements Output {
        public EffectUpdated {
            effectKey = requireText(effectKey, "effectKey");
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record ProjectionPublished(
            WorkflowRunProjectionV1 projection) implements Output {
        public ProjectionPublished {
            projection = Objects.requireNonNull(
                    projection, "projection");
        }
    }

    public record Stopping(
            String runId,
            String reason) implements Output {
        public Stopping {
            runId = requireText(runId, "runId");
            reason = requireText(reason, "reason");
        }
    }

    private DurableDevLoopActorV1() { }

    /**
     * Spawn a capture-free private actor. Persistent configuration arrives in
     * the first OpenRun message and the SQLite handle is created on the actor's
     * serialized execution lane.
     */
    public static ActorRuntime.ActorRef<Message> spawn(
            ActorRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return runtime.spawnPrivate(
                DurableDevLoopActorV1::createBehavior);
    }

    private static ActorRuntime.Behavior<Message> createBehavior(
            ActorRuntime.ActorContext<Message> ignored) {
        return new Behavior();
    }

    private static final class Behavior
            implements ActorRuntime.Behavior<Message> {
        private SqliteWorkflowJournalV1 store;
        private String runId;
        private int maxIterations;

        @Override
        public void onMessage(
                Message message,
                ActorRuntime.ActorContext<Message> context) {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(context, "context");

            if (message instanceof OpenRun open) {
                open(open, context);
                return;
            }

            requireOpen();

            if (message instanceof Deliver deliver) {
                DurableDevLoopV1 machine =
                        store.replayDevLoop().machine();
                DurableDevLoopV1.Transition transition =
                        machine.accept(
                                deliver.eventId(),
                                deliver.event());
                store.append(transition);
                emit(
                        context,
                        new Transitioned(
                                transition.journalEntry(),
                                projection()));
                return;
            }

            if (message instanceof ClaimEffect claim) {
                var claimed = store.claimNext(
                        claim.workerId(),
                        claim.nowMillis(),
                        claim.leaseMillis());
                if (claimed.isPresent()) {
                    emit(
                            context,
                            new EffectClaimed(
                                    claimed.orElseThrow(),
                                    projection()));
                } else {
                    emit(
                            context,
                            new NoEffectAvailable(projection()));
                }
                return;
            }

            if (message instanceof CompleteEffect complete) {
                store.complete(
                        complete.effectKey(),
                        complete.claimToken(),
                        complete.receiptRef(),
                        complete.nowMillis());
                emit(
                        context,
                        new EffectUpdated(
                                complete.effectKey(),
                                projection()));
                return;
            }

            if (message instanceof ReleaseEffect release) {
                store.releaseUnexecuted(
                        release.effectKey(),
                        release.claimToken(),
                        release.reasonRef(),
                        release.nowMillis());
                emit(
                        context,
                        new EffectUpdated(
                                release.effectKey(),
                                projection()));
                return;
            }

            if (message instanceof ExpireClaims expire) {
                store.expireClaims(expire.nowMillis());
                emit(
                        context,
                        new ProjectionPublished(projection()));
                return;
            }

            if (message instanceof ReconcileCompleted completed) {
                store.resolveAmbiguousAsCompleted(
                        completed.effectKey(),
                        completed.receiptRef(),
                        completed.reconciliationRef());
                emit(
                        context,
                        new EffectUpdated(
                                completed.effectKey(),
                                projection()));
                return;
            }

            if (message instanceof ReconcileRetry retry) {
                store.resolveAmbiguousForRetry(
                        retry.effectKey(),
                        retry.reconciliationRef());
                emit(
                        context,
                        new EffectUpdated(
                                retry.effectKey(),
                                projection()));
                return;
            }

            if (message instanceof QueryProjection) {
                emit(
                        context,
                        new ProjectionPublished(projection()));
                return;
            }

            if (message instanceof Shutdown shutdown) {
                emit(
                        context,
                        new Stopping(runId, shutdown.reason()));
                SqliteWorkflowJournalV1 closing = store;
                store = null;
                context.runtime().endCurrentActorWithCleanup(
                        closing::close);
                return;
            }

            throw new IllegalStateException(
                    "unsupported dev-loop actor message "
                            + message.getClass().getName());
        }

        private void open(
                OpenRun open,
                ActorRuntime.ActorContext<Message> context) {
            if (store != null) {
                throw new IllegalStateException(
                        "dev-loop actor is already open");
            }

            SqliteWorkflowJournalV1 opened =
                    SqliteWorkflowJournalV1.open(
                            Path.of(open.databasePath()));
            boolean accepted = false;
            try {
                WorkflowJournalV1.StoreSnapshot snapshot =
                        opened.snapshot();
                DurableDevLoopV1 machine;

                if (snapshot.runId() == null) {
                    machine = new DurableDevLoopV1(
                            open.runId(), open.maxIterations());
                    opened.append(machine.accept(
                            open.startEventId(),
                            new DurableDevLoopV1.Start(open.goal())));
                } else {
                    if (!open.runId().equals(snapshot.runId())
                            || snapshot.maxIterations() == null
                            || open.maxIterations()
                                    != snapshot.maxIterations()) {
                        throw new IllegalStateException(
                                "existing durable run identity/policy "
                                        + "does not match OpenRun");
                    }
                    machine = opened.replayDevLoop().machine();
                    if (!open.goal().equals(
                            machine.snapshot().goal())) {
                        throw new IllegalStateException(
                                "existing durable run goal does not "
                                        + "match OpenRun");
                    }
                }

                this.store = opened;
                this.runId = open.runId();
                this.maxIterations = open.maxIterations();
                accepted = true;
                emit(context, new Opened(projection()));
            } finally {
                if (!accepted) {
                    opened.close();
                }
            }
        }

        private WorkflowRunProjectionV1 projection() {
            requireOpen();
            DurableDevLoopV1.Snapshot reducer =
                    store.replayDevLoop().machine().snapshot();
            WorkflowJournalV1.StoreSnapshot persisted =
                    store.snapshot();
            if (!runId.equals(reducer.runId())
                    || maxIterations != reducer.maxIterations()) {
                throw new IllegalStateException(
                        "actor durable run identity/policy drift");
            }
            return WorkflowRunProjectionV1.from(
                    reducer, persisted);
        }

        private void requireOpen() {
            if (store == null) {
                throw new IllegalStateException(
                        "OpenRun must be the first dev-loop actor message");
            }
        }

        private static void emit(
                ActorRuntime.ActorContext<Message> context,
                Output output) {
            context.runtime().emitCurrentActorOutput(output);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        return normalized;
    }
}
