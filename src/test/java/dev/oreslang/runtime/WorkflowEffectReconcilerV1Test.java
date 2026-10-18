package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowEffectReconcilerV1Test {

    @Test
    void exactRemoteCommitAndTreeProvesPushCompleted() {
        var result = WorkflowEffectReconcilerV1.reconcileGitPush(
                new WorkflowEffectReconcilerV1.GitPushExpectation(
                        "Org/Repo",
                        "refs/heads/feature",
                        sha('a'),
                        sha('b'),
                        sha('c')),
                new WorkflowEffectReconcilerV1.GitRemoteRef(
                        "org/repo",
                        "refs/heads/feature",
                        true,
                        sha('b'),
                        sha('c'),
                        "github://ref/feature"));

        assertEquals(
                WorkflowEffectReconcilerV1.Decision.COMPLETED,
                result.decision());
        assertEquals(
                "github://ref/feature",
                result.receiptRef());
    }

    @Test
    void unchangedPrePushRefIsSafeToRetry() {
        var result = WorkflowEffectReconcilerV1.reconcileGitPush(
                new WorkflowEffectReconcilerV1.GitPushExpectation(
                        "org/repo",
                        "refs/heads/feature",
                        sha('a'),
                        sha('b'),
                        sha('c')),
                new WorkflowEffectReconcilerV1.GitRemoteRef(
                        "org/repo",
                        "refs/heads/feature",
                        true,
                        sha('a'),
                        sha('d'),
                        "github://ref/pre"));

        assertEquals(
                WorkflowEffectReconcilerV1.Decision.SAFE_TO_RETRY,
                result.decision());
    }

    @Test
    void unexpectedRemoteMovementRequiresHuman() {
        var result = WorkflowEffectReconcilerV1.reconcileGitPush(
                new WorkflowEffectReconcilerV1.GitPushExpectation(
                        "org/repo",
                        "refs/heads/feature",
                        sha('a'),
                        sha('b'),
                        sha('c')),
                new WorkflowEffectReconcilerV1.GitRemoteRef(
                        "org/repo",
                        "refs/heads/feature",
                        true,
                        sha('e'),
                        sha('f'),
                        "github://ref/moved"));

        assertEquals(
                WorkflowEffectReconcilerV1.Decision.REQUIRES_HUMAN,
                result.decision());
    }

    @Test
    void expectedCommitWithWrongTreeRequiresHuman() {
        var result = WorkflowEffectReconcilerV1.reconcileGitPush(
                new WorkflowEffectReconcilerV1.GitPushExpectation(
                        "org/repo",
                        "refs/heads/feature",
                        sha('a'),
                        sha('b'),
                        sha('c')),
                new WorkflowEffectReconcilerV1.GitRemoteRef(
                        "org/repo",
                        "refs/heads/feature",
                        true,
                        sha('b'),
                        sha('d'),
                        "github://ref/wrong-tree"));

        assertEquals(
                WorkflowEffectReconcilerV1.Decision.REQUIRES_HUMAN,
                result.decision());
    }

    @Test
    void missingRemoteRefIsSafeToRetry() {
        var result = WorkflowEffectReconcilerV1.reconcileGitPush(
                new WorkflowEffectReconcilerV1.GitPushExpectation(
                        "org/repo",
                        "refs/heads/new-feature",
                        null,
                        sha('b'),
                        sha('c')),
                new WorkflowEffectReconcilerV1.GitRemoteRef(
                        "org/repo",
                        "refs/heads/new-feature",
                        false,
                        null,
                        null,
                        null));

        assertEquals(
                WorkflowEffectReconcilerV1.Decision.SAFE_TO_RETRY,
                result.decision());
    }

    @Test
    void createEffectMarkerMustResolveUniquely() {
        var expected =
                new WorkflowEffectReconcilerV1.MarkerExpectation(
                        "github",
                        "org/repo/issues",
                        "devloop:run-1:effect-bug");

        var found = WorkflowEffectReconcilerV1.reconcileStableMarker(
                expected,
                new WorkflowEffectReconcilerV1.MarkerObservation(
                        true,
                        true,
                        "devloop:run-1:effect-bug",
                        "github://issues/42"));
        assertEquals(
                WorkflowEffectReconcilerV1.Decision.COMPLETED,
                found.decision());

        var duplicate =
                WorkflowEffectReconcilerV1.reconcileStableMarker(
                        expected,
                        new WorkflowEffectReconcilerV1.MarkerObservation(
                                true,
                                false,
                                "devloop:run-1:effect-bug",
                                "github://search"));
        assertEquals(
                WorkflowEffectReconcilerV1.Decision.REQUIRES_HUMAN,
                duplicate.decision());
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
