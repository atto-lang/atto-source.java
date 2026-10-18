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
    void supervisorCancellationTerminatesDurableWorkflowFirst()
            throws Exception {
        Path workflowDb = tempDir.resolve("cancel-workflow.db");
        Path supervisorDb = tempDir.resolve("cancel-supervisor.db");
        var loop = new DurableDevLoopV1("run-cancel-authority", 3);
        try (var workflow = SqliteWorkflowJournalV1.open(workflowDb)) {
            workflow.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
        }

        var policy = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                1_000L,
                3,
                2_000_000L,
                1,
                false);

        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowSupervisorActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new WorkflowSupervisorActorV1.Configure(
                    supervisorDb.toString(),
                    "run-cancel-authority",
                    policy,
                    100L,
                    workflowDb.toString()));
            next(outputs);

            actor.send(new WorkflowSupervisorActorV1.Cancel(
                    "operator request"));
            var cancelled = assertInstanceOf(
                    WorkflowSupervisorActorV1.UsagePublished.class,
                    next(outputs));
            assertTrue(cancelled.usage().cancelled());
            assertEquals(
                    "operator request",
                    cancelled.usage().cancellationReason());

            actor.send(new WorkflowSupervisorActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var workflow = SqliteWorkflowJournalV1.open(workflowDb)) {
            var snapshot = workflow.replayDevLoop().machine().snapshot();
            assertEquals(
                    DurableDevLoopV1.Phase.CANCELLED,
                    snapshot.phase());
            assertEquals("operator request", snapshot.cancelReason());
            assertEquals(
                    WorkflowJournalV1.EffectState.ABANDONED,
                    workflow.effect(
                            "devloop/run-cancel-authority/iteration/1/code")
                            .orElseThrow()
                            .state());
            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    workflow.effect(
                            "devloop/run-cancel-authority/cancel")
                            .orElseThrow()
                            .state());
        }
    }

    @Test
    void configureRepairsLedgerAfterWorkflowCancellationCommittedFirst()
            throws Exception {
        Path workflowDb = tempDir.resolve("cancel-repair-workflow.db");
        Path supervisorDb = tempDir.resolve("cancel-repair-supervisor.db");
        var loop = new DurableDevLoopV1("run-cancel-repair", 3);
        try (var workflow = SqliteWorkflowJournalV1.open(workflowDb)) {
            workflow.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
            var machine = workflow.replayDevLoop().machine();
            workflow.append(machine.accept(
                    "supervisor-cancel",
                    new DurableDevLoopV1.Cancel(
                            "crash-window cancellation")));
        }

        var policy = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                1_000L,
                3,
                2_000_000L,
                1,
                false);

        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowSupervisorActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new WorkflowSupervisorActorV1.Configure(
                    supervisorDb.toString(),
                    "run-cancel-repair",
                    policy,
                    100L,
                    workflowDb.toString()));
            var ready = assertInstanceOf(
                    WorkflowSupervisorActorV1.Ready.class,
                    next(outputs));
            assertTrue(ready.usage().cancelled());
            assertEquals(
                    "crash-window cancellation",
                    ready.usage().cancellationReason());

            actor.send(new WorkflowSupervisorActorV1.Admit(
                    WorkflowActivityProtocolV1.Kind.CODING,
                    200L,
                    1L,
                    1L,
                    0,
                    false));
            var admission = assertInstanceOf(
                    WorkflowSupervisorActorV1.AdmissionEvaluated.class,
                    next(outputs));
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.CANCEL,
                    admission.admission().decision());

            actor.send(new WorkflowSupervisorActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var ledger =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            supervisorDb,
                            "run-cancel-repair",
                            policy,
                            100L)) {
            assertTrue(ledger.usage().cancelled());
            assertEquals(
                    "crash-window cancellation",
                    ledger.usage().cancellationReason());
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

    @Test
    void reservationMustReferenceDurableEffectWithMatchingKind()
            throws Exception {
        Path db = tempDir.resolve("reserve-authority.db");
        var loop = new DurableDevLoopV1("run-reserve-authority", 3);
        try (var workflow = SqliteWorkflowJournalV1.open(db)) {
            workflow.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
        }

        var policy = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                1_000L,
                3,
                2_000_000L,
                1,
                false);

        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowSupervisorActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new WorkflowSupervisorActorV1.Configure(
                    db.toString(),
                    "run-reserve-authority",
                    policy,
                    100L,
                    db.toString()));
            next(outputs);

            actor.send(new WorkflowSupervisorActorV1.Reserve(
                    "devloop/run-reserve-authority/iteration/1/code",
                    WorkflowActivityProtocolV1.Kind.GIT,
                    200L,
                    100L,
                    100_000L,
                    0,
                    false));

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.raw().getDone().get(
                            5, TimeUnit.SECONDS));
            assertTrue(actor.raw().failure().isPresent());
        }
    }

    @Test
    void settlementOverageCancelsDurableWorkflowImmediately()
            throws Exception {
        Path db = tempDir.resolve("settlement-cancels-workflow.db");
        var loop = new DurableDevLoopV1("run-settlement-cancel", 3);
        try (var workflow = SqliteWorkflowJournalV1.open(db)) {
            workflow.append(loop.accept(
                    "start",
                    new DurableDevLoopV1.Start("goal")));
        }

        var policy = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                500L,
                3,
                2_000_000L,
                1,
                false);

        try (var runtime = new ActorRuntime()) {
            var actor = WorkflowSupervisorActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new WorkflowSupervisorActorV1.Configure(
                    db.toString(),
                    "run-settlement-cancel",
                    policy,
                    100L,
                    db.toString()));
            next(outputs);

            actor.send(new WorkflowSupervisorActorV1.Reserve(
                    "devloop/run-settlement-cancel/iteration/1/code",
                    WorkflowActivityProtocolV1.Kind.CODING,
                    200L,
                    400L,
                    100_000L,
                    0,
                    false));
            var reserved = assertInstanceOf(
                    WorkflowSupervisorActorV1.AdmissionEvaluated.class,
                    next(outputs));
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.ALLOW,
                    reserved.admission().decision());

            actor.send(new WorkflowSupervisorActorV1.SettleUsage(
                    "devloop/run-settlement-cancel/iteration/1/code",
                    700L,
                    100_000L));
            var settled = assertInstanceOf(
                    WorkflowSupervisorActorV1.UsagePublished.class,
                    next(outputs));
            assertTrue(settled.usage().cancelled());
            assertEquals(
                    "model_token_budget_exhausted_after_settlement",
                    settled.usage().cancellationReason());

            actor.send(new WorkflowSupervisorActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var workflow = SqliteWorkflowJournalV1.open(db)) {
            var snapshot = workflow.replayDevLoop().machine().snapshot();
            assertEquals(
                    DurableDevLoopV1.Phase.CANCELLED,
                    snapshot.phase());
            assertEquals(
                    "model_token_budget_exhausted_after_settlement",
                    snapshot.cancelReason());
            assertEquals(
                    WorkflowJournalV1.EffectState.ABANDONED,
                    workflow.effect(
                            "devloop/run-settlement-cancel/iteration/1/code")
                            .orElseThrow()
                            .state());
            assertEquals(
                    WorkflowJournalV1.EffectState.PENDING,
                    workflow.effect(
                            "devloop/run-settlement-cancel/cancel")
                            .orElseThrow()
                            .state());
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
