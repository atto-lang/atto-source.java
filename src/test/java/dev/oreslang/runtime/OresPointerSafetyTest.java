package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class OresPointerSafetyTest {
    @Test
    void unrefMovesPointeeIdentityExactlyOnce() {
        Object original = new Object();
        OresPointer pointer = new OresPointer(original);

        assertSame(original, pointer.target());
        assertSame(original, pointer.unref()); // no pointee clone or copy
        assertThrows(IllegalStateException.class, pointer::target);
        assertThrows(IllegalStateException.class, pointer::unref);
        assertThrows(NullPointerException.class, () -> new OresPointer(null));
    }

    @Test
    void concurrentUnrefCannotReturnTargetTwice() throws Exception {
        Object original = new Object();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 200; attempt++) {
                OresPointer pointer = new OresPointer(original);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger moved = new AtomicInteger();
                AtomicInteger stale = new AtomicInteger();
                Runnable contender = () -> {
                    try {
                        start.await();
                        if (pointer.unref() == original) moved.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        stale.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                };
                Future<?> first = executor.submit(contender);
                Future<?> second = executor.submit(contender);
                start.countDown();
                first.get();
                second.get();
                assertEquals(1, moved.get(), "unique ownership must transfer only once");
                assertEquals(1, stale.get(), "second unwrap must be rejected");
                assertThrows(IllegalStateException.class, pointer::target);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
