package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowActivityProtocolV1Test {

    @Test
    void codingActivityRequiresExplicitCapabilities() {
        var claim = claim(new DurableDevLoopV1.RequestCode(
                1,
                "goal",
                null,
                "devloop/run/iteration/1/code"));

        assertThrows(
                SecurityException.class,
                () -> new WorkflowActivityProtocolV1.Request(
                        "run",
                        claim,
                        WorkflowActivityProtocolV1.Kind.CODING,
                        Set.of(
                                WorkflowActivityProtocolV1.Capability
                                        .REPOSITORY_READ,
                                WorkflowActivityProtocolV1.Capability
                                        .WORKSPACE_WRITE)));

        var request = new WorkflowActivityProtocolV1.Request(
                "run",
                claim,
                WorkflowActivityProtocolV1.Kind.CODING,
                WorkflowActivityProtocolV1.requiredCapabilities(
                        claim.command()));
        assertEquals(
                WorkflowActivityProtocolV1.Kind.CODING,
                request.kind());
    }

    @Test
    void activityKindCannotBeRelabeledToGainDifferentAuthority() {
        var claim = claim(new DurableDevLoopV1.PushCode(
                1,
                "patch://one",
                "devloop/run/iteration/1/push"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowActivityProtocolV1.Request(
                        "run",
                        claim,
                        WorkflowActivityProtocolV1.Kind.CODING,
                        Set.copyOf(
                                WorkflowActivityProtocolV1
                                        .requiredCapabilities(
                                                claim.command()))));
    }

    @Test
    void activityClaimCannotBeRelabeledToDifferentRun() {
        var claim = claim(new DurableDevLoopV1.RequestCode(
                1,
                "goal",
                null,
                "devloop/run-a/iteration/1/code"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowActivityProtocolV1.Request(
                        "run-b",
                        claim,
                        WorkflowActivityProtocolV1.Kind.CODING,
                        WorkflowActivityProtocolV1.requiredCapabilities(
                                claim.command())));
    }

    @Test
    void outcomeMustBelongToExactClaim() {
        var claim = claim(new DurableDevLoopV1.PushCode(
                1,
                "patch://one",
                "devloop/run/iteration/1/push"));
        var outcome = new WorkflowActivityProtocolV1.Succeeded(
                "effect/other",
                claim.claimToken(),
                "receipt://push",
                new DurableDevLoopV1.PushRecorded(
                        1, sha('a')));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        claim, outcome));
    }

    @Test
    void staleOutcomeFromOlderClaimAttemptIsRejected() {
        var claim = claim(new DurableDevLoopV1.PushCode(
                1,
                "patch://one",
                "devloop/run/iteration/1/push"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        claim,
                        new WorkflowActivityProtocolV1.KnownUnexecuted(
                                claim.effectKey(),
                                "older/claim/token",
                                "worker://not-started")));
    }

    @Test
    void successfulOutcomeMustHaveCommandCompatibleEvent() {
        var claim = claim(new DurableDevLoopV1.PushCode(
                1,
                "patch://one",
                "devloop/run/iteration/1/push"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        claim,
                        new WorkflowActivityProtocolV1.Succeeded(
                                "devloop/run/iteration/1/push",
                                claim.claimToken(),
                                "receipt://wrong",
                                new DurableDevLoopV1.EvaluationPassed(
                                        sha('a')))));

        assertDoesNotThrow(
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        claim,
                        new WorkflowActivityProtocolV1.Succeeded(
                                "devloop/run/iteration/1/push",
                                claim.claimToken(),
                                "receipt://push",
                                new DurableDevLoopV1.PushRecorded(
                                        1, sha('a')))));
    }

    @Test
    void successfulOutcomeMustMatchExactIterationAndCommitIdentity() {
        var codeClaim = claim(new DurableDevLoopV1.RequestCode(
                2,
                "goal",
                "bug://1",
                "devloop/run/iteration/2/code"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        codeClaim,
                        new WorkflowActivityProtocolV1.Succeeded(
                                codeClaim.effectKey(),
                                codeClaim.claimToken(),
                                "receipt://code",
                                new DurableDevLoopV1.CodeChanged(
                                        1, "patch://wrong-iteration"))));

        var ciClaim = claim(new DurableDevLoopV1.AwaitCi(
                2,
                sha('a'),
                "devloop/run/iteration/2/ci/" + sha('a')));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        ciClaim,
                        new WorkflowActivityProtocolV1.Succeeded(
                                ciClaim.effectKey(),
                                ciClaim.claimToken(),
                                "receipt://ci",
                                new DurableDevLoopV1.CiCompleted(
                                        sha('b'),
                                        DurableDevLoopV1.CiConclusion.SUCCESS,
                                        "artifact://wrong-sha"))));

        var bugClaim = claim(new DurableDevLoopV1.CreateBugReport(
                3,
                sha('c'),
                "artifact://tests",
                "review://failure",
                "devloop/run/iteration/3/bug/" + sha('c')));
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        bugClaim,
                        new WorkflowActivityProtocolV1.Succeeded(
                                bugClaim.effectKey(),
                                bugClaim.claimToken(),
                                "receipt://bug",
                                new DurableDevLoopV1.BugReportCreated(
                                        2,
                                        sha('c'),
                                        "bug://wrong-iteration"))));
    }

    @Test
    void terminalActivityUsesCompletionWithoutInventingWorkflowEvent() {
        var cancelClaim = claim(new DurableDevLoopV1.CancelWork(
                "operator",
                "devloop/run/cancel"));
        var escalationClaim = claim(new DurableDevLoopV1.Escalate(
                3,
                "budget exhausted",
                "devloop/run/iteration/3/escalate"));

        assertDoesNotThrow(
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        cancelClaim,
                        new WorkflowActivityProtocolV1.Completed(
                                cancelClaim.effectKey(),
                                cancelClaim.claimToken(),
                                "receipt://cancel")));
        assertDoesNotThrow(
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        escalationClaim,
                        new WorkflowActivityProtocolV1.Completed(
                                escalationClaim.effectKey(),
                                escalationClaim.claimToken(),
                                "receipt://notify")));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        cancelClaim,
                        new WorkflowActivityProtocolV1.Succeeded(
                                cancelClaim.effectKey(),
                                cancelClaim.claimToken(),
                                "receipt://wrong",
                                new DurableDevLoopV1.Cancel("duplicate"))));
    }

    @Test
    void eventProducingActivityCannotReportCompletionWithoutEvent() {
        var claim = claim(new DurableDevLoopV1.RequestCode(
                1,
                "goal",
                null,
                "devloop/run/iteration/1/code"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        claim,
                        new WorkflowActivityProtocolV1.Completed(
                                claim.effectKey(),
                                claim.claimToken(),
                                "receipt://code")));
    }

    private static WorkflowJournalV1.Claim claim(
            DurableDevLoopV1.Command command) {
        return new WorkflowJournalV1.Claim(
                command.effectKey(),
                command,
                "claim/token",
                1,
                1000L);
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
