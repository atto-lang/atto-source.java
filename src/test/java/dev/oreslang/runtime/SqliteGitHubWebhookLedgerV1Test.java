package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class SqliteGitHubWebhookLedgerV1Test {

    @TempDir
    Path tempDir;

    @Test
    void expectationsAndDeliveryIdentitySurviveReopen() {
        Path db = tempDir.resolve("webhook-ledger.db");
        var expectation = new GitHubCiCorrelationV1.Expectation(
                "run-1",
                "org/repo",
                sha('a'),
                sha('b'),
                "ci");

        try (var ledger = SqliteGitHubWebhookLedgerV1.open(db)) {
            ledger.register(expectation);
            assertEquals(
                    SqliteGitHubWebhookLedgerV1.DeliveryDisposition.FIRST_SEEN,
                    ledger.recordDelivery(
                            "delivery-1",
                            "1".repeat(64),
                            "workflow_run"));
        }

        try (var reopened = SqliteGitHubWebhookLedgerV1.open(db)) {
            assertEquals(
                    java.util.List.of(expectation),
                    reopened.expectations());
            assertEquals(
                    SqliteGitHubWebhookLedgerV1.DeliveryDisposition
                            .EXACT_REDELIVERY,
                    reopened.recordDelivery(
                            "delivery-1",
                            "1".repeat(64),
                            "workflow_run"));
        }
    }

    @Test
    void deliveryIdCannotBeReboundToDifferentAuthenticatedPayload() {
        Path db = tempDir.resolve("delivery-conflict.db");

        try (var ledger = SqliteGitHubWebhookLedgerV1.open(db)) {
            ledger.recordDelivery(
                    "delivery-1",
                    "1".repeat(64),
                    "workflow_run");

            assertThrows(
                    SecurityException.class,
                    () -> ledger.recordDelivery(
                            "delivery-1",
                            "2".repeat(64),
                            "workflow_run"));
            assertThrows(
                    SecurityException.class,
                    () -> ledger.recordDelivery(
                            "delivery-1",
                            "1".repeat(64),
                            "push"));
        }
    }

    @Test
    void deliveryIdCannotRebindTreeProofOnExactSignedPayload() {
        Path db = tempDir.resolve("proof-conflict.db");

        try (var ledger = SqliteGitHubWebhookLedgerV1.open(db)) {
            assertEquals(
                    SqliteGitHubWebhookLedgerV1.DeliveryDisposition
                            .FIRST_SEEN,
                    ledger.recordDelivery(
                            "delivery-proof",
                            "1".repeat(64),
                            "workflow_run",
                            sha('a'),
                            sha('b'),
                            "tree-proof://one"));

            assertThrows(
                    SecurityException.class,
                    () -> ledger.recordDelivery(
                            "delivery-proof",
                            "1".repeat(64),
                            "workflow_run",
                            sha('a'),
                            sha('c'),
                            "tree-proof://two"));
        }
    }

    @Test
    void conflictingRunRegistrationFailsClosedAcrossRestart() {
        Path db = tempDir.resolve("registration-conflict.db");
        var first = new GitHubCiCorrelationV1.Expectation(
                "run-1",
                "org/repo",
                sha('a'),
                sha('b'),
                "ci");

        try (var ledger = SqliteGitHubWebhookLedgerV1.open(db)) {
            ledger.register(first);
        }

        try (var reopened = SqliteGitHubWebhookLedgerV1.open(db)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> reopened.register(
                            new GitHubCiCorrelationV1.Expectation(
                                    "run-1",
                                    "org/repo",
                                    sha('c'),
                                    sha('d'),
                                    "ci")));
            assertEquals(
                    java.util.List.of(first),
                    reopened.expectations());
        }
    }

    @Test
    void unregisterPersistsAcrossRestart() {
        Path db = tempDir.resolve("unregister.db");
        var expectation = new GitHubCiCorrelationV1.Expectation(
                "run-1",
                "org/repo",
                sha('a'),
                sha('b'),
                "ci");

        try (var ledger = SqliteGitHubWebhookLedgerV1.open(db)) {
            ledger.register(expectation);
            ledger.unregister("run-1");
        }

        try (var reopened = SqliteGitHubWebhookLedgerV1.open(db)) {
            assertTrue(reopened.expectations().isEmpty());
        }
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
