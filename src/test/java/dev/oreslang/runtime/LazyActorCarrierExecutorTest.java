package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class LazyActorCarrierExecutorTest {
    @Test
    void constructedAndClosedWithoutWorkNeverCreatesThreadpool() throws Exception {
        AtomicInteger created = new AtomicInteger();
        LazyActorCarrierExecutor dormant = new LazyActorCarrierExecutor(() -> {
            created.incrementAndGet();
            return Executors.newSingleThreadExecutor();
        }, false);
        assertFalse(dormant.activated());
        assertEquals(0, created.get());
        assertFalse(dormant.isTerminated());
        dormant.shutdown();
        assertTrue(dormant.awaitTermination(1, TimeUnit.SECONDS));
        assertTrue(dormant.isTerminated());
        assertEquals(0, created.get());
        assertThrows(RejectedExecutionException.class, () -> dormant.execute(() -> {}));
        assertEquals(0, created.get());
    }

    @Test
    void concurrentFirstAdmissionActivatesExactlyOneDelegate() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        LazyActorCarrierExecutor lazy = new LazyActorCarrierExecutor(() -> {
            created.incrementAndGet();
            return Executors.newFixedThreadPool(2);
        }, false);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(12);
            try (var submitters = Executors.newFixedThreadPool(12)) {
                for (int i = 0; i < 12; ++i) {
                    submitters.execute(() -> {
                        try {
                            start.await();
                            lazy.execute(() -> {
                                completed.incrementAndGet();
                                finish.countDown();
                            });
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                start.countDown();
                assertTrue(finish.await(3, TimeUnit.SECONDS));
            }
            assertTrue(lazy.activated());
            assertEquals(1, created.get());
            assertEquals(12, completed.get());
        } finally {
            lazy.shutdown();
            assertTrue(lazy.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedActivationPublishesNoWorkAndShutdownFencesRetry() {
        AtomicInteger attempts = new AtomicInteger();
        LazyActorCarrierExecutor lazy = new LazyActorCarrierExecutor(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("native activation failure");
        }, true);
        AtomicInteger invoked = new AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> lazy.execute(invoked::incrementAndGet));
        assertFalse(lazy.activated());
        assertEquals(0, invoked.get());
        lazy.shutdownNow();
        assertThrows(RejectedExecutionException.class,
                () -> lazy.execute(invoked::incrementAndGet));
        assertEquals(1, attempts.get());
    }

    @Test
    void actorRuntimeAndGroupsCreateNoCarrierPoolsUntilFirstMail() throws Exception {
        String beforeMode = System.getProperty("ores.runtime.carriers");
        String beforeAffinity = System.getProperty("ores.actor.affinity");
        System.setProperty("ores.runtime.carriers", "java");
        System.setProperty("ores.actor.affinity", "lane");
        try {
            var config = new ActorRuntime.DispatcherConfig(2, 2, 8, 64);
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                assertEquals(0, runtime.activatedActorCarrierPools());
                var group = runtime.createActorGroup();
                assertEquals(0, runtime.activatedActorCarrierPools(),
                        "creating an ActorGroup must not activate carrier workers");
                group.close();
                CountDownLatch handled = new CountDownLatch(1);
                var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                    handled.countDown();
                    context.self().stop();
                });
                assertEquals(0, runtime.activatedActorCarrierPools(),
                        "actor allocation alone must not start an OS thread");
                actor.send("work");
                assertTrue(handled.await(3, TimeUnit.SECONDS));
                assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
                assertEquals(1, runtime.activatedActorCarrierPools(),
                        "only the selected shared-carrier lane should activate");
            }
        } finally {
            if (beforeMode == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", beforeMode);
            if (beforeAffinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", beforeAffinity);
        }
    }
}
