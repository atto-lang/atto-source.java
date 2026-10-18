package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowRunProjectionV1Test {

    @Test
    void projectionExposesReadOnlyWorkflowRevisionAndEffectCounts() {
        var loop = new DurableDevLoopV1("run-ui", 4);
        var store = new WorkflowJournalV1();
        var start = loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal"));
        store.append(start);

        var projection = WorkflowRunProjectionV1.from(
                loop.snapshot(), store.snapshot());

        assertEquals("run-ui", projection.runId());
        assertEquals(DurableDevLoopV1.Phase.CODING,
                projection.phase());
        assertEquals(1, projection.iteration());
        assertEquals(1L, projection.lastTransitionSequence());
        assertEquals(1, projection.pendingEffects());
        assertFalse(projection.pendingApproval());
        assertFalse(projection.terminal());
        assertFalse(projection.requiresAttention());
        assertNull(projection.attentionReason());
    }

    @Test
    void ambiguousExternalEffectBecomesUiAttentionState() {
        var loop = new DurableDevLoopV1("run-ui-ambiguous", 4);
        var store = new WorkflowJournalV1();
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));

        store.claimNext(
                "worker-a", 10L, 10L).orElseThrow();
        store.expireClaims(20L);

        var projection = WorkflowRunProjectionV1.from(
                loop.snapshot(), store.snapshot());

        assertTrue(projection.requiresAttention());
        assertEquals(
                "ambiguous_external_effect",
                projection.attentionReason());
        assertEquals(1, projection.ambiguousEffects());
    }

    @Test
    void approvalGateProjectsExplicitHumanAttention() {
        var loop = new DurableDevLoopV1("run-ui-approval", 4);
        var store = new WorkflowJournalV1();
        String sha = String.valueOf('a').repeat(40);

        append(store, loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));
        append(store, loop.accept(
                "code",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://1")));
        append(store, loop.accept(
                "push",
                new DurableDevLoopV1.PushRecorded(
                        1, sha)));
        append(store, loop.accept(
                "ci",
                new DurableDevLoopV1.CiCompleted(
                        sha,
                        DurableDevLoopV1.CiConclusion.SUCCESS,
                        "artifact://tests")));
        append(store, loop.accept(
                "eval",
                new DurableDevLoopV1.EvaluationPassed(sha)));

        var projection = WorkflowRunProjectionV1.from(
                loop.snapshot(), store.snapshot());

        assertEquals(
                DurableDevLoopV1.Phase.WAITING_FOR_APPROVAL,
                projection.phase());
        assertTrue(projection.pendingApproval());
        assertTrue(projection.requiresAttention());
        assertEquals(
                "approval_required",
                projection.attentionReason());
        assertFalse(projection.terminal());
        assertEquals(5L, projection.lastTransitionSequence());
    }


    @Test
    void cancellationProjectsAbandonedNotStartedEffects() {
        var loop = new DurableDevLoopV1("run-ui-cancel", 4);
        var store = new WorkflowJournalV1();
        append(store, loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));
        append(store, loop.accept(
                "cancel",
                new DurableDevLoopV1.Cancel("operator request")));

        var projection = WorkflowRunProjectionV1.from(
                loop.snapshot(), store.snapshot());

        assertEquals(DurableDevLoopV1.Phase.CANCELLED,
                projection.phase());
        assertTrue(projection.terminal());
        assertEquals(1, projection.abandonedEffects());
        assertEquals(1, projection.pendingEffects(),
                "cancel effect remains runnable");
    }

    @Test
    void projectionFailsClosedOnReducerJournalRevisionSkew() {
        var loop = new DurableDevLoopV1("run-ui-skew", 4);
        var store = new WorkflowJournalV1();
        store.append(loop.accept(
                "start",
                new DurableDevLoopV1.Start("goal")));

        loop.accept(
                "code-not-persisted",
                new DurableDevLoopV1.CodeChanged(
                        1, "patch://uncommitted"));

        assertThrows(
                IllegalStateException.class,
                () -> WorkflowRunProjectionV1.from(
                        loop.snapshot(), store.snapshot()));
    }

    private static void append(
            WorkflowJournalV1 store,
            DurableDevLoopV1.Transition transition) {
        store.append(transition);
    }
}
