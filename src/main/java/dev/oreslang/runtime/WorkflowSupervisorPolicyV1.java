package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Immutable resource policy and accounting state for one supervised workflow.
 *
 * <p>This policy is intentionally independent from the reducer so a supervisor
 * can stop or escalate expensive/non-progressing work without changing the
 * deterministic business state machine.</p>
 */
public final class WorkflowSupervisorPolicyV1 {

    public enum Decision {
        ALLOW,
        REQUIRE_HUMAN,
        CANCEL
    }

    public record Policy(
            long maxWallClockMillis,
            long maxModelTokens,
            int maxCiRuns,
            long maxSpendMicros,
            int maxAmbiguousEffects,
            boolean requireApprovalBeforeProtectedWrite) {
        public Policy {
            if (maxWallClockMillis <= 0
                    || maxModelTokens < 0
                    || maxCiRuns < 0
                    || maxSpendMicros < 0
                    || maxAmbiguousEffects < 0) {
                throw new IllegalArgumentException(
                        "workflow supervisor limits must be non-negative "
                                + "and wall clock must be positive");
            }
        }
    }

    public record Usage(
            long startedAtMillis,
            long modelTokens,
            int ciRuns,
            long spendMicros,
            int ambiguousEffects,
            boolean cancelled,
            String cancellationReason) {
        public Usage {
            if (startedAtMillis < 0
                    || modelTokens < 0
                    || ciRuns < 0
                    || spendMicros < 0
                    || ambiguousEffects < 0) {
                throw new IllegalArgumentException(
                        "workflow usage counters cannot be negative");
            }
            if (cancelled != (cancellationReason != null)) {
                throw new IllegalArgumentException(
                        "cancelled usage must carry exactly one reason");
            }
        }

        public static Usage start(long nowMillis) {
            return new Usage(
                    nowMillis,
                    0L,
                    0,
                    0L,
                    0,
                    false,
                    null);
        }
    }

    public record Admission(
            Decision decision,
            Usage usage,
            String reason) {
        public Admission {
            decision = Objects.requireNonNull(decision, "decision");
            usage = Objects.requireNonNull(usage, "usage");
            if (decision == Decision.ALLOW && reason != null) {
                throw new IllegalArgumentException(
                        "allowed admission cannot carry rejection reason");
            }
            if (decision != Decision.ALLOW) {
                reason = requireText(reason, "reason");
            }
        }
    }

    private WorkflowSupervisorPolicyV1() { }

    public static Admission admit(
            Policy policy,
            Usage usage,
            WorkflowActivityProtocolV1.Kind activity,
            long nowMillis,
            long additionalModelTokens,
            long additionalSpendMicros,
            int currentAmbiguousEffects,
            boolean protectedWrite) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(activity, "activity");

        if (nowMillis < usage.startedAtMillis()) {
            throw new IllegalArgumentException(
                    "supervisor clock cannot move backwards");
        }
        if (additionalModelTokens < 0
                || additionalSpendMicros < 0
                || currentAmbiguousEffects < 0) {
            throw new IllegalArgumentException(
                    "supervisor estimates cannot be negative");
        }
        Usage observed = new Usage(
                usage.startedAtMillis(),
                usage.modelTokens(),
                usage.ciRuns(),
                usage.spendMicros(),
                currentAmbiguousEffects,
                usage.cancelled(),
                usage.cancellationReason());

        if (observed.cancelled()) {
            return new Admission(
                    Decision.CANCEL,
                    observed,
                    observed.cancellationReason());
        }

        long elapsed = Math.subtractExact(
                nowMillis, observed.startedAtMillis());
        if (elapsed > policy.maxWallClockMillis()) {
            return cancelAdmission(
                    observed,
                    "wall_clock_budget_exhausted");
        }

        long nextTokens = Math.addExact(
                usage.modelTokens(), additionalModelTokens);
        if (nextTokens > policy.maxModelTokens()) {
            return cancelAdmission(
                    observed,
                    "model_token_budget_exhausted");
        }

        long nextSpend = Math.addExact(
                usage.spendMicros(), additionalSpendMicros);
        if (nextSpend > policy.maxSpendMicros()) {
            return cancelAdmission(
                    observed,
                    "spend_budget_exhausted");
        }

        int nextCiRuns = Math.addExact(
                usage.ciRuns(),
                activity == WorkflowActivityProtocolV1.Kind.CI ? 1 : 0);
        if (nextCiRuns > policy.maxCiRuns()) {
            return cancelAdmission(
                    observed,
                    "ci_run_budget_exhausted");
        }

        if (currentAmbiguousEffects
                > policy.maxAmbiguousEffects()) {
            return new Admission(
                    Decision.REQUIRE_HUMAN,
                    observed,
                    "ambiguous_effect_budget_exhausted");
        }

        if (protectedWrite
                && policy.requireApprovalBeforeProtectedWrite()) {
            return new Admission(
                    Decision.REQUIRE_HUMAN,
                    observed,
                    "protected_write_requires_approval");
        }

        Usage updated = new Usage(
                observed.startedAtMillis(),
                nextTokens,
                nextCiRuns,
                nextSpend,
                currentAmbiguousEffects,
                false,
                null);
        return new Admission(
                Decision.ALLOW,
                updated,
                null);
    }

    public static Usage cancel(
            Usage usage,
            String reason) {
        Objects.requireNonNull(usage, "usage");
        reason = requireText(reason, "reason");
        return new Usage(
                usage.startedAtMillis(),
                usage.modelTokens(),
                usage.ciRuns(),
                usage.spendMicros(),
                usage.ambiguousEffects(),
                true,
                reason);
    }

    private static Admission cancelAdmission(
            Usage usage,
            String reason) {
        Usage cancelled = cancel(usage, reason);
        return new Admission(
                Decision.CANCEL,
                cancelled,
                reason);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        return normalized;
    }
}
