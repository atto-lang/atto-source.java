package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Regression for ores.actor.affinity=none JVM first-carrier admission. */
@Timeout(30)
final class ActorJvmFirstCarrierAdmissionTest {

    @Test
    void failedFirstWorkerMustNotReturnSuccess() {
        var pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8)) {
            @Override public boolean prestartCoreThread() { return false; }
        };
        try {
            assertThrows(RejectedExecutionException.class,
                    () -> ActorRuntime.startUnlanedJvmCarrier(pool));
            assertEquals(0, pool.getPoolSize());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closedPoolCannotPublishAnActorCarrier() {
        var pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8));
        pool.shutdownNow();
        assertThrows(RejectedExecutionException.class,
                () -> ActorRuntime.startUnlanedJvmCarrier(pool));
    }

    @Test
    void repeatedJvmPrestartReusesTheExistingWorker() {
        var pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8));
        try {
            ActorRuntime.startUnlanedJvmCarrier(pool);
            assertEquals(1, pool.getPoolSize());
            ActorRuntime.startUnlanedJvmCarrier(pool);
            assertEquals(1, pool.getPoolSize());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void nonAffinityJvmRuntimeSpawnsIdleActorsWithoutGrowingWorkers() {
        String previousCarriers = System.getProperty("ores.runtime.carriers");
        String previousAffinity = System.getProperty("ores.actor.affinity");
        System.setProperty("ores.runtime.carriers", "java");
        System.setProperty("ores.actor.affinity", "none");
        try {
            var config = new ActorRuntime.DispatcherConfig(2, 3, 8, 64);
            try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                assertEquals(new ActorRuntime.ActorCarrierCounts(0, 0, 0),
                        runtime.startedActorCarriers());
                List<ActorRuntime.ActorRef<Object>> refs = new ArrayList<>();
                for (int i = 0; i < 32; i++) {
                    refs.add(runtime.<Object>spawnShared(() -> (message, context) -> {}));
                }
                assertEquals(new ActorRuntime.ActorCarrierCounts(0, 1, 0),
                        runtime.startedActorCarriers());
                refs.forEach(ActorRuntime.ActorRef::stop);
            }
        } finally {
            restore("ores.runtime.carriers", previousCarriers);
            restore("ores.actor.affinity", previousAffinity);
        }
    }

    private static void restore(String name, String previous) {
        if (previous == null) System.clearProperty(name);
        else System.setProperty(name, previous);
    }
}
