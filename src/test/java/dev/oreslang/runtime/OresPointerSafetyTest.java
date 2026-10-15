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
    void concurrentCrossDomainUnrefCannotStealOwnership() throws Exception {
        // The parent branch now fences handles to their creating execution
        // domain. Host worker threads are different domains and must never
        // race to win ownership over a pointer created on this thread.
        Object original = new Object();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 200; attempt++) {
                OresPointer pointer = new OresPointer(original);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger denied = new AtomicInteger();
                Runnable contender = () -> {
                    try {
                        start.await();
                        pointer.unref();
                        throw new AssertionError("a foreign execution domain unwrapped a pointer");
                    } catch (IllegalStateException expected) {
                        denied.incrementAndGet();
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
                assertEquals(2, denied.get(), "foreign domains must never unwrap");
                assertSame(original, pointer.target(), "denied attempts cannot consume the handle");
                assertSame(original, pointer.unref(), "the owner may still move the original pointee");
                assertThrows(IllegalStateException.class, pointer::unref);
                assertThrows(IllegalStateException.class, pointer::target);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
