package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HOST-ONLY composition check for the inbound admission -> real actor mailbox
 * -> outbound W3C header seams. No socket is opened and no Atto guest or
 * OTLP exporter is involved, so this is NOT an end-to-end distributed trace.
 */
@Timeout(30)
final class HostOnlyTracePathCompositionTest {
    @Test
    void remoteW3cParentFlowsIntoActorAndOnwardToOutboundClientHeader()
            throws Exception {
        String incoming = "00-0123456789abcdef0123456789abcdef-abcdef0123456789-01";
        TraceParent remote = TraceParent.parse(incoming).orElseThrow();
        TraceParent serverContext =
                NativeHttp.admittedServerTraceContext(List.of(incoming), true);
        assertEquals(remote.traceId(), serverContext.traceId());
        assertNotEquals(remote.spanId(), serverContext.spanId());

        AtomicReference<TraceParent> actorContext = new AtomicReference<>();
        AtomicReference<TraceParent> outboundContext = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        try (ActorRuntime actors = new ActorRuntime()) {
            actors.configureTracePropagation(true);
            var consumer = actors.<String>spawnShared(() -> (message, ctx) -> {
                actorContext.set(TaskContext.traceParent());
                var headers = OutboundHttpRequest.withTraceparent(
                        List.of(), TaskContext.traceParent());
                outboundContext.set(
                        TraceParent.parse(headers.getFirst().getValue()).orElseThrow());
                delivered.countDown();
            });

            TaskContext prior = TaskContext.install(null);
            try {
                TaskContext.pushTraceParent(serverContext);
                consumer.send("ordinary-message-without-tracing-fields");
            } finally {
                TaskContext.install(prior);
            }
            assertTrue(delivered.await(12, TimeUnit.SECONDS));
            assertEquals(serverContext, actorContext.get());
            TraceParent outbound = outboundContext.get();
            assertNotNull(outbound);
            assertEquals(remote.traceId(), outbound.traceId());
            assertNotEquals(remote.spanId(), outbound.spanId());
            assertNotEquals(serverContext.spanId(), outbound.spanId());
            assertTrue(outbound.sampled());
            consumer.stop();
            assertTrue(consumer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
