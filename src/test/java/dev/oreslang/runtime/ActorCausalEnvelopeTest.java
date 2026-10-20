package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host mailbox runtime integration, not merely an OresAsyncTrace unit test.
 * Uses real ActorRef.send/ActorCell turns and tests the resulting failure object.
 */
final class ActorCausalEnvelopeTest {

    private static String traceText(ActorRuntime.ActorRef<?> failed) throws InterruptedException {
        assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS), "actor must finalize");
        Throwable cause = failed.failure().orElseThrow();
        assertInstanceOf(IllegalStateException.class, cause);
        return OresDiagnosticRenderer.renderText(cause);
    }

    @Test
    void eightMailboxSendsRetainLatestFiveActorsInFailureCausality() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> leaf = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> {
                        throw new IllegalStateException("expected leaf failure");
                    });
            List<ActorRuntime.ActorRef<String>> chain = new ArrayList<>();
            chain.add(leaf);
            for (int i = 0; i < 7; i++) {
                ActorRuntime.ActorRef<String> target = chain.get(0);
                chain.add(0, runtime.spawnPrivateTrusted(
                        ignored -> (message, context) -> target.send(message)));
            }
            chain.get(0).send("private-message-payload");
            String result = traceText(leaf);
            for (int i = 3; i < 8; i++) {
                assertTrue(result.contains(chain.get(i).id().value().toString()),
                        "missing hop " + i + ": " + result);
            }
            assertFalse(result.contains("private-message-payload"),
                    "trace must not retain arbitrary guest message payloads");
            assertTrue(result.contains("Actor causality"), result);
        }
    }

    @Test
    void concurrentFanoutNeverAddsSiblingActorToTheOtherFailure() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> left = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> {
                        throw new IllegalStateException("left leaf failed");
                    });
            ActorRuntime.ActorRef<String> right = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> {
                        throw new IllegalStateException("right leaf failed");
                    });
            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> {
                        if ("L".equals(message)) left.send(message);
                        else right.send(message);
                    });
            parent.send("L");
            parent.send("R");
            String leftText = traceText(left);
            String rightText = traceText(right);
            String parentId = parent.id().value().toString();
            assertTrue(leftText.contains(parentId), leftText);
            assertTrue(rightText.contains(parentId), rightText);
            assertFalse(leftText.contains(right.id().value().toString()), leftText);
            assertFalse(rightText.contains(left.id().value().toString()), rightText);
        }
    }

    @Test
    void hostSendCreatesFreshLineageRatherThanReusingLastMailTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> first = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> { });
            first.send("success");
            ActorRuntime.ActorRef<String> second = runtime.spawnPrivateTrusted(
                    ignored -> (message, context) -> {
                        throw new IllegalStateException("failure");
                    });
            second.send("fail");
            String result = traceText(second);
            assertTrue(result.contains(second.id().value().toString()), result);
            assertFalse(result.contains(first.id().value().toString()), result);
        }
    }
}
