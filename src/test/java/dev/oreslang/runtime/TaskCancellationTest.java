package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

public class TaskCancellationTest {
    @Test void propagationIsDownwardAndIdempotent() {
        TaskCancellation root = new TaskCancellation();
        TaskCancellation a = root.child();
        TaskCancellation b = a.child();
        TaskCancellation sibling = root.child();
        assertTrue(a.cancel("stop-a"));
        assertTrue(b.isCancelled());
        assertEquals("stop-a", b.cancellationReason());
        assertFalse(root.isCancelled());
        assertFalse(sibling.isCancelled());
        assertFalse(a.cancel("second"));
        assertEquals("stop-a", a.cancellationReason());
        assertTrue(root.cancel("shutdown"));
        assertEquals("shutdown", sibling.cancellationReason());
    }

    @Test void allDescendantsAreMarkedBeforeCallbacksFire() {
        TaskCancellation root = new TaskCancellation();
        TaskCancellation a = root.child();
        TaskCancellation b = root.child();
        AtomicInteger observed = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean bAlreadyCancelled = new java.util.concurrent.atomic.AtomicBoolean();
        a.onRevoke(() -> {
            bAlreadyCancelled.set(b.isCancelled());
            observed.incrementAndGet();
        });
        b.onRevoke(observed::incrementAndGet);
        root.cancel("stop");
        assertEquals(2, observed.get());
        assertTrue(bAlreadyCancelled.get());
    }

    @Test void callbacksAreOrderedAndIdempotent() {
        TaskCancellation token = new TaskCancellation();
        List<String> events = new ArrayList<>();
        token.onCancel(() -> events.add("observer"));
        token.onRevoke(() -> events.add("revoker"));
        token.onRevoke(() -> { throw new IllegalStateException("bad hook"); });
        token.onRevoke(() -> events.add("later revoker"));
        assertTrue(token.cancel("stop"));
        assertEquals(List.of("revoker", "later revoker", "observer"), events);
        token.onCancel(() -> events.add("late observer"));
        assertEquals(4, events.size());
    }

    @Test void disconnectedLinkDoesNotCancelTarget() {
        TaskCancellation source = new TaskCancellation();
        TaskCancellation target = new TaskCancellation();
        TaskCancellation.Subscription link = target.linkCancellationFrom(source);
        link.close();
        source.cancel("source-stop");
        assertFalse(target.isCancelled());
    }

    @Test void activeLinkOnlyPropagatesOneWay() {
        TaskCancellation a = new TaskCancellation();
        TaskCancellation b = new TaskCancellation();
        b.linkCancellationFrom(a);
        b.cancel("b");
        assertFalse(a.isCancelled());
        TaskCancellation c = new TaskCancellation();
        c.linkCancellationFrom(a);
        a.cancel("a");
        assertTrue(c.isCancelled());
        assertEquals("linked:a", c.cancellationReason());
    }

    @Test void transferOwnershipAndRejectCycles() {
        TaskCancellation root = new TaskCancellation();
        TaskCancellation ownerA = root.child();
        TaskCancellation ownerB = root.child();
        TaskCancellation work = ownerA.child();
        work.adoptInto(ownerB);
        assertTrue(work.isOwnedBy(ownerB));
        ownerA.cancel("old-owner");
        assertFalse(work.isCancelled());
        assertThrows(IllegalArgumentException.class, () -> ownerB.adoptInto(work));
        ownerB.cancel("new-owner");
        assertTrue(work.isCancelled());
        assertThrows(IllegalStateException.class, () -> work.adoptInto(root));
    }

    @Test void childOfCancelledParentStartsCancelled() {
        TaskCancellation parent = new TaskCancellation();
        parent.cancel("closed");
        TaskCancellation late = parent.child();
        assertTrue(late.isCancelled());
        assertEquals("closed", late.cancellationReason());
        assertThrows(IllegalStateException.class, () -> late.adoptInto(new TaskCancellation()));
    }

