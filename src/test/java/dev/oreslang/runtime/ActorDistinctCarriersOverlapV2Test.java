package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Captures real overlapping handlers of two DISTINCT JVM host actors.
 *
 * Not multiple receivers of one actor; not compiled .atto execution. The
 * send-admission gates prevent observed handler START from overtaking a
 * successful send's admit receipt. Event slots are assigned in the live
 * handlers but the JSON is materialized after physical termination.
 */
@Timeout(35)
final class ActorDistinctCarriersOverlapV2Test {
    private static final int SPAWN = 0;
    private static final int ACCEPT = 1;
    private static final int START = 2;
    private static final int COMPLETE = 3;
    private static final int CLOSE = 4;
    private static final int RETIRE = 5;

    private record Observation(int kind, int actor) {}

    private static void observe(
            AtomicInteger seq,
            AtomicReferenceArray<Observation> events,
            AtomicBoolean invalid,
            int kind,
            int actor) {
        int index = seq.getAndIncrement();
        if (index >= events.length()) {
            invalid.set(true);
            return;
        }
        events.set(index, new Observation(kind, actor));
    }

    private static String eventJson(int seq, Observation value) {
        String actor = value.actor() == 0 ? "jvm_alpha" : "jvm_beta";
        String message = value.actor() == 0 ? "message_alpha" : "message_beta";
        String receiver = value.actor() == 0 ? "receiver_alpha" : "receiver_beta";
        String common = "{\"seq\":" + seq + ",\"actor_id\":\"" + actor
                + "\",\"generation\":1,";
        return switch (value.kind()) {
            case SPAWN -> common + "\"kind\":\"actor_spawn\",\"serial\":true}";
            case ACCEPT -> common + "\"kind\":\"send_accept\",\"message_id\":\""
                    + message + "\"}";
            case START -> common + "\"kind\":\"receive_start\",\"message_id\":\""
                    + message + "\",\"receiver_id\":\"" + receiver + "\"}";
            case COMPLETE -> common + "\"kind\":\"receive_complete\",\"message_id\":\""
                    + message + "\",\"receiver_id\":\"" + receiver + "\"}";
            case CLOSE -> common + "\"kind\":\"actor_close\"}";
            case RETIRE -> common + "\"kind\":\"actor_retire\"}";
            default -> throw new IllegalArgumentException("unsupported observation");
        };
    }

    @Test
    void distinctSharedActorsOverlapWithCausalTraceAndRetire() throws Exception {
        AtomicInteger seq = new AtomicInteger();
        AtomicReferenceArray<Observation> events = new AtomicReferenceArray<>(12);
        CountDownLatch alphaAccepted = new CountDownLatch(1);
        CountDownLatch betaAccepted = new CountDownLatch(1);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch bothCompleted = new CountDownLatch(2);
        AtomicBoolean invalid = new AtomicBoolean(false);
        AtomicInteger running = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean(false);

        observe(seq, events, invalid, SPAWN, 0);
        observe(seq, events, invalid, SPAWN, 1);
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 64),
                ActorRuntime.TurnExecutor.direct())) {

            var first = runtime.<String>spawnShared(() -> (message, context) -> {
                if (!"alpha".equals(message)) invalid.set(true);
                try {
                    if (!alphaAccepted.await(8, TimeUnit.SECONDS)) invalid.set(true);
                    observe(seq, events, invalid, START, 0);
                    if (running.incrementAndGet() == 2) overlapped.set(true);
                    bothEntered.countDown();
                    if (!bothEntered.await(8, TimeUnit.SECONDS)) invalid.set(true);
                    observe(seq, events, invalid, COMPLETE, 0);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    invalid.set(true);
                } finally {
                    running.decrementAndGet();
                    bothCompleted.countDown();
                }
            });
            var second = runtime.<String>spawnShared(() -> (message, context) -> {
                if (!"beta".equals(message)) invalid.set(true);
                try {
                    if (!betaAccepted.await(8, TimeUnit.SECONDS)) invalid.set(true);
                    observe(seq, events, invalid, START, 1);
                    if (running.incrementAndGet() == 2) overlapped.set(true);
                    bothEntered.countDown();
                    if (!bothEntered.await(8, TimeUnit.SECONDS)) invalid.set(true);
                    observe(seq, events, invalid, COMPLETE, 1);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    invalid.set(true);
                } finally {
                    running.decrementAndGet();
                    bothCompleted.countDown();
                }
            });

            first.send("alpha");
            observe(seq, events, invalid, ACCEPT, 0);
            alphaAccepted.countDown();
            second.send("beta");
            observe(seq, events, invalid, ACCEPT, 1);
            betaAccepted.countDown();

            assertTrue(bothCompleted.await(12, TimeUnit.SECONDS),
                    "both real host handlers must settle");
            assertTrue(overlapped.get(),
                    "two independent host actors must physically overlap");
            assertFalse(invalid.get(), "actor handler or recording failed");
            assertEquals(0, running.get(), "all receiver frames must finish");

            first.stop();
            second.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS),
                    "first actor must retire");
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS),
                    "second actor must retire");
            assertTrue(first.failure().isEmpty(), "first actor failed");
            assertTrue(second.failure().isEmpty(), "second actor failed");
            observe(seq, events, invalid, CLOSE, 0);
            observe(seq, events, invalid, RETIRE, 0);
            observe(seq, events, invalid, CLOSE, 1);
            observe(seq, events, invalid, RETIRE, 1);
        }

        assertFalse(invalid.get(), "bounded online recorder must not fail");
        assertEquals(12, seq.get(), "two actors require exactly twelve events");
        StringBuilder receipt = new StringBuilder(2400);
        receipt.append("{\"schema\":\"atto.actor-trace.v2\",\"complete\":true,");
        receipt.append("\"producer\":{\"backend\":\"java\",\"subject\":\"runtime_host\",");
        receipt.append("\"emission\":\"reconstructed_after_quiescence\"},\"events\":[");
        for (int i = 0; i < seq.get(); ++i) {
            Observation observation = events.get(i);
            assertNotNull(observation, "missing observed runtime event " + i);
            if (i != 0) receipt.append(',');
            receipt.append(eventJson(i, observation));
        }
        receipt.append("]}");
        Path output = Path.of("target", "actor-two-carrier-graal-v2.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, receipt.toString() + "\n", StandardCharsets.UTF_8);
    }
}
