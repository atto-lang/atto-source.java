package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Integration between the actual SelectPlan channel registration and the
 * execution-context suspension ABI. A pending select cannot monopolize the
 * only scheduler worker and channel completion must enqueue its continuation.
 *
 * This is not yet LLVM guest-source lowering.
 */
final class SelectPlanSchedulerSuspensionTest {
    @Test
    void pendingSelectReleasesSingleCarrierAndResumesOnlyOnScheduler() throws Exception {
        ChannelRuntime.Channel<Integer> input = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectPlan plan =
                ChannelRuntime.SelectPlan.of(ChannelRuntime.read(input));
        CountDownLatch registered = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Thread> producer = new AtomicReference<>();

        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> awaiting = scheduler.start(resume -> {
                assertSame(scheduler, OresScheduler.current());
                if (executions.getAndIncrement() == 0) {
                    var pending = plan.selectAsync();
                    assertFalse(pending.isDone());
                    registered.countDown();
                    return OresScheduler.await(pending);
                }
                assertFalse(resume.initial());
                assertNull(resume.failure());
                assertNotSame(producer.get(), Thread.currentThread(),
                        "guest continuation may not execute inline on producer");
                var result = (ChannelRuntime.SelectResult) resume.value();
                assertEquals(0, result.index());
                return OresScheduler.done((Integer) result.value());
            });

            assertTrue(registered.await(5, TimeUnit.SECONDS));
            // If await blocked the only carrier, this unrelated task hangs.
            assertEquals(99, scheduler.start(
                    resume -> OresScheduler.done(99)).get(5, TimeUnit.SECONDS));

            Thread send = Thread.ofPlatform().start(() -> {
                producer.set(Thread.currentThread());
                assertTrue(input.tryWrite(42));
            });
            send.join(5_000);
            assertFalse(send.isAlive());
            assertEquals(42, awaiting.get(5, TimeUnit.SECONDS));
            assertEquals(2, executions.get(),
                    "one suspending turn plus exactly one re-admitted continuation");
        }
    }
}
