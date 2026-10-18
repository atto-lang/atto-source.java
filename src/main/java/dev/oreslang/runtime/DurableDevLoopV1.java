package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;

/**
 * Deterministic correctness kernel for a durable AI development-loop actor.
 *
 * <p>This class deliberately performs no network, filesystem, model, Git, or CI
 * side effects. Instead, accepted events advance a finite state machine and
 * emit commands with stable effect keys. A durable actor runtime can journal the
 * {@link JournalEntry} values, execute commands through an outbox/effect
 * journal, and replay the same entries after a crash without inventing new
 * identities.</p>
 *
 * <p>The v1 loop is:
 * CREATED -> CODING -> WAITING_FOR_PUSH -> WAITING_FOR_CI -> EVALUATING ->
 * WAITING_FOR_APPROVAL -> COMPLETED, with CI/evaluation failures entering
 * WRITING_BUG_REPORT and then either returning to CODING or terminating at the
 * retry budget.</p>
 *
 * <p>Duplicate delivery IDs with the exact same immutable event are recorded
 * as no-op DUPLICATE entries. Reuse of a delivery ID with a different payload
 * is recorded as CONFLICT and cannot advance the workflow. CI events for a
 * commit other than the exact expected commit are recorded as STALE.</p>
 */
public final class DurableDevLoopV1 {

    public enum Phase {
        CREATED,
        CODING,
        WAITING_FOR_PUSH,
        WAITING_FOR_CI,
        EVALUATING,
        WRITING_BUG_REPORT,
        WAITING_FOR_APPROVAL,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum Disposition {
        APPLIED,
        DUPLICATE,
        CONFLICT,
        STALE,
        REJECTED
    }

    public enum CiConclusion {
        SUCCESS,
        FAILURE,
        CANCELLED
    }

    public sealed interface Event permits Start, CodeChanged, PushRecorded,
            CiCompleted, EvaluationPassed, EvaluationFailed,
            BugReportCreated, ApprovalGranted, Cancel { }

    public record Start(String goal) implements Event {
        public Start {
            goal = requireText(goal, "goal", 100_000);
        }
    }

    public record CodeChanged(int iteration, String patchRef) implements Event {
        public CodeChanged {
            requirePositive(iteration, "iteration");
            patchRef = requireText(patchRef, "patchRef", 4_096);
        }
    }

    public record PushRecorded(
            int iteration,
            String commitSha) implements Event {
        public PushRecorded {
            requirePositive(iteration, "iteration");
            commitSha = normalizeCommitSha(commitSha);
        }
    }

    public record CiCompleted(
            String commitSha,
            CiConclusion conclusion,
            String reportRef) implements Event {
        public CiCompleted {
            commitSha = normalizeCommitSha(commitSha);
            conclusion = Objects.requireNonNull(conclusion, "conclusion");
            reportRef = requireText(reportRef, "reportRef", 4_096);
        }
    }

    public record EvaluationPassed(String commitSha) implements Event {
        public EvaluationPassed {
            commitSha = normalizeCommitSha(commitSha);
        }
    }

    public record EvaluationFailed(
            String commitSha,
            String reasonRef) implements Event {
        public EvaluationFailed {
            commitSha = normalizeCommitSha(commitSha);
            reasonRef = requireText(reasonRef, "reasonRef", 4_096);
        }
    }

    public record BugReportCreated(
            int iteration,
            String commitSha,
            String bugReportRef) implements Event {
        public BugReportCreated {
            requirePositive(iteration, "iteration");
            commitSha = normalizeCommitSha(commitSha);
            bugReportRef = requireText(
                    bugReportRef, "bugReportRef", 4_096);
        }
    }

    public record ApprovalGranted(String commitSha) implements Event {
        public ApprovalGranted {
            commitSha = normalizeCommitSha(commitSha);
        }
    }

    public record Cancel(String reason) implements Event {
        public Cancel {
            reason = requireText(reason, "reason", 4_096);
        }
    }

    public sealed interface Command permits RequestCode, PushCode, AwaitCi,
            Evaluate, CreateBugReport, RequestApproval, Escalate, CancelWork {
        String effectKey();
    }

    public record RequestCode(
            int iteration,
            String goal,
            String feedbackRef,
            String effectKey) implements Command { }

    public record PushCode(
            int iteration,
            String patchRef,
            String effectKey) implements Command { }

    public record AwaitCi(
            int iteration,
            String commitSha,
            String effectKey) implements Command { }

