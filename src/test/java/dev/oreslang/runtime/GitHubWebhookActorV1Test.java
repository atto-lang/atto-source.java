package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

final class GitHubWebhookActorV1Test {

    @TempDir
    Path tempDir;

    @Test
    void sharedIngressAuthenticatesAndEmitsExactCiMatch()
            throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        String commit = sha('a');
        String tree = sha('b');
        byte[] body = workflowRunBody(
                "org/repo",
                commit,
                42L,
                "ci",
                "success",
                "https://github.example/actions/runs/42");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(secret));
            assertInstanceOf(
                    GitHubWebhookActorV1.Ready.class,
                    next(outputs));

            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-1",
                            "org/repo",
                            commit,
                            tree,
                            "ci")));
            next(outputs);

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + hmac(secret, body),
                            "delivery-1",
                            "workflow_run"),
                    body,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit,
                            tree,
                            "tree-proof://42")));

            var matched = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));
            assertEquals("run-1", matched.runId());
            assertEquals("delivery-1", matched.deliveryId());
            assertEquals("tree-proof://42",
                    matched.treeProofReceiptRef());
            assertEquals(
                    DurableDevLoopV1.CiConclusion.SUCCESS,
                    matched.event().conclusion());
            assertEquals(
                    "https://github.example/actions/runs/42",
                    matched.event().reportRef());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void matchedDeliveryReplayIsIdempotentAndPayloadConflictFailsClosed()
            throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        String commit = sha('a');
        String tree = sha('b');
        byte[] body = workflowRunBody(
                "org/repo",
                commit,
                50L,
                "ci",
                "success",
                "https://github.example/actions/runs/50");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(secret));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-replay",
                            "org/repo",
                            commit,
                            tree,
                            "ci")));
            next(outputs);

            var firstMessage = new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + hmac(secret, body),
                            "delivery-replay",
                            "workflow_run"),
                    body,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit,
                            tree,
                            "tree-proof://50"));
            actor.send(firstMessage);
            var first = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));

            actor.send(firstMessage);
            var replayed = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));
            assertEquals(first, replayed);

            byte[] changedBody = workflowRunBody(
                    "org/repo",
                    commit,
                    51L,
                    "ci",
                    "success",
                    "https://github.example/actions/runs/51");
            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + hmac(secret, changedBody),
                            "delivery-replay",
                            "workflow_run"),
                    changedBody,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit,
                            tree,
                            "tree-proof://51")));

            var conflict = assertInstanceOf(
                    GitHubWebhookActorV1.Unmatched.class,
                    next(outputs));
            assertTrue(conflict.reason().contains("reused"));
            assertEquals("delivery-replay", conflict.deliveryId());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void unmatchedDeliveryCanMatchAfterRunRegistration() throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        String commit = sha('d');
        String tree = sha('e');
        byte[] body = workflowRunBody(
                "org/repo",
                commit,
                52L,
                "ci",
                "success",
                "https://github.example/actions/runs/52");
        var message = new GitHubWebhookActorV1.ReceiveCiWebhook(
                new GitHubWebhookSecurityV1.Headers(
                        "sha256=" + hmac(secret, body),
                        "delivery-early",
                        "workflow_run"),
                body,
                new GitHubWorkflowRunPayloadV1.GitTreeProof(
                        commit,
                        tree,
                        "tree-proof://52"));

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(secret));
            next(outputs);

            actor.send(message);
            assertInstanceOf(
                    GitHubWebhookActorV1.Unmatched.class,
                    next(outputs));

            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-late-register",
                            "org/repo",
                            commit,
                            tree,
                            "ci")));
            next(outputs);

            actor.send(message);
            var matched = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));
            assertEquals("run-late-register", matched.runId());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void wrongTreeIsAuthenticatedButNeverRoutedAsSuccess()
            throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] body = workflowRunBody(
                "org/repo",
                sha('a'),
                43L,
                "ci",
                "success",
                "https://github.example/actions/runs/43");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(secret));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run",
                            "org/repo",
                            sha('a'),
                            sha('b'),
                            "ci")));
            next(outputs);

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + hmac(secret, body),
                            "delivery-2",
                            "workflow_run"),
                    body,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            sha('a'),
                            sha('c'),
                            "tree-proof://43")));

            var unmatched = assertInstanceOf(
                    GitHubWebhookActorV1.Unmatched.class,
                    next(outputs));
            assertTrue(unmatched.reason().contains("tree"));

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void durableIngressRestoresCorrelationAndExactRedeliveryAfterRestart()
            throws Exception {
        Path db = tempDir.resolve("webhook-restart.db");
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        String commit = sha('a');
        String tree = sha('b');
        byte[] body = workflowRunBody(
                "org/repo",
                commit,
                47L,
                "ci",
                "success",
                "https://github.example/actions/runs/47");
        var headers = new GitHubWebhookSecurityV1.Headers(
                "sha256=" + hmac(secret, body),
                "delivery-restart",
                "workflow_run");
        var proof = new GitHubWorkflowRunPayloadV1.GitTreeProof(
                commit,
                tree,
                "tree-proof://47");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(
                    secret, db.toString()));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-restart",
                            "org/repo",
                            commit,
                            tree,
                            "ci")));
            next(outputs);

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    headers, body, proof));
            var first = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));
            assertEquals("run-restart", first.runId());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(
                    secret, db.toString()));
            next(outputs);

            // No RegisterRun message: correlation was restored from SQLite.
            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    headers, body, proof));
            var replayed = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));
            assertEquals("run-restart", replayed.runId());
            assertEquals("delivery-restart", replayed.deliveryId());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void durableDeliveryIdConflictIsUnmatchedAfterRestart()
            throws Exception {
        Path db = tempDir.resolve("webhook-conflict-restart.db");
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        String commit = sha('a');
        String tree = sha('b');
        byte[] firstBody = workflowRunBody(
                "org/repo",
                commit,
                54L,
                "ci",
                "success",
                "https://github.example/actions/runs/54");
        var firstHeaders = new GitHubWebhookSecurityV1.Headers(
                "sha256=" + hmac(secret, firstBody),
                "delivery-conflict-restart",
                "workflow_run");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(
                    secret, db.toString()));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-conflict-restart",
                            "org/repo",
                            commit,
                            tree,
                            "ci")));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    firstHeaders,
                    firstBody,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit,
                            tree,
                            "tree-proof://54")));
            assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        byte[] changedBody = workflowRunBody(
                "org/repo",
                commit,
                55L,
                "ci",
                "success",
                "https://github.example/actions/runs/55");
        var changedHeaders = new GitHubWebhookSecurityV1.Headers(
                "sha256=" + hmac(secret, changedBody),
                "delivery-conflict-restart",
                "workflow_run");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(
                    secret, db.toString()));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    changedHeaders,
                    changedBody,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit,
                            tree,
                            "tree-proof://55")));

            var conflict = assertInstanceOf(
                    GitHubWebhookActorV1.Unmatched.class,
                    next(outputs));
            assertEquals(
                    "delivery-conflict-restart",
                    conflict.deliveryId());
            assertTrue(conflict.reason().contains("reused"));
            assertTrue(actor.raw().failure().isEmpty());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void ambiguousExactCorrelationRoutesNowhere() throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        String commit = sha('a');
        String tree = sha('b');
        byte[] body = workflowRunBody(
                "org/repo",
                commit,
                45L,
                "ci",
                "success",
                "https://github.example/actions/runs/45");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(secret));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-a", "org/repo", commit, tree, "ci")));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-b", "org/repo", commit, tree, "ci")));
            next(outputs);

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + hmac(secret, body),
                            "delivery-ambiguous",
                            "workflow_run"),
                    body,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit,
                            tree,
                            "tree-proof://45")));

            var unmatched = assertInstanceOf(
                    GitHubWebhookActorV1.Unmatched.class,
                    next(outputs));
            assertTrue(unmatched.reason().contains("ambiguous"));
            assertTrue(unmatched.reason().contains("2"));

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void treeProofMustBelongToAuthenticatedCommit() throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] body = workflowRunBody(
                "org/repo",
                sha('a'),
                46L,
                "ci",
                "success",
                "https://github.example/actions/runs/46");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(secret));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run", "org/repo", sha('a'), sha('b'), "ci")));
            next(outputs);

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + hmac(secret, body),
                            "delivery-wrong-proof",
                            "workflow_run"),
                    body,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            sha('c'),
                            sha('b'),
                            "tree-proof://wrong")));

            var rejected = assertInstanceOf(
                    GitHubWebhookActorV1.Rejected.class,
                    next(outputs));
            assertTrue(rejected.reason().contains("rejected"));
            assertTrue(actor.raw().failure().isEmpty());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectedTreeProofDoesNotPoisonDurableDeliveryAcrossRestart()
            throws Exception {
        Path db = tempDir.resolve("webhook-proof-poison.db");
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        String commit = sha('a');
        String tree = sha('b');
        byte[] body = workflowRunBody(
                "org/repo",
                commit,
                53L,
                "ci",
                "success",
                "https://github.example/actions/runs/53");
        var headers = new GitHubWebhookSecurityV1.Headers(
                "sha256=" + hmac(secret, body),
                "delivery-proof-retry",
                "workflow_run");

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(
                    secret, db.toString()));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run-proof-retry",
                            "org/repo",
                            commit,
                            tree,
                            "ci")));
            next(outputs);

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    headers,
                    body,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            sha('c'),
                            tree,
                            "tree-proof://bad")));

            var rejected = assertInstanceOf(
                    GitHubWebhookActorV1.Rejected.class,
                    next(outputs));
            assertTrue(rejected.reason().contains("rejected"));
            assertTrue(actor.raw().failure().isEmpty());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(
                    secret, db.toString()));
            next(outputs);

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    headers,
                    body,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            commit,
                            tree,
                            "tree-proof://53")));

            var matched = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));
            assertEquals("run-proof-retry", matched.runId());
            assertEquals("delivery-proof-retry", matched.deliveryId());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidWebhookSignatureIsRejectedWithoutKillingIngressActor()
            throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(secret));
            next(outputs);
            actor.send(new GitHubWebhookActorV1.RegisterRun(
                    new GitHubCiCorrelationV1.Expectation(
                            "run",
                            "org/repo",
                            sha('a'),
                            sha('b'),
                            "ci")));
            next(outputs);

            byte[] body = workflowRunBody(
                    "org/repo",
                    sha('a'),
                    44L,
                    "ci",
                    "success",
                    "https://github.example/actions/runs/44");
            var proof = new GitHubWorkflowRunPayloadV1.GitTreeProof(
                    sha('a'),
                    sha('b'),
                    "tree-proof://44");

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + "00".repeat(32),
                            "delivery-bad",
                            "workflow_run"),
                    body,
                    proof));

            var rejected = assertInstanceOf(
                    GitHubWebhookActorV1.Rejected.class,
                    next(outputs));
            assertTrue(rejected.reason().contains("signature"));
            assertTrue(actor.raw().failure().isEmpty());

            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + hmac(secret, body),
                            "delivery-bad",
                            "workflow_run"),
                    body,
                    proof));

            var matched = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));
            assertEquals("run", matched.runId());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.raw().getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void webhookActorKeepsPersistentStateInPrivateMemoryAndRetiresIt()
            throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            assertEquals(
                    ActorRuntime.ActorKind.PRIVATE,
                    actor.raw().kind(),
                    "authenticated ingress must use a private actor");
            actor.raw().getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            actor.send(new GitHubWebhookActorV1.Configure(secret));
            assertInstanceOf(
                    GitHubWebhookActorV1.Ready.class,
                    next(outputs));
            assertTrue(
                    runtime.privateMemoryBytes() > 0L,
                    "configured webhook state must live in private actor memory");

            actor.send(new GitHubWebhookActorV1.Shutdown());
            assertInstanceOf(
                    GitHubWebhookActorV1.Stopping.class,
                    next(outputs));
            actor.raw().getDone().get(5, TimeUnit.SECONDS);

            assertEquals(
                    0L,
                    runtime.privateMemoryBytes(),
                    "webhook teardown must retire private state memory");
        }
    }

    private static Object next(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return GitHubWebhookActorV1.decodeOutput(
                step.value().value());
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

    private static String hmac(byte[] secret, byte[] body)
            throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
