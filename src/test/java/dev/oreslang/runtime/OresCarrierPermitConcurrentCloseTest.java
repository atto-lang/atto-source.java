package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Concurrent close must retire one physical carrier reservation exactly once. */
final class OresCarrierPermitConcurrentCloseTest {
    @Test void concurrentCloseOfSamePermitDoesNotUnderflowOrReleaseOtherDomains()
            throws Exception {
        var pool = new OresCarrierPermitPool(5);
        var shared = pool.tryAcquire(OresCarrierPermitPool.Domain.SHARED);
        var privatePermit = pool.tryAcquire(OresCarrierPermitPool.Domain.PRIVATE);
        assertNotNull(shared);
        assertNotNull(privatePermit);
        int contenders = 16;
        var ready = new CountDownLatch(contenders);
        var start = new CountDownLatch(1);
        var finished = new CountDownLatch(contenders);
        var failure = new AtomicReference<Throwable>();
        var threads = new ArrayList<Thread>();
        try {
            for (int i = 0; i < contenders; i++) {
                Thread thread = new Thread(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("close start timed out");
                        for (int n = 0; n < 100; n++) shared.close();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        finished.countDown();
                    }
                }, "permit-close-racer-" + i);
                threads.add(thread);
                thread.start();
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            if (failure.get() != null)
                fail("concurrent close failed", failure.get());
            var snapshot = pool.snapshot();
            assertEquals(1, snapshot.totalHeld());
            assertEquals(0, snapshot.sharedHeld());
            assertEquals(1, snapshot.privateHeld(),
                    "closing a SHARED lease must not release a PRIVATE lease");
        } finally {
            start.countDown();
            for (Thread thread : threads) thread.join(3_000L);
            shared.close();
            privatePermit.close();
        }
        assertEquals(0, pool.totalHeld());
    }
}
