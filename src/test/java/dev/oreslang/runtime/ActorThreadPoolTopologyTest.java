package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorThreadPoolTopologyTest {
    private static final ConcurrentHashMap<ActorRuntime.ActorKind, String> THREADS =
            new ConcurrentHashMap<>();
    private static final AtomicReference<CountDownLatch> DELIVERED =
            new AtomicReference<>();

    private static final ActorRuntime.BehaviorFactory<String> RECORD_THREAD =
            context -> (message, turn) -> {
                THREADS.put(context.kind(), Thread.currentThread().getName());
                CountDownLatch latch = DELIVERED.get();
                if (latch != null) latch.countDown();
                turn.self().stop();
            };

    @Test
    void privateSharedAndUntrustedMapToThreeDistinctPoolDomains() {
        assertEquals(
                ActorRuntime.ActorThreadPoolDomain.ISOLATED,
                ActorRuntime.threadPoolDomain(ActorRuntime.ActorKind.PRIVATE));
        assertEquals(
                ActorRuntime.ActorThreadPoolDomain.SHARED,
                ActorRuntime.threadPoolDomain(ActorRuntime.ActorKind.SHARED));
        assertEquals(
                ActorRuntime.ActorThreadPoolDomain.UNTRUSTED,
                ActorRuntime.threadPoolDomain(ActorRuntime.ActorKind.UNTRUSTED));
    }

    @Test
    void allThreePoolObjectsExistAtStartupWithZeroJvmWorkers() {
        withJavaCarriers(() -> {
            var config = new ActorRuntime.DispatcherConfig(2, 2, 8, 64);
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                var status = runtime.actorThreadPoolStatus();
                assertEquals(3, status.size());

                Map<ActorRuntime.ActorThreadPoolDomain, ActorRuntime.ActorThreadPoolStatus> byDomain =
                        new EnumMap<>(ActorRuntime.ActorThreadPoolDomain.class);
                for (var entry : status) byDomain.put(entry.domain(), entry);

                assertEquals(
                        java.util.Set.of(
                                ActorRuntime.ActorThreadPoolDomain.SHARED,
                                ActorRuntime.ActorThreadPoolDomain.ISOLATED,
                                ActorRuntime.ActorThreadPoolDomain.UNTRUSTED),
                        byDomain.keySet());
                assertTrue(
                        status.stream().allMatch(entry -> entry.workerThreads() == 0),
                        "actor pool objects must be eager but physical workers must be lazy");
            }
        });
    }

    @Test
    void eachActorClassLazilyActivatesOnlyItsOwnPool() throws Exception {
        String oldCarriers = System.getProperty("ores.runtime.carriers");
        String oldAffinity = System.getProperty("ores.actor.affinity");
        try {
            System.setProperty("ores.runtime.carriers", "java");
            System.setProperty("ores.actor.affinity", "none");
            THREADS.clear();
            CountDownLatch delivered = new CountDownLatch(3);
            DELIVERED.set(delivered);

            var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 64);
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                assertTrue(runtime.actorThreadPoolStatus().stream()
                        .allMatch(entry -> entry.workerThreads() == 0));

                var isolated = runtime.<String>spawnPrivate(RECORD_THREAD);
                var shared = runtime.<String>spawnShared(RECORD_THREAD);
                var untrusted = runtime.<String>spawnUntrusted(RECORD_THREAD);

                isolated.send("isolated");
                shared.send("shared");
                untrusted.send("untrusted");

                assertTrue(delivered.await(5, TimeUnit.SECONDS));
                assertTrue(isolated.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(shared.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(untrusted.awaitTermination(5, TimeUnit.SECONDS));

                assertTrue(THREADS.get(ActorRuntime.ActorKind.PRIVATE)
                        .startsWith("ores-isolated-actor-dispatcher-"));
                assertTrue(THREADS.get(ActorRuntime.ActorKind.SHARED)
                        .startsWith("ores-shared-actor-dispatcher-"));
                assertTrue(THREADS.get(ActorRuntime.ActorKind.UNTRUSTED)
                        .startsWith("ores-untrusted-actor-dispatcher-"));

                var status = runtime.actorThreadPoolStatus();
                assertTrue(status.stream().allMatch(entry -> entry.workerThreads() == 1));
            }
        } finally {
            DELIVERED.set(null);
            THREADS.clear();
            restore("ores.runtime.carriers", oldCarriers);
            restore("ores.actor.affinity", oldAffinity);
        }
    }

    private static void withJavaCarriers(Runnable body) {
        String oldCarriers = System.getProperty("ores.runtime.carriers");
        try {
            System.setProperty("ores.runtime.carriers", "java");
            body.run();
        } finally {
            restore("ores.runtime.carriers", oldCarriers);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
