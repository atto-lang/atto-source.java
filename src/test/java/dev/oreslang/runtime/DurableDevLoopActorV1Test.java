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
            actor.getReady().get(5, TimeUnit.SECONDS);
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

            actor.send(new DurableDevLoopActorV1.Deliver(
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
            actor.getDone().get(5, TimeUnit.SECONDS);
        }

        try (var runtime = new ActorRuntime()) {
            var recovered = DurableDevLoopActorV1.spawn(runtime);
            recovered.getReady().get(5, TimeUnit.SECONDS);
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
            recovered.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorClaimsAndCompletesDurableEffectByMessages() throws Exception {
        Path db = tempDir.resolve("actor-effect.db");

        try (var runtime = new ActorRuntime()) {
            var actor = DurableDevLoopActorV1.spawn(runtime);
            actor.getReady().get(5, TimeUnit.SECONDS);
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

            actor.send(new DurableDevLoopActorV1.CompleteEffect(
                    claimed.claim().effectKey(),
                    claimed.claim().claimToken(),
                    "receipt://code/1",
                    150L));
            var completed = assertInstanceOf(
                    DurableDevLoopActorV1.EffectUpdated.class,
                    next(outputs));
            assertEquals(
                    1,
                    completed.projection().completedEffects());

            actor.send(new DurableDevLoopActorV1.QueryProjection());
            var projection = assertInstanceOf(
                    DurableDevLoopActorV1.ProjectionPublished.class,
                    next(outputs));
            assertEquals(
                    DurableDevLoopV1.Phase.CODING,
                    projection.projection().phase());

            actor.send(new DurableDevLoopActorV1.Shutdown("done"));
            next(outputs);
            actor.getDone().get(5, TimeUnit.SECONDS);
        }

        try (var store = SqliteWorkflowJournalV1.open(db)) {
            var effect = store.effect(
                    "devloop/run-effect/iteration/1/code")
                    .orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    effect.state());
            assertEquals("receipt://code/1", effect.receiptRef());
        }
    }

    @Test
    void actorRejectsOpeningExistingDatabaseUnderDifferentGoal()
            throws Exception {
        Path db = tempDir.resolve("actor-goal.db");
        seed(db, "run-goal", "original");

        try (var runtime = new ActorRuntime()) {
            var actor = DurableDevLoopActorV1.spawn(runtime);
            actor.getReady().get(5, TimeUnit.SECONDS);

            actor.send(new DurableDevLoopActorV1.OpenRun(
                    db.toString(),
                    "run-goal",
                    3,
                    "start",
                    "different"));

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent());
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
        return step.value().value();
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