    public record Evaluate(
            int iteration,
            String commitSha,
            String reportRef,
            String effectKey) implements Command { }

    public record CreateBugReport(
            int iteration,
            String commitSha,
            String reportRef,
            String reasonRef,
            String effectKey) implements Command { }

    public record RequestApproval(
            int iteration,
            String commitSha,
            String effectKey) implements Command { }

    public record Escalate(
            int iteration,
            String reason,
            String effectKey) implements Command { }

    public record CancelWork(
            String reason,
            String effectKey) implements Command { }

    public record JournalEntry(
            long sequence,
            String eventId,
            Event event,
            Disposition disposition) {
        public JournalEntry {
            if (sequence <= 0) {
                throw new IllegalArgumentException(
                        "sequence must be positive");
            }
            eventId = requireText(eventId, "eventId", 512);
            event = Objects.requireNonNull(event, "event");
            disposition = Objects.requireNonNull(
                    disposition, "disposition");
        }
    }

    public record Snapshot(
            String runId,
            Phase phase,
            int iteration,
            int maxIterations,
            String goal,
            String currentPatchRef,
            String expectedCommitSha,
            String lastReportRef,
            String lastFailureRef,
            String lastBugReportRef,
            String cancelReason,
            long nextSequence,
            int uniqueDeliveryIds,
            int journalEntries) { }

    public record Transition(
            JournalEntry journalEntry,
            Snapshot snapshot,
            List<Command> commands) {
        public Transition {
            journalEntry = Objects.requireNonNull(
                    journalEntry, "journalEntry");
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            commands = List.copyOf(commands);
        }
    }

    public record ReplayResult(
            DurableDevLoopV1 machine,
            List<Command> regeneratedCommands) {
        public ReplayResult {
            machine = Objects.requireNonNull(machine, "machine");
            regeneratedCommands = List.copyOf(regeneratedCommands);
        }
    }

    private record ApplyResult(
            Disposition disposition,
            List<Command> commands) { }

    private final String runId;
    private final int maxIterations;
    private record DeliveryRecord(Event event, boolean sealed) { }

    private final Map<String, DeliveryRecord> seenEvents = new HashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();

    private Phase phase = Phase.CREATED;
    private int iteration;
    private String goal;
    private String currentPatchRef;
    private String expectedCommitSha;
    private String lastReportRef;
    private String lastFailureRef;
    private String lastBugReportRef;
    private String cancelReason;
    private long nextSequence = 1L;

    public DurableDevLoopV1(String runId, int maxIterations) {
        this.runId = requireText(runId, "runId", 512);
        if (maxIterations <= 0 || maxIterations > 10_000) {
            throw new IllegalArgumentException(
                    "maxIterations must be in [1, 10000]");
        }
        this.maxIterations = maxIterations;
        assertInvariants();
    }

    /**
     * Apply one durable input delivery.
     *
     * <p>Every delivery obtains a monotonically increasing journal sequence,
     * including stale/rejected/duplicate deliveries, so an operator can audit
     * why an external event did not advance the workflow.</p>
     */
    public synchronized Transition accept(String eventId, Event event) {
        eventId = requireText(eventId, "eventId", 512);
        event = Objects.requireNonNull(event, "event");

        final long sequence = nextSequence;
        final long followingSequence =
                Math.addExact(nextSequence, 1L);

        final ApplyResult result;
        DeliveryRecord prior = seenEvents.get(eventId);
        if (prior != null && !prior.event().equals(event)) {
            result = new ApplyResult(
                    Disposition.CONFLICT, List.of());
        } else if (prior != null && prior.sealed()) {
            result = new ApplyResult(
                    Disposition.DUPLICATE, List.of());
        } else {
            result = applyFresh(event);
            boolean sealed =
                    result.disposition() != Disposition.REJECTED;
            seenEvents.put(
                    eventId,
                    new DeliveryRecord(event, sealed));
        }

        assertInvariants();

        nextSequence = followingSequence;
        JournalEntry entry = new JournalEntry(
                sequence, eventId, event, result.disposition());
        journal.add(entry);
        return new Transition(entry, snapshot(), result.commands());
    }

