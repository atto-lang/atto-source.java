package dev.oreslang.runtime;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Capability-checked boundary between durable workflow effects and activity
 * workers (AI coding, Git, CI, evaluation, bug reporting and approvals).
 */
public final class WorkflowActivityProtocolV1 {

    public enum Kind {
        CODING,
        GIT,
        CI,
        EVALUATION,
        BUG_REPORT,
        APPROVAL,
        CANCELLATION,
        ESCALATION
    }

    public enum Capability {
        REPOSITORY_READ,
        WORKSPACE_WRITE,
        MODEL_INVOKE,
        GIT_PUSH,
        CI_READ,
        ISSUE_WRITE,
        APPROVAL_READ,
        CANCEL_REMOTE_WORK,
        NOTIFY_HUMAN
    }

    public record Request(
            String runId,
            WorkflowJournalV1.Claim claim,
            Kind kind,
            Set<Capability> grantedCapabilities) {
        public Request {
            runId = requireText(runId, "runId");
            claim = Objects.requireNonNull(claim, "claim");
            kind = Objects.requireNonNull(kind, "kind");
            grantedCapabilities = Set.copyOf(
                    Objects.requireNonNull(
                            grantedCapabilities,
                            "grantedCapabilities"));

            String expectedEffectKey =
                    expectedEffectKey(runId, claim.command());
            if (!expectedEffectKey.equals(claim.effectKey())) {
                throw new IllegalArgumentException(
                        "activity claim does not belong to run "
                                + runId);
            }

            Kind expected = kindFor(claim.command());
            if (kind != expected) {
                throw new IllegalArgumentException(
                        "activity kind "
                                + kind
                                + " does not match command kind "
                                + expected);
            }
            Set<Capability> required =
                    requiredCapabilities(claim.command());
            if (!grantedCapabilities.containsAll(required)) {
                EnumSet<Capability> missing =
                        EnumSet.copyOf(required);
                missing.removeAll(grantedCapabilities);
                throw new SecurityException(
                        "activity lacks required capabilities "
                                + missing);
            }
        }
    }

    public sealed interface Outcome permits Succeeded, Completed,
            KnownUnexecuted, Ambiguous { }

    public record Succeeded(
            String effectKey,
            String claimToken,
            String receiptRef,
            DurableDevLoopV1.Event resultingEvent) implements Outcome {
        public Succeeded {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            receiptRef = requireText(receiptRef, "receiptRef");
            resultingEvent = Objects.requireNonNull(
                    resultingEvent, "resultingEvent");
        }
    }

    public record Completed(
            String effectKey,
            String claimToken,
            String receiptRef) implements Outcome {
        public Completed {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            receiptRef = requireText(receiptRef, "receiptRef");
        }
    }

    public record KnownUnexecuted(
            String effectKey,
            String claimToken,
            String reasonRef) implements Outcome {
        public KnownUnexecuted {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            reasonRef = requireText(reasonRef, "reasonRef");
        }
    }

    public record Ambiguous(
            String effectKey,
            String claimToken,
            String reconciliationRef) implements Outcome {
        public Ambiguous {
            effectKey = requireText(effectKey, "effectKey");
            claimToken = requireText(claimToken, "claimToken");
            reconciliationRef = requireText(
                    reconciliationRef, "reconciliationRef");
        }
    }

    private WorkflowActivityProtocolV1() { }

