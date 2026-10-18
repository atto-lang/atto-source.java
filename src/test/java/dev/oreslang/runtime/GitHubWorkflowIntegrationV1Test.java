package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

final class GitHubWorkflowIntegrationV1Test {

    @Test
    void webhookVerificationBindsSignatureDeliveryAndRawPayloadDigest()
            throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] body =
                "{\"action\":\"completed\"}"
                        .getBytes(StandardCharsets.UTF_8);
        String signature = "sha256=" + hmac(secret, body);

        var verified = GitHubWebhookSecurityV1.verify(
                secret,
                body,
                new GitHubWebhookSecurityV1.Headers(
                        signature,
                        "delivery-1",
                        "workflow_run"));

        assertEquals("delivery-1", verified.deliveryId());
        assertEquals("workflow_run", verified.eventName());
        assertEquals(64, verified.payloadSha256().length());
    }

    @Test
    void webhookVerificationRejectsWrongSignatureAndMalformedHeaders()
            throws Exception {
        byte[] secret =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

        assertThrows(
                SecurityException.class,
                () -> GitHubWebhookSecurityV1.verify(
                        secret,
                        body,
                        new GitHubWebhookSecurityV1.Headers(
                                "sha256=" + "00".repeat(32),
                                "delivery-1",
                                "workflow_run")));

        assertThrows(
                SecurityException.class,
                () -> GitHubWebhookSecurityV1.verify(
                        secret,
                        body,
                        new GitHubWebhookSecurityV1.Headers(
                                "sha1=" + hmac(secret, body),
                                "delivery-1",
                                "workflow_run")));
    }

    @Test
    void shortWebhookSecretIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GitHubWebhookSecurityV1.verify(
                        "short".getBytes(StandardCharsets.UTF_8),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        new GitHubWebhookSecurityV1.Headers(
                                "sha256=" + "00".repeat(32),
                                "delivery",
                                "push")));
    }

    @Test
    void exactCiRepositoryCommitTreeAndWorkflowMatchProducesEvent() {
        String commit = sha('a');
        String tree = sha('b');
        var expected = new GitHubCiCorrelationV1.Expectation(
                "run-1",
                "ORESoftware/Repo",
                commit,
                tree,
                "ci");
        var observed = new GitHubCiCorrelationV1.Observed(
                "oresoftware/repo",
                commit,
                tree,
                42L,
                "ci",
                GitHubCiCorrelationV1.Conclusion.SUCCESS,
                "artifact://ci/42");

        var result = GitHubCiCorrelationV1.correlate(
                expected, observed);
        assertTrue(result.matched());
        assertEquals(
                DurableDevLoopV1.CiConclusion.SUCCESS,
                result.event().conclusion());
        assertEquals(commit, result.event().commitSha());
    }

    @Test
    void wrongCommitNeverAdvancesCiEvidence() {
        var result = GitHubCiCorrelationV1.correlate(
                new GitHubCiCorrelationV1.Expectation(
                        "run",
                        "org/repo",
                        sha('a'),
                        sha('b'),
                        "ci"),
                new GitHubCiCorrelationV1.Observed(
                        "org/repo",
                        sha('c'),
                        sha('b'),
                        1L,
                        "ci",
                        GitHubCiCorrelationV1.Conclusion.SUCCESS,
                        "artifact://ci"));

        assertEquals(
                GitHubCiCorrelationV1.Status.WRONG_COMMIT,
                result.status());
        assertNull(result.event());
    }

    @Test
    void mirroredCiRequiresExactTreeWhenExpectationPinsTree() {
        var result = GitHubCiCorrelationV1.correlate(
                new GitHubCiCorrelationV1.Expectation(
                        "run",
                        "org/repo",
                        sha('a'),
                        sha('b'),
                        "ci"),
                new GitHubCiCorrelationV1.Observed(
                        "org/repo",
                        sha('a'),
                        sha('c'),
                        1L,
                        "ci",
                        GitHubCiCorrelationV1.Conclusion.SUCCESS,
                        "artifact://ci"));

        assertEquals(
                GitHubCiCorrelationV1.Status.WRONG_TREE,
                result.status());
    }

    @Test
    void wrongWorkflowAndNonEvidenceConclusionsFailClosed() {
        var expected = new GitHubCiCorrelationV1.Expectation(
                "run",
                "org/repo",
                sha('a'),
                null,
                "ci");

        var wrongWorkflow = GitHubCiCorrelationV1.correlate(
                expected,
                new GitHubCiCorrelationV1.Observed(
                        "org/repo",
                        sha('a'),
                        null,
                        1L,
                        "other",
                        GitHubCiCorrelationV1.Conclusion.SUCCESS,
                        "artifact://ci"));
        assertEquals(
                GitHubCiCorrelationV1.Status.WRONG_WORKFLOW,
                wrongWorkflow.status());

        var skipped = GitHubCiCorrelationV1.correlate(
                expected,
                new GitHubCiCorrelationV1.Observed(
                        "org/repo",
                        sha('a'),
                        null,
                        2L,
                        "ci",
                        GitHubCiCorrelationV1.Conclusion.SKIPPED,
                        "artifact://ci"));
        assertEquals(
                GitHubCiCorrelationV1.Status.UNUSABLE_CONCLUSION,
                skipped.status());
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