    /**
     * Deterministically reconstruct a run from its journal and verify that each
     * recorded sequence/disposition matches the reducer's result.
     */
    public static ReplayResult replay(
            String runId,
            int maxIterations,
            List<JournalEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        DurableDevLoopV1 replayed =
                new DurableDevLoopV1(runId, maxIterations);
        List<Command> regenerated = new ArrayList<>();

        for (JournalEntry recorded : entries) {
            Objects.requireNonNull(recorded, "journal entry");
            Transition transition = replayed.accept(
                    recorded.eventId(), recorded.event());
            JournalEntry actual = transition.journalEntry();
            if (actual.sequence() != recorded.sequence()) {
                throw new IllegalStateException(
                        "journal sequence mismatch at "
                                + recorded.sequence());
            }
            if (actual.disposition() != recorded.disposition()) {
                throw new IllegalStateException(
                        "journal disposition mismatch at sequence "
                                + recorded.sequence()
                                + ": recorded="
                                + recorded.disposition()
                                + ", replayed="
                                + actual.disposition());
            }
            regenerated.addAll(transition.commands());
        }

        return new ReplayResult(replayed, regenerated);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                runId,
                phase,
                iteration,
                maxIterations,
                goal,
                currentPatchRef,
                expectedCommitSha,
                lastReportRef,
                lastFailureRef,
                lastBugReportRef,
                cancelReason,
                nextSequence,
                seenEvents.size(),
                journal.size());
    }

    public synchronized List<JournalEntry> journal() {
        return List.copyOf(journal);
    }

    private ApplyResult applyFresh(Event event) {
        if (isTerminal(phase)) {
            return rejected();
        }

        if (event instanceof Cancel cancel) {
            phase = Phase.CANCELLED;
            cancelReason = cancel.reason();
            return applied(new CancelWork(
                    cancel.reason(), effectKey("cancel")));
        }

        if (event instanceof Start start) {
            if (phase != Phase.CREATED) return rejected();
            goal = start.goal();
            iteration = 1;
            phase = Phase.CODING;
            return applied(new RequestCode(
                    iteration,
                    goal,
                    null,
                    effectKey("iteration/" + iteration + "/code")));
        }

        if (event instanceof CodeChanged changed) {
            if (phase != Phase.CODING
                    || changed.iteration() != iteration) {
                return rejected();
            }
            currentPatchRef = changed.patchRef();
            phase = Phase.WAITING_FOR_PUSH;
            return applied(new PushCode(
                    iteration,
                    currentPatchRef,
                    effectKey("iteration/" + iteration + "/push")));
        }

        if (event instanceof PushRecorded pushed) {
            if (phase != Phase.WAITING_FOR_PUSH
                    || pushed.iteration() != iteration) {
                return rejected();
            }
            expectedCommitSha = pushed.commitSha();
            phase = Phase.WAITING_FOR_CI;
            return applied(new AwaitCi(
                    iteration,
                    expectedCommitSha,
                    effectKey("iteration/" + iteration
                            + "/ci/" + expectedCommitSha)));
        }

        if (event instanceof CiCompleted ci) {
            if (phase != Phase.WAITING_FOR_CI) {
                return rejected();
            }
            if (!ci.commitSha().equals(expectedCommitSha)) {
                return stale();
            }
            lastReportRef = ci.reportRef();
            if (ci.conclusion() == CiConclusion.SUCCESS) {
                lastFailureRef = null;
                phase = Phase.EVALUATING;
                return applied(new Evaluate(
                        iteration,
                        expectedCommitSha,
                        lastReportRef,
                        effectKey("iteration/" + iteration
                                + "/evaluate/" + expectedCommitSha)));
            }

            lastFailureRef =
                    "ci:" + ci.conclusion().name().toLowerCase(
                            Locale.ROOT);
            phase = Phase.WRITING_BUG_REPORT;
            return applied(new CreateBugReport(
                    iteration,
                    expectedCommitSha,
                    lastReportRef,
                    lastFailureRef,
                    effectKey("iteration/" + iteration
                            + "/bug/" + expectedCommitSha)));
        }

        if (event instanceof EvaluationPassed passed) {
            if (phase != Phase.EVALUATING) return rejected();
            if (!passed.commitSha().equals(expectedCommitSha)) {
                return stale();
            }
            phase = Phase.WAITING_FOR_APPROVAL;
            return applied(new RequestApproval(
                    iteration,
                    expectedCommitSha,
                    effectKey("iteration/" + iteration
                            + "/approval/" + expectedCommitSha)));
        }

        if (event instanceof EvaluationFailed failed) {
            if (phase != Phase.EVALUATING) return rejected();
            if (!failed.commitSha().equals(expectedCommitSha)) {
                return stale();
            }
            lastFailureRef = failed.reasonRef();
            phase = Phase.WRITING_BUG_REPORT;
            return applied(new CreateBugReport(
                    iteration,
                    expectedCommitSha,
                    lastReportRef,
                    lastFailureRef,
                    effectKey("iteration/" + iteration
                            + "/bug/" + expectedCommitSha)));
        }

        if (event instanceof BugReportCreated bug) {
            if (phase != Phase.WRITING_BUG_REPORT
                    || bug.iteration() != iteration) {
                return rejected();
            }
            if (!bug.commitSha().equals(expectedCommitSha)) {
                return stale();
            }

            lastBugReportRef = bug.bugReportRef();
            if (iteration >= maxIterations) {
                phase = Phase.FAILED;
                return applied(new Escalate(
                        iteration,
                        "iteration budget exhausted after "
                                + lastBugReportRef,
                        effectKey("iteration/" + iteration
                                + "/escalate")));
            }

            iteration++;
            phase = Phase.CODING;
            currentPatchRef = null;
            expectedCommitSha = null;
            lastReportRef = null;
            lastFailureRef = null;
            return applied(new RequestCode(
                    iteration,
                    goal,
                    lastBugReportRef,
                    effectKey("iteration/" + iteration + "/code")));
        }

        if (event instanceof ApprovalGranted approval) {
            if (phase != Phase.WAITING_FOR_APPROVAL) {
                return rejected();
            }
            if (!approval.commitSha().equals(expectedCommitSha)) {
                return stale();
            }
            phase = Phase.COMPLETED;
            return applied();
        }

        return rejected();
    }

