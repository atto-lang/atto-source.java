package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class DurableDevLoopActorV1Test {

    @TempDir
    Path tempDir;

    @Test
    void actorStartsPersistsProjectsAndRecoversSameRun() throws Exception {
        Path db = tempDir.resolve("actor-recover.db");

        try (var runtime = new ActorRuntime()) {
            var actor = DurableDevLoopActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new DurableDevLoopActorV1.OpenRun(
                    db.toString(),
                    "run-actor",
                    4,
                    "start",
                    "repair parser"));

            var opened = assertInstanceOf(
                    DurableDevLoopActorV1.Opened.class,
                    next(outputs));
            assertEquals(
                    DurableDevLoopV1.Phase.CODING,
                    opened.projection().phase());

            actor.send(new DurableDevLoopActorV1.ClaimEffect(
                    "coder-recover",
                    100L,
                    100L));
            var claim = assertInstanceOf(
                    DurableDevLoopActorV1.EffectClaimed.class,
                    next(outputs)).claim();

            actor.send(new DurableDevLoopActorV1.CompleteAndDeliver(
                    claim.effectKey(),
                    claim.claimToken(),
                    "receipt://code/recover",
                    150L,
                    "code-1",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://one")));

            var transitioned = assertInstanceOf(
                    DurableDevLoopActorV1.Transitioned.class,
                    next(outputs));
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    transitioned.projection().phase());

            actor.send(new DurableDevLoopActorV1.Shutdown(
                    "test restart"));
            assertInstanceOf(
                    DurableDevLoopActorV1.Stopping.class,
                    next(outputs));
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var runtime = new ActorRuntime()) {
            var recovered = DurableDevLoopActorV1.spawn(runtime);
            recovered.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = recovered.outputs();

            recovered.send(new DurableDevLoopActorV1.OpenRun(
                    db.toString(),
                    "run-actor",
                    4,
                    "start",
                    "repair parser"));

            var opened = assertInstanceOf(
                    DurableDevLoopActorV1.Opened.class,
                    next(outputs));
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    opened.projection().phase());
            assertEquals(2L,
                    opened.projection().lastTransitionSequence());

            recovered.send(new DurableDevLoopActorV1.Shutdown(
                    "test done"));
            next(outputs);
            recovered.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorClaimsAndAtomicallyCompletesDurableEffectWithOutcome()
            throws Exception {
        Path db = tempDir.resolve("actor-effect.db");

        try (var runtime = new ActorRuntime()) {
            var actor = DurableDevLoopActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new DurableDevLoopActorV1.OpenRun(
                    db.toString(),
                    "run-effect",
                    3,
                    "start",
                    "goal"));
            next(outputs);

            actor.send(new DurableDevLoopActorV1.ClaimEffect(
                    "coder-1",
                    100L,
                    100L));
            var claimed = assertInstanceOf(
                    DurableDevLoopActorV1.EffectClaimed.class,
                    next(outputs));
            assertInstanceOf(
                    DurableDevLoopV1.RequestCode.class,
                    claimed.claim().command());

            actor.send(new DurableDevLoopActorV1.CompleteAndDeliver(
                    claimed.claim().effectKey(),
                    claimed.claim().claimToken(),
                    "receipt://code/1",
                    150L,
                    "code-1",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://one")));
            var completed = assertInstanceOf(
                    DurableDevLoopActorV1.Transitioned.class,
                    next(outputs));
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    completed.projection().phase());
            assertEquals(
                    1,
                    completed.projection().completedEffects());

            actor.send(new DurableDevLoopActorV1.Shutdown("done"));
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            var effect = store.effect(
                    "devloop/run-effect/iteration/1/code")
                    .orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    effect.state());
            assertEquals("receipt://code/1", effect.receiptRef());
            assertEquals(
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH,
                    store.replayDevLoop().machine().snapshot().phase());
            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    store.effect(
                            "devloop/run-effect/iteration/1/push")
                            .orElseThrow()
                            .state());
        }
    }

    @Test
    void actorRejectsSplitCompletionForEffectThatRequiresOutcome()
            throws Exception {
        Path db = tempDir.resolve("actor-split-completion.db");

        try (var runtime = new ActorRuntime()) {
            var actor = DurableDevLoopActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new DurableDevLoopActorV1.OpenRun(
                    db.toString(),
                    "run-split",
                    3,
                    "start",
                    "goal"));
            next(outputs);

            actor.send(new DurableDevLoopActorV1.ClaimEffect(
                    "coder-1", 100L, 100L));
            var claimed = assertInstanceOf(
                    DurableDevLoopActorV1.EffectClaimed.class,
                    next(outputs));

            actor.send(new DurableDevLoopActorV1.CompleteEffect(
                    claimed.claim().effectKey(),
                    claimed.claim().claimToken(),
                    "receipt://unsafe",
                    150L));

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.raw().getDone().get(
                            5, TimeUnit.SECONDS));
            assertTrue(actor.raw().failure().isPresent());
        }

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            var effect = store.effect(
                    "devloop/run-split/iteration/1/code")
                    .orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.CLAIMED,
                    effect.state());
            assertNull(effect.receiptRef());
            assertEquals(1, store.journal().size());
        }
    }

    @Test
    void actorRejectsSplitDeliveryForCausalOutcome()
            throws Exception {
        Path db = tempDir.resolve("actor-split-delivery.db");

        try (var runtime = new ActorRuntime()) {
            var actor = DurableDevLoopActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new DurableDevLoopActorV1.OpenRun(
                    db.toString(),
                    "run-split-delivery",
                    3,
                    "start",
                    "goal"));
            next(outputs);

            actor.send(new DurableDevLoopActorV1.ClaimEffect(
                    "coder-1", 100L, 100L));
            var claimed = assertInstanceOf(
                    DurableDevLoopActorV1.EffectClaimed.class,
                    next(outputs));

            actor.send(new DurableDevLoopActorV1.Deliver(
                    "code-unsafe",
                    new DurableDevLoopV1.CodeChanged(
                            1, "patch://unsafe")));

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.raw().getDone().get(
                            5, TimeUnit.SECONDS));
            assertTrue(actor.raw().failure().isPresent());

            assertInstanceOf(
                    DurableDevLoopV1.RequestCode.class,
                    claimed.claim().command());
        }

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            var effect = store.effect(
                    "devloop/run-split-delivery/iteration/1/code")
                    .orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.CLAIMED,
                    effect.state());
            assertNull(effect.receiptRef());
            assertEquals(1, store.journal().size());
            assertEquals(
                    DurableDevLoopV1.Phase.CODING,
                    store.replayDevLoop().machine().snapshot().phase());
        }
    }

    @Test
    void actorRejectsOpeningExistingDatabaseUnderDifferentGoal()
            throws Exception {
        Path db = tempDir.resolve("actor-goal.db");
        seed(db, "run-goal", "original");

        try (var runtime = new ActorRuntime()) {
            var actor = DurableDevLoopActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);

            actor.send(new DurableDevLoopActorV1.OpenRun(
                    db.toString(),
                    "run-goal",
                    3,
                    "start",
                    "different"));

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.raw().getDone().get(5, TimeUnit.SECONDS));
            assertTrue(actor.raw().failure().isPresent());
        }

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            assertEquals(
                    "original",
                    store.replayDevLoop().machine()
                            .snapshot().goal());
        }
    }

    private static Object next(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done(), "actor output stream ended early");
        return DurableDevLoopActorV1.decodeOutput(
                step.value().value());
    }

    private static void seed(Path db, String runId, String goal) {
        var loop = new DurableDevLoopV1(runId, 3);
        try (var store = SqliteWorkflowJournalV1.open(db)) {
            store.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start(goal)));
        }
    }
}