    @Test void cancellationAndTransferRaceIsLinearized() throws Exception {
        for (int i = 0; i < 100; i++) {
            TaskCancellation oldOwner = new TaskCancellation();
            TaskCancellation newOwner = new TaskCancellation();
            TaskCancellation child = oldOwner.child();
            CountDownLatch start = new CountDownLatch(1);
            Thread canceller = new Thread(() -> {
                await(start);
                oldOwner.cancel("old");
            });
            Thread mover = new Thread(() -> {
                await(start);
                try { child.adoptInto(newOwner); }
                catch (IllegalStateException ignored) { }
            });
            canceller.start(); mover.start(); start.countDown();
            canceller.join(3000); mover.join(3000);
            assertFalse(canceller.isAlive());
            assertFalse(mover.isAlive());
            if (child.isCancelled()) assertEquals("old", child.cancellationReason());
            else assertTrue(child.isOwnedBy(newOwner));
            newOwner.cancel("new");
            assertTrue(child.isCancelled());
        }
    }


    @Test void lateObserverWaitsForRevocationBarrier() throws Exception {
        TaskCancellation token = new TaskCancellation();
        CountDownLatch revokerEntered = new CountDownLatch(1);
        CountDownLatch releaseRevoker = new CountDownLatch(1);
        AtomicInteger notified = new AtomicInteger();
        token.onRevoke(() -> {
            revokerEntered.countDown();
            await(releaseRevoker);
        });
        Thread canceller = new Thread(() -> token.cancel("stop"));
        canceller.start();
        try {
            assertTrue(revokerEntered.await(3, TimeUnit.SECONDS));
            token.onCancel(notified::incrementAndGet);
            assertEquals(0, notified.get());
        } finally {
            releaseRevoker.countDown();
            canceller.join(3000);
        }
        assertFalse(canceller.isAlive());
        assertEquals(1, notified.get());
    }

    @Test void longLinkChainDoesNotOverflowOrGrowReasons() {
        TaskCancellation root = new TaskCancellation();
        TaskCancellation prev = root;
        for (int i = 0; i < 8000; i++) {
            TaskCancellation next = new TaskCancellation();
            next.linkCancellationFrom(prev);
            prev = next;
        }
        assertTrue(root.cancel("shutdown"));
        assertTrue(prev.isCancelled());
        assertEquals("linked:shutdown", prev.cancellationReason());
    }

    @Test void cyclicLinksAreIdempotent() {
        TaskCancellation a = new TaskCancellation();
        TaskCancellation b = new TaskCancellation();
        a.linkCancellationFrom(b);
        b.linkCancellationFrom(a);
        assertTrue(a.cancel("stop"));
        assertTrue(b.isCancelled());
        assertEquals("linked:stop", b.cancellationReason());
        assertEquals("stop", a.cancellationReason());
    }

    @Test void cancellationClearsParentPointers() {
        TaskCancellation root = new TaskCancellation();
        TaskCancellation child = root.child();
        TaskCancellation grandchild = child.child();
        assertTrue(grandchild.isOwnedBy(child));
        root.cancel("stop");
        assertFalse(child.isOwnedBy(root));
        assertFalse(grandchild.isOwnedBy(child));
    }

    @Test void cancellingAndRegisteringObserverDeliversExactlyOnce() throws Exception {
        for (int i = 0; i < 200; i++) {
            TaskCancellation token = new TaskCancellation();
            AtomicInteger deliveries = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            Thread a = new Thread(() -> { await(go); token.cancel("done"); });
            Thread b = new Thread(() -> { await(go); token.onCancel(deliveries::incrementAndGet); });
            a.start(); b.start(); go.countDown();
            a.join(3000); b.join(3000);
            assertFalse(a.isAlive());
            assertFalse(b.isAlive());
            assertEquals(1, deliveries.get());
        }
    }

    @Test void reasonBoundsAndLateRevoker() {
        TaskCancellation token = new TaskCancellation();
        assertThrows(IllegalArgumentException.class, () -> token.cancel(" "));
        assertThrows(IllegalArgumentException.class, () -> token.cancel("x".repeat(257)));
        assertFalse(token.isCancelled());
        token.cancel("ok");
        AtomicInteger called = new AtomicInteger();
        token.onRevoke(called::incrementAndGet);
        assertEquals(1, called.get());
    }

    @Test void detachedSubscriptionNeverExecutesCallback() {
        TaskCancellation token = new TaskCancellation();
        AtomicInteger callbacks = new AtomicInteger();
        TaskCancellation.Subscription observer = token.onCancel(callbacks::incrementAndGet);
        observer.close();
        observer.close();
        token.cancel("done");
        assertEquals(0, callbacks.get());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
