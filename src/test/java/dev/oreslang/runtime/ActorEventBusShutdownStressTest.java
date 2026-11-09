package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Small, reproducible host-side stress gate for publication/close overlap.
 * Non-deterministic ordering is allowed; post-close admission and leaked
 * reservation bytes are not. This is not a distributed backend proof.
 */
@Timeout(45)
final class ActorEventBusShutdownStressTest {

    @Test
    void repeatedConcurrentPublisherAndGroupShutdownHaveNoResidualReservations()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ExecutorService host = Executors.newFixedThreadPool(2);
            try {
                final int rounds = 12;
                final int attemptsPerRound = 32;
                for (int round = 0; round < rounds; round++) {
                    final int seededRound = round;
                    ActorRuntime.ActorGroup group = runtime.createActorGroup();
                    ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
                    ActorEventBus bus = group.events();
                    bus.defineTopic("overlap", ActorEventBus.DeliveryPolicy.LOSSY, 1);

                    CountDownLatch ready = new CountDownLatch(1);
                    AtomicReference<Throwable> setupFailure = new AtomicReference<>();
                    ActorRuntime.ActorRef<Integer> subscriber =
                            runtime.spawnSharedTrusted(factory -> (message, context) -> {
                                try {
                                    join.join();
                                    bus.subscribe("overlap");
                                } catch (Throwable problem) {
                                    setupFailure.compareAndSet(null, problem);
                                } finally {
                                    ready.countDown();
                                }
                            });

                    subscriber.send(1);
                    assertTrue(ready.await(5, TimeUnit.SECONDS));
                    assertNull(setupFailure.get(),
                            () -> "round " + seededRound + ": " + setupFailure.get());

                    CyclicBarrier start = new CyclicBarrier(2);
                    AtomicInteger successes = new AtomicInteger();
                    AtomicInteger refusals = new AtomicInteger();
                    Future<?> publisher = host.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        Random schedule = new Random(0xA770L + seededRound);
                        for (int i = 0; i < attemptsPerRound; i++) {
                            if (schedule.nextInt(4) == 0) Thread.yield();
                            try {
                                bus.publishSystem(
                                        "overlap", new ArrayList<>(List.of(seededRound, i)));
                                successes.incrementAndGet();
                            } catch (IllegalStateException closed) {
                                // If group close linearized first, refusal is valid.
                                refusals.incrementAndGet();
                            }
                        }
                        return null;
                    });
                    Future<?> closer = host.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        group.close();
                        return null;
                    });
                    publisher.get(8, TimeUnit.SECONDS);
                    closer.get(8, TimeUnit.SECONDS);
                    assertEquals(attemptsPerRound, successes.get() + refusals.get());
                    assertTrue(group.closed());
                    assertTrue(bus.closed());
                    assertThrows(IllegalStateException.class,
                            () -> bus.publishSystem("overlap", List.of(999)),
                            "closed group must not admit a new publication");
                    assertEquals(0L, runtime.sharedMemoryBytes(),
                            "round " + round + ": every frozen event reservation must be released");
                    assertTrue(subscriber.isAlive(),
                            "closing an event group must not stop an ordinary actor");

                    subscriber.stop();
                    assertTrue(subscriber.awaitTermination(5, TimeUnit.SECONDS));
                    assertEquals(0L, runtime.sharedMemoryBytes(),
                            "round " + round + ": actor teardown cannot leak event memory");
                }
            } finally {
                host.shutdownNow();
                assertTrue(host.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
