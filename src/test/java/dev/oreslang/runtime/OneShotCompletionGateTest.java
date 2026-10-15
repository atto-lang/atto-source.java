package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OneShotCompletionGateTest {
    @Test
    void cancelledCompletionDropsLateSuccessAndFailure() {
        OneShotCompletionGate gate = new OneShotCompletionGate();
        AtomicReference<String> last = new AtomicReference<>();
        assertTrue(gate.deliver(() -> last.set("cancelled")));
        assertFalse(gate.deliver(() -> last.set("late-success")));
        assertFalse(gate.deliver(() -> last.set("late-error")));
        assertEquals("cancelled", last.get());
        assertTrue(gate.delivered());
    }

    @Test
    void consumerThrowIsNotConvertedIntoAnotherDelivery() {
        OneShotCompletionGate gate = new OneShotCompletionGate();
        AtomicInteger callbacks = new AtomicInteger();
        IllegalStateException original = new IllegalStateException("consumer-failure");
        IllegalStateException observed = assertThrows(IllegalStateException.class,
                () -> gate.deliver(() -> {
                    callbacks.incrementAndGet();
                    throw original;
                }));
        assertSame(original, observed);
        assertFalse(gate.deliver(callbacks::incrementAndGet));
        assertEquals(1, callbacks.get());
    }

    @Test
    void concurrentCompletionsRunConsumerAtMostOnce() throws Exception {
        OneShotCompletionGate gate = new OneShotCompletionGate();
        AtomicInteger callbacks = new AtomicInteger();
        int attempts = 16;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(attempts)) {
            List<java.util.concurrent.Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                claims.add(pool.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start latch timed out");
                    }
                    return gate.deliver(callbacks::incrementAndGet);
                }));
            }
            start.countDown();
            int winners = 0;
            for (var claim : claims) {
                if (claim.get(10, TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners);
            assertEquals(1, callbacks.get());
        }
    }
}
