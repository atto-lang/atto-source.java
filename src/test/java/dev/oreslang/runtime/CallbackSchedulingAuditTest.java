package dev.oreslang.runtime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class CallbackSchedulingAuditTest {
    @ParameterizedTest
    @CsvSource({"true,success", "false,success", "true,failure", "false,failure",
            "true,cancel", "false,cancel"})
    void callbackTimingAndOutcomeNeverChangeContinuationContext(boolean ready, String outcome)
            throws Exception {
        AtomicReference<OresFuture.Callback<Integer>> callback = new AtomicReference<>();
        OresFuture<Integer> source = OresFuture.fromCallback(callback::set);
        Runnable settle = () -> {
            switch (outcome) {
                case "success" -> callback.get().resolve(42);
                case "failure" -> callback.get().reject(new IllegalStateException("expected"));
                case "cancel" -> callback.get().cancel();
                default -> fail("unknown outcome");
            }
        };
        if (ready) settle.run();
        CountDownLatch suspending = new CountDownLatch(1);
        AtomicInteger pc = new AtomicInteger();
        AtomicLong firstDispatch = new AtomicLong();
        AtomicInteger activeAdmissions = new AtomicInteger();
        try (OresScheduler scheduler = OresScheduler.managed(1, turn -> {
            assertEquals(1, activeAdmissions.incrementAndGet());
            try { turn.run(); }
            finally { assertEquals(0, activeAdmissions.decrementAndGet()); }
        })) {
            OresFuture<Integer> result = scheduler.start(resume -> {
                assertSame(scheduler, OresScheduler.current());
                assertEquals(1, activeAdmissions.get());
                if (pc.getAndIncrement() == 0) {
                    firstDispatch.set(OresScheduler.currentDispatchId());
                    suspending.countDown();
                    return OresScheduler.await(source);
                }
                assertNotEquals(firstDispatch.get(), OresScheduler.currentDispatchId());
                if (outcome.equals("success")) {
                    assertNull(resume.failure());
                    assertEquals(42, resume.value());
                } else if (outcome.equals("failure")) {
                    assertInstanceOf(IllegalStateException.class, resume.failure());
                } else {
                    assertInstanceOf(java.util.concurrent.CancellationException.class, resume.failure());
                }
                return OresScheduler.done(7);
            });
            assertTrue(suspending.await(5, TimeUnit.SECONDS));
            if (!ready) settle.run();
            assertEquals(7, result.get(5, TimeUnit.SECONDS));
            assertEquals(2, pc.get());
            assertEquals(0, source.pendingRuntimeWaiterCount());
        }
        assertNull(OresScheduler.current());
    }
}
