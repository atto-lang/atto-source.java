package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

final class DurableDevLoopEndToEndV1Test {

    @TempDir
    Path tempDir;

    @Test
    void failureLoopThenSuccessCompletesThroughActorsAndDurableStore()
            throws Exception {
        Path database = tempDir.resolve("devloop-e2e.db");
        byte[] webhookSecret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);

        try (var runtime = new ActorRuntime()) {
            var workflow = DurableDevLoopActorV1.spawn(runtime);
            var supervisor = WorkflowSupervisorActorV1.spawn(runtime);
            var coding = configuredActivity(
                    runtime,
                    "coding-worker",
                    WorkflowActivityProtocolV1.Kind.CODING);
            var git = configuredActivity(
                    runtime,
                    "git-worker",
                    WorkflowActivityProtocolV1.Kind.GIT);
            var ci = configuredActivity(
                    runtime,
                    "ci-worker",
                    WorkflowActivityProtocolV1.Kind.CI);
            var evaluation = configuredActivity(
                    runtime,
                    "evaluation-worker",
                    WorkflowActivityProtocolV1.Kind.EVALUATION);
            var bugs = configuredActivity(
                    runtime,
                    "bug-worker",
                    WorkflowActivityProtocolV1.Kind.BUG_REPORT);
            var approval = configuredActivity(
                    runtime,
                    "approval-worker",
                    WorkflowActivityProtocolV1.Kind.APPROVAL);
            var webhook = GitHubWebhookActorV1.spawn(runtime);

            awaitReady(workflow.raw());
            awaitReady(supervisor.raw());
            awaitReady(webhook.raw());

            var workflowOut = workflow.outputs();
            var supervisorOut = supervisor.outputs();
            var webhookOut = webhook.outputs();

            supervisor.send(new WorkflowSupervisorActorV1.Configure(
                    database.toString(),
                    "run-e2e",
                    new WorkflowSupervisorPolicyV1.Policy(
                            60_000L,
                            100_000L,
                            10,
                            50_000_000L,
                            2,
                            false),
                    100L,
                    database.toString()));
            assertInstanceOf(
                    WorkflowSupervisorActorV1.Ready.class,
                    nextSupervisor(supervisorOut));

            workflow.send(new DurableDevLoopActorV1.OpenRun(
                    database.toString(),
                    "run-e2e",
                    3,
                    "start",
                    "repair actor pipeline"));
            var opened = assertInstanceOf(
                    DurableDevLoopActorV1.Opened.class,
                    nextWorkflow(workflowOut));
            assertEquals(
                    DurableDevLoopV1.Phase.CODING,
                    opened.projection().phase());

            webhook.send(new GitHubWebhookActorV1.Configure(
                    webhookSecret,
                    database.toString()));
            assertInstanceOf(
                    GitHubWebhookActorV1.Ready.class,
                    nextWebhook(webhookOut));

            // Iteration 1: code.
            var code1 = claim(workflow, workflowOut, "coding-worker", 200L);
            assertInstanceOf(
                    DurableDevLoopV1.RequestCode.class,
                    code1.command());
            reserve(
                    supervisor,
                    supervisorOut,
                    code1,
                    WorkflowActivityProtocolV1.Kind.CODING,
                    210L,
                    1_000L,
                    100_000L);
            var code1Event = new DurableDevLoopV1.CodeChanged(
                    1, "patch://iteration-1");
            executeActivity(
                    coding,
                    code1,
                    "receipt://code/1",
                    code1Event);
            settle(
                    supervisor,
                    supervisorOut,
                    code1,
                    900L,
                    90_000L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    code1,
                    "receipt://code/1",
                    220L,
                    "code-1",
                    code1Event,
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH);

            // Iteration 1: push exact commit A.
            String commit1 = sha('a');
            String tree1 = sha('b');
            var push1 = claim(workflow, workflowOut, "git-worker", 230L);
            assertInstanceOf(
                    DurableDevLoopV1.PushCode.class,
                    push1.command());
            reserve(
                    supervisor,
                    supervisorOut,
                    push1,
                    WorkflowActivityProtocolV1.Kind.GIT,
                    240L,
                    0L,
                    0L);
            var push1Event = new DurableDevLoopV1.PushRecorded(
                    1, commit1);
            executeActivity(
                    git,
                    push1,
                    "github://push/1",
                    push1Event);
            settle(
                    supervisor,
                    supervisorOut,
                    push1,
                    0L,
                    0L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    push1,
                    "github://push/1",
                    250L,
                    "push-1",
                    push1Event,
                    DurableDevLoopV1.Phase.WAITING_FOR_CI);

            // Iteration 1: durable CI wait + authenticated webhook.
            var ci1 = claim(workflow, workflowOut, "ci-worker", 260L);
            assertInstanceOf(
                    DurableDevLoopV1.AwaitCi.class,
                    ci1.command());
            reserve(
                    supervisor,
                    supervisorOut,
                    ci1,
                    WorkflowActivityProtocolV1.Kind.CI,
                    270L,
                    0L,
                    0L);
            dispatch(ci, ci1);

            webhook.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-e2e",
                            "org/repo",
                            commit1,
                            tree1,
                            "ci")));
            nextWebhook(webhookOut);

            byte[] failBody = workflowRunBody(
                    "org/repo",
                    commit1,
                    1001L,
                    "ci",
                    "failure",
                    "https://github.example/actions/runs/1001");
            webhook.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    headers(webhookSecret, failBody, "delivery-ci-1"),
                    failBody,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit1,
                            tree1,
                            "tree-proof://1001")));
            var ci1Matched = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    nextWebhook(webhookOut));

            reportActivity(
                    ci,
                    new WorkflowActivityProtocolV1.Succeeded(
                            ci1.effectKey(),
                            ci1.claimToken(),
                            "github://workflow/1001",
                            ci1Matched.event()));
            settle(
                    supervisor,
                    supervisorOut,
                    ci1,
                    0L,
                    0L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    ci1,
                    "github://workflow/1001",
                    280L,
                    ci1Matched.deliveryId(),
                    ci1Matched.event(),
                    DurableDevLoopV1.Phase.WRITING_BUG_REPORT);

            // Bug report closes iteration 1 and feeds iteration 2.
            var bug1 = claim(workflow, workflowOut, "bug-worker", 290L);
            assertInstanceOf(
                    DurableDevLoopV1.CreateBugReport.class,
                    bug1.command());
            reserve(
                    supervisor,
                    supervisorOut,
                    bug1,
                    WorkflowActivityProtocolV1.Kind.BUG_REPORT,
                    300L,
                    500L,
                    50_000L);
            var bug1Event =
                    new DurableDevLoopV1.BugReportCreated(
                            1,
                            commit1,
                            "github://issues/42");
            executeActivity(
                    bugs,
                    bug1,
                    "github://issues/42",
                    bug1Event);
            settle(
                    supervisor,
                    supervisorOut,
                    bug1,
                    400L,
                    40_000L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    bug1,
                    "github://issues/42",
                    310L,
                    "bug-1",
                    bug1Event,
                    DurableDevLoopV1.Phase.CODING);

            // Iteration 2 code + push.
            var code2 = claim(workflow, workflowOut, "coding-worker", 320L);
            reserve(
                    supervisor,
                    supervisorOut,
                    code2,
                    WorkflowActivityProtocolV1.Kind.CODING,
                    325L,
                    1_000L,
                    100_000L);
            var code2Event = new DurableDevLoopV1.CodeChanged(
                    2, "patch://iteration-2");
            executeActivity(
                    coding,
                    code2,
                    "receipt://code/2",
                    code2Event);
            settle(
                    supervisor,
                    supervisorOut,
                    code2,
                    800L,
                    80_000L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    code2,
                    "receipt://code/2",
                    330L,
                    "code-2",
                    code2Event,
                    DurableDevLoopV1.Phase.WAITING_FOR_PUSH);

            String commit2 = sha('c');
            String tree2 = sha('d');
            var push2 = claim(workflow, workflowOut, "git-worker", 340L);
            reserve(
                    supervisor,
                    supervisorOut,
                    push2,
                    WorkflowActivityProtocolV1.Kind.GIT,
                    345L,
                    0L,
                    0L);
            var push2Event = new DurableDevLoopV1.PushRecorded(
                    2, commit2);
            executeActivity(
                    git,
                    push2,
                    "github://push/2",
                    push2Event);
            settle(
                    supervisor,
                    supervisorOut,
                    push2,
                    0L,
                    0L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    push2,
                    "github://push/2",
                    350L,
                    "push-2",
                    push2Event,
                    DurableDevLoopV1.Phase.WAITING_FOR_CI);

            // Replace correlation with iteration-2 exact identity.
            webhook.send(new GitHubWebhookActorV1.UnregisterRun(
                    "run-e2e"));
            nextWebhook(webhookOut);
            webhook.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-e2e",
                            "org/repo",
                            commit2,
                            tree2,
                            "ci")));
            nextWebhook(webhookOut);

            var ci2 = claim(workflow, workflowOut, "ci-worker", 360L);
            reserve(
                    supervisor,
                    supervisorOut,
                    ci2,
                    WorkflowActivityProtocolV1.Kind.CI,
                    365L,
                    0L,
                    0L);
            dispatch(ci, ci2);
            byte[] successBody = workflowRunBody(
                    "org/repo",
                    commit2,
                    1002L,
                    "ci",
                    "success",
                    "https://github.example/actions/runs/1002");
            webhook.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    headers(webhookSecret, successBody, "delivery-ci-2"),
                    successBody,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit2,
                            tree2,
                            "tree-proof://1002")));
            var ci2Matched = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    nextWebhook(webhookOut));
            reportActivity(
                    ci,
                    new WorkflowActivityProtocolV1.Succeeded(
                            ci2.effectKey(),
                            ci2.claimToken(),
                            "github://workflow/1002",
                            ci2Matched.event()));
            settle(
                    supervisor,
                    supervisorOut,
                    ci2,
                    0L,
                    0L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    ci2,
                    "github://workflow/1002",
                    370L,
                    ci2Matched.deliveryId(),
                    ci2Matched.event(),
                    DurableDevLoopV1.Phase.EVALUATING);

            // Evaluation passes.
            var eval2 = claim(workflow, workflowOut, "evaluation-worker", 380L);
            reserve(
                    supervisor,
                    supervisorOut,
                    eval2,
                    WorkflowActivityProtocolV1.Kind.EVALUATION,
                    390L,
                    500L,
                    50_000L);
            var pass =
                    new DurableDevLoopV1.EvaluationPassed(commit2);
            executeActivity(
                    evaluation,
                    eval2,
                    "receipt://evaluation/2",
                    pass);
            settle(
                    supervisor,
                    supervisorOut,
                    eval2,
                    450L,
                    45_000L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    eval2,
                    "receipt://evaluation/2",
                    400L,
                    "evaluation-2",
                    pass,
                    DurableDevLoopV1.Phase.WAITING_FOR_APPROVAL);

            // Approval is itself a durable activity/effect.
            var approve = claim(
                    workflow, workflowOut, "approval-worker", 410L);
            reserve(
                    supervisor,
                    supervisorOut,
                    approve,
                    WorkflowActivityProtocolV1.Kind.APPROVAL,
                    415L,
                    0L,
                    0L);
            var approved =
                    new DurableDevLoopV1.ApprovalGranted(commit2);
            executeActivity(
                    approval,
                    approve,
                    "approval://human/7",
                    approved);
            settle(
                    supervisor,
                    supervisorOut,
                    approve,
                    0L,
                    0L);
            completeAndDeliver(
                    workflow,
                    workflowOut,
                    approve,
                    "approval://human/7",
                    420L,
                    "approval-2",
                    approved,
                    DurableDevLoopV1.Phase.COMPLETED);

            workflow.send(new DurableDevLoopActorV1.QueryProjection());
            var terminal = assertInstanceOf(
                    DurableDevLoopActorV1.ProjectionPublished.class,
                    nextWorkflow(workflowOut));
            assertTrue(terminal.projection().terminal());
            assertEquals(2, terminal.projection().iteration());
            assertEquals(
                    DurableDevLoopV1.Phase.COMPLETED,
                    terminal.projection().phase());

            shutdown(coding);
            shutdown(git);
            shutdown(ci);
            shutdown(evaluation);
            shutdown(bugs);
            shutdown(approval);

            webhook.send(new GitHubWebhookActorV1.Shutdown());
            nextWebhook(webhookOut);
            webhook.raw().getDone().get(5, TimeUnit.SECONDS);

            supervisor.send(new WorkflowSupervisorActorV1.Shutdown());
            nextSupervisor(supervisorOut);
            supervisor.raw().getDone().get(5, TimeUnit.SECONDS);

            workflow.send(new DurableDevLoopActorV1.Shutdown("done"));
            nextWorkflow(workflowOut);
            workflow.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        // Process-level truth survives actor/runtime teardown.
        try (var store = SqliteWorkflowJournalV1.open(database)) {
            var replay = store.replayDevLoop().machine().snapshot();
            assertEquals(DurableDevLoopV1.Phase.COMPLETED, replay.phase());
            assertEquals(2, replay.iteration());
            assertTrue(store.snapshot().completedEffects() >= 7);
        }

        try (var ledger = SqliteWorkflowSupervisorLedgerV1.open(
                database,
                "run-e2e",
                new WorkflowSupervisorPolicyV1.Policy(
                        60_000L,
                        100_000L,
                        10,
                        50_000_000L,
                        2,
                        false),
                100L)) {
            var usage = ledger.usage();
            assertEquals(2_550L, usage.modelTokens());
            assertEquals(255_000L, usage.spendMicros());
            assertEquals(2, usage.ciRuns());
            assertFalse(usage.cancelled());
        }
    }

    @Test
    void cancellationTerminalEffectCompletesThroughActivityActor()
            throws Exception {
        Path database = tempDir.resolve("devloop-cancel-e2e.db");
        String runId = "run-cancel-e2e";

        try (var runtime = new ActorRuntime()) {
            var workflow = DurableDevLoopActorV1.spawn(runtime);
            var supervisor = WorkflowSupervisorActorV1.spawn(runtime);
            var cancellation = configuredActivity(
                    runtime,
                    "cancel-worker",
                    WorkflowActivityProtocolV1.Kind.CANCELLATION);

            awaitReady(workflow.raw());
            awaitReady(supervisor.raw());

            var workflowOut = workflow.outputs();
            var supervisorOut = supervisor.outputs();

            workflow.send(new DurableDevLoopActorV1.OpenRun(
                    database.toString(),
                    runId,
                    3,
                    "start",
                    "cancel workflow"));
            assertInstanceOf(
                    DurableDevLoopActorV1.Opened.class,
                    nextWorkflow(workflowOut));

            supervisor.send(new WorkflowSupervisorActorV1.Configure(
                    database.toString(),
                    runId,
                    new WorkflowSupervisorPolicyV1.Policy(
                            60_000L,
                            10_000L,
                            5,
                            5_000_000L,
                            1,
                            false),
                    100L,
                    database.toString()));
            assertInstanceOf(
                    WorkflowSupervisorActorV1.Ready.class,
                    nextSupervisor(supervisorOut));

            supervisor.send(new WorkflowSupervisorActorV1.Cancel(
                    "operator request"));
            var usage = assertInstanceOf(
                    WorkflowSupervisorActorV1.UsagePublished.class,
                    nextSupervisor(supervisorOut));
            assertTrue(usage.usage().cancelled());

            workflow.send(new DurableDevLoopActorV1.ClaimEffect(
                    "cancel-worker",
                    200L,
                    100L));
            var claimed = assertInstanceOf(
                    DurableDevLoopActorV1.EffectClaimed.class,
                    nextWorkflow(workflowOut));
            assertInstanceOf(
                    DurableDevLoopV1.CancelWork.class,
                    claimed.claim().command());

            cancellation.send(new WorkflowActivityActorV1.Dispatch(
                    runId,
                    claimed.claim()));
            assertInstanceOf(
                    WorkflowActivityActorV1.Authorized.class,
                    nextActivity(cancellation.outputs()));

            var completed = new WorkflowActivityProtocolV1.Completed(
                    claimed.claim().effectKey(),
                    claimed.claim().claimToken(),
                    "receipt://cancel/e2e");
            cancellation.send(new WorkflowActivityActorV1.ReportOutcome(
                    completed));
            assertInstanceOf(
                    WorkflowActivityActorV1.OutcomeAccepted.class,
                    nextActivity(cancellation.outputs()));

            workflow.send(new DurableDevLoopActorV1.CompleteEffect(
                    claimed.claim().effectKey(),
                    claimed.claim().claimToken(),
                    completed.receiptRef(),
                    250L));
            var updated = assertInstanceOf(
                    DurableDevLoopActorV1.EffectUpdated.class,
                    nextWorkflow(workflowOut));
            assertEquals(
                    DurableDevLoopV1.Phase.CANCELLED,
                    updated.projection().phase());
            assertEquals(1, updated.projection().completedEffects());
            assertEquals(1, updated.projection().abandonedEffects());

            shutdown(cancellation);

            supervisor.send(new WorkflowSupervisorActorV1.Shutdown());
            nextSupervisor(supervisorOut);
            supervisor.raw().getDone().get(5, TimeUnit.SECONDS);

            workflow.send(new DurableDevLoopActorV1.Shutdown(
                    "test complete"));
            assertInstanceOf(
                    DurableDevLoopActorV1.Stopping.class,
                    nextWorkflow(workflowOut));
            workflow.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var store = SqliteWorkflowJournalV1.open(database)) {
            assertEquals(
                    DurableDevLoopV1.Phase.CANCELLED,
                    store.replayDevLoop().machine().snapshot().phase());
            var effect = store.effect(
                    "devloop/" + runId + "/cancel").orElseThrow();
            assertEquals(
                    WorkflowJournalV1.EffectState.COMPLETED,
                    effect.state());
            assertEquals(
                    "receipt://cancel/e2e",
                    effect.receiptRef());
        }
    }

    private static WorkflowActivityActorV1.Ref configuredActivity(
            ActorRuntime runtime,
            String workerId,
            WorkflowActivityProtocolV1.Kind kind) throws Exception {
        var actor = WorkflowActivityActorV1.spawn(runtime);
        awaitReady(actor.raw());
        actor.send(new WorkflowActivityActorV1.Configure(
                workerId,
                kind,
                capabilities(kind)));
        assertInstanceOf(
                WorkflowActivityActorV1.Ready.class,
                nextActivity(actor.outputs()));
        return actor;
    }

    private static Set<WorkflowActivityProtocolV1.Capability> capabilities(
            WorkflowActivityProtocolV1.Kind kind) {
        return switch (kind) {
            case CODING -> Set.of(
                    WorkflowActivityProtocolV1.Capability.REPOSITORY_READ,
                    WorkflowActivityProtocolV1.Capability.WORKSPACE_WRITE,
                    WorkflowActivityProtocolV1.Capability.MODEL_INVOKE);
            case GIT -> Set.of(
                    WorkflowActivityProtocolV1.Capability.REPOSITORY_READ,
                    WorkflowActivityProtocolV1.Capability.GIT_PUSH);
            case CI -> Set.of(
                    WorkflowActivityProtocolV1.Capability.CI_READ);
            case EVALUATION -> Set.of(
                    WorkflowActivityProtocolV1.Capability.REPOSITORY_READ,
                    WorkflowActivityProtocolV1.Capability.MODEL_INVOKE);
            case BUG_REPORT -> Set.of(
                    WorkflowActivityProtocolV1.Capability.MODEL_INVOKE,
                    WorkflowActivityProtocolV1.Capability.ISSUE_WRITE);
            case APPROVAL -> Set.of(
                    WorkflowActivityProtocolV1.Capability.APPROVAL_READ);
            case CANCELLATION -> Set.of(
                    WorkflowActivityProtocolV1.Capability.CANCEL_REMOTE_WORK);
            case ESCALATION -> Set.of(
                    WorkflowActivityProtocolV1.Capability.NOTIFY_HUMAN);
        };
    }

    private static WorkflowJournalV1.Claim claim(
            DurableDevLoopActorV1.Ref workflow,
            ActorRuntime.ActorOutputStream<Object> outputs,
            String worker,
            long now) throws Exception {
        workflow.send(new DurableDevLoopActorV1.ClaimEffect(
                worker, now, 1_000L));
        return assertInstanceOf(
                DurableDevLoopActorV1.EffectClaimed.class,
                nextWorkflow(outputs))
                .claim();
    }

    private static void completeAndDeliver(
            DurableDevLoopActorV1.Ref workflow,
            ActorRuntime.ActorOutputStream<Object> outputs,
            WorkflowJournalV1.Claim claim,
            String receipt,
            long now,
            String eventId,
            DurableDevLoopV1.Event event,
            DurableDevLoopV1.Phase expectedPhase) throws Exception {
        workflow.send(new DurableDevLoopActorV1.CompleteAndDeliver(
                claim.effectKey(),
                claim.claimToken(),
                receipt,
                now,
                eventId,
                event));
        var transition = assertInstanceOf(
                DurableDevLoopActorV1.Transitioned.class,
                nextWorkflow(outputs));
        assertEquals(expectedPhase, transition.projection().phase());
    }

    private static void executeActivity(
            WorkflowActivityActorV1.Ref actor,
            WorkflowJournalV1.Claim claim,
            String receipt,
            DurableDevLoopV1.Event event) throws Exception {
        dispatch(actor, claim);
        reportActivity(
                actor,
                new WorkflowActivityProtocolV1.Succeeded(
                        claim.effectKey(),
                        claim.claimToken(),
                        receipt,
                        event));
    }

    private static void dispatch(
            WorkflowActivityActorV1.Ref actor,
            WorkflowJournalV1.Claim claim) throws Exception {
        actor.send(new WorkflowActivityActorV1.Dispatch(
                "run-e2e", claim));
        assertInstanceOf(
                WorkflowActivityActorV1.Authorized.class,
                nextActivity(actor.outputs()));
    }

    private static void reportActivity(
            WorkflowActivityActorV1.Ref actor,
            WorkflowActivityProtocolV1.Outcome outcome) throws Exception {
        actor.send(new WorkflowActivityActorV1.ReportOutcome(outcome));
        assertInstanceOf(
                WorkflowActivityActorV1.OutcomeAccepted.class,
                nextActivity(actor.outputs()));
    }

    private static void reserve(
            WorkflowSupervisorActorV1.Ref supervisor,
            ActorRuntime.ActorOutputStream<Object> outputs,
            WorkflowJournalV1.Claim claim,
            WorkflowActivityProtocolV1.Kind kind,
            long now,
            long tokens,
            long spend) throws Exception {
        supervisor.send(new WorkflowSupervisorActorV1.Reserve(
                claim.effectKey(),
                kind,
                now,
                tokens,
                spend,
                0,
                false));
        var result = assertInstanceOf(
                WorkflowSupervisorActorV1.AdmissionEvaluated.class,
                nextSupervisor(outputs));
        assertEquals(
                WorkflowSupervisorPolicyV1.Decision.ALLOW,
                result.admission().decision());
    }

    private static void settle(
            WorkflowSupervisorActorV1.Ref supervisor,
            ActorRuntime.ActorOutputStream<Object> outputs,
            WorkflowJournalV1.Claim claim,
            long actualTokens,
            long actualSpend) throws Exception {
        supervisor.send(new WorkflowSupervisorActorV1.SettleUsage(
                claim.effectKey(),
                actualTokens,
                actualSpend));
        var result = assertInstanceOf(
                WorkflowSupervisorActorV1.UsagePublished.class,
                nextSupervisor(outputs));
        assertFalse(result.usage().cancelled());
    }

    private static void shutdown(
            WorkflowActivityActorV1.Ref actor) throws Exception {
        actor.send(new WorkflowActivityActorV1.Shutdown());
        assertInstanceOf(
                WorkflowActivityActorV1.Stopping.class,
                nextActivity(actor.outputs()));
        actor.raw().getDone().get(5, TimeUnit.SECONDS);
    }

    private static byte[] workflowRunBody(
            String repository,
            String commit,
            long runId,
            String workflow,
            String conclusion,
            String htmlUrl) {
        String json = "{"
                + "\"action\":\"completed\","
                + "\"repository\":{"
                + "\"full_name\":\"" + repository + "\"},"
                + "\"workflow_run\":{"
                + "\"id\":" + runId + ","
                + "\"name\":\"" + workflow + "\","
                + "\"head_sha\":\"" + commit + "\","
                + "\"status\":\"completed\","
                + "\"conclusion\":\"" + conclusion + "\","
                + "\"html_url\":\"" + htmlUrl + "\""
                + "}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static GitHubWebhookSecurityV1.Headers headers(
            byte[] secret,
            byte[] body,
            String delivery) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String signature =
                "sha256="
                        + HexFormat.of().formatHex(
                                mac.doFinal(body));
        return new GitHubWebhookSecurityV1.Headers(
                signature,
                delivery,
                "workflow_run");
    }

    private static Object nextWorkflow(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return DurableDevLoopActorV1.decodeOutput(
                step.value().value());
    }

    private static Object nextActivity(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return WorkflowActivityActorV1.decodeOutput(
                step.value().value());
    }

    private static Object nextWebhook(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return GitHubWebhookActorV1.decodeOutput(
                step.value().value());
    }

    private static Object nextSupervisor(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return WorkflowSupervisorActorV1.decodeOutput(
                step.value().value());
    }

    private static void awaitReady(
            ActorRuntime.ActorRef<Object> actor)
            throws Exception {
        actor.getReady().get(5, TimeUnit.SECONDS);
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
