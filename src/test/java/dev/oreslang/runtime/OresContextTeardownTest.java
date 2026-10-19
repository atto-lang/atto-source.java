package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Pure Java regression tests for context teardown failure barriers. */
final class OresContextTeardownTest {
    private static final String[] NAMES = {
        "tasks", "async", "native", "http", "actors",
        "vm", "images", "gc", "flush"
    };

    private static final class Fixture {
        final List<String> calls = new ArrayList<>();
        final Runnable[] steps = new Runnable[NAMES.length];
        String fail = "";
        String secondFailure = "";

        Fixture() {
            for (int i = 0; i < NAMES.length; ++i) {
                String name = NAMES[i];
                steps[i] = () -> {
                    calls.add(name);
                    if (name.equals(fail) || name.equals(secondFailure))
                        throw new IllegalArgumentException(name + " failed");
                };
            }
        }

        void close() {
            OresContextTeardown.close(steps[0], steps[1], steps[2],
                    steps[3], steps[4], steps[5], steps[6], steps[7], steps[8]);
        }

        void expect(String... names) {
            assertEquals(List.of(names), calls);
        }
    }

    @Test
    void closesEverythingInDependencyOrder() {
        Fixture f = new Fixture();
        assertDoesNotThrow(f::close);
        f.expect(NAMES);
    }

    @Test
    void taskFenceFailureStopsAllFurtherTeardown() {
        Fixture f = new Fixture();
        f.fail = "tasks";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks");
    }

    @Test
    void asyncCarrierFailurePreservesProvidersAndVm() {
        Fixture f = new Fixture();
        f.fail = "async";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async");
    }

    @Test
    void nativeAndHttpFailuresAggregateWhileActorsStillStop() {
        Fixture f = new Fixture();
        f.fail = "native";
        f.secondFailure = "http";
        IllegalStateException error = assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors");
        assertNotNull(error.getCause());
        assertEquals(1, error.getCause().getSuppressed().length);
    }

    @Test
    void actorShutdownFailurePreventsVmShutdown() {
        Fixture f = new Fixture();
        f.fail = "actors";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors");
    }

    @Test
    void vmShutdownFailureNeverReclaimsSharedCode() {
        Fixture f = new Fixture();
        f.fail = "vm";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors", "vm");
    }

    @Test
    void sharedCodeFailurePreservesGarbageCollectorAndOutput() {
        Fixture f = new Fixture();
        f.fail = "images";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors", "vm", "images");
    }

    @Test
    void garbageCollectorShutdownFailureDoesNotFlush() {
        Fixture f = new Fixture();
        f.fail = "gc";
        assertThrows(IllegalStateException.class, f::close);
        f.expect("tasks", "async", "native", "http", "actors",
                "vm", "images", "gc");
    }

    @Test
    void duplicateHostCloseRunsOnePhysicalTeardown() {
        OresContextTeardown.Once once = new OresContextTeardown.Once();
        AtomicInteger attempts = new AtomicInteger();
        once.close(attempts::incrementAndGet);
        once.close(attempts::incrementAndGet);
        assertEquals(1, attempts.get());
    }

    @Test
    void concurrentHostCloseJoinsPhysicalShutdown() throws Exception {
        OresContextTeardown.Once once = new OresContextTeardown.Once();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch permitExit = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Throwable> background = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                once.close(() -> {
                    executions.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!permitExit.await(3, TimeUnit.SECONDS))
                            throw new IllegalStateException("release never arrived");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                });
            } catch (Throwable failure) {
                background.set(failure);
            }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread second = Thread.ofVirtual().start(() -> {
            try {
                once.close(executions::incrementAndGet);
                joined.countDown();
            } catch (Throwable failure) {
                background.set(failure);
            }
        });
        try {
            assertFalse(joined.await(50, TimeUnit.MILLISECONDS),
                    "concurrent close may not report success while owner still runs");
        } finally {
            permitExit.countDown();
        }
        first.join();
        second.join();
        assertNull(background.get());
        assertEquals(1, executions.get());
        assertEquals(0L, joined.getCount());
    }

    @Test
    void shutdownFailureIsReplayedWithoutDoubleReleasingResources() {
        OresContextTeardown.Once once = new OresContextTeardown.Once();
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("generation lease leaked");
        for (int repeat = 0; repeat < 3; repeat++) {
            IllegalStateException observed = assertThrows(
                    IllegalStateException.class,
                    () -> once.close(() -> {
                        attempts.incrementAndGet();
                        throw failure;
                    }));
            assertSame(failure, observed,
                    "later close callers must observe the same failed retirement");
        }
        assertEquals(1, attempts.get());
    }

    @Test
    void recursiveHostCloseIsRejectedWithoutDeadlockingOwner() {
        OresContextTeardown.Once once = new OresContextTeardown.Once();
        AtomicInteger attempts = new AtomicInteger();
        assertDoesNotThrow(() -> once.close(() -> {
            attempts.incrementAndGet();
            assertThrows(IllegalStateException.class,
                    () -> once.close(attempts::incrementAndGet));
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    void interruptedJoinerStillWaitsForPhysicalRetirement() throws Exception {
        OresContextTeardown.Once once = new OresContextTeardown.Once();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> background = new AtomicReference<>();
        AtomicInteger interruptionPreserved = new AtomicInteger();
        Thread owner = Thread.ofVirtual().start(() -> {
            try {
                once.close(() -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                });
            } catch (Throwable failure) {
                background.set(failure);
            }
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread waiter = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                once.close(() -> fail("must not run a second shutdown"));
                if (Thread.currentThread().isInterrupted()) interruptionPreserved.incrementAndGet();
            } catch (Throwable failure) {
                background.set(failure);
            }
        });
        release.countDown();
        owner.join();
        waiter.join();
        assertNull(background.get());
        assertEquals(1, interruptionPreserved.get());
    }

}
