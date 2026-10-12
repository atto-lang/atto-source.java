package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Admission of new native regions must coexist with late release of old ones. */
@Timeout(value = 25, unit = TimeUnit.SECONDS)
final class ConcurrentNativeReservationRetirementTest {
    @Test
    void lateLoanRefundAndNewActorRegionAdmissionShareOneBudgetLock() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch begin = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicReference<ExplicitNativeI64Region.Loan> retainedLoan = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnPrivate(() -> (message, ctx) -> {
                try {
                    if (message == 1) {
                        var region = ctx.actorOwnedMemory().orElseThrow()
                                .newExplicitNativeI64Region(1);
                        retainedLoan.set(region.borrow(region.allocate(73)));
                        region.close();
                        created.countDown();
                    } else if (message == 2) {
                        secondEntered.countDown();
                        if (!begin.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("concurrent refund did not start");
                        }
                        // Each admission charges the same actor/global budget
                        // that the old completion thread is concurrently
                        // refunding. All regions finish physical retirement.
                        for (int i = 0; i < 48; i++) {
                            var region = ctx.actorOwnedMemory().orElseThrow()
                                    .newExplicitNativeI64Region(1);
                            var slot = region.allocate(i);
                            assertEquals(i, region.read(slot));
                            region.drop(slot);
                            region.close();
                        }
                        secondFinished.countDown();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    if (message == 1) created.countDown();
                    if (message == 2) secondFinished.countDown();
                }
            });
            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send(1);
            assertTrue(created.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            assertEquals(8L, runtime.privateMemoryBytes());
            actor.send(2);
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            var closer = new Thread(() -> {
                try {
                    if (!begin.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("reservation did not start");
                    }
                    retainedLoan.get().close();
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }, "native-late-loan-refund");
            closer.start();
            begin.countDown();
            assertTrue(secondFinished.await(10, TimeUnit.SECONDS));
            closer.join(5000);
            assertFalse(closer.isAlive());
            if (failure.get() != null) fail(failure.get());
            actor.stop();
            actor.get_done().get(5, TimeUnit.SECONDS);
            actor.get_shutdown_result().get(5, TimeUnit.SECONDS);
            assertEquals(0L, runtime.privateMemoryBytes(),
                    "concurrent reservation and native refund must preserve the aggregate budget");
        }
    }
}
