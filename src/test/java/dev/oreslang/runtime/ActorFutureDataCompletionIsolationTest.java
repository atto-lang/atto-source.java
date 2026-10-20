package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorFutureDataCompletionIsolationTest {
    private static final long ONE_MIB_LOGICAL_BYTES =
            24L + UnaryTransportFrame.MAX_PAYLOAD_BYTES;
    private static final long MIN_PRIVATE_HEAP_BYTES = 16L * 1024 * 1024;

    @Test
    void privateFutureCompletionIsCopiedAndByteReservedUntilContinuationReturns()
            throws Exception {
        OresFuture<OresBytes> producer = new OresFuture<>();
        OresBytes source = OresBytes.copyOf(
                new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES]);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<OresBytes> delivered = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivateTrusted(
                    context -> (message, turnContext) -> {
                        ActorRuntime.ContinuationTarget target =
                                runtime.captureCurrentContinuationTarget();
                        runtime.enqueueDataOnCompletion(
                                producer,
                                target,
                                (value, terminal) -> {
                                    assertTrue(
                                            runtime.currentThreadOwnsActorExecutionLease(),
                                            "data continuation must run on the actor lane");
                                    delivered.set(value);
                                    failure.set(terminal);
                                    entered.countDown();
                                    await(release);
                                });
                        registered.countDown();
                    });

            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            actor.drain().get(5, TimeUnit.SECONDS);
            long baseline = runtime.privateMemoryBytes();

            producer.completeFromRuntime(source);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertNull(failure.get());
            assertNotNull(delivered.get());
            assertNotSame(source, delivered.get(),
                    "PRIVATE future data must not retain producer-owned OresBytes");
            assertEquals(source, delivered.get());
            assertEquals(
                    baseline + ONE_MIB_LOGICAL_BYTES,
                    runtime.privateMemoryBytes(),
                    "PRIVATE queued completion data must be byte-reserved");

            release.countDown();
            awaitTrue(() -> runtime.privateMemoryBytes() == baseline,
                    "PRIVATE completion reservation release");

            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void sharedFutureCompletionKeepsImmutableBytesIdentityButStillReservesBytes()
            throws Exception {
        OresFuture<OresBytes> producer = new OresFuture<>();
        OresBytes source = OresBytes.copyOf(
                new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES]);
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<OresBytes> delivered = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnSharedTrusted(
                    context -> (message, turnContext) -> {
                        ActorRuntime.ContinuationTarget target =
                                runtime.captureCurrentContinuationTarget();
                        runtime.enqueueDataOnCompletion(
                                producer,
                                target,
                                (value, terminal) -> {
                                    assertNull(terminal);
                                    assertTrue(
                                            runtime.currentThreadOwnsActorExecutionLease(),
                                            "data continuation must run on the actor lane");
                                    delivered.set(value);
                                    entered.countDown();
                                    await(release);
                                });
                        registered.countDown();
                    });

            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            actor.drain().get(5, TimeUnit.SECONDS);
            long baseline = runtime.sharedMemoryBytes();

            producer.completeFromRuntime(source);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertSame(source, delivered.get(),
                    "SHARED immutable OresBytes should remain zero-copy");
            assertEquals(
                    baseline + ONE_MIB_LOGICAL_BYTES,
                    runtime.sharedMemoryBytes(),
                    "SHARED queued completion data must be byte-reserved");

            release.countDown();
            awaitTrue(() -> runtime.sharedMemoryBytes() == baseline,
                    "SHARED completion reservation release");

            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void privateDataContinuationOverflowDrainsEveryQueuedReservation()
            throws Exception {
        List<OresFuture<OresBytes>> producers = new ArrayList<>();
        for (int i = 0; i < 1_025; i++) producers.add(new OresFuture<>());

        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch releaseActorTurn = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        OresBytes payload = OresBytes.copyOf(new byte[] {7});

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivateTrusted(
                    context -> (message, turnContext) -> {
                        ActorRuntime.ContinuationTarget target =
                                runtime.captureCurrentContinuationTarget();
                        for (OresFuture<OresBytes> producer : producers) {
                            runtime.enqueueDataOnCompletion(
                                    producer,
                                    target,
                                    (value, terminal) -> callbacks.incrementAndGet());
                        }
                        registered.countDown();
                        await(releaseActorTurn);
                    });

            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            long baseline = runtime.privateMemoryBytes();

            for (OresFuture<OresBytes> producer : producers) {
                producer.completeFromRuntime(payload);
            }

            assertEquals(0, callbacks.get(),
                    "blocked actor turn must not execute queued data continuations");
            assertEquals(baseline, runtime.privateMemoryBytes(),
                    "overflow fail-stop must drain every queued data reservation");
            Throwable terminal = actor.failure().orElseThrow(
                    () -> new AssertionError("continuation overflow did not fail actor"));
            assertInstanceOf(IllegalStateException.class, terminal);
            assertTrue(terminal.getMessage().contains("continuation queue overflow"));

            releaseActorTurn.countDown();
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> actor.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.privateMemoryBytes());
        } finally {
            releaseActorTurn.countDown();
        }
    }

    @Test
    void oversizedPrivateFutureCompletionFailsBeforeReservationAndDoesNotLeak()
            throws Exception {
        OresFuture<OresBytes> producer = new OresFuture<>();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<OresBytes> delivered = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                MIN_PRIVATE_HEAP_BYTES,
                64,
                java.time.Duration.ofSeconds(30),
                false);

        try (ActorRuntime runtime = new ActorRuntime(
                policy,
                new ActorRuntime.DispatcherConfig(1, 1, 64, 128))) {
            var actor = runtime.<String>spawnPrivateTrusted(
                    policy,
                    context -> (message, turnContext) -> {
                        ActorRuntime.ContinuationTarget target =
                                runtime.captureCurrentContinuationTarget();
                        runtime.enqueueDataOnCompletion(
                                producer,
                                target,
                                (value, terminal) -> {
                                    assertTrue(
                                            runtime.currentThreadOwnsActorExecutionLease(),
                                            "oversize rejection must run on the actor lane");
                                    delivered.set(value);
                                    failure.set(terminal);
                                    observed.countDown();
                                });
                        registered.countDown();
                    });

            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            actor.drain().get(5, TimeUnit.SECONDS);
            long baseline = runtime.privateMemoryBytes();

            // The payload alone fits the nominal heap, but its OresBytes
            // logical overhead makes the transported value exceed it.
            OresBytes tooLarge = OresBytes.copyOf(
                    new byte[(int) MIN_PRIVATE_HEAP_BYTES]);
            producer.completeFromRuntime(tooLarge);

            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertNull(delivered.get());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(baseline, runtime.privateMemoryBytes(),
                    "rejected data completion must not retain a private reservation");

            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void mutableHostCompletionBecomesActorLaneFailureInsteadOfRawData()
            throws Exception {
        OresFuture<Object> producer = new OresFuture<>();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Object> delivered = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> producerThread = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivateTrusted(
                    context -> (message, turnContext) -> {
                        ActorRuntime.ContinuationTarget target =
                                runtime.captureCurrentContinuationTarget();
                        runtime.enqueueDataOnCompletion(
                                producer,
                                target,
                                (value, terminal) -> {
                                    callbackThread.set(Thread.currentThread());
                                    delivered.set(value);
                                    failure.set(terminal);
                                    assertTrue(runtime.currentThreadOwnsActorExecutionLease());
                                    observed.countDown();
                                });
                        registered.countDown();
                    });

            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            actor.drain().get(5, TimeUnit.SECONDS);

            Thread hostProducer = new Thread(() -> {
                producerThread.set(Thread.currentThread());
                producer.completeFromRuntime(new StringBuilder("mutable"));
            }, "future-host-producer");
            hostProducer.start();
            hostProducer.join(5_000);
            assertFalse(hostProducer.isAlive());

            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertNull(delivered.get());
            assertInstanceOf(IllegalArgumentException.class, failure.get());
            assertNotEquals(producerThread.get(), callbackThread.get(),
                    "transport rejection must still be delivered on the actor lane");

            actor.stop();
            actor.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void awaitTrue(
            java.util.function.BooleanSupplier condition,
            String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("timed out waiting for " + what);
            }
            Thread.sleep(2);
        }
    }
}
