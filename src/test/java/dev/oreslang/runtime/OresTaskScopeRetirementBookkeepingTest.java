package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A physical Task exit must report owner-slot corruption, rather than
 * swallowing it in a generic Future listener and claiming safe teardown.
 */
@Timeout(20)
final class OresTaskScopeRetirementBookkeepingTest {
    private static Field ownerSlots() throws ReflectiveOperationException {
        Field slots = OresTaskScope.class.getDeclaredField("tasks");
        slots.setAccessible(true);
        return slots;
    }

    @Test
    void successfulCarrierExitReportsFailedOwnerRetirement() throws Exception {
        OresTaskScope scope = OresTaskScope.compilerOwned();
        Field slots = ownerSlots();
        Object original = slots.get(scope);
        AtomicInteger removals = new AtomicInteger();
        Set<Object> broken = new HashSet<>() {
            @Override public boolean remove(Object entry) {
                removals.incrementAndGet();
                throw new IllegalStateException("injected owner-retirement fault");
            }
        };
        slots.set(scope, broken);
        AtomicInteger codePinReleases = new AtomicInteger();
        try (var scheduler = new OresScheduler(1)) {
            OresTask<Integer> task = scope.startCompilerFrame(scheduler,
                    resume -> OresScheduler.done(13),
                    codePinReleases::incrementAndGet);
            assertEquals(13, AsyncRuntime.await(task.getAwaited()));
            var retirement = assertThrows(IllegalStateException.class,
                    () -> AsyncRuntime.await(task.getDone()));
            assertTrue(retirement.toString().contains("injected owner-retirement fault"));
            assertEquals(1, removals.get());
            assertEquals(1, codePinReleases.get(), "code pin must retire once");
            assertFalse(scope.physicallyQuiescent(),
                    "failed owner retirement must preserve the unsafe slot");
            var close = assertThrows(IllegalStateException.class, scope::close);
            assertTrue(close.getMessage().contains("retirement failed"));
            assertEquals(1, codePinReleases.get(),
                    "closing may not attempt external code release twice");
        } finally {
            slots.set(scope, original);
            // close is idempotent but intentionally remembers its first
            // retirement failure; the test has already asserted that failure.
            try { scope.close(); } catch (IllegalStateException expected) { }
        }
    }

    @Test
    void preDispatchRejectionSurfacesFailedRemovalWithoutDoubleRelease()
            throws Exception {
        OresTaskScope scope = OresTaskScope.compilerOwned();
        Field slots = ownerSlots();
        Object original = slots.get(scope);
        AtomicInteger removed = new AtomicInteger();
        Set<Object> broken = new HashSet<>() {
            @Override public boolean remove(Object entry) {
                removed.incrementAndGet();
                throw new IllegalStateException("injected rejected-owner removal fault");
            }
        };
        slots.set(scope, broken);
        AtomicInteger releases = new AtomicInteger();
        try (var scheduler = new OresScheduler(1)) {
            scheduler.close();
            var rejection = assertThrows(RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> OresScheduler.done(1),
                            releases::incrementAndGet));
            assertEquals(1, removed.get(), "one explicit removal attempt only");
            assertEquals(1, releases.get(), "release pin exactly once");
            assertEquals(1, rejection.getSuppressed().length);
            assertTrue(rejection.getSuppressed()[0].toString()
                    .contains("injected rejected-owner removal fault"));
            assertFalse(scope.physicallyQuiescent());
        } finally {
            slots.set(scope, original);
            scope.close();
        }
    }

    @Test
    void failingPreparationBookkeepingStillReleasesExternalPin() throws Exception {
        OresTaskScope scope = OresTaskScope.compilerOwned();
        Field slots = ownerSlots();
        Object original = slots.get(scope);
        AtomicInteger releases = new AtomicInteger();
        Set<Object> broken = new HashSet<>() {
            @Override public boolean add(Object entry) {
                throw new IllegalStateException("injected owner insertion fault");
            }
            @Override public boolean remove(Object entry) {
                throw new IllegalStateException("injected rollback removal fault");
            }
        };
        slots.set(scope, broken);
        try (var scheduler = new OresScheduler(1)) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> OresScheduler.done(1),
                            releases::incrementAndGet));
            assertTrue(failure.getMessage().contains("insertion fault"));
            assertEquals(1, releases.get(), "cleanup must outlive rollback errors");
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0].toString()
                    .contains("rollback removal fault"));
        } finally {
            slots.set(scope, original);
            scope.close();
        }
    }
}
