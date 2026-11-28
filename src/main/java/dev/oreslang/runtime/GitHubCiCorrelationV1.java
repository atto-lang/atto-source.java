package dev.oreslang.runtime;

import java.util.Locale;
import java.util.Objects;

/**
 * Fail-closed correlation for GitHub CI evidence.
 *
 * <p>A branch name never proves tested identity. Matching requires the exact
 * repository and commit object id. Mirrored CI may additionally require the
 * exact Git tree object id and workflow name.</p>
 */
public final class GitHubCiCorrelationV1 {

    public enum Conclusion {
        SUCCESS,
        FAILURE,
        CANCELLED,
        TIMED_OUT,
        ACTION_REQUIRED,
        NEUTRAL,
        SKIPPED
    }

    public enum Status {
        MATCH,
        WRONG_REPOSITORY,
        WRONG_COMMIT,
        WRONG_TREE,
        WRONG_WORKFLOW,
        UNUSABLE_CONCLUSION
    }

    public record Expectation(
            String runId,
            String repositoryFullName,
            String commitSha,
            String treeSha,
            String workflowName) {
        public Expectation {
            runId = requireText(runId, "runId");
            repositoryFullName = normalizeRepository(
                    repositoryFullName);
            commitSha = normalizeObjectId(commitSha, "commitSha");
            treeSha = normalizeOptionalObjectId(treeSha, "treeSha");
            workflowName = normalizeOptionalText(workflowName);
        }
    }

    public record Observed(
            String repositoryFullName,
            String commitSha,
            String treeSha,
            long workflowRunId,
            String workflowName,
            Conclusion conclusion,
            String reportRef) {
        public Observed {
            repositoryFullName = normalizeRepository(
                    repositoryFullName);
            commitSha = normalizeObjectId(commitSha, "commitSha");
            treeSha = normalizeOptionalObjectId(treeSha, "treeSha");
            if (workflowRunId <= 0) {
                throw new IllegalArgumentException(
                        "workflowRunId must be positive");
            }
            workflowName = requireText(
                    workflowName, "workflowName");
            conclusion = Objects.requireNonNull(
                    conclusion, "conclusion");
            reportRef = requireText(reportRef, "reportRef");
        }
    }

    public record Result(
            Status status,
            String reason,
            DurableDevLoopV1.CiCompleted event) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            reason = requireText(reason, "reason");
            if ((status == Status.MATCH) != (event != null)) {
                throw new IllegalArgumentException(
                        "only MATCH correlation may carry a CI event");
            }
        }

        public boolean matched() {
            return status == Status.MATCH;
        }
    }

    private GitHubCiCorrelationV1() { }

    public static Result correlate(
            Expectation expected,
            Observed observed) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(observed, "observed");

        if (!expected.repositoryFullName().equals(
                observed.repositoryFullName())) {
            return reject(
                    Status.WRONG_REPOSITORY,
                    "repository identity mismatch");
        }
        if (!expected.commitSha().equals(observed.commitSha())) {
            return reject(
                    Status.WRONG_COMMIT,
                    "commit object id mismatch");
        }
        if (expected.treeSha() != null
                && !expected.treeSha().equals(observed.treeSha())) {
            return reject(
                    Status.WRONG_TREE,
                    "Git tree object id mismatch");
        }
        if (expected.workflowName() != null
                && !expected.workflowName().equals(
                        observed.workflowName())) {
            return reject(
                    Status.WRONG_WORKFLOW,
                    "workflow identity mismatch");
        }

        DurableDevLoopV1.CiConclusion conclusion =
                switch (observed.conclusion()) {
                    case SUCCESS ->
                            DurableDevLoopV1.CiConclusion.SUCCESS;
                    case FAILURE, TIMED_OUT ->
                            DurableDevLoopV1.CiConclusion.FAILURE;
                    case CANCELLED ->
                            DurableDevLoopV1.CiConclusion.CANCELLED;
                    case ACTION_REQUIRED, NEUTRAL, SKIPPED -> null;
                };
        if (conclusion == null) {
            return reject(
                    Status.UNUSABLE_CONCLUSION,
                    "CI conclusion is not terminal evidence");
        }

        return new Result(
                Status.MATCH,
                "exact CI identity matched",
                new DurableDevLoopV1.CiCompleted(
                        observed.commitSha(),
                        conclusion,
                        observed.reportRef()));
    }

    private static Result reject(Status status, String reason) {
        return new Result(status, reason, null);
    }

    private static String normalizeRepository(String value) {
        return requireText(value, "repositoryFullName")
                .toLowerCase(Locale.ROOT);
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
        return value == null ? null : normalizeObjectId(value, name);
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? null : requireText(value, "text");
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
