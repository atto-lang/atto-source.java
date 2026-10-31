package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only retirement mechanics; deterministic artificial stalled-close
 * scenarios. These are authored JUnit tests, not guest/WebSocket wire proof.
 */
@Timeout(15)
final class WebSocketHostRetirementTest {
    @Test
    void oneBlockedCloseDoesNotStarveOtherHostRetirementLanes() throws Exception {
        try (var retirement = new WebSocketHostRetirement(4, 1, 10)) {
            var blockedEntered = new CountDownLatch(1);
            var unblock = new CountDownLatch(1);
            var otherClosed = new CountDownLatch(12);
            try {
                retirement.request(() -> {
                    blockedEntered.countDown();
                    if (!unblock.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("simulated kernel close remained blocked");
                    }
                });
                assertTrue(blockedEntered.await(3, TimeUnit.SECONDS));
                for (int i = 0; i < 12; i++) {
                    retirement.request(otherClosed::countDown);
                }
                assertTrue(otherClosed.await(3, TimeUnit.SECONDS),
                        "three remaining fixed lanes must continue under stalled close");
                assertEquals(1L, unblock.getCount(),
                        "other connections retired while one OS-close was still stuck");
            } finally {
                unblock.countDown();
            }
        }
    }

    @Test
    void duplicatePendingNotificationsCollapseIntoOneClose() throws Exception {
        try (var retirement = new WebSocketHostRetirement(1, 8, 250)) {
            var count = new AtomicInteger();
            var closed = new CountDownLatch(1);
            AutoCloseable physical = () -> {
                count.incrementAndGet();
                closed.countDown();
            };
            for (int i = 0; i < 1000; i++) {
                retirement.request(physical);
            }
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            assertEquals(1, count.get(),
                    "duplicate notifications before first sweep must not duplicate close");
        }
    }

    @Test
    void singleLanePreservesPendingFifoOrder() throws Exception {
        try (var retirement = new WebSocketHostRetirement(1, 4, 20)) {
            var closed = new CountDownLatch(80);
            List<Integer> actual = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < 80; i++) {
                final int id = i;
                retirement.request(() -> {
                    actual.add(id);
                    closed.countDown();
                });
            }
            assertTrue(closed.await(4, TimeUnit.SECONDS));
            assertEquals(java.util.stream.IntStream.range(0, 80).boxed().toList(), actual);
        }
    }

    @Test
    void cancellationBeforeFirstSweepAvoidsUnneededClose() throws Exception {
        try (var retirement = new WebSocketHostRetirement(1, 8, 200)) {
            var closed = new AtomicInteger();
            AutoCloseable physical = closed::incrementAndGet;
            retirement.request(physical);
            retirement.forget(physical);
            Thread.sleep(350);
            assertEquals(0, closed.get(),
                    "directly retired physical socket should not be closed by fallback again");
        }
    }

    @Test
    void fixedPoolNeverStartsMoreThanFourCloseWorkers() throws Exception {
        try (var retirement = new WebSocketHostRetirement(4, 1, 10)) {
            var entered = new CountDownLatch(4);
            var unblock = new CountDownLatch(1);
            var finished = new CountDownLatch(24);
            var active = new AtomicInteger();
            var maxActive = new AtomicInteger();
            try {
                for (int i = 0; i < 24; i++) {
                    retirement.request(() -> {
                        int concurrent = active.incrementAndGet();
                        maxActive.accumulateAndGet(concurrent, Math::max);
                        entered.countDown();
                        try {
                            if (!unblock.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("synthetic close stalled");
                            }
                        } finally {
                            active.decrementAndGet();
                            finished.countDown();
                        }
                    });
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(maxActive.get() <= 4,
                        "there must be no per-connection retirement thread");
            } finally {
                unblock.countDown();
            }
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertTrue(maxActive.get() <= 4);
        }
    }
}