    public static String expectedEffectKey(
            String runId,
            DurableDevLoopV1.Command command) {
        runId = requireText(runId, "runId");
        Objects.requireNonNull(command, "command");
        String prefix = "devloop/" + runId + "/";
        if (command instanceof DurableDevLoopV1.RequestCode value) {
            return prefix + "iteration/" + value.iteration() + "/code";
        }
        if (command instanceof DurableDevLoopV1.PushCode value) {
            return prefix + "iteration/" + value.iteration() + "/push";
        }
        if (command instanceof DurableDevLoopV1.AwaitCi value) {
            return prefix
                    + "iteration/"
                    + value.iteration()
                    + "/ci/"
                    + value.commitSha();
        }
        if (command instanceof DurableDevLoopV1.Evaluate value) {
            return prefix
                    + "iteration/"
                    + value.iteration()
                    + "/evaluate/"
                    + value.commitSha();
        }
        if (command instanceof DurableDevLoopV1.CreateBugReport value) {
            return prefix
                    + "iteration/"
                    + value.iteration()
                    + "/bug/"
                    + value.commitSha();
        }
        if (command instanceof DurableDevLoopV1.RequestApproval value) {
            return prefix
                    + "iteration/"
                    + value.iteration()
                    + "/approval/"
                    + value.commitSha();
        }
        if (command instanceof DurableDevLoopV1.Escalate value) {
            return prefix
                    + "iteration/"
                    + value.iteration()
                    + "/escalate";
        }
        if (command instanceof DurableDevLoopV1.CancelWork) {
            return prefix + "cancel";
        }
        throw new IllegalArgumentException(
                "unknown workflow command "
                        + command.getClass().getName());
    }

    public static Kind kindFor(DurableDevLoopV1.Command command) {
        Objects.requireNonNull(command, "command");
        if (command instanceof DurableDevLoopV1.RequestCode) {
            return Kind.CODING;
        }
        if (command instanceof DurableDevLoopV1.PushCode) {
            return Kind.GIT;
        }
        if (command instanceof DurableDevLoopV1.AwaitCi) {
            return Kind.CI;
        }
        if (command instanceof DurableDevLoopV1.Evaluate) {
            return Kind.EVALUATION;
        }
        if (command instanceof DurableDevLoopV1.CreateBugReport) {
            return Kind.BUG_REPORT;
        }
        if (command instanceof DurableDevLoopV1.RequestApproval) {
            return Kind.APPROVAL;
        }
        if (command instanceof DurableDevLoopV1.CancelWork) {
            return Kind.CANCELLATION;
        }
        if (command instanceof DurableDevLoopV1.Escalate) {
            return Kind.ESCALATION;
        }
        throw new IllegalArgumentException(
                "unknown workflow command "
                        + command.getClass().getName());
    }

    public static Set<Capability> requiredCapabilities(
            DurableDevLoopV1.Command command) {
        return switch (kindFor(command)) {
            case CODING -> Set.of(
                    Capability.REPOSITORY_READ,
                    Capability.WORKSPACE_WRITE,
                    Capability.MODEL_INVOKE);
            case GIT -> Set.of(
                    Capability.REPOSITORY_READ,
                    Capability.GIT_PUSH);
            case CI -> Set.of(Capability.CI_READ);
            case EVALUATION -> Set.of(
                    Capability.REPOSITORY_READ,
                    Capability.MODEL_INVOKE);
            case BUG_REPORT -> Set.of(
                    Capability.MODEL_INVOKE,
                    Capability.ISSUE_WRITE);
            case APPROVAL -> Set.of(
                    Capability.APPROVAL_READ);
            case CANCELLATION -> Set.of(
                    Capability.CANCEL_REMOTE_WORK);
            case ESCALATION -> Set.of(
                    Capability.NOTIFY_HUMAN);
        };
    }

    /**
     * Ensure an activity cannot report a result for a different durable claim.
     */
    public static void validateOutcome(
            WorkflowJournalV1.Claim claim,
            Outcome outcome) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(outcome, "outcome");

        String key = switch (outcome) {
            case Succeeded value -> value.effectKey();
            case Completed value -> value.effectKey();
            case KnownUnexecuted value -> value.effectKey();
            case Ambiguous value -> value.effectKey();
        };
        String token = switch (outcome) {
            case Succeeded value -> value.claimToken();
            case Completed value -> value.claimToken();
            case KnownUnexecuted value -> value.claimToken();
            case Ambiguous value -> value.claimToken();
        };
        if (!claim.effectKey().equals(key)) {
            throw new IllegalArgumentException(
                    "activity outcome effect key does not match claim");
        }
        if (!claim.claimToken().equals(token)) {
            throw new IllegalArgumentException(
                    "activity outcome claim token does not match attempt");
        }

