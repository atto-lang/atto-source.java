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

    public sealed interface Outcome permits Succeeded,
            KnownUnexecuted, Ambiguous { }

    public record Succeeded(
            String effectKey,
            String receiptRef,
            DurableDevLoopV1.Event resultingEvent) implements Outcome {
        public Succeeded {
            effectKey = requireText(effectKey, "effectKey");
            receiptRef = requireText(receiptRef, "receiptRef");
            resultingEvent = Objects.requireNonNull(
                    resultingEvent, "resultingEvent");
        }
    }

    public record KnownUnexecuted(
            String effectKey,
            String reasonRef) implements Outcome {
        public KnownUnexecuted {
            effectKey = requireText(effectKey, "effectKey");
            reasonRef = requireText(reasonRef, "reasonRef");
        }
    }

    public record Ambiguous(
            String effectKey,
            String reconciliationRef) implements Outcome {
        public Ambiguous {
            effectKey = requireText(effectKey, "effectKey");
            reconciliationRef = requireText(
                    reconciliationRef, "reconciliationRef");
        }
    }

    private WorkflowActivityProtocolV1() { }

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
            case KnownUnexecuted value -> value.effectKey();
            case Ambiguous value -> value.effectKey();
        };
        if (!claim.effectKey().equals(key)) {
            throw new IllegalArgumentException(
                    "activity outcome effect key does not match claim");
        }

        if (outcome instanceof Succeeded succeeded) {
            validateResultingEvent(
                    claim.command(),
                    succeeded.resultingEvent());
        }
    }

    private static void validateResultingEvent(
            DurableDevLoopV1.Command command,
            DurableDevLoopV1.Event event) {
        boolean valid =
                command instanceof DurableDevLoopV1.RequestCode
                        && event instanceof DurableDevLoopV1.CodeChanged
                || command instanceof DurableDevLoopV1.PushCode
                        && event instanceof DurableDevLoopV1.PushRecorded
                || command instanceof DurableDevLoopV1.AwaitCi
                        && event instanceof DurableDevLoopV1.CiCompleted
                || command instanceof DurableDevLoopV1.Evaluate
                        && (event
                                instanceof DurableDevLoopV1.EvaluationPassed
                            || event
                                instanceof DurableDevLoopV1.EvaluationFailed)
                || command instanceof DurableDevLoopV1.CreateBugReport
                        && event
                                instanceof DurableDevLoopV1.BugReportCreated
                || command instanceof DurableDevLoopV1.RequestApproval
                        && event
                                instanceof DurableDevLoopV1.ApprovalGranted
                || command instanceof DurableDevLoopV1.CancelWork
                        && event instanceof DurableDevLoopV1.Cancel;

        if (!valid) {
            throw new IllegalArgumentException(
                    "activity result event "
                            + event.getClass().getSimpleName()
                            + " does not match command "
                            + command.getClass().getSimpleName());
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
