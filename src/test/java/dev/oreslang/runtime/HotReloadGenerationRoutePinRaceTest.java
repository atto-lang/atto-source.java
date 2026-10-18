package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
final class HotReloadGenerationRoutePinRaceTest {
    private static final String SOURCE =
            "pub routine main(): void { return; }";

    @Test
    void retireAndActorRoutePinHaveOneLinearizationWinner() throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(
                             IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            for (int iteration = 0; iteration < 64; iteration++) {
                var generation = manager.stageActorGeneration(
                        "pin-race-" + iteration + ".ores",
                        SOURCE);
                CountDownLatch start = new CountDownLatch(1);
                AtomicBoolean pinSucceeded = new AtomicBoolean();
                AtomicBoolean retireSucceeded = new AtomicBoolean();
                AtomicReference<HotReloadManager.Generation.ActorRouteLease> lease =
                        new AtomicReference<>();
                AtomicReference<Throwable> unexpected = new AtomicReference<>();

                Thread pinner = new Thread(() -> {
                    try {
                        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        lease.set(generation.pinActorRoute());
                        pinSucceeded.set(true);
                    } catch (IllegalStateException expectedLostRace) {
                        // retirement won
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                }, "hot-reload-route-pinner");

                Thread retirer = new Thread(() -> {
                    try {
                        assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        manager.retire(generation.id());
                        retireSucceeded.set(true);
                    } catch (IllegalStateException expectedLostRace) {
                        // route pin won
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                }, "hot-reload-generation-retirer");

                pinner.start();
                retirer.start();
                start.countDown();
                pinner.join(5000);
                retirer.join(5000);
                assertFalse(pinner.isAlive());
                assertFalse(retirer.isAlive());
                assertNull(unexpected.get());
                assertNotEquals(pinSucceeded.get(), retireSucceeded.get(),
                        "exactly one side must win pin-vs-retire admission");

                if (pinSucceeded.get()) {
                    assertFalse(generation.closed(),
                            "retire may not invalidate the winning pinned route");
                    assertThrows(IllegalStateException.class, generation::start,
                            "generation main must not execute while route-pinned");
                    lease.get().close();
                    manager.retire(generation.id());
                    assertTrue(generation.closed());
                } else {
                    assertTrue(generation.closed());
                    assertThrows(IllegalStateException.class,
                            generation::pinActorRoute);
                }
            }
        }
    }
}
