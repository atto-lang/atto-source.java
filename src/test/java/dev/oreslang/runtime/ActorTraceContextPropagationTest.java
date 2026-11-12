package dev.oreslang.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs actual JVM actor mailbox dispatch and captures immutable W3C context
 * exclusively from private envelope metadata. Not a source Atto guest nor
 * network/W3C header injection or OTLP export.
 */
@Timeout(30)
final class ActorTraceContextPropagationTest {
    @Test
    void trustedHostSyncSendTransfersTraceOutsideMessageAndIsolatesNextMail() throws Exception {
        Map<String, TraceParent> seen = new ConcurrentHashMap<>();
        CountDownLatch delivered = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        TraceParent parent = TraceParent.newRoot(true);

        try (ActorRuntime actors = new ActorRuntime()) {
            actors.configureTracePropagation(true);
            var consumer = actors.<String>spawnShared(() -> (message, ctx) -> {
                try {
                    TraceParent current = TaskContext.traceParent();
                    if (current != null) seen.put(message, current);
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                } finally {
                    delivered.countDown();
                }
            });

            TaskContext before = TaskContext.pushTraceParent(parent);
            try {
                consumer.send("traced");
            } finally {
                TaskContext.install(before);
            }
            assertNull(TaskContext.traceParent());
            consumer.send("plain");

            assertTrue(delivered.await(12, TimeUnit.SECONDS),
                    "real actor mail delivery not completed");
            assertNull(failure.get());
            assertEquals(parent, seen.get("traced"));
            assertFalse(seen.containsKey("plain"),
                    "tracing context must not leak across unrelated actor turns");
            consumer.stop();
            assertTrue(consumer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorToActorSendCarriesImmutableContextAndPreservesSenderScope() throws Exception {
        AtomicReference<TraceParent> received = new AtomicReference<>();
        AtomicReference<TraceParent> stillInProducer = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        TraceParent parent = TraceParent.newRoot(false); // unsampled is not contextless
        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.configureTracePropagation(true);
            var downstream = runtime.<String>spawnShared(() -> (message, ctx) -> {
                received.set(TaskContext.traceParent());
                delivered.countDown();
            });
            var upstream = runtime.<String>spawnShared(() -> (message, ctx) -> {
                downstream.send(message);
                stillInProducer.set(TaskContext.traceParent());
            });

            TaskContext saved = TaskContext.pushTraceParent(parent);
            try {
                upstream.send("message");
            } finally {
                TaskContext.install(saved);
            }

            assertTrue(delivered.await(12, TimeUnit.SECONDS));
            assertEquals(parent, received.get());
            // Unmodified parent context is passed; no mutable Span crosses mail.
            assertEquals(parent, stillInProducer.get());
            upstream.stop();
            downstream.stop();
            assertTrue(upstream.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(downstream.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void standaloneRuntimeDefaultsToNoTracePropagation() throws Exception {
        AtomicReference<TraceParent> observed = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        try (ActorRuntime actors = new ActorRuntime()) {
            var actor = actors.<String>spawnShared(() -> (message, ctx) -> {
                observed.set(TaskContext.traceParent());
                delivered.countDown();
            });
            TaskContext saved = TaskContext.pushTraceParent(TraceParent.newRoot(true));
            try {
                actor.send("inert");
            } finally {
                TaskContext.install(saved);
            }
            assertTrue(delivered.await(12, TimeUnit.SECONDS));
            assertNull(observed.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void activeActorContextIsRestoredOnThrowingHandler() throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        TraceParent parent = TraceParent.newRoot(true);
        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.configureTracePropagation(true);
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> {
                assertEquals(parent, TaskContext.traceParent());
                seen.countDown();
                throw new IllegalStateException("deliberate handler failure");
            });
            TaskContext saved = TaskContext.pushTraceParent(parent);
            try {
                actor.send("throws");
            } finally {
                TaskContext.install(saved);
            }
            assertTrue(seen.await(12, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isPresent(), "guest actor failure must be preserved");
        }
    }
}
