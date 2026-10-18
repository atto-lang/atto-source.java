package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

final class GitHubWebhookActorV1Test {

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

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.raw().getDone().get(5, TimeUnit.SECONDS));
            assertTrue(actor.raw().failure().isPresent());
        }
    }

    @Test
    void invalidWebhookSignatureFailsIngressActor() throws Exception {
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
            actor.send(new GitHubWebhookActorV1.ReceiveCiWebhook(
                    new GitHubWebhookSecurityV1.Headers(
                            "sha256=" + "00".repeat(32),
                            "delivery-bad",
                            "workflow_run"),
                    body,
                    new GitHubWorkflowRunPayloadV1.GitTreeProof(
                            sha('a'),
                            sha('b'),
                            "tree-proof://44")));

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
