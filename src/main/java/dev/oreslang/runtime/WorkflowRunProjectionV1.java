package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Immutable UI/read-model projection of one durable development-loop run.
 *
 * <p>The projection cross-checks reducer and journal revisions before exposing
 * state. A browser, CLI, timeline, or dashboard may render this value, but it
 * has no mutation authority over either the actor state machine or outbox.</p>
 */
public record WorkflowRunProjectionV1(
        String runId,
        DurableDevLoopV1.Phase phase,
        int iteration,
        int maxIterations,
        String expectedCommitSha,
        String lastBugReportRef,
        long lastTransitionSequence,
        int pendingEffects,
        int claimedEffects,
        int ambiguousEffects,
        int completedEffects,
        int abandonedEffects,
        boolean pendingApproval,
        boolean terminal,
        boolean requiresAttention,
        String attentionReason) {

    public WorkflowRunProjectionV1 {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(phase, "phase");
        if (iteration < 0 || iteration > maxIterations) {
            throw new IllegalArgumentException(
                    "projection iteration outside configured budget");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException(
                    "projection maxIterations must be positive");
        }
        if (lastTransitionSequence < 0
                || pendingEffects < 0
                || claimedEffects < 0
                || ambiguousEffects < 0
                || completedEffects < 0
                || abandonedEffects < 0) {
            throw new IllegalArgumentException(
                    "projection counters must be non-negative");
        }
        if (requiresAttention
                != (attentionReason != null)) {
            throw new IllegalArgumentException(
                    "attention flag/reason must agree");
        }
    }

    public static WorkflowRunProjectionV1 from(
            DurableDevLoopV1.Snapshot reducer,
            WorkflowJournalV1.StoreSnapshot store) {
        Objects.requireNonNull(reducer, "reducer");
        Objects.requireNonNull(store, "store");

        if (!Objects.equals(reducer.runId(), store.runId())) {
            throw new IllegalStateException(
                    "projection refused mismatched workflow run ids");
        }
        if (store.maxIterations() == null
                || reducer.maxIterations()
                        != store.maxIterations().intValue()) {
            throw new IllegalStateException(
                    "projection refused mismatched workflow policy");
        }
        if (reducer.journalEntries() != store.journalEntries()) {
            throw new IllegalStateException(
                    "projection refused reducer/store revision skew");
        }
        if (reducer.nextSequence() != store.nextSequence()) {
            throw new IllegalStateException(
                    "projection refused reducer/store sequence skew");
        }

        boolean approval =
                reducer.phase()
                        == DurableDevLoopV1.Phase.WAITING_FOR_APPROVAL;
        boolean terminal = switch (reducer.phase()) {
            case COMPLETED, FAILED, CANCELLED -> true;
            default -> false;
        };

        String attention = null;
        if (store.ambiguousEffects() > 0) {
            attention = "ambiguous_external_effect";
        } else if (approval) {
            attention = "approval_required";
        } else if (reducer.phase() == DurableDevLoopV1.Phase.FAILED) {
            attention = "workflow_failed";
        }

        return new WorkflowRunProjectionV1(
                reducer.runId(),
                reducer.phase(),
                reducer.iteration(),
                reducer.maxIterations(),
                reducer.expectedCommitSha(),
                reducer.lastBugReportRef(),
                reducer.nextSequence() - 1L,
                store.pendingEffects(),
                store.claimedEffects(),
                store.ambiguousEffects(),
                store.completedEffects(),
                store.abandonedEffects(),
                approval,
                terminal,
                attention != null,
                attention);
    }
}
