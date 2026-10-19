package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresTaskScopeTest {

    @Test
    void cancellationDoesNotReleaseScopeUntilRunningCarrierUnwinds() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean physicallyExited = new AtomicBoolean();

            OresFuture<Integer> child = scope.fork(resume -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } finally {
                    physicallyExited.set(true);
                }
                return OresScheduler.done(42);
            });

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> drained = scope.cancelAndQuiesce();

            assertTrue(child.isCancelled(),
                    "logical cancellation should become visible immediately");
            assertFalse(drained.isDone(),
                    "scope must not reclaim while the cancelled carrier still executes");
            assertFalse(physicallyExited.get());

            release.countDown();
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertTrue(physicallyExited.get());
            assertEquals(0, scope.activeChildren());
        }
    }

    @Test
    void normalQuiescenceIsAwaitableOnOneCarrierWithoutDeadlock() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            AtomicInteger parentPc = new AtomicInteger();
            AtomicBoolean childRan = new AtomicBoolean();

            OresFuture<Integer> parent = scheduler.start(resume -> {
                if (parentPc.getAndIncrement() == 0) {
                    scope.fork(childResume -> {
                        childRan.set(true);
                        return OresScheduler.done(41);
                    });
                    return OresScheduler.await(scope.quiesce());
                }

                assertNull(resume.failure());
                assertNull(resume.value());
                assertTrue(childRan.get());
                return OresScheduler.done(42);
            });

            assertEquals(42, parent.get(5, TimeUnit.SECONDS));
            assertEquals(2, parentPc.get());
            assertEquals(0, scope.activeChildren());
        }
    }

    @Test
    void normalQuiesceDoesNotCancelExistingChild() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch release = new CountDownLatch(1);

            OresFuture<Integer> child = scope.fork(resume -> {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(7);
            });

            OresFuture<Void> drained = scope.quiesce();
            assertFalse(child.isCancelled());
            assertFalse(drained.isDone());

            release.countDown();
            assertEquals(7, child.get(5, TimeUnit.SECONDS));
            assertNull(drained.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationCanEscalateAPreviouslyNormalQuiesce() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            OresFuture<Integer> child = scope.fork(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            OresFuture<Void> first = scope.quiesce();
            OresFuture<Void> second = scope.cancelAndQuiesce();
            assertSame(first, second);
            assertTrue(child.isCancelled());
            assertFalse(first.isDone());

            release.countDown();
            assertNull(first.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sealedScopeRejectsNewChildrenAndBarrierIsIdempotent() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);

            OresFuture<Void> first = scope.quiesce();
            OresFuture<Void> second = scope.quiesce();

            assertSame(first, second);
            assertNull(first.get(5, TimeUnit.SECONDS));
            assertFalse(scope.isOpen());
            assertThrows(IllegalStateException.class,
                    () -> scope.fork(resume -> OresScheduler.done(1)));
        }
    }

    @Test
    void parentCancellationAutomaticallyCancelsAndDrainsOwnedScopes() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            CountDownLatch childEntered = new CountDownLatch(1);
            CountDownLatch releaseChild = new CountDownLatch(1);
            CountDownLatch parentQuiescent = new CountDownLatch(1);
            OresFuture<Integer> never = new OresFuture<>();
            AtomicReference<OresFuture<Integer>> childRef = new AtomicReference<>();
            AtomicInteger parentPc = new AtomicInteger();

            OresFuture<Integer> parent = scheduler.start(resume -> {
                if (parentPc.getAndIncrement() != 0) {
                    throw new AssertionError("cancelled parent must never resume");
                }

                OresTaskScope scope = OresTaskScope.openCurrent();
                childRef.set(scope.fork(childResume -> {
                    childEntered.countDown();
                    assertTrue(releaseChild.await(5, TimeUnit.SECONDS));
                    return OresScheduler.done(7);
                }));
                return OresScheduler.await(never);
            }, parentQuiescent::countDown);

            assertTrue(childEntered.await(5, TimeUnit.SECONDS));
            assertTrue(parent.cancel(false));
            assertTrue(parent.isCancelled());

            OresFuture<Integer> child = childRef.get();
            assertNotNull(child);
            assertTrue(child.isCancelled(),
                    "parent cancellation must propagate into its structured children");
            assertFalse(parentQuiescent.await(100, TimeUnit.MILLISECONDS),
                    "parent memory must remain live while a cancelled child carrier is still running");

            releaseChild.countDown();
            assertTrue(parentQuiescent.await(5, TimeUnit.SECONDS),
                    "parent may retire only after the structured child is physically quiescent");
            assertEquals(1, parentPc.get());
        }
    }

    @Test
    void openCurrentScopeRequiresAnExecutingTask() {
        assertThrows(IllegalStateException.class, OresTaskScope::openCurrent);
    }

}
