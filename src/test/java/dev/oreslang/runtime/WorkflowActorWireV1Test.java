package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class WorkflowActorWireV1Test {

    @Test
    void eventCommandClaimAndProjectionRoundTrip() {
        var event = new DurableDevLoopV1.CiCompleted(
                sha('a'),
                DurableDevLoopV1.CiConclusion.SUCCESS,
                "artifact://ci");
        assertEquals(
                event,
                WorkflowActorWireV1.event(
                        WorkflowActorWireV1.event(event)));

        var command = new DurableDevLoopV1.Evaluate(
                2,
                sha('a'),
                "artifact://ci",
                "effect/evaluate");
        assertEquals(
                command,
                WorkflowActorWireV1.command(
                        WorkflowActorWireV1.command(command)));

        var claim = new WorkflowJournalV1.Claim(
                command.effectKey(),
                command,
                "claim/9",
                3,
                10_000L);
        assertEquals(
                claim,
                WorkflowActorWireV1.claim(
                        WorkflowActorWireV1.claim(claim)));

        var projection = new WorkflowRunProjectionV1(
                "run-1",
                DurableDevLoopV1.Phase.EVALUATING,
                2,
                4,
                sha('a'),
                "bug://1",
                12L,
                1,
                0,
                0,
                3,
                2,
                false,
                false,
                false,
                null);
        assertEquals(
                projection,
                WorkflowActorWireV1.projection(
                        WorkflowActorWireV1.projection(projection)));
    }

    @Test
    void rawBytesUseStableBase64ScalarInsteadOfArrayTransport() {
        byte[] value =
                "signed raw webhook bytes"
                        .getBytes(StandardCharsets.UTF_8);
        String wire = WorkflowActorWireV1.bytes(value);

        assertArrayEquals(
                value,
                WorkflowActorWireV1.bytes(wire));
        assertFalse(wire.contains("["));
    }

    @Test
    void capabilitiesAndAttemptBoundOutcomeRoundTrip() {
        Set<WorkflowActivityProtocolV1.Capability> capabilities =
                Set.of(
                        WorkflowActivityProtocolV1.Capability.REPOSITORY_READ,
                        WorkflowActivityProtocolV1.Capability.GIT_PUSH);
        assertEquals(
                capabilities,
                WorkflowActorWireV1.capabilities(
                        WorkflowActorWireV1.capabilities(
                                capabilities)));

        var outcome = new WorkflowActivityProtocolV1.Succeeded(
                "effect/push",
                "claim/2",
                "receipt://push",
                new DurableDevLoopV1.PushRecorded(
                        2, sha('b')));
        assertEquals(
                outcome,
                WorkflowActorWireV1.activityOutcome(
                        WorkflowActorWireV1.activityOutcome(
                                outcome)));

        var completed = new WorkflowActivityProtocolV1.Completed(
                "effect/cancel",
                "claim/terminal",
                "receipt://cancel");
        assertEquals(
                completed,
                WorkflowActorWireV1.activityOutcome(
                        WorkflowActorWireV1.activityOutcome(
                                completed)));
    }

    @Test
    void capabilityWireOrderIsDeterministic() {
        var values = new java.util.LinkedHashSet<
                WorkflowActivityProtocolV1.Capability>();
        values.add(WorkflowActivityProtocolV1.Capability.GIT_PUSH);
        values.add(
                WorkflowActivityProtocolV1.Capability.REPOSITORY_READ);

        assertEquals(
                java.util.List.of("GIT_PUSH", "REPOSITORY_READ"),
                WorkflowActorWireV1.capabilities(values));
    }

    @Test
    void malformedEncodedIntegerFailsAsWireValidationError() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActorWireV1.event(
                        WorkflowActorWireV1.map(
                                "type", "code_changed",
                                "int1", "not-a-number",
                                "text1", "patch://one",
                                "text2", null,
                                "text3", null,
                                "text4", null)));
    }

    @Test
    void actualActorRuntimeAcceptsWireMapAndOutput() throws Exception {
        try (var runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> actor =
                    runtime.spawnPrivate(ignored ->
                            (message, context) ->
                                    context.runtime()
                                            .emitCurrentActorOutput(
                                                    message));
            actor.getReady().get(5, TimeUnit.SECONDS);
            var outputs = actor.outputs();

            var message = WorkflowActorWireV1.map(
                    "type", "wire-proof",
                    "nested", WorkflowActorWireV1.map(
                            "event",
                            WorkflowActorWireV1.event(
                                    new DurableDevLoopV1.Start(
                                            "goal"))),
                    "items", java.util.List.of(
                            "a", 1L, true));
            actor.send(message);

            var step = outputs.next().get(
                    5, TimeUnit.SECONDS);
            assertFalse(step.done());
            assertEquals(
                    message,
                    WorkflowActorWireV1.objectMap(
                            step.value().value()));

            actor.gracefulStop().get(5, TimeUnit.SECONDS);
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void malformedWireValuesFailClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> WorkflowActorWireV1.event(
                        WorkflowActorWireV1.map(
                                "type", "unknown",
                                "int1", null,
                                "text1", null,
                                "text2", null,
                                "text3", null,
                                "text4", null)));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkflowActorWireV1.objectMap("not-a-map"));
    }

    private static String sha(char value) {
        return String.valueOf(value).repeat(40);
    }
}
