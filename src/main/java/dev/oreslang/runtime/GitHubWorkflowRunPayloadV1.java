package dev.oreslang.runtime;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;

/**
 * Bounded streaming parser for GitHub {@code workflow_run} webhook payloads.
 *
 * <p>The parser intentionally extracts only the fields required for durable CI
 * correlation. It rejects duplicate JSON object keys and applies explicit
 * document/token/depth/name/string/number limits before any workflow identity
 * is trusted.</p>
 */
public final class GitHubWorkflowRunPayloadV1 {

    private static final JsonFactory JSON =
            JsonFactory.builder()
                    .enable(
                            StreamReadFeature
                                    .STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxDocumentLength(2_000_000L)
                                    .maxTokenCount(100_000L)
                                    .maxNestingDepth(64)
                                    .maxNameLength(256)
                                    .maxStringLength(262_144)
                                    .maxNumberLength(32)
                                    .build())
                    .build();

    public record Parsed(
            String repositoryFullName,
            String commitSha,
            long workflowRunId,
            String workflowName,
            GitHubCiCorrelationV1.Conclusion conclusion,
            String reportRef) {
        public Parsed {
            repositoryFullName = requireText(
                    repositoryFullName, "repositoryFullName");
            commitSha = normalizeObjectId(
                    commitSha, "commitSha");
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

        public GitHubCiCorrelationV1.Observed observed(
                String treeSha) {
            return new GitHubCiCorrelationV1.Observed(
                    repositoryFullName,
                    commitSha,
                    treeSha,
                    workflowRunId,
                    workflowName,
                    conclusion,
                    reportRef);
        }
    }

    public record GitTreeProof(
            String commitSha,
            String treeSha,
            String receiptRef) {
        public GitTreeProof {
            commitSha = normalizeObjectId(
                    commitSha, "commitSha");
            treeSha = normalizeObjectId(
                    treeSha, "treeSha");
            receiptRef = requireText(receiptRef, "receiptRef");
        }

        public void verifyCommit(String expectedCommitSha) {
            String expected = normalizeObjectId(
                    expectedCommitSha, "expectedCommitSha");
            if (!commitSha.equals(expected)) {
                throw new SecurityException(
                        "Git tree proof commit does not match "
                                + "authenticated workflow_run head_sha");
            }
        }
    }

    private GitHubWorkflowRunPayloadV1() { }

    public static Parsed parse(byte[] rawBody) {
        Objects.requireNonNull(rawBody, "rawBody");
        if (rawBody.length == 0) {
            throw new IllegalArgumentException(
                    "workflow_run payload must not be empty");
        }
        if (rawBody.length > 2_000_000) {
            throw new IllegalArgumentException(
                    "workflow_run payload exceeds 2 MB");
        }

        try (JsonParser parser = JSON.createParser(rawBody)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformed(
                        "workflow_run payload root must be an object");
            }

            String action = null;
            String repository = null;
            WorkflowRun workflowRun = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                requireToken(
                        parser.currentToken(),
                        JsonToken.FIELD_NAME,
                        "top-level field name");
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == null) {
                    throw malformed(
                            "unexpected end of workflow_run payload");
                }

                switch (field) {
                    case "action" -> {
                        requireToken(
                                value,
                                JsonToken.VALUE_STRING,
                                "action");
                        action = parser.getText();
                    }
                    case "repository" -> {
                        requireToken(
                                value,
                                JsonToken.START_OBJECT,
                                "repository");
                        repository = parseRepository(parser);
                    }
                    case "workflow_run" -> {
                        requireToken(
                                value,
                                JsonToken.START_OBJECT,
                                "workflow_run");
                        workflowRun = parseWorkflowRun(parser);
                    }
                    default -> parser.skipChildren();
                }
            }

            if (parser.nextToken() != null) {
                throw malformed(
                        "trailing JSON content after workflow_run payload");
            }

            if (!"completed".equals(action)) {
                throw malformed(
                        "workflow_run action must be completed");
            }
            if (repository == null) {
                throw malformed(
                        "workflow_run payload lacks repository.full_name");
            }
            if (workflowRun == null) {
                throw malformed(
                        "workflow_run payload lacks workflow_run object");
            }
            if (!"completed".equals(workflowRun.status)) {
                throw malformed(
                        "workflow_run.status must be completed");
            }

