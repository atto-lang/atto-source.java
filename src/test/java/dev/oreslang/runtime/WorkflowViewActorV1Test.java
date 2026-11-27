package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowViewActorV1Test {

    @Test
    void subscriberReceivesImmutableProjectionWithViewRevision()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var view = WorkflowViewActorV1.spawn(runtime);
            var sink = sink(runtime);
            view.raw().getReady().get(5, TimeUnit.SECONDS);
            sink.getReady().get(5, TimeUnit.SECONDS);

            var viewOut = view.outputs();
            var sinkOut = sink.outputs();

            view.send(new WorkflowViewActorV1.Configure("run-ui"));
            var ready = assertInstanceOf(
                    WorkflowViewActorV1.Ready.class,
                    nextView(viewOut));

            view.send(new WorkflowViewActorV1.Subscribe(
                    "browser-1", sink));
            assertInstanceOf(
                    WorkflowViewActorV1.SubscriberAdded.class,
                    nextView(viewOut));

            view.send(new WorkflowViewActorV1.Publish(
                    projection(1, 1, 0)));
            var published = assertInstanceOf(
                    WorkflowViewActorV1.Published.class,
                    nextView(viewOut));
            assertEquals(1L, published.viewRevision());

            var update = WorkflowViewActorV1.decodeUiUpdate(
                    nextRaw(sinkOut));
            assertEquals("run-ui", update.runId());
            assertEquals("browser-1", update.subscriptionId());
            assertEquals(ready.epoch(), update.epoch());
            assertEquals(1L, update.viewRevision());
            assertEquals(1, update.projection().pendingEffects());
        }
    }

    @Test
    void effectOnlyProjectionChangesStillAdvanceViewRevision()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var view = WorkflowViewActorV1.spawn(runtime);
            var sink = sink(runtime);
            view.raw().getReady().get(5, TimeUnit.SECONDS);
            sink.getReady().get(5, TimeUnit.SECONDS);
            var viewOut = view.outputs();
            var sinkOut = sink.outputs();

            view.send(new WorkflowViewActorV1.Configure("run-ui"));
            nextView(viewOut);
            view.send(new WorkflowViewActorV1.Subscribe(
                    "browser", sink));
            nextView(viewOut);

            // Both durable projections have the same journal transition
            // sequence, but the effect lifecycle changed.
            view.send(new WorkflowViewActorV1.Publish(
                    projection(7, 1, 0)));
            nextView(viewOut);
            var first = WorkflowViewActorV1.decodeUiUpdate(
                    nextRaw(sinkOut));

            view.send(new WorkflowViewActorV1.Publish(
                    projection(7, 0, 1)));
            nextView(viewOut);
            var second = WorkflowViewActorV1.decodeUiUpdate(
                    nextRaw(sinkOut));

            assertEquals(
                    first.projection().lastTransitionSequence(),
                    second.projection().lastTransitionSequence());
            assertEquals(
                    first.viewRevision() + 1,
                    second.viewRevision());
            assertEquals(0, second.projection().pendingEffects());
            assertEquals(1, second.projection().claimedEffects());
        }
    }

    @Test
    void lateSubscriberGetsLatestSnapshotImmediately()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var view = WorkflowViewActorV1.spawn(runtime);
            var sink = sink(runtime);
            view.raw().getReady().get(5, TimeUnit.SECONDS);
            sink.getReady().get(5, TimeUnit.SECONDS);
            var viewOut = view.outputs();
            var sinkOut = sink.outputs();

            view.send(new WorkflowViewActorV1.Configure("run-ui"));
            nextView(viewOut);
            view.send(new WorkflowViewActorV1.Publish(
                    projection(4, 1, 0)));
            nextView(viewOut);

            view.send(new WorkflowViewActorV1.Subscribe(
                    "late-browser", sink));
            nextView(viewOut);

            var snapshot = WorkflowViewActorV1.decodeUiUpdate(
                    nextRaw(sinkOut));
            assertEquals(1L, snapshot.viewRevision());
            assertEquals(
                    4L,
                    snapshot.projection()
                            .lastTransitionSequence());
        }
    }

    @Test
    void restartedViewUsesNewEpoch() throws Exception {
        String firstEpoch;
        try (var runtime = new ActorRuntime()) {
            var first = WorkflowViewActorV1.spawn(runtime);
            first.raw().getReady().get(5, TimeUnit.SECONDS);
            var out = first.outputs();
            first.send(new WorkflowViewActorV1.Configure("run-ui"));
            firstEpoch = assertInstanceOf(
                    WorkflowViewActorV1.Ready.class,
                    nextView(out)).epoch();
            first.send(new WorkflowViewActorV1.Shutdown());
            nextView(out);
            first.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var runtime = new ActorRuntime()) {
            var second = WorkflowViewActorV1.spawn(runtime);
            second.raw().getReady().get(5, TimeUnit.SECONDS);
            var out = second.outputs();
            second.send(new WorkflowViewActorV1.Configure("run-ui"));
            String secondEpoch = assertInstanceOf(
                    WorkflowViewActorV1.Ready.class,
                    nextView(out)).epoch();
            assertNotEquals(firstEpoch, secondEpoch);
        }
    }

    private static WorkflowRunProjectionV1 projection(
            long transitionSequence,
            int pending,
            int claimed) {
        return new WorkflowRunProjectionV1(
                "run-ui",
                DurableDevLoopV1.Phase.CODING,
                1,
                3,
                null,
                null,
                transitionSequence,
                pending,
                claimed,
                0,
                0,
                0,
                false,
                false,
                false,
                null);
    }

    private static ActorRuntime.ActorRef<Object> sink(
            ActorRuntime runtime) {
        return runtime.spawnPrivate(ignored ->
                (message, context) ->
                        context.runtime()
                                .emitCurrentActorOutput(message));
    }

    private static Object nextView(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return WorkflowViewActorV1.decodeOutput(
                step.value().value());
    }

    private static Object nextRaw(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return step.value().value();
    }
}
