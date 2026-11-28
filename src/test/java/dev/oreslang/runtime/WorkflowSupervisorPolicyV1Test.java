package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowSupervisorPolicyV1Test {

    private static final WorkflowSupervisorPolicyV1.Policy POLICY =
            new WorkflowSupervisorPolicyV1.Policy(
                    10_000L,
                    10_000L,
                    2,
                    5_000_000L,
                    1,
                    true);

    @Test
    void admissionAccountsUsageOnlyWhenAllowed() {
        var usage = WorkflowSupervisorPolicyV1.Usage.start(100L);

        var admitted = WorkflowSupervisorPolicyV1.admit(
                POLICY,
                usage,
                WorkflowActivityProtocolV1.Kind.CODING,
                200L,
                500L,
                250_000L,
                0,
                false);
        assertEquals(
                WorkflowSupervisorPolicyV1.Decision.ALLOW,
                admitted.decision());
        assertEquals(500L, admitted.usage().modelTokens());
        assertEquals(250_000L, admitted.usage().spendMicros());
    }

    @Test
    void tokenBudgetExhaustionCancelsRun() {
        var usage = WorkflowSupervisorPolicyV1.Usage.start(100L);
        var denied = WorkflowSupervisorPolicyV1.admit(
                POLICY,
                usage,
                WorkflowActivityProtocolV1.Kind.CODING,
                200L,
                10_001L,
                0L,
                0,
                false);

        assertEquals(
                WorkflowSupervisorPolicyV1.Decision.CANCEL,
                denied.decision());
        assertTrue(denied.usage().cancelled());
        assertEquals(
                "model_token_budget_exhausted",
                denied.reason());
    }

    @Test
    void tooManyAmbiguousEffectsRequiresHumanInsteadOfBlindRetry() {
        var usage = WorkflowSupervisorPolicyV1.Usage.start(100L);
        var denied = WorkflowSupervisorPolicyV1.admit(
                POLICY,
                usage,
                WorkflowActivityProtocolV1.Kind.GIT,
                200L,
                0L,
                0L,
                2,
                false);

        assertEquals(
                WorkflowSupervisorPolicyV1.Decision.REQUIRE_HUMAN,
                denied.decision());
        assertFalse(denied.usage().cancelled());
    }

    @Test
    void protectedWriteRequiresApproval() {
        var usage = WorkflowSupervisorPolicyV1.Usage.start(100L);
        var denied = WorkflowSupervisorPolicyV1.admit(
                POLICY,
                usage,
                WorkflowActivityProtocolV1.Kind.GIT,
                200L,
                0L,
                0L,
                0,
                true);

        assertEquals(
                WorkflowSupervisorPolicyV1.Decision.REQUIRE_HUMAN,
                denied.decision());
        assertEquals(
                "protected_write_requires_approval",
                denied.reason());
    }

    @Test
    void wallClockBudgetCancelsRun() {
        var usage = WorkflowSupervisorPolicyV1.Usage.start(100L);
        var denied = WorkflowSupervisorPolicyV1.admit(
                POLICY,
                usage,
                WorkflowActivityProtocolV1.Kind.CI,
                10_101L,
                0L,
                0L,
                0,
                false);
        assertEquals(
                WorkflowSupervisorPolicyV1.Decision.CANCEL,
                denied.decision());
        assertEquals(
                "wall_clock_budget_exhausted",
                denied.reason());
    }

    @Test
    void supervisorRejectsClockRegression() {
        var usage = WorkflowSupervisorPolicyV1.Usage.start(100L);
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowSupervisorPolicyV1.admit(
                        POLICY,
                        usage,
                        WorkflowActivityProtocolV1.Kind.CI,
                        99L,
                        0L,
                        0L,
                        0,
                        false));
    }
}
