package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.concurrent.RejectedExecutionException;
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
}
