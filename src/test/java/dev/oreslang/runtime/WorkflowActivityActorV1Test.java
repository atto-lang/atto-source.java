package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowActivityActorV1Test {

    @Test
    void activityActorAuthorizesExactClaimAndAcceptsAttemptBoundOutcome()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowActivityActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new WorkflowActivityActorV1.Configure(
                    "coder-1",
                    WorkflowActivityProtocolV1.Kind.CODING,
                    WorkflowActivityProtocolV1.requiredCapabilities(
                            new DurableDevLoopV1.RequestCode(
                                    1,
                                    "goal",
                                    null,
                                    "devloop/run-1/iteration/1/code"))));
            assertInstanceOf(
                    WorkflowActivityActorV1.Ready.class,
                    next(outputs));

            var claim = new WorkflowJournalV1.Claim(
                    "devloop/run-1/iteration/1/code",
                    new DurableDevLoopV1.RequestCode(
                            1,
                            "goal",
                            null,
                            "devloop/run-1/iteration/1/code"),
                    "claim/1",
                    1,
                    1000L);
            actor.send(new WorkflowActivityActorV1.Dispatch(
                    "run-1", claim));

            var authorized = assertInstanceOf(
                    WorkflowActivityActorV1.Authorized.class,
                    next(outputs));
            assertEquals(
                    "claim/1",
                    authorized.request().claim().claimToken());

            actor.send(new WorkflowActivityActorV1.ReportOutcome(
                    new WorkflowActivityProtocolV1.Succeeded(
                            "devloop/run-1/iteration/1/code",
                            "claim/1",
                            "receipt://code",
                            new DurableDevLoopV1.CodeChanged(
                                    1, "patch://one"))));
            assertInstanceOf(
                    WorkflowActivityActorV1.OutcomeAccepted.class,
                    next(outputs));

            actor.send(new WorkflowActivityActorV1.Shutdown());
            assertInstanceOf(
                    WorkflowActivityActorV1.Stopping.class,
                    next(outputs));
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void terminalActivityAcceptsCompletedOutcomeWithoutReducerEvent()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowActivityActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            var command = new DurableDevLoopV1.CancelWork(
                    "operator",
                    "devloop/run-1/cancel");
            actor.send(new WorkflowActivityActorV1.Configure(
                    "cancel-1",
                    WorkflowActivityProtocolV1.Kind.CANCELLATION,
                    WorkflowActivityProtocolV1.requiredCapabilities(
                            command)));
            next(outputs);

            var claim = new WorkflowJournalV1.Claim(
                    command.effectKey(),
                    command,
                    "claim/cancel",
                    1,
                    1000L);
            actor.send(new WorkflowActivityActorV1.Dispatch(
                    "run-1", claim));
            next(outputs);

            var completed = new WorkflowActivityProtocolV1.Completed(
                    claim.effectKey(),
                    claim.claimToken(),
                    "receipt://cancel");
            actor.send(new WorkflowActivityActorV1.ReportOutcome(
                    completed));
            var accepted = assertInstanceOf(
                    WorkflowActivityActorV1.OutcomeAccepted.class,
                    next(outputs));
            assertEquals(completed, accepted.outcome());

            actor.send(new WorkflowActivityActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void claimLeasedToDifferentWorkerIsRejected()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowActivityActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            var command = new DurableDevLoopV1.PushCode(
                    1, "patch://one", "devloop/run-1/iteration/1/push");
            actor.send(new WorkflowActivityActorV1.Configure(
                    "git-worker-b",
                    WorkflowActivityProtocolV1.Kind.GIT,
                    WorkflowActivityProtocolV1.requiredCapabilities(
                            command)));
            next(outputs);

            actor.send(new WorkflowActivityActorV1.Dispatch(
                    "run-1",
                    new WorkflowJournalV1.Claim(
                            command.effectKey(),
                            command,
                            "claim/current",
                            1,
                            1000L,
                            "git-worker-a")));

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.raw().getDone().get(
                            5, TimeUnit.SECONDS));
            assertTrue(actor.raw().failure().isPresent());
        }
    }

    @Test
    void staleClaimOutcomeFailsWorkerActorInsteadOfCompletingNewAttempt()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowActivityActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            var command = new DurableDevLoopV1.PushCode(
                    1, "patch://one", "devloop/run-1/iteration/1/push");
            actor.send(new WorkflowActivityActorV1.Configure(
                    "git-1",
                    WorkflowActivityProtocolV1.Kind.GIT,
                    WorkflowActivityProtocolV1.requiredCapabilities(
                            command)));
            next(outputs);

            actor.send(new WorkflowActivityActorV1.Dispatch(
                    "run-1",
                    new WorkflowJournalV1.Claim(
                            command.effectKey(),
                            command,
                            "claim/current",
                            2,
                            1000L)));
            next(outputs);

            actor.send(new WorkflowActivityActorV1.ReportOutcome(
                    new WorkflowActivityProtocolV1.KnownUnexecuted(
                            command.effectKey(),
                            "claim/old",
                            "worker://not-started")));

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.raw().getDone().get(5, TimeUnit.SECONDS));
            assertTrue(actor.raw().failure().isPresent());
        }
    }

    private static Object next(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return WorkflowActivityActorV1.decodeOutput(
                step.value().value());
    }
}
