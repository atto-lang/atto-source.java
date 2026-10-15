package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual OresScheduler.TaskRunner -> JNI -> native C source-turn smoke.
 *
 * This is test-only explicit turn wrapping, NOT an installed production bridge.
 * Run only with -Dores.native.actor.turn.experimental=true and a built JNI
 * library on java.library.path. Normal Maven builds must never load JNI.
 */
@EnabledIfSystemProperty(named = "ores.native.actor.turn.experimental", matches = "true")
final class NativeActorTurnSchedulerJniTest {
    @Test
    void actualSchedulerSuspendsResumesAndAcknowledgesPhysicalQuiescence() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = new OresScheduler(1)) {
                var source = kernel.register(1, 1);
                var gate = new OresFuture<Integer>();
                var steps = new AtomicInteger();
                var started = new CountDownLatch(1);
                OresFuture<Integer> result = scheduler.start(resume -> {
                    kernel.enter(source);
                    try {
                        if (steps.getAndIncrement() == 0) {
                            started.countDown();
                            return OresScheduler.<Integer>await(gate);
                        }
                        assertFalse(resume.initial());
                        return OresScheduler.done(42);
                    } finally {
                        kernel.exit(source);
                    }
                }, () -> {
                    kernel.settled(source);
                    kernel.retire(source);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                gate.completeFromRuntime(42);
                assertEquals(42, result.get(5, TimeUnit.SECONDS));
                assertEquals(2, steps.get());
                kernel.stop();
            }
        }
    }

    @Test
    void stopRejectsResumedGuestTurnAndStillRetiresQuiescentSource() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = new OresScheduler(1)) {
                var source = kernel.register(2, 1);
                var gate = new OresFuture<Integer>();
                var started = new CountDownLatch(1);
                var steps = new AtomicInteger();
                OresFuture<Integer> result = scheduler.start(resume -> {
                    kernel.enter(source); // must be rejected after STOP
                    try {
                        steps.incrementAndGet();
                        started.countDown();
                        return OresScheduler.<Integer>await(gate);
                    } finally {
                        kernel.exit(source);
                    }
                }, () -> {
                    kernel.settled(source);
                    kernel.retire(source);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                kernel.stop();
                gate.completeFromRuntime(1);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> result.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertEquals(1, steps.get(), "guest must not resume after STOP");
            }
        }
    }
}
