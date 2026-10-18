package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    void concurrentConnectionsCannotDoubleSpendRemainingBudget()
            throws Exception {
        Path db = tempDir.resolve("concurrent-budget.db");
        var tightPolicy = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                1_000L,
                10,
                2_000_000L,
                1,
                false);

        try (var first =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            db, "run-race", tightPolicy, 100L);
             var second =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            db, "run-race", tightPolicy, 100L);
             var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var results = new ArrayList<
                    java.util.concurrent.Future<
                            WorkflowSupervisorPolicyV1.Admission>>();

            results.add(pool.submit(() -> {
                start.await();
                return first.admit(
                        WorkflowActivityProtocolV1.Kind.CODING,
                        200L,
                        700L,
                        0L,
                        0,
                        false);
            }));
            results.add(pool.submit(() -> {
                start.await();
                return second.admit(
                        WorkflowActivityProtocolV1.Kind.CODING,
                        200L,
                        700L,
                        0L,
                        0,
                        false);
            }));
            start.countDown();

            int allowed = 0;
            int cancelled = 0;
            for (var future : results) {
                var admission = future.get(10, TimeUnit.SECONDS);
                if (admission.decision()
                        == WorkflowSupervisorPolicyV1.Decision.ALLOW) {
                    allowed++;
                } else if (admission.decision()
                        == WorkflowSupervisorPolicyV1.Decision.CANCEL) {
                    cancelled++;
                }
            }

            assertEquals(1, allowed);
            assertEquals(1, cancelled);
        }

        try (var reopened =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            db, "run-race", tightPolicy, 100L)) {
            var usage = reopened.usage();
            assertEquals(700L, usage.modelTokens());
            assertTrue(usage.cancelled());
            assertEquals(
                    "model_token_budget_exhausted",
                    usage.cancellationReason());
        }
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
