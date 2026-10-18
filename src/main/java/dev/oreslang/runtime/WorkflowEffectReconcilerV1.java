package dev.oreslang.runtime;

import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic reconciliation helpers for ambiguous external workflow effects.
 */
public final class WorkflowEffectReconcilerV1 {

    public enum Decision {
        COMPLETED,
        SAFE_TO_RETRY,
        REQUIRES_HUMAN
    }

    public record Result(
            Decision decision,
            String reason,
            String receiptRef) {
        public Result {
            decision = Objects.requireNonNull(decision, "decision");
            reason = requireText(reason, "reason");
            if (decision == Decision.COMPLETED) {
                receiptRef = requireText(receiptRef, "receiptRef");
            } else if (receiptRef != null) {
                throw new IllegalArgumentException(
                        "only completed reconciliation carries receipt");
            }
        }
    }

    public record GitPushExpectation(
            String repositoryFullName,
            String refName,
            String beforeCommitSha,
            String expectedCommitSha,
            String expectedTreeSha) {
        public GitPushExpectation {
            repositoryFullName = normalizeRepository(
                    repositoryFullName);
            refName = normalizeRef(refName);
            beforeCommitSha = normalizeOptionalObjectId(
                    beforeCommitSha, "beforeCommitSha");
            expectedCommitSha = normalizeObjectId(
                    expectedCommitSha, "expectedCommitSha");
            expectedTreeSha = normalizeObjectId(
                    expectedTreeSha, "expectedTreeSha");
            if (expectedCommitSha.equals(beforeCommitSha)) {
                throw new IllegalArgumentException(
                        "expected commit must differ from before commit");
            }
        }
    }

    public record GitRemoteRef(
            String repositoryFullName,
            String refName,
            boolean exists,
            String commitSha,
            String treeSha,
            String receiptRef) {
        public GitRemoteRef {
            repositoryFullName = normalizeRepository(
                    repositoryFullName);
            refName = normalizeRef(refName);
            if (exists) {
                commitSha = normalizeObjectId(
                        commitSha, "commitSha");
                treeSha = normalizeObjectId(
                        treeSha, "treeSha");
                receiptRef = requireText(
                        receiptRef, "receiptRef");
            } else if (commitSha != null
                    || treeSha != null
                    || receiptRef != null) {
                throw new IllegalArgumentException(
                        "missing remote ref cannot carry object ids/receipt");
            }
        }
    }

    public record MarkerExpectation(
            String provider,
            String scope,
            String stableMarker) {
        public MarkerExpectation {
            provider = requireText(provider, "provider")
                    .toLowerCase(Locale.ROOT);
            scope = requireText(scope, "scope");
            stableMarker = requireText(
                    stableMarker, "stableMarker");
        }
    }

    public record MarkerObservation(
            boolean found,
            boolean unique,
            String stableMarker,
            String receiptRef) {
        public MarkerObservation {
            if (found) {
                stableMarker = requireText(
                        stableMarker, "stableMarker");
                receiptRef = requireText(
                        receiptRef, "receiptRef");
            } else if (stableMarker != null || receiptRef != null) {
                throw new IllegalArgumentException(
                        "missing marker observation cannot carry marker/receipt");
            }
            if (!found && unique) {
                throw new IllegalArgumentException(
                        "missing marker cannot be unique");
            }
        }
    }

    private WorkflowEffectReconcilerV1() { }

    /**
     * Reconcile an ambiguous Git push without ever guessing.
     *
     * <p>Exact expected commit+tree means the push completed. A still-missing
     * ref, or the exact known pre-push commit, is safe to retry. Any unexpected
     * remote movement requires a human/merge policy.</p>
     */
    public static Result reconcileGitPush(
            GitPushExpectation expected,
            GitRemoteRef observed) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observed, "observed");

        if (!expected.repositoryFullName().equals(
                observed.repositoryFullName())
                || !expected.refName().equals(observed.refName())) {
            return human("remote Git identity mismatch");
        }

        if (!observed.exists()) {
            return retry("remote ref does not exist");
        }

        if (expected.expectedCommitSha().equals(
                observed.commitSha())) {
            if (!expected.expectedTreeSha().equals(
                    observed.treeSha())) {
                return human(
                        "expected commit resolved to unexpected Git tree");
            }
            return new Result(
                    Decision.COMPLETED,
                    "remote ref exactly matches expected commit and tree",
                    observed.receiptRef());
        }

        if (expected.beforeCommitSha() != null
                && expected.beforeCommitSha().equals(
                        observed.commitSha())) {
            return retry(
                    "remote ref remains at exact pre-push commit");
        }

        return human(
                "remote ref moved to an unexpected commit");
    }

    /**
     * Reconcile create-style APIs (issue/PR/ticket) by a stable effect marker.
     */
    public static Result reconcileStableMarker(
            MarkerExpectation expected,
            MarkerObservation observed) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observed, "observed");

        if (!observed.found()) {
            return retry(
                    "stable provider marker was not found");
        }
        if (!expected.stableMarker().equals(
                observed.stableMarker())) {
            return human(
                    "provider returned a different stable marker");
        }
        if (!observed.unique()) {
            return human(
                    "stable provider marker matched multiple resources");
        }
        return new Result(
                Decision.COMPLETED,
                "stable provider marker resolved uniquely",
                observed.receiptRef());
    }

    private static Result retry(String reason) {
        return new Result(
                Decision.SAFE_TO_RETRY, reason, null);
    }

    private static Result human(String reason) {
        return new Result(
                Decision.REQUIRES_HUMAN, reason, null);
    }

    private static String normalizeRepository(String value) {
        return requireText(value, "repositoryFullName")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeRef(String value) {
        String ref = requireText(value, "refName");
        if (!ref.startsWith("refs/heads/")) {
            throw new IllegalArgumentException(
                    "Git push reconciliation requires full refs/heads/* name");
        }
        return ref;
    }

    private static String normalizeObjectId(
            String value,
            String name) {
        String normalized = requireText(value, name)
                .toLowerCase(Locale.ROOT);
        if ((normalized.length() != 40
                && normalized.length() != 64)
                || !normalized.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(
                    name + " must be an exact 40- or 64-hex Git object id");
        }
        return normalized;
    }

    private static String normalizeOptionalObjectId(
            String value,
            String name) {
        return value == null
                ? null
                : normalizeObjectId(value, name);
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
