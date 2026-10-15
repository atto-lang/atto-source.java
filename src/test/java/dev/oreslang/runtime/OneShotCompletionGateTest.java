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
    void reentrantConsumerCannotDeliverASecondTime() {
        OneShotCompletionGate gate = new OneShotCompletionGate();
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue(gate.deliver(() -> {
            callbacks.incrementAndGet();
            assertTrue(gate.delivered());
            assertFalse(gate.deliver(callbacks::incrementAndGet));
        }));
        assertEquals(1, callbacks.get());
    }

    @Test
    void firstThrowRemainsPrimaryWhenDuplicateAttemptOccursInsideConsumer() {
        OneShotCompletionGate gate = new OneShotCompletionGate();
        IllegalArgumentException original = new IllegalArgumentException("original");
        AtomicInteger callbacks = new AtomicInteger();
        IllegalArgumentException caught = assertThrows(IllegalArgumentException.class,
                () -> gate.deliver(() -> {
                    callbacks.incrementAndGet();
                    assertFalse(gate.deliver(callbacks::incrementAndGet));
                    throw original;
                }));
        assertSame(original, caught);
        assertEquals(1, callbacks.get());
        assertFalse(gate.deliver(callbacks::incrementAndGet));
    }

    @Test
    void nullCallbacksAreRejectedWithoutConsumingTheCompletion() {
        OneShotCompletionGate gate = new OneShotCompletionGate();
        assertThrows(NullPointerException.class, () -> gate.deliver(null));
        assertFalse(gate.delivered());
        assertTrue(gate.deliver(() -> { }));
        assertThrows(NullPointerException.class, () -> gate.deliver(null));
        assertTrue(gate.delivered());
    }

    @Test
    void repeatedContendedClaimsAlwaysHaveOneWinner() throws Exception {
        // Reuse workers but create a fresh gate for every round. A successful
        // first delivery must publish its result before any other callback runs.
        final int attempts = 16;
        final int rounds = 64;
        try (var pool = Executors.newFixedThreadPool(attempts)) {
            for (int round = 0; round < rounds; round++) {
                OneShotCompletionGate gate = new OneShotCompletionGate();
                AtomicInteger callbacks = new AtomicInteger();
                CountDownLatch ready = new CountDownLatch(attempts);
                CountDownLatch start = new CountDownLatch(1);
                List<java.util.concurrent.Future<Boolean>> claims = new ArrayList<>();
                for (int i = 0; i < attempts; i++) {
                    claims.add(pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("race barrier timed out");
                        }
                        return gate.deliver(callbacks::incrementAndGet);
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS),
                        "all workers must reach barrier for round " + round);
                start.countDown();
                int winners = 0;
                for (var claim : claims) {
                    if (claim.get(10, TimeUnit.SECONDS)) winners++;
                }
                assertEquals(1, winners, "round " + round);
                assertEquals(1, callbacks.get(), "round " + round);
                assertTrue(gate.delivered());
            }
        }
    }

    @Test
    void claimIsVisibleBeforeTheFirstConsumerReturnsAndLateCompletionCannotReenter()
            throws Exception {
        OneShotCompletionGate gate = new OneShotCompletionGate();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> gate.deliver(() -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("consumer release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                callbacks.incrementAndGet();
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS),
                    "first callback must enter before duplicate delivery");
            assertTrue(gate.delivered(),
                    "terminal claim must publish before consumer execution finishes");
            var duplicate = pool.submit(() -> gate.deliver(callbacks::incrementAndGet));
            assertFalse(duplicate.get(5, TimeUnit.SECONDS),
                    "duplicate callback must not wait for or overlap first completion");
            release.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void racingCancellationSuccessAndFailureAreLinearized() throws Exception {
        final int rounds = 128;
        final int actors = 3;
        try (var pool = Executors.newFixedThreadPool(actors)) {
            for (int round = 0; round < rounds; round++) {
                OneShotCompletionGate gate = new OneShotCompletionGate();
                AtomicInteger published = new AtomicInteger();
                AtomicReference<String> result = new AtomicReference<>();
                CountDownLatch ready = new CountDownLatch(actors);
                CountDownLatch start = new CountDownLatch(1);
                List<java.util.concurrent.Future<Boolean>> claims = new ArrayList<>();
                for (String terminal : List.of("cancelled", "success", "failure")) {
                    claims.add(pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("completion race start timed out");
                        }
                        return gate.deliver(() -> {
                            published.incrementAndGet();
                            result.set(terminal);
                        });
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                int winners = 0;
                for (var claim : claims) if (claim.get(5, TimeUnit.SECONDS)) winners++;
                assertEquals(1, winners, "round " + round);
                assertEquals(1, published.get(), "round " + round);
                assertTrue(List.of("cancelled", "success", "failure").contains(result.get()));
                assertTrue(gate.delivered());
            }
        }
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
