package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Exact-once code/region retirement across physical exit and failed admission. */
@Timeout(20)
final class OresTaskPhysicalExitOnceTest {

    @Test
    void manyConcurrentCancellationAndCompletionPathsRetireEachPinOnce() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(4);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            final int count = 96;
            AtomicInteger releases = new AtomicInteger();
            List<OresTask<Integer>> children = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                OresTask<Integer> child = scope.startCompilerFrame(
                        scheduler, resume -> new OresScheduler.Done<>(index),
                        releases::incrementAndGet);
                children.add(child);
                if ((i & 1) == 0) child.cancel();
            }
            for (OresTask<Integer> child : children) {
                assertNull(AsyncRuntime.await(child.getDone()));
                assertTrue(child.isQuiescent());
            }
            assertEquals(count, releases.get(),
                    "each scheduler physical exit must retire exactly one pin");
            scope.cancel();
            assertEquals(count, releases.get(),
                    "cancellation after quiescence must not release a pin twice");
        }
    }

    @Test
    void rejectedAdmissionAndSubsequentCloseCannotRetireTwice() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            assertTrue(scope.cancel());
            AtomicInteger releases = new AtomicInteger();
            assertThrows(RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(1),
                            releases::incrementAndGet));
            assertEquals(1, releases.get());
            scope.close();
            assertEquals(1, releases.get());
        }
    }

    @Test
    void failedPhysicalRetirementIsObservedAndNeverRetried() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(runtime);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicInteger invocations = new AtomicInteger();
            try {
                OresTask<Integer> task = scope.startCompilerFrame(scheduler,
                        resume -> {
                            entered.countDown();
                            assertTrue(finish.await(5, TimeUnit.SECONDS));
                            return new OresScheduler.Done<>(1);
                        }, () -> {
                            invocations.incrementAndGet();
                            throw new IllegalStateException("retirement failed");
                        });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertTrue(task.cancel());
                    assertEquals(0, invocations.get(),
                            "cancellation is not physical retirement");
                } finally {
                    finish.countDown();
                }
                assertThrows(RuntimeException.class,
                        () -> AsyncRuntime.await(task.getDone()));
                assertEquals(1, invocations.get());
                assertThrows(RuntimeException.class, scope::close);
                assertEquals(1, invocations.get(),
                        "failed code-image release must not be retried on close");
            } finally {
                finish.countDown();
            }
        }
    }
}
