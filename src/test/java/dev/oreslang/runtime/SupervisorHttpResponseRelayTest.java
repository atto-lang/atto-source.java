package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SupervisorHttpResponseRelayTest {
    private static final String ACTOR = "actor-123";
    private static final String REQUEST = "req-7";

    private static final class RecordingSink implements SupervisorHttpResponseRelay.Sink {
        final List<String> events = new ArrayList<>();
        final List<byte[]> body = new ArrayList<>();
        @Override public void headers(int status, Map<String, String> headers) {
            events.add("status:" + status + ":" + headers.get("content-type"));
        }
        @Override public void chunk(byte[] value) {
            events.add("chunk");
            body.add(value.clone());
        }
        @Override public void end() { events.add("end"); }
        @Override public void abort(String reason) { events.add("abort:" + reason); }
    }

    private static Map<String, Object> headers(String mime) {
        return Map.of("request_id", REQUEST, "kind", "headers",
                "status", 200, "headers", Map.of("content-type", mime));
    }
    private static Map<String, Object> chunk(String data) {
        return Map.of("request_id", REQUEST, "kind", "chunk", "body", data);
    }
    private static Map<String, Object> end() {
        return Map.of("request_id", REQUEST, "kind", "end");
    }

    @Test
    void htmlSsrAndJsonShareIdenticalAuthenticatedFrameProtocol() {
        for (String mime : List.of("text/html; charset=utf-8", "application/json")) {
            RecordingSink sink = new RecordingSink();
            var relay = new SupervisorHttpResponseRelay(ACTOR, REQUEST, 128, 256, sink);
            String payload = mime.startsWith("text")
                    ? "<main>SSR ✓</main>" : "{\"ok\":true}";
            int size = payload.getBytes(StandardCharsets.UTF_8).length;
            relay.grantCredit(size);
            relay.accept(ACTOR, 0, headers(mime));
            relay.accept(ACTOR, 1, chunk(payload));
            relay.accept(ACTOR, 2, end());
            assertEquals(SupervisorHttpResponseRelay.State.ENDED, relay.state());
            assertEquals(size, relay.totalBytes());
            assertEquals(0, relay.remainingCredit());
            assertEquals(payload, new String(sink.body.getFirst(), StandardCharsets.UTF_8));
            assertEquals(List.of("status:200:" + mime, "chunk", "end"), sink.events);
            assertThrows(IllegalStateException.class,
                    () -> relay.accept(ACTOR, 3, chunk("late")));
        }
    }

    @Test
    void refusesForgedIdsHeadersAndBodyWithoutCredit() {
        var sink = new RecordingSink();
        var relay = new SupervisorHttpResponseRelay(ACTOR, REQUEST, 5, 20, sink);
        assertThrows(SecurityException.class,
                () -> relay.accept("imposter", 0, headers("text/html")));
        assertThrows(SecurityException.class,
                () -> relay.accept(ACTOR, 0,
                        Map.of("request_id", "other", "kind", "end")));
        assertThrows(IllegalArgumentException.class,
                () -> relay.accept(ACTOR, 0, chunk("no headers")));
        assertThrows(SecurityException.class,
                () -> relay.accept(ACTOR, 0,
                        Map.of("request_id", REQUEST, "kind", "headers",
                                "status", 200, "headers",
                                Map.of("transfer-encoding", "chunked"))));
        assertThrows(SecurityException.class,
                () -> relay.accept(ACTOR, 0,
                        Map.of("request_id", REQUEST, "kind", "headers",
                                "status", 200, "headers",
                                Map.of("content-type", "text/html\r\nX-Corrupted: 1"))));
        relay.accept(ACTOR, 0, headers("text/html"));
        assertThrows(IllegalStateException.class,
                () -> relay.accept(ACTOR, 1, chunk("hello")));
        assertTrue(sink.body.isEmpty());
        relay.grantCredit(5);
        relay.accept(ACTOR, 1, chunk("hello"));
        assertEquals(5, relay.totalBytes());
        assertThrows(IllegalArgumentException.class,
                () -> relay.accept(ACTOR, 1, end()));
    }

    @Test
    void binaryFramesAndSupervisorAbortAreBounded() {
        var sink = new RecordingSink();
        var relay = new SupervisorHttpResponseRelay(ACTOR, REQUEST, 4, 8, sink);
        relay.grantCredit(4);
        relay.accept(ACTOR, 0, headers("application/octet-stream"));
        relay.accept(ACTOR, 1, Map.of(
                "request_id", REQUEST, "kind", "bytes",
                "data", List.of(0, 255, 65, 66)));
        assertArrayEquals(new byte[]{0, (byte) 255, 65, 66}, sink.body.getFirst());
        assertThrows(IllegalStateException.class,
                () -> relay.accept(ACTOR, 2, Map.of(
                        "request_id", REQUEST, "kind", "bytes",
                        "data", List.of(1))));
        relay.abortFromSupervisor("client disconnected");
        relay.abortFromSupervisor("duplicate");
        assertEquals(SupervisorHttpResponseRelay.State.ABORTED, relay.state());
        assertEquals(1, sink.events.stream().filter(e -> e.startsWith("abort:")).count());
    }

    @Test
    void failedDownstreamSocketWriterIsFencedAfterPartialCommit() {
        final int[] aborts = {0};
        SupervisorHttpResponseRelay.Sink failing = new SupervisorHttpResponseRelay.Sink() {
            @Override public void headers(int status, Map<String, String> h) { }
            @Override public void chunk(byte[] bytes) {
                throw new IllegalStateException("socket write failed");
            }
            @Override public void end() { }
            @Override public void abort(String why) { aborts[0]++; }
        };
        var relay = new SupervisorHttpResponseRelay(ACTOR, REQUEST, 64, 128, failing);
        relay.grantCredit(10);
        relay.accept(ACTOR, 0, headers("text/html"));
        assertThrows(IllegalStateException.class,
                () -> relay.accept(ACTOR, 1, chunk("partial")));
        assertEquals(SupervisorHttpResponseRelay.State.ABORTED, relay.state());
        assertEquals(1, aborts[0]);
        assertThrows(IllegalStateException.class,
                () -> relay.accept(ACTOR, 1, chunk("retry")));
    }

    @Test
    void existingActorOutputTransportCarriesOnlyDataToTrustedSupervisor() throws Exception {
        // The real actor ID is minted by ActorRuntime and cannot be supplied
        // by the guest wire map; bind a new per-request relay to that ID.
        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<Integer>spawn(() -> (message, context) -> {
                context.runtime().emitCurrentActorOutput(headers("application/json"));
                context.runtime().emitCurrentActorOutput(chunk("{\"ok\":true}"));
                context.runtime().emitCurrentActorOutput(end());
                context.self().stop();
            });
            RecordingSink accepted = new RecordingSink();
            var bound = new SupervisorHttpResponseRelay(
                    worker.id().value().toString(), REQUEST, 64, 128, accepted);
            bound.grantCredit(64);
            worker.send(1);
            for (int i = 0; i < 3; i++) {
                var step = worker.outputs().next().get(5, TimeUnit.SECONDS);
                assertFalse(step.done());
                var envelope = step.value();
                bound.accept(envelope.actorId().value().toString(),
                        envelope.sequence(), envelope.value());
            }
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(SupervisorHttpResponseRelay.State.ENDED, bound.state());
            assertEquals(List.of("status:200:application/json", "chunk", "end"),
                    accepted.events);
        }
    }
}
