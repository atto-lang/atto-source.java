package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-runtime race test (not a source-free model). Each round joins
 * admission, parent cancellation and a physical child-retirement fence.
 * A race may reject admission or return a Task, but may never orphan one.
 */
@Timeout(90)
final class OresTaskScopeAdmissionStressTest {
    @Test
    void repeatedOrdinaryAdmissionVsParentCancellationNeverLeaks() throws Exception {
        final int rounds = 600;
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger entered = new AtomicInteger();

        try (AsyncRuntime runtime = new AsyncRuntime()) {
            for (int i = 0; i < rounds; i++) {
                OresTaskScope scope = new OresTaskScope(runtime);
                CountDownLatch release = new CountDownLatch(1);
                AtomicReference<OresTask<Integer>> child = new AtomicReference<>();
                AtomicReference<Throwable> admissionFailure = new AtomicReference<>();
                AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();

                Thread submission = Thread.ofVirtual().start(() -> {
                    try {
                        release.await();
                        child.set(scope.submit(() -> {
                            entered.incrementAndGet();
                            return 7;
                        }));
                        admitted.incrementAndGet();
                    } catch (RejectedExecutionException expected) {
                        rejected.incrementAndGet();
                    } catch (Throwable unexpected) {
                        admissionFailure.set(unexpected);
                    }
                });
                Thread cancellation = Thread.ofVirtual().start(() -> {
                    try {
                        release.await();
                        scope.cancel();
                    } catch (Throwable unexpected) {
                        cancellationFailure.set(unexpected);
                    }
                });
                release.countDown();
                try {
                    submission.join(TimeUnit.SECONDS.toMillis(5));
                    cancellation.join(TimeUnit.SECONDS.toMillis(5));
                    assertFalse(submission.isAlive(), "admission hung at round " + i);
                    assertFalse(cancellation.isAlive(), "cancellation hung at round " + i);
                    assertNull(admissionFailure.get(), String.valueOf(admissionFailure.get()));
                    assertNull(cancellationFailure.get(), String.valueOf(cancellationFailure.get()));
                    scope.close();
                    OresTask<Integer> accepted = child.get();
                    if (accepted != null) {
                        assertTrue(accepted.getDone().isDone(),
                                "close must retire the accepted Task at round " + i);
                        assertNull(AsyncRuntime.await(accepted.getDone()));
                    }
                    assertTrue(scope.physicallyQuiescent(),
                            "no owned Task may remain after close at round " + i);
                } finally {
                    // Ensure a failed test does not leave a test carrier running.
                    release.countDown();
                    submission.join(TimeUnit.SECONDS.toMillis(5));
                    cancellation.join(TimeUnit.SECONDS.toMillis(5));
                    scope.close();
                }
            }
        }
        assertEquals(rounds, admitted.get() + rejected.get(),
                "every race must either reject admission or return one owned Task");
        assertTrue(entered.get() <= admitted.get(),
                "no guest turn can execute without an admitted owner");
    }
}
