package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pre-executable Task ownership must survive allocation failures, rejection
 * and synchronous quiescence callbacks. The Task slot must be reserved before
 * AsyncRuntime/OresScheduler can admit the first executable turn.
 */
@Timeout(30)
final class OresTaskScopePreAdmissionOwnershipTest {
    private static Field tasksField() throws ReflectiveOperationException {
        Field field = OresTaskScope.class.getDeclaredField("tasks");
        field.setAccessible(true);
        return field;
    }

    private static HashSet<Object> failingOwnerSet() {
        return new HashSet<>() {
            @Override public boolean add(Object value) {
                throw new OutOfMemoryError(
                        "injected ownership-slot expansion before task admission");
            }
        };
    }

    @Test
    void asyncOwnerSlotOomeCannotAdmitUntrackedExecutableTask()
            throws Exception {
        try (var runtime = new AsyncRuntime();
             var scope = new OresTaskScope(runtime)) {
            Field field = tasksField();
            Object original = field.get(scope);
            var executed = new AtomicInteger();
            field.set(scope, failingOwnerSet());
            try {
                assertThrows(OutOfMemoryError.class,
                        () -> scope.submit(() -> executed.incrementAndGet()));
                assertEquals(0, executed.get(),
                        "no AsyncRuntime carrier may be launched before ownership reservation");
            } finally {
                field.set(scope, original);
            }
            assertTrue(scope.physicallyQuiescent());
            var admitted = scope.submit(() -> executed.incrementAndGet());
            assertEquals(1, admitted.getAwaited().get());
            assertEquals(1, executed.get());
        }
    }

    @Test
    void compilerOwnerSlotOomeReleasesPreparedCodeLeaseExactlyOnce()
            throws Exception {
        try (var scheduler = new OresScheduler(1);
             var scope = OresTaskScope.compilerOwned()) {
            Field field = tasksField();
            Object original = field.get(scope);
            var executed = new AtomicInteger();
            var released = new AtomicInteger();
            field.set(scope, failingOwnerSet());
            try {
                assertThrows(OutOfMemoryError.class,
                        () -> scope.startCompilerFrame(
                                scheduler,
                                resume -> {
                                    executed.incrementAndGet();
                                    return OresScheduler.done(1);
                                }, released::incrementAndGet));
                assertEquals(0, executed.get(),
                        "scheduler admission must not happen after failed owner reservation");
                assertEquals(1, released.get(),
                        "already acquired external generation pin must retire exactly once");
            } finally {
                field.set(scope, original);
            }
            assertTrue(scope.physicallyQuiescent());
            var accepted = scope.startCompilerFrame(
                    scheduler,
                    resume -> OresScheduler.done(2));
            assertEquals(2, accepted.getAwaited().get());
            accepted.getDone().get();
        }
    }

    @Test
    void compilerRetirementPublicationFailureDoesNotReplayExternalRelease()
            throws Exception {
        try (var scheduler = new OresScheduler(1);
             var scope = OresTaskScope.compilerOwned()) {
            var entered = new CountDownLatch(1);
            var releaseFrame = new CountDownLatch(1);
            var externalReleases = new AtomicInteger();

            var task = scope.startCompilerFrame(
                    scheduler,
                    resume -> {
                        entered.countDown();
                        try {
                            if (!releaseFrame.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "compiler frame release timeout");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return OresScheduler.done(7);
                    },
                    externalReleases::incrementAndGet);

            assertTrue(entered.await(5, TimeUnit.SECONDS));

            Field field = tasksField();
            @SuppressWarnings("unchecked")
            var owned = (java.util.Set<Object>) field.get(scope);
            assertEquals(1, owned.size());
            Object slot = owned.iterator().next();

            Field retirementField = slot.getClass().getDeclaredField(
                    "physicalRetirement");
            retirementField.setAccessible(true);
            Object retirement = retirementField.get(slot);
            assertNotNull(retirement);

            Field faultField = retirement.getClass().getDeclaredField(
                    "afterReleaseBeforePublishFaultForTesting");
            Field releaseCompleteField = retirement.getClass().getDeclaredField(
                    "releaseComplete");
            Field publishedField = retirement.getClass().getDeclaredField(
                    "retirementPublished");
            faultField.setAccessible(true);
            releaseCompleteField.setAccessible(true);
            publishedField.setAccessible(true);

            var publicationAttempts = new AtomicInteger();
            faultField.set(retirement, (Runnable) () -> {
                if (publicationAttempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected after external release before retirement publication");
                }
            });

            releaseFrame.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (publicationAttempts.get() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, publicationAttempts.get(),
                    "scheduler exit must cross the injected publication boundary once");
            assertEquals(1, externalReleases.get(),
                    "external generation/resource release must already have succeeded");
            assertTrue(releaseCompleteField.getBoolean(retirement));
            assertFalse(publishedField.getBoolean(retirement),
                    "failed Future publication cannot forge a retirement witness");
            assertFalse(task.isQuiescent(),
                    "Task quiescence must remain false until the retirement fence publishes");

            scope.close();

            assertEquals(2, publicationAttempts.get(),
                    "host close must retry only the publication stage");
            assertEquals(1, externalReleases.get(),
                    "successful external release must never replay");
            assertTrue(publishedField.getBoolean(retirement));
            assertTrue(task.isQuiescent());
            assertTrue(scope.physicallyQuiescent());
            assertTrue(owned.isEmpty(),
                    "published retirement must release the exact Task ownership slot");
        }
    }

    @Test
    void closedAsyncRuntimeRejectsSubmissionWithoutOrphaningOwnership()
            throws Exception {
        var runtime = new AsyncRuntime();
        try (var scope = new OresTaskScope(runtime)) {
            runtime.close();
            assertThrows(RejectedExecutionException.class,
                    () -> scope.submit(() -> 3));
            assertTrue(scope.physicallyQuiescent(),
                    "rejected runtime admission must release its reserved owner slot");
        } finally {
            runtime.close();
        }
    }
}
