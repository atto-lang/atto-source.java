package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner-set removal is secondary cleanup, never authority to skip retiring a
 * generation pin for a scheduler Task which was rejected before admission.
 */
@Timeout(20)
final class OresTaskScopeFailedSlotCleanupTest {
    @Test
    void failingOwnerSetRemovalCannotSuppressPhysicalCodeLeaseRelease()
            throws Exception {
        try (var scheduler = new OresScheduler(1);
             var scope = OresTaskScope.compilerOwned()) {
            var field = OresTaskScope.class.getDeclaredField("tasks");
            field.setAccessible(true);
            Object original = field.get(scope);
            var removalAttempts = new AtomicInteger();
            field.set(scope, new HashSet<Object>() {
                @Override public boolean remove(Object value) {
                    removalAttempts.incrementAndGet();
                    throw new OutOfMemoryError("injected owner-set removal fault");
                }
            });
            var releases = new AtomicInteger();
            scheduler.close();
            try {
                var failure = assertThrows(RejectedExecutionException.class,
                        () -> scope.startCompilerFrame(
                                scheduler,
                                resume -> OresScheduler.done(7),
                                releases::incrementAndGet));
                assertEquals(1, removalAttempts.get());
                assertEquals(1, releases.get(),
                        "failed owner bookkeeping cannot skip code-image pin release");
                assertEquals(1, failure.getSuppressed().length);
                assertInstanceOf(OutOfMemoryError.class, failure.getSuppressed()[0]);
            } finally {
                // The injected Set is deliberately faulty; scope ownership
                // remains fail-closed until the fixture removes corruption.
                field.set(scope, original);
            }
            assertTrue(scope.physicallyQuiescent());
        }
    }

    @Test
    void failedPostExitRetirementRemainsOwnedAndCloseCanRetry()
            throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var scope = OresTaskScope.compilerOwned();
            var releaseAttempts = new AtomicInteger();

            var task = scope.startCompilerFrame(
                    scheduler,
                    resume -> OresScheduler.done(17),
                    () -> {
                        int attempt = releaseAttempts.incrementAndGet();
                        if (attempt <= 2) {
                            throw new IllegalStateException(
                                    "injected retryable generation release " + attempt);
                        }
                    });

            assertEquals(17, task.getAwaited().get(5, TimeUnit.SECONDS));

            // Scheduler physical exit performs the first retirement attempt.
            // The first close owns the retained slot and performs the second.
            var first = assertThrows(IllegalStateException.class, scope::close);
            assertTrue(first.getMessage().contains(
                    "injected retryable generation release 2"));
            assertEquals(2, releaseAttempts.get());
            assertFalse(scope.physicallyQuiescent(),
                    "failed trusted retirement must retain exact scope ownership");
            assertFalse(task.getDone().isDone(),
                    "physical carrier exit alone cannot forge resource retirement");

            // A later host cleanup attempt must reacquire close ownership and
            // retry the same callback. No Task result/cancellation is replayed.
            scope.close();

            assertEquals(3, releaseAttempts.get());
            assertTrue(scope.physicallyQuiescent());
            assertTrue(task.getDone().get(5, TimeUnit.SECONDS) == null);
            // Successful close is idempotent and cannot release again.
            scope.close();
            assertEquals(3, releaseAttempts.get());
        }
    }

}
