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
    void reservationSettlementRefundsEstimateAndSurvivesReopen() {
        Path db = tempDir.resolve("reservation-settle.db");

        try (var ledger =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            db, "run-settle", POLICY, 100L)) {
            var reserved = ledger.reserve(
                    "effect/model-1",
                    WorkflowActivityProtocolV1.Kind.CODING,
                    200L,
                    600L,
                    600_000L,
                    0,
                    false);
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.ALLOW,
                    reserved.decision());
            assertEquals(600L, reserved.usage().modelTokens());

            var settled = ledger.settle(
                    "effect/model-1",
                    250L,
                    200_000L);
            assertEquals(250L, settled.modelTokens());
            assertEquals(200_000L, settled.spendMicros());

            assertEquals(
                    settled,
                    ledger.settle(
                            "effect/model-1",
                            250L,
                            200_000L));
            assertThrows(
                    IllegalStateException.class,
                    () -> ledger.settle(
                            "effect/model-1",
                            251L,
                            200_000L));
        }

        try (var reopened =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            db, "run-settle", POLICY, 100L)) {
            assertEquals(250L, reopened.usage().modelTokens());
            assertEquals(200_000L, reopened.usage().spendMicros());

            var replay = reopened.reserve(
                    "effect/model-1",
                    WorkflowActivityProtocolV1.Kind.CODING,
                    300L,
                    600L,
                    600_000L,
                    0,
                    false);
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.ALLOW,
                    replay.decision());
            assertEquals(250L, replay.usage().modelTokens());
        }
    }

    @Test
    void actualUsageOverageIsRecordedAndCancelsFutureAdmission() {
        Path db = tempDir.resolve("settlement-overage.db");
        var tight = new WorkflowSupervisorPolicyV1.Policy(
                10_000L,
                500L,
                10,
                500_000L,
                1,
                false);

        try (var ledger =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            db, "run-overage", tight, 100L)) {
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.ALLOW,
                    ledger.reserve(
                            "effect/model-1",
                            WorkflowActivityProtocolV1.Kind.CODING,
                            200L,
                            400L,
                            400_000L,
                            0,
                            false)
                            .decision());

            var usage = ledger.settle(
                    "effect/model-1",
                    700L,
                    450_000L);
            assertEquals(700L, usage.modelTokens());
            assertEquals(450_000L, usage.spendMicros());
            assertTrue(usage.cancelled());
            assertEquals(
                    "model_token_budget_exhausted_after_settlement",
                    usage.cancellationReason());

            var future = ledger.admit(
                    WorkflowActivityProtocolV1.Kind.GIT,
                    300L,
                    0L,
                    0L,
                    0,
                    false);
            assertEquals(
                    WorkflowSupervisorPolicyV1.Decision.CANCEL,
                    future.decision());
        }
    }

    @Test
    void reservationKeyCannotBeReusedWithDifferentEstimate() {
        Path db = tempDir.resolve("reservation-conflict.db");

        try (var ledger =
                    SqliteWorkflowSupervisorLedgerV1.open(
                            db, "run-reservation-conflict", POLICY, 100L)) {
            ledger.reserve(
                    "effect/model-1",
                    WorkflowActivityProtocolV1.Kind.CODING,
                    200L,
                    100L,
                    100_000L,
                    0,
                    false);

            assertThrows(
                    IllegalStateException.class,
                    () -> ledger.reserve(
                            "effect/model-1",
                            WorkflowActivityProtocolV1.Kind.CODING,
                            201L,
                            101L,
                            100_000L,
                            0,
                            false));
        }
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
    void cancellationReasonIsImmutableAndSameReasonIsIdempotent() {
        Path db = tempDir.resolve("cancel-reason.db");

        try (var ledger =
                SqliteWorkflowSupervisorLedgerV1.open(
                        db, "run-cancel-reason", POLICY, 100L)) {
            var first = ledger.cancel("operator");
            assertTrue(first.cancelled());
            assertEquals("operator", first.cancellationReason());

            assertEquals(first, ledger.cancel("operator"));

            assertThrows(
                    IllegalStateException.class,
                    () -> ledger.cancel("budget"));
            assertEquals(
                    "operator",
                    ledger.usage().cancellationReason());
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
