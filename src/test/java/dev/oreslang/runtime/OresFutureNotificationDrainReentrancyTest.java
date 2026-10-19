package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Future terminal notification-release owner is retained until the
 * synchronous waiters and the release itself finish. Runtime retries must
 * neither recursively run an in-progress owner nor lose an owner that throws.
 */
@Timeout(15)
final class OresFutureNotificationDrainReentrancyTest {
    @Test
    void notificationReleaseCannotRecursivelyRetryItself() throws Exception {
        var owner = new AtomicReference<OresFuture<Integer>>();
        var releases = new AtomicInteger();
        var future = new OresFuture<Integer>(
                () -> { },
                () -> {
                    releases.incrementAndGet();
                    // Java synchronized monitors are reentrant. An inner
                    // settlement/reaper call must not replay this release.
                    assertFalse(owner.get().retryNotificationDrainedFromRuntime());
                    assertFalse(owner.get().runtimeNotificationDrained());
                });
        owner.set(future);

        assertFalse(future.retryNotificationDrainedFromRuntime(),
                "a pending Future has no terminal notification release");
        assertTrue(future.completeFromRuntime(7));
        assertEquals(7, future.get(1, TimeUnit.SECONDS));
        assertEquals(1, releases.get());
        assertTrue(future.runtimeNotificationDrained());
        assertTrue(future.retryNotificationDrainedFromRuntime(),
                "a drained release is idempotently observable");
        assertEquals(1, releases.get());
    }

    @Test
    void throwingReleaseRemainsOwnedAndCanBeRetriedExactlyOnce()
            throws Exception {
        var releases = new AtomicInteger();
        var future = new OresFuture<Integer>(
                () -> { },
                () -> {
                    if (releases.incrementAndGet() == 1) {
                        throw new IllegalStateException("injected release failure");
                    }
                });

        var failed = assertThrows(IllegalStateException.class,
                () -> future.completeFromRuntime(19));
        assertEquals("injected release failure", failed.getMessage());
        assertTrue(future.isDone(),
                "terminal state remains authoritative after a release exception");
        assertFalse(future.runtimeNotificationDrained(),
                "a throwing callback must not forfeit its recovery owner");

        assertTrue(future.retryNotificationDrainedFromRuntime());
        assertTrue(future.runtimeNotificationDrained());
        assertEquals(19, future.get(1, TimeUnit.SECONDS));
        assertTrue(future.retryNotificationDrainedFromRuntime());
        assertEquals(2, releases.get(),
                "successful retry removes the release owner once");
    }
}