        if (outcome instanceof Succeeded succeeded) {
            if (!requiresResultingEvent(claim.command())) {
                throw new IllegalArgumentException(
                        "activity command "
                                + claim.command().getClass().getSimpleName()
                                + " completes without a workflow event");
            }
            validateResultingEvent(
                    claim.command(),
                    succeeded.resultingEvent());
        }
        if (outcome instanceof Completed) {
            if (requiresResultingEvent(claim.command())) {
                throw new IllegalArgumentException(
                        "activity command "
                                + claim.command().getClass().getSimpleName()
                                + " requires a causal workflow event");
            }
        }
    }

    public static boolean requiresResultingEvent(
            DurableDevLoopV1.Command command) {
        Objects.requireNonNull(command, "command");
        return command instanceof DurableDevLoopV1.RequestCode
                || command instanceof DurableDevLoopV1.PushCode
                || command instanceof DurableDevLoopV1.AwaitCi
                || command instanceof DurableDevLoopV1.Evaluate
                || command instanceof DurableDevLoopV1.CreateBugReport
                || command instanceof DurableDevLoopV1.RequestApproval;
    }

    private static void validateResultingEvent(
            DurableDevLoopV1.Command command,
            DurableDevLoopV1.Event event) {
        if (command instanceof DurableDevLoopV1.RequestCode expected
                && event instanceof DurableDevLoopV1.CodeChanged actual) {
            requireEqual(
                    expected.iteration(),
                    actual.iteration(),
                    "coding result iteration");
            return;
        }
        if (command instanceof DurableDevLoopV1.PushCode expected
                && event instanceof DurableDevLoopV1.PushRecorded actual) {
            requireEqual(
                    expected.iteration(),
                    actual.iteration(),
                    "push result iteration");
            return;
        }
        if (command instanceof DurableDevLoopV1.AwaitCi expected
                && event instanceof DurableDevLoopV1.CiCompleted actual) {
            requireEqual(
                    expected.commitSha(),
                    actual.commitSha(),
                    "CI result commitSha");
            return;
        }
        if (command instanceof DurableDevLoopV1.Evaluate expected) {
            if (event instanceof DurableDevLoopV1.EvaluationPassed actual) {
                requireEqual(
                        expected.commitSha(),
                        actual.commitSha(),
                        "evaluation result commitSha");
                return;
            }
            if (event instanceof DurableDevLoopV1.EvaluationFailed actual) {
                requireEqual(
                        expected.commitSha(),
                        actual.commitSha(),
                        "evaluation result commitSha");
                return;
            }
        }
        if (command instanceof DurableDevLoopV1.CreateBugReport expected
                && event instanceof DurableDevLoopV1.BugReportCreated actual) {
            requireEqual(
                    expected.iteration(),
                    actual.iteration(),
                    "bug-report result iteration");
            requireEqual(
                    expected.commitSha(),
                    actual.commitSha(),
                    "bug-report result commitSha");
            return;
        }
        if (command instanceof DurableDevLoopV1.RequestApproval expected
                && event instanceof DurableDevLoopV1.ApprovalGranted actual) {
            requireEqual(
                    expected.commitSha(),
                    actual.commitSha(),
                    "approval result commitSha");
            return;
        }

        throw new IllegalArgumentException(
                "activity result event "
                        + event.getClass().getSimpleName()
                        + " does not match command "
                        + command.getClass().getSimpleName());
    }

    private static void requireEqual(
            Object expected,
            Object actual,
            String field) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(
                    field
                            + " does not match durable command: expected "
                            + expected
                            + " but received "
                            + actual);
        }
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
