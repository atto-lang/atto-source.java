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
        byte[] body =
                "{\"action\":\"completed\"}"
                        .getBytes(StandardCharsets.UTF_8);
        String commit = sha('a');
        String tree = sha('b');

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.getReady().get(5, TimeUnit.SECONDS);
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
                    new GitHubCiCorrelationV1.Observed(
                            "org/repo",
                            commit,
                            tree,
                            42L,
                            "ci",
                            GitHubCiCorrelationV1.Conclusion.SUCCESS,
                            "artifact://ci/42")));

            var matched = assertInstanceOf(
                    GitHubWebhookActorV1.Matched.class,
                    next(outputs));
            assertEquals("run-1", matched.runId());
            assertEquals("delivery-1", matched.deliveryId());
            assertEquals(
                    DurableDevLoopV1.CiConclusion.SUCCESS,
                    matched.event().conclusion());

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void wrongTreeIsAuthenticatedButNeverRoutedAsSuccess()
            throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.getReady().get(5, TimeUnit.SECONDS);
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
                    new GitHubCiCorrelationV1.Observed(
                            "org/repo",
                            sha('a'),
                            sha('c'),
                            43L,
                            "ci",
                            GitHubCiCorrelationV1.Conclusion.SUCCESS,
                            "artifact://ci/43")));

            var unmatched = assertInstanceOf(
                    GitHubWebhookActorV1.Unmatched.class,
                    next(outputs));
            assertTrue(unmatched.reason().contains("tree"));

            actor.send(new GitHubWebhookActorV1.Shutdown());
            next(outputs);
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void invalidWebhookSignatureFailsIngressActor() throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);

        try (var runtime = new ActorRuntime()) {
            var actor = GitHubWebhookActorV1.spawn(runtime);
            actor.getReady().get(5, TimeUnit.SECONDS);
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
                            "sha256=" + "00".repeat(32),
                            "delivery-bad",
                            "workflow_run"),
                    "{}".getBytes(StandardCharsets.UTF_8),
                    new GitHubCiCorrelationV1.Observed(
                            "org/repo",
                            sha('a'),
                            sha('b'),
                            44L,
                            "ci",
                            GitHubCiCorrelationV1.Conclusion.SUCCESS,
                            "artifact://ci/44")));

            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent());
        }
    }

    private static Object next(
            ActorRuntime.ActorOutputStream<Object> outputs)
            throws Exception {
        var step = outputs.next().get(5, TimeUnit.SECONDS);
        assertFalse(step.done());
        return step.value().value();
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
