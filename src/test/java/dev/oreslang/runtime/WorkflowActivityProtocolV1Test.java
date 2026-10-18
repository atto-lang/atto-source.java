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
                "effect/code"));

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
                "effect/push"));

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
    void outcomeMustBelongToExactClaim() {
        var claim = claim(new DurableDevLoopV1.PushCode(
                1,
                "patch://one",
                "effect/push"));
        var outcome = new WorkflowActivityProtocolV1.Succeeded(
                "effect/other",
                "receipt://push",
                new DurableDevLoopV1.PushRecorded(
                        1, sha('a')));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        claim, outcome));
    }

    @Test
    void successfulOutcomeMustHaveCommandCompatibleEvent() {
        var claim = claim(new DurableDevLoopV1.PushCode(
                1,
                "patch://one",
                "effect/push"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        claim,
                        new WorkflowActivityProtocolV1.Succeeded(
                                "effect/push",
                                "receipt://wrong",
                                new DurableDevLoopV1.EvaluationPassed(
                                        sha('a')))));

        assertDoesNotThrow(
                () -> WorkflowActivityProtocolV1.validateOutcome(
                        claim,
                        new WorkflowActivityProtocolV1.Succeeded(
                                "effect/push",
                                "receipt://push",
                                new DurableDevLoopV1.PushRecorded(
                                        1, sha('a')))));
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
