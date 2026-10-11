package dev.oreslang.runtime;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actor-bound source tasks must use their issuing task placement on every segment. */
final class SerialActorBoundSchedulerTest {
    @Test void initialAwaitAndCooperateAllReenterTicketedActorLane() throws Exception {
        LinkedBlockingQueue<Runnable> mailbox = new LinkedBlockingQueue<>();
        AtomicInteger issuedSegments = new AtomicInteger();
        AtomicInteger evaluatedSegments = new AtomicInteger();
        OresFuture<Integer> awaited = new OresFuture<>();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "serial-test", 1, command -> fail("unbound fallback scheduler executor used"))) {
            var completion = scheduler.startActorBound(resume -> {
                int pc = evaluatedSegments.getAndIncrement();
                return switch (pc) {
                    case 0 -> OresScheduler.await(awaited);
                    case 1 -> OresScheduler.cooperate();
                    case 2 -> OresScheduler.done(42);
                    default -> throw new AssertionError("duplicate actor continuation");
                };
            }, command -> {
                issuedSegments.incrementAndGet();
                mailbox.add(command);
            });

            assertEquals(1, issuedSegments.get());
            Runnable initial = mailbox.poll(1, TimeUnit.SECONDS);
            assertNotNull(initial);
            initial.run();
            assertEquals(1, evaluatedSegments.get());
            awaited.completeFromRuntime(9);
            Runnable second = mailbox.poll(1, TimeUnit.SECONDS);
            assertNotNull(second, "await must schedule through original actor ticket");
            second.run();
            Runnable third = mailbox.poll(1, TimeUnit.SECONDS);
            assertNotNull(third, "rt cooperate must schedule through original actor ticket");
            third.run();
            assertEquals(42, completion.get(1, TimeUnit.SECONDS));
            assertEquals(3, issuedSegments.get());
            assertEquals(3, evaluatedSegments.get());
            assertNull(mailbox.poll(), "completed task cannot requeue");
        }
    }
}
