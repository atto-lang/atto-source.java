package dev.oreslang;

import dev.oreslang.runtime.TaskCancellation;
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

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
