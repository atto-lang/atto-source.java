package dev.oreslang.runtime;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class HotReloadConcurrentPublicationTest {
    private static String module(int value) {
        return """
                pub define contract WorkerApi as
                  fnc run(): int;
                end
                define module Worker conforms WorkerApi as
                  pub fnc run(): int { return %d; }
                end
                """.formatted(value);
    }

    @RepeatedTest(12)
    @Timeout(35)
    void concurrentModuleReplacementsCannotPublishStaleCandidates() throws Exception {
        try (HotReloadManager loader = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var initial = loader.reloadModule("worker.ores", "Worker", module(1), "WorkerApi");
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch gate = new CountDownLatch(1);
                List<Future<HotReloadManager.Generation>> attempts = new ArrayList<>();
                for (int version : new int[]{2, 3}) {
                    attempts.add(workers.submit(() -> {
                        gate.await();
                        try {
                            return loader.reloadModule(
                                    "worker.ores", "Worker", module(version), "WorkerApi");
                        } catch (IllegalStateException stale) {
                            assertTrue(stale.getMessage().contains("stale module candidate"),
                                    "only a stale candidate may lose the publication race: " + stale);
                            return null;
                        }
                    }));
                }
                gate.countDown();
                var first = attempts.get(0).get(25, TimeUnit.SECONDS);
                var second = attempts.get(1).get(25, TimeUnit.SECONDS);
                int winners = (first == null ? 0 : 1) + (second == null ? 0 : 1);
                assertTrue(winners >= 1);
                var active = loader.active("worker.ores");
                assertNotNull(active);
                assertSame(active, loader.active());
                assertTrue(active.started());
                assertFalse(active.closed());
                assertTrue(active == first || active == second);
                int result = active.invokePublic("Worker.run").asInt();
                assertTrue(result == 2 || result == 3);
                assertFalse(initial.closed());
                assertEquals(1 + winners, loader.liveGenerations(),
                        "a rejected candidate must not leak a generation");
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @RepeatedTest(20)
    @Timeout(35)
    void concurrentGenericStartsKeepOnlySuccessfulGenerations() throws Exception {
        try (HotReloadManager loader = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var initial = loader.loadAndStart("legacy.ores",
                    "pub routine main(): void { return; }");
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch gate = new CountDownLatch(1);
                List<Future<HotReloadManager.Generation>> attempts = new ArrayList<>();
                for (int version : new int[]{2, 3}) {
                    attempts.add(workers.submit(() -> {
                        gate.await();
                        try {
                            return loader.loadAndStart("legacy.ores",
                                    "pub val int version = " + version + ";");
                        } catch (IllegalStateException stale) {
                            assertTrue(stale.getMessage().contains("stale generic startup candidate"),
                                    "only a stale candidate may lose the publication race: " + stale);
                            return null;
                        }
                    }));
                }
                gate.countDown();
                var first = attempts.get(0).get(25, TimeUnit.SECONDS);
                var second = attempts.get(1).get(25, TimeUnit.SECONDS);
                int winners = (first == null ? 0 : 1) + (second == null ? 0 : 1);
                assertTrue(winners >= 1);
                var active = loader.active("legacy.ores");
                assertNotNull(active);
                assertTrue(active.started());
                assertTrue(active == first || active == second);
                assertFalse(initial.closed());
                assertEquals(1 + winners, loader.liveGenerations());
            } finally {
                workers.shutdownNow();
            }
        }
    }
}
