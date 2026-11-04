package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only retirement/turn linearization adversaries. Source-level .atto
 * execution and physical WebSocket output fencing require separate evidence.
 */
@Timeout(20)
final class CounterLiveTurnModelConcurrencyTest {
    @Test
    void supervisorRetirementWinsWhileReducerCallbackIsBlocked() throws Exception {
        for (int attempt = 0; attempt < 32; attempt++) {
            CounterLiveTurnModel model = new CounterLiveTurnModel();
            CountDownLatch inCallback = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            CountDownLatch retireFinished = new CountDownLatch(1);
            AtomicReference<CounterLiveTurnModel.Outcome> published = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread turn = new Thread(() -> {
                try {
                    published.set(model.increment("alice", "12345678", 0, () -> {
                        inCallback.countDown();
                        try {
                            if (!releaseCallback.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("callback release deadline");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("interrupted callback", e);
                        }
                    }));
                } catch (Throwable t) { failure.set(t); }
            }, "counter-live-turn");
            Thread retire = new Thread(() -> {
                try { model.retire(); }
                finally { retireFinished.countDown(); }
            }, "counter-live-supervisor-stop");
            turn.start();
            boolean preempted;
            try {
                assertTrue(inCallback.await(5, TimeUnit.SECONDS), "turn callback entry");
                retire.start();
                preempted = retireFinished.await(300, TimeUnit.MILLISECONDS);
            } finally {
                releaseCallback.countDown();
                turn.join(5000);
                if (retire.isAlive()) retire.join(5000);
            }
            assertFalse(turn.isAlive() || retire.isAlive(), "no callback/retire thread leak");
            assertTrue(preempted, "retirement must not block behind guest callback");
            assertNull(published.get(), "retired turn may not publish a patch or ACK");
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(0, model.count());
            assertEquals(0, model.revision());
            assertThrows(IllegalStateException.class,
                    () -> model.increment("alice", "abcdef12", 0));
        }
    }

    @Test
    void completedCommitLinearizesBeforeLaterRetirement() {
        CounterLiveTurnModel model = new CounterLiveTurnModel();
        CounterLiveTurnModel.Outcome committed = model.increment("alice", "12345678", 0);
        assertTrue(committed.applied());
        assertEquals(1, committed.revision());
        model.retire();
        assertEquals(1, model.count());
        assertEquals(1, model.revision());
        assertThrows(IllegalStateException.class,
                () -> model.increment("alice", "12345678", 0));
        assertThrows(IllegalStateException.class,
                () -> model.increment("alice", "abcdef12", 0));
    }
}
