package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * REAL Java/Graal Oreslang guest execution of exact copied .ores fixtures.
 * This is not host-side simulation. Fixture string approvals/evidence are not
 * authenticated production capabilities and state remains non-durable.
 *
 * Nine base fixtures: oreslang-step-function-demo at
 * 0ba32f019a0c1dfcf9aaa2f91accb030178f5a45.
 * Four additional saga fixtures: feat/native-checkout-saga-20261029;
 * their exact Git blobs are pinned below after same-organization copying.
 * This file's expected Git blobs bind all fourteen copied source fixtures. The
 * 14th fixture is an actor-owned in-memory effect receipt ledger. The
 * coordinator fixture is the proposed sealed-state source, not the legacy
 * ordinary compatibility-field actor. Native compiler acceptance pending.
 */
final class SourceActorDemoWorkflowExecutionTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executesAcceptedOrderForkAndJoin() throws Exception {
        assertGuest("order-workflow", "order:complete", "7fc2e538d980d7f925f5c6d8d81395533ff09e7a");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void rejectsInvalidOrderBeforeDownstreamWork() throws Exception {
        assertGuest("order-rejected", "order:rejected", "4319ec422a864feddf531eab7a80ade20f36fb5a");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executesMemoryFixtureCaptureVerificationAndIndex() throws Exception {
        assertGuest("agent-memory-workflow", "memory:fixture-indexed", "058cf01e876faee1e27908039a5e81aea793346b");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quarantinesUntrustedEvidenceWithoutIndex() throws Exception {
        assertGuest("agent-memory-quarantine", "memory:quarantined", "f11f77b3216cfb2a05d6ea9d8a8b21c3bff56b62");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executesReviewFixtureToPreviewOnly() throws Exception {
        assertGuest("release-review-workflow", "release:preview-only", "f971404e7c33d95e9e92d88a76672f92bc23662c");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void staysWaitingWithoutApprovalFixture() throws Exception {
        assertGuest("release-waiting", "release:waiting", "2f99ce9bf194689659d49dbac57496cee022a759");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void boundsRetryToTwoDistinctActors() throws Exception {
        assertGuest("bounded-retry", "retry:complete-after-two", "606f40654c35da572f8ef35fd71f53ed8a9e2508");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void stopsBeforeWorkOnCancellation() throws Exception {
        assertGuest("cancel-before-work", "cancel:stopped-before-work", "b774babccb52ce4d51eff2d8262f6a5e6eeab0de");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void rejectsForeignDuplicateAndOutOfOrderMessages() throws Exception {
        assertGuest("coordinator-state", "workflow:state-guarded", "baef061676411aa00ee1a0596a1a9a9b19b09f12");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void executesSagaSuccessWithNoCompensation() throws Exception {
        assertGuest("order-saga-success", "saga:complete", "1d47ce2cfb929d43a7501fbc4bf2363ec2195fde");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void compensatesKnownNoEffectRejectionInReverseOrder() throws Exception {
        assertGuest("order-saga-compensated", "saga:compensated", "cbf0650473414a7d53755f756633a07fe64f14c3");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quarantinesAmbiguousSagaOutcomeWithoutCompensation() throws Exception {
        assertGuest("order-saga-ambiguous", "saga:reconciliation-required", "51ca94e1cbf983a495ed89f1531e9e740d50c34d");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void doesNotReleaseInventoryAfterUnconfirmedPaymentVoid() throws Exception {
        assertGuest("order-saga-void-unknown", "saga:compensation-incomplete",
                "82c9e08e95ab443e9ae5949466d322478ddec20a");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void rejectsDuplicateAndForeignEffectReceiptsAndBlocksUnknownCancellation() throws Exception {
        assertGuest("effect-receipt-ledger", "effect:fixture-reconciled-no-replay",
                "56535ad1df72c1eaf2493e3e97e9d562c611c666");
    }

    private static void assertGuest(String fixture, String expected, String expectedGitBlob)
            throws Exception {
        String resource = "/parity/step-demo-v1/" + fixture + ".ores";
        byte[] bytes;
        try (InputStream file = SourceActorDemoWorkflowExecutionTest.class
                .getResourceAsStream(resource)) {
            assertNotNull(file, "Native .ores fixture missing: " + resource);
            bytes = file.readAllBytes();
        }
        MessageDigest gitSha1 = MessageDigest.getInstance("SHA-1");
        gitSha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        gitSha1.update(bytes);
        assertEquals(expectedGitBlob, HexFormat.of().formatHex(gitSha1.digest()),
                "Source bytes drifted from pinned native demo fixture: " + resource);

        String sourceText = new String(bytes, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, resource)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals(expected, output.toString(StandardCharsets.UTF_8),
                "Unexpected guest stdout from " + resource);
        System.out.println("ORESLANG_NATIVE_GUEST_PASS fixture=" + fixture
                + " git_blob=" + expectedGitBlob);
    }
}