    private ApplyResult applied(Command... commands) {
        return new ApplyResult(
                Disposition.APPLIED, List.of(commands));
    }

    private ApplyResult rejected() {
        return new ApplyResult(Disposition.REJECTED, List.of());
    }

    private ApplyResult stale() {
        return new ApplyResult(Disposition.STALE, List.of());
    }

    private String effectKey(String suffix) {
        return "devloop/" + runId + "/" + suffix;
    }

    private void assertInvariants() {
        if (iteration < 0 || iteration > maxIterations) {
            throw new IllegalStateException(
                    "iteration escaped configured budget");
        }
        if (phase == Phase.CREATED) {
            if (iteration != 0
                    || goal != null
                    || currentPatchRef != null
                    || expectedCommitSha != null) {
                throw new IllegalStateException(
                        "created workflow contains execution state");
            }
            return;
        }

        if (phase == Phase.CANCELLED) {
            if (cancelReason == null) {
                throw new IllegalStateException(
                        "cancelled workflow lacks reason");
            }
            return;
        }

        if (goal == null || iteration <= 0) {
            throw new IllegalStateException(
                    "started workflow lacks goal/iteration");
        }

        switch (phase) {
            case CODING -> {
                if (expectedCommitSha != null
                        || currentPatchRef != null) {
                    throw new IllegalStateException(
                            "coding phase retained prior patch/commit");
                }
            }
            case WAITING_FOR_PUSH -> {
                if (currentPatchRef == null
                        || expectedCommitSha != null) {
                    throw new IllegalStateException(
                            "push phase lacks patch or already has commit");
                }
            }
            case WAITING_FOR_CI, EVALUATING,
                    WRITING_BUG_REPORT, WAITING_FOR_APPROVAL,
                    COMPLETED, FAILED -> {
                if (expectedCommitSha == null) {
                    throw new IllegalStateException(
                            phase + " requires exact expected commit");
                }
            }
            case CANCELLED -> throw new IllegalStateException(
                    "cancelled handled above");
            case CREATED -> throw new IllegalStateException(
                    "created handled above");
        }

        if ((phase == Phase.EVALUATING
                || phase == Phase.WRITING_BUG_REPORT)
                && lastReportRef == null) {
            throw new IllegalStateException(
                    phase + " requires test report evidence");
        }
    }

    private static boolean isTerminal(Phase value) {
        return value == Phase.COMPLETED
                || value == Phase.FAILED
                || value == Phase.CANCELLED;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive");
        }
        return value;
    }

    private static String requireText(
            String value,
            String name,
            int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum length " + maxLength);
        }
        return normalized;
    }

    private static String normalizeCommitSha(String value) {
        String sha = requireText(value, "commitSha", 64)
                .toLowerCase(Locale.ROOT);
        int length = sha.length();
        if ((length != 40 && length != 64)
                || !sha.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(
                    "commitSha must be an exact 40- or 64-hex object id");
        }
        return sha;
    }
}