            final GitHubCiCorrelationV1.Conclusion conclusion;
            try {
                conclusion = GitHubCiCorrelationV1.Conclusion.valueOf(
                        workflowRun.conclusion
                                .toUpperCase(Locale.ROOT));
            } catch (RuntimeException failure) {
                throw malformed(
                        "unsupported workflow_run conclusion "
                                + workflowRun.conclusion,
                        failure);
            }

            return new Parsed(
                    repository,
                    workflowRun.headSha,
                    workflowRun.id,
                    workflowRun.name,
                    conclusion,
                    workflowRun.htmlUrl);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "invalid GitHub workflow_run JSON payload",
                    failure);
        }
    }

    private static String parseRepository(JsonParser parser)
            throws IOException {
        String fullName = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(
                    parser.currentToken(),
                    JsonToken.FIELD_NAME,
                    "repository field");
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if ("full_name".equals(field)) {
                requireToken(
                        value,
                        JsonToken.VALUE_STRING,
                        "repository.full_name");
                fullName = parser.getText();
            } else {
                parser.skipChildren();
            }
        }
        return fullName;
    }

    private static WorkflowRun parseWorkflowRun(
            JsonParser parser) throws IOException {
        Long id = null;
        String name = null;
        String headSha = null;
        String status = null;
        String conclusion = null;
        String htmlUrl = null;

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            requireToken(
                    parser.currentToken(),
                    JsonToken.FIELD_NAME,
                    "workflow_run field");
            String field = parser.currentName();
            JsonToken value = parser.nextToken();

            switch (field) {
                case "id" -> {
                    requireToken(
                            value,
                            JsonToken.VALUE_NUMBER_INT,
                            "workflow_run.id");
                    id = parser.getLongValue();
                }
                case "name" -> {
                    requireToken(
                            value,
                            JsonToken.VALUE_STRING,
                            "workflow_run.name");
                    name = parser.getText();
                }
                case "head_sha" -> {
                    requireToken(
                            value,
                            JsonToken.VALUE_STRING,
                            "workflow_run.head_sha");
                    headSha = parser.getText();
                }
                case "status" -> {
                    requireToken(
                            value,
                            JsonToken.VALUE_STRING,
                            "workflow_run.status");
                    status = parser.getText();
                }
                case "conclusion" -> {
                    requireToken(
                            value,
                            JsonToken.VALUE_STRING,
                            "workflow_run.conclusion");
                    conclusion = parser.getText();
                }
                case "html_url" -> {
                    requireToken(
                            value,
                            JsonToken.VALUE_STRING,
                            "workflow_run.html_url");
                    htmlUrl = parser.getText();
                }
                default -> parser.skipChildren();
            }
        }

        if (id == null
                || name == null
                || headSha == null
                || status == null
                || conclusion == null
                || htmlUrl == null) {
            throw malformed(
                    "workflow_run payload lacks required CI fields");
        }
        if (id <= 0) {
            throw malformed(
                    "workflow_run.id must be positive");
        }
        return new WorkflowRun(
                id,
                requireText(name, "workflow_run.name"),
                normalizeObjectId(
                        headSha, "workflow_run.head_sha"),
                requireText(status, "workflow_run.status")
                        .toLowerCase(Locale.ROOT),
                requireText(
                        conclusion, "workflow_run.conclusion")
                        .toUpperCase(Locale.ROOT),
                requireText(
                        htmlUrl, "workflow_run.html_url"));
    }

    private record WorkflowRun(
            long id,
            String name,
            String headSha,
            String status,
            String conclusion,
            String htmlUrl) { }

    private static void requireToken(
            JsonToken actual,
            JsonToken expected,
            String field) {
        if (actual != expected) {
            throw malformed(
                    field
                            + " has wrong JSON type: expected "
                            + expected
                            + " but found "
                            + actual);
        }
    }

    private static IllegalArgumentException malformed(
            String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException malformed(
            String message,
            Throwable cause) {
        return new IllegalArgumentException(message, cause);
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
