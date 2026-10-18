package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class SqliteWorkflowSupervisorLedgerV1Test {

    @TempDir
    Path tempDir;

    private static final WorkflowSupervisorPolicyV1.Policy POLICY =
            new WorkflowSupervisorPolicyV1.Policy(
                    10_000L,
                    1_000L,
                    2,
                    2_000_000L,
                    1,
                    true);

    @Test
    void usageSurvivesReopenAndPolicyCannotChange() {
        Path db = tempDir.resolve("supervisor.db");

        try (var ledger =
                SqliteWorkflowSupervisorLedgerV1.open(
                        db, "run-1", POLICY, 100L)) {
            var admitted = ledger.admit(
                    WorkflowActivityProtocolV1.Kind.CODING,
                    200L,
                    400L,
                    500_000L,
                    0,
                    false);
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.ALLOW,
                    admitted.decision());
        }

        try (var reopened =
                SqliteWorkflowSupervisorLedgerV1.open(
                        db, "run-1", POLICY, 100L)) {
            assertEquals(400L, reopened.usage().modelTokens());
            assertEquals(500_000L, reopened.usage().spendMicros());
        }

        var changed = new WorkflowSupervisorPolicyV1.Policy(
                20_000L,
                1_000L,
                2,
                2_000_000L,
                1,
                true);
        assertThrows(
                IllegalStateException.class,
                () -> SqliteWorkflowSupervisorLedgerV1.open(
                        db, "run-1", changed, 100L));
    }

    @Test
    void cancelledLedgerRemainsCancelledAcrossRestart() {
        Path db = tempDir.resolve("cancelled.db");

        try (var ledger =
                SqliteWorkflowSupervisorLedgerV1.open(
                        db, "run-2", POLICY, 100L)) {
            ledger.cancel("operator");
        }

        try (var reopened =
                SqliteWorkflowSupervisorLedgerV1.open(
                        db, "run-2", POLICY, 100L)) {
            assertTrue(reopened.usage().cancelled());
            assertEquals(
                    "operator",
                    reopened.usage().cancellationReason());

            var admission = reopened.admit(
                    WorkflowActivityProtocolV1.Kind.CI,
                    200L,
                    0L,
                    0L,
                    0,
                    false);
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.CANCEL,
                    admission.decision());
        }
    }

    @Test
    void ciBudgetCannotBeResetByReopeningLedger() {
        Path db = tempDir.resolve("ci-budget.db");

        try (var ledger =
                SqliteWorkflowSupervisorLedgerV1.open(
                        db, "run-3", POLICY, 100L)) {
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.ALLOW,
                    ledger.admit(
                            WorkflowActivityProtocolV1.Kind.CI,
                            200L, 0L, 0L, 0, false)
                            .decision());
        }

        try (var reopened =
                SqliteWorkflowSupervisorLedgerV1.open(
                        db, "run-3", POLICY, 100L)) {
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.ALLOW,
                    reopened.admit(
                            WorkflowActivityProtocolV1.Kind.CI,
                            300L, 0L, 0L, 0, false)
                            .decision());
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.CANCEL,
                    reopened.admit(
                            WorkflowActivityProtocolV1.Kind.CI,
                            400L, 0L, 0L, 0, false)
                            .decision());
        }
    }
}
