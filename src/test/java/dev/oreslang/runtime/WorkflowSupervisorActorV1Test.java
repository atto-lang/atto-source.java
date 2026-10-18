package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowSupervisorActorV1Test {

    @TempDir
    Path tempDir;

    @Test
    void supervisorActorPersistsUsageAcrossActorRestart() throws Exception {
        Path db = tempDir.resolve("supervisor-actor.db");
        var policy = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                1_000L,
                3,
                2_000_000L,
                1,
                true);

        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowSupervisorActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new WorkflowSupervisorActorV1.Configure(
                    db.toString(),
                    "run-supervisor",
                    policy,
                    100L));
            assertInstanceOf(
                    WorkflowSupervisorActorV1.Ready.class,
                    next(outputs));

            actor.send(new WorkflowSupervisorActorV1.Admit(
                    WorkflowActivityProtocolV1.Kind.CODING,
                    200L,
                    500L,
                    250_000L,
                    0,
                    false));
            var admitted = assertInstanceOf(
                    WorkflowSupervisorActorV1.AdmissionEvaluated.class,
                    next(outputs));
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.ALLOW,
                    admitted.admission().decision());

            actor.send(new WorkflowSupervisorActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowSupervisorActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new WorkflowSupervisorActorV1.Configure(
                    db.toString(),
                    "run-supervisor",
                    policy,
                    100L));
            var ready = assertInstanceOf(
                    WorkflowSupervisorActorV1.Ready.class,
                    next(outputs));
            assertEquals(500L, ready.usage().modelTokens());
            assertEquals(250_000L, ready.usage().spendMicros());

            actor.send(new WorkflowSupervisorActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void protectedWriteProducesHumanGateWithoutConsumingBudget()
            throws Exception {
        Path db = tempDir.resolve("supervisor-human.db");
        var policy = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                1_000L,
                3,
                2_000_000L,
                1,
                true);

        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowSupervisorActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();
            actor.send(new WorkflowSupervisorActorV1.Configure(
                    db.toString(), "run-human", policy, 100L));
            next(outputs);

            actor.send(new WorkflowSupervisorActorV1.Admit(
                    WorkflowActivityProtocolV1.Kind.GIT,
                    200L,
                    0L,
                    0L,
                    0,
                    true));
            var result = assertInstanceOf(
                    WorkflowSupervisorActorV1.AdmissionEvaluated.class,
                    next(outputs));
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.REQUIRE_HUMAN,
                    result.admission().decision());
            assertEquals(0L, result.admission().usage().spendMicros());

            actor.send(new WorkflowSupervisorActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void supervisorUsesDurableAmbiguityInsteadOfCallerReportedCount()
            throws Exception {
        Path db = tempDir.resolve("supervisor-durable-ambiguity.db");
        var loop = new DurableDevLoopV1("run-durable-ambiguity", 3);

        try (var workflow = SqliteWorkflowJournalV1.open(db)) {
            workflow.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            var claim = workflow.claimNext(
                    "coder", 100L, 10L).orElseThrow();
            assertEquals(1, workflow.expireClaims(110L));
            assertEquals(
                    WorkflowJournalV1.EffectState.AMBIGUOUS,
                    workflow.effect(claim.effectKey()).orElseThrow().state());
        }

        var policy = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                1_000L,
                3,
                2_000_000L,
                0,
                false);

        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowSupervisorActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new WorkflowSupervisorActorV1.Configure(
                    db.toString(),
                    "run-durable-ambiguity",
                    policy,
                    100L,
                    db.toString()));
            next(outputs);

            actor.send(new WorkflowSupervisorActorV1.Admit(
                    WorkflowActivityProtocolV1.Kind.GIT,
                    200L,
                    0L,
                    0L,
                    0,
                    false));
            var result = assertInstanceOf(
                    WorkflowSupervisorActorV1.AdmissionEvaluated.class,
                    next(outputs));
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.REQUIRE_HUMAN,
                    result.admission().decision());
            assertEquals(
                    "ambiguous_effect_budget_exhausted",
                    result.admission().reason());
            assertEquals(
                    1,
                    result.admission().usage().ambiguousEffects());

            actor.send(new WorkflowSupervisorActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    private static Object next(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return WorkflowSupervisorActorV1.decodeOutput(
                step.value().value());
    }
}
