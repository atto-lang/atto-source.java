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


    @Test void fatalRevokerStillCompletesRevocationBarrierAndPreservesError() {
        TaskCancellation root = new TaskCancellation();
        TaskCancellation child = root.child();
        AtomicInteger revoked = new AtomicInteger();
        AtomicInteger observed = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();

        child.onRevoke(() -> { throw new LinkageError("host callback failed"); });
        child.onRevoke(revoked::incrementAndGet);
        child.onCancel(() -> {
            assertEquals(1, revoked.get(), "revokers must finish before observers");
            observed.incrementAndGet();
        });
        assertThrows(LinkageError.class, () -> root.cancel("shutdown"));
        assertTrue(root.isCancelled());
        assertTrue(child.isCancelled());
        assertEquals(1, revoked.get(), "remaining revokers must not be skipped");
        assertEquals(1, observed.get(), "observers must not be stranded");
        child.onCancel(late::incrementAndGet);
        assertEquals(1, late.get(), "late observer must see completed revocation");
        assertFalse(root.cancel("again"));
    }

    @Test void fatalNestedObserverCannotDiscardQueuedCancellation() {
        for (int i = 0; i < 100; i++) {
            TaskCancellation parent = new TaskCancellation();
            TaskCancellation downstream = new TaskCancellation();
            AtomicInteger revoked = new AtomicInteger();
            AtomicInteger observed = new AtomicInteger();
            downstream.onRevoke(revoked::incrementAndGet);
            downstream.onCancel(observed::incrementAndGet);
            parent.onCancel(() -> {
                downstream.cancel("linked");
                throw new LinkageError("failure after nested cancellation admission");
            });
            assertThrows(LinkageError.class, () -> parent.cancel("stop"));
            assertTrue(downstream.isCancelled());
            assertEquals(1, revoked.get(),
                    "queued descendant revoker dropped when parent hook failed");
            assertEquals(1, observed.get(),
                    "queued descendant observer dropped when parent hook failed");
        }
    }


    @Test void retiredTokenClosesLateSubscriptionsAndCannotBeCancelledAgain()
            throws Exception {
        for (int i = 0; i < 100; i++) {
            TaskCancellation parent = new TaskCancellation();
            TaskCancellation finished = parent.child();
            finished.retire();
            finished.retire(); // Idempotent retirement.
            assertEquals(0, parent.ownedChildCount());
            AtomicInteger callbacks = new AtomicInteger();
            TaskCancellation.Subscription observer =
                    finished.onCancel(callbacks::incrementAndGet);
            TaskCancellation.Subscription revoker =
                    finished.onRevoke(callbacks::incrementAndGet);
            java.lang.reflect.Field active =
                    observer.getClass().getDeclaredField("active");
            active.setAccessible(true);
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)
                    active.get(observer)).get(),
                    "late observer must not pin a completed task");
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)
                    active.get(revoker)).get(),
                    "late revoker must not pin a completed task");
            assertFalse(finished.cancel("too late"));
            assertEquals(0, callbacks.get());
            observer.close();
            revoker.close();
        }
    }

    @Test void retiredTokensCannotSpawnOrAdoptLiveTasks() {
        for (int i = 0; i < 100; i++) {
            TaskCancellation root = new TaskCancellation();
            TaskCancellation retired = root.child();
            retired.retire();
            assertThrows(IllegalStateException.class, retired::child);
            TaskCancellation destination = new TaskCancellation();
            TaskCancellation live = destination.child();
            assertThrows(IllegalStateException.class,
                    () -> live.adoptInto(retired));
            assertTrue(live.isOwnedBy(destination));
            assertThrows(IllegalStateException.class,
                    () -> retired.adoptInto(destination));
        }
    }

    @Test void concurrentRetirementAndLateSubscriptionNeverRetainsCallback()
            throws Exception {
        for (int i = 0; i < 200; i++) {
            TaskCancellation finished = new TaskCancellation();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger callbacks = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<TaskCancellation.Subscription> sub =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread registrant = new Thread(() -> {
                await(start);
                sub.set(finished.onCancel(callbacks::incrementAndGet));
            });
            Thread terminator = new Thread(() -> {
                await(start);
                finished.retire();
            });
            registrant.start();
            terminator.start();
            start.countDown();
            registrant.join(3000);
            terminator.join(3000);
            assertFalse(registrant.isAlive());
            assertFalse(terminator.isAlive());
            java.lang.reflect.Field active =
                    sub.get().getClass().getDeclaredField("active");
            active.setAccessible(true);
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)
                    active.get(sub.get())).get(),
                    "retirement must detach racing subscription");
            assertFalse(finished.cancel("post-retirement"));
            assertEquals(0, callbacks.get());
        }
    }

    @Test void parentRetirementCannotOrphanLiveChildren() {
        for (int i = 0; i < 100; i++) {
            TaskCancellation parent = new TaskCancellation();
            TaskCancellation child = parent.child();
            assertThrows(IllegalStateException.class, parent::retire);
            assertTrue(child.isOwnedBy(parent),
                    "rejected retirement must not detach a live child");
            assertEquals(1, parent.ownedChildCount());
            assertTrue(parent.cancel("shutdown"));
            assertTrue(child.isCancelled());
            assertThrows(IllegalStateException.class, parent::retire,
                    "cancellation alone does not prove child physical retirement");
            child.retire();
            parent.retire();
            assertFalse(parent.cancel("too late"));
        }
    }

    @Test void retirementDuringRevocationDeliversPreAdmittedObserver()
            throws Exception {
        for (int i = 0; i < 100; i++) {
            TaskCancellation token = new TaskCancellation();
            CountDownLatch revokerStarted = new CountDownLatch(1);
            CountDownLatch finishRevocation = new CountDownLatch(1);
            AtomicInteger notifications = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Throwable> error =
                    new java.util.concurrent.atomic.AtomicReference<>();

            token.onRevoke(() -> {
                revokerStarted.countDown();
                await(finishRevocation);
            });
            token.onCancel(notifications::incrementAndGet);
            Thread cancellation = new Thread(() -> {
                try { token.cancel("shutdown"); }
                catch (Throwable failure) { error.set(failure); }
            });
            cancellation.start();
            try {
                assertTrue(revokerStarted.await(3, TimeUnit.SECONDS),
                        "revocation never reached the blocking hook");
                assertTrue(token.isCancelled());
                token.retire();
                assertEquals(0, notifications.get(),
                        "observer must await the revocation barrier");
                assertFalse(token.cancel("after retirement"));
                token.onCancel(() -> notifications.addAndGet(100));
            } finally {
                finishRevocation.countDown();
            }
            cancellation.join(3000);
            assertFalse(cancellation.isAlive());
            assertNull(error.get());
            assertEquals(1, notifications.get(),
                    "retirement must not discard observers admitted before cancel");
        }
    }

    @Test void reentrantRetirementCannotDropCancellationObserver() {
        for (int i = 0; i < 100; i++) {
            TaskCancellation token = new TaskCancellation();
            AtomicInteger notifications = new AtomicInteger();
            token.onCancel(notifications::incrementAndGet);
            token.onRevoke(token::retire);
            assertTrue(token.cancel("stop"));
            assertEquals(1, notifications.get(),
                    "retiring inside a revoker must preserve committed observers");
            assertFalse(token.cancel("again"));
            token.onCancel(() -> notifications.addAndGet(100));
            assertEquals(1, notifications.get(),
                    "post-retirement subscribers must not be called");
        }
    }

    @Test void childRetirementDuringParentCancellationPreservesObserver()
            throws Exception {
        for (int i = 0; i < 100; i++) {
            TaskCancellation parent = new TaskCancellation();
            TaskCancellation child = parent.child();
            CountDownLatch revokerStarted = new CountDownLatch(1);
            CountDownLatch finishRevocation = new CountDownLatch(1);
            AtomicInteger observations = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();

            child.onRevoke(() -> {
                revokerStarted.countDown();
                await(finishRevocation);
            });
            child.onCancel(observations::incrementAndGet);
            Thread cancelling = new Thread(() -> {
                try { parent.cancel("shutdown"); }
                catch (Throwable error) { failure.set(error); }
            });
            cancelling.start();
            try {
                assertTrue(revokerStarted.await(3, TimeUnit.SECONDS));
                assertTrue(child.isCancelled());
                child.retire();
                assertEquals(0, observations.get());
            } finally {
                finishRevocation.countDown();
            }
            cancelling.join(3000);
            assertFalse(cancelling.isAlive());
            assertNull(failure.get());
            assertEquals(1, observations.get(),
                    "child retirement must not suppress parent cancellation");
            assertEquals(0, parent.ownedChildCount());
        }
    }


    @Test void retiredTargetClosesUpstreamCancellationSubscription()
            throws Exception {
        for (int i = 0; i < 100; i++) {
            TaskCancellation source = new TaskCancellation();
            TaskCancellation target = new TaskCancellation();
            TaskCancellation.Subscription link = target.linkCancellationFrom(source);
            java.lang.reflect.Field active = link.getClass().getDeclaredField("active");
            active.setAccessible(true);
            assertTrue(((java.util.concurrent.atomic.AtomicBoolean)
                    active.get(link)).get());
            target.retire();
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)
                    active.get(link)).get(),
                    "retirement must unregister listener from long-lived source");
            java.lang.reflect.Field pending =
                    TaskCancellation.class.getDeclaredField("observers");
            pending.setAccessible(true);
            assertEquals(0, ((java.util.Set<?>) pending.get(source)).size(),
                    "upstream source must not retain the completed target");
            assertTrue(source.cancel("upstream stopped"));
            assertFalse(target.isCancelled(),
                    "completed target must not be reactivated from a source link");
            link.close();
        }
    }

    @Test void linkingAfterRetirementCannotPinCompletedTarget()
            throws Exception {
        for (int i = 0; i < 100; i++) {
            TaskCancellation source = new TaskCancellation();
            TaskCancellation target = new TaskCancellation();
            target.retire();
            TaskCancellation.Subscription link = target.linkCancellationFrom(source);
            java.lang.reflect.Field active = link.getClass().getDeclaredField("active");
            active.setAccessible(true);
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)
                    active.get(link)).get(),
                    "late link must be immediately closed");
            assertTrue(source.cancel("upstream stopped"));
            assertFalse(target.isCancelled());
        }
    }

    @Test void concurrentLinkAdmissionAndRetirementAlwaysClosesLink()
            throws Exception {
        for (int i = 0; i < 150; i++) {
            TaskCancellation source = new TaskCancellation();
            TaskCancellation target = new TaskCancellation();
            CountDownLatch go = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<TaskCancellation.Subscription> link =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread linker = new Thread(() -> {
                await(go);
                link.set(target.linkCancellationFrom(source));
            });
            Thread retiree = new Thread(() -> {
                await(go);
                target.retire();
            });
            linker.start();
            retiree.start();
            go.countDown();
            linker.join(3000);
            retiree.join(3000);
            assertFalse(linker.isAlive());
            assertFalse(retiree.isAlive());
            assertNotNull(link.get());
            java.lang.reflect.Field active =
                    link.get().getClass().getDeclaredField("active");
            active.setAccessible(true);
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean)
                    active.get(link.get())).get(),
                    "race must not retain completed cancellation target");
            source.cancel("upstream stopped");
            assertFalse(target.isCancelled());
        }
    }


    @Test void cancellationDetachesRevocationButNotPhysicalLifetime() {
        for (int i = 0; i < 100; i++) {
            TaskCancellation parent = new TaskCancellation();
            TaskCancellation child = parent.child();
            TaskCancellation grandchild = child.child();
            assertTrue(parent.cancel("shutdown"));
            assertEquals(0, parent.ownedChildCount(),
                    "cancelled revocation graph must be cleared");
            AtomicInteger ready = new AtomicInteger();
            assertFalse(parent.sealAndObserveChildQuiescence(ready::incrementAndGet),
                    "cancelled child is not physically retired");
            assertThrows(IllegalStateException.class, parent::retire);
            grandchild.retire();
            assertEquals(0, ready.get(),
                    "child itself has not yet physically retired");
            child.retire();
            assertEquals(1, ready.get());
            parent.retire();
            assertFalse(parent.cancel("again"));
        }
    }

    @Test void movingChildTransfersPhysicalJoinResponsibility() {
        for (int i = 0; i < 100; i++) {
            TaskCancellation original = new TaskCancellation();
            TaskCancellation replacement = new TaskCancellation();
            TaskCancellation child = original.child();
            AtomicInteger oldDrained = new AtomicInteger();
            assertFalse(original.sealAndObserveChildQuiescence(
                    oldDrained::incrementAndGet));
            child.adoptInto(replacement);
            assertEquals(1, oldDrained.get(),
                    "transferring last child releases old join barrier");
            original.retire();
            AtomicInteger newDrained = new AtomicInteger();
            assertFalse(replacement.sealAndObserveChildQuiescence(
                    newDrained::incrementAndGet));
            assertThrows(IllegalStateException.class,
                    () -> child.adoptInto(original),
                    "sealed/retired owner must reject adoption");
            child.retire();
            assertEquals(1, newDrained.get());
            replacement.retire();
        }
    }

    @Test void sealingChildJoinClosesAdmissionWithoutDroppingLiveChildren() {
        for (int i = 0; i < 100; i++) {
            TaskCancellation parent = new TaskCancellation();
            TaskCancellation running = parent.child();
            AtomicInteger ready = new AtomicInteger();
            assertFalse(parent.sealAndObserveChildQuiescence(
                    ready::incrementAndGet));
            assertThrows(IllegalStateException.class, parent::child);
            TaskCancellation other = new TaskCancellation();
            TaskCancellation moving = other.child();
            assertThrows(IllegalStateException.class,
                    () -> moving.adoptInto(parent));
            assertTrue(moving.isOwnedBy(other));
            running.retire();
            assertEquals(1, ready.get());
            parent.retire();
            moving.retire();
            other.retire();
        }
    }

    @Test void retirementRacingParentJoinRegistrationNotifiesExactlyOnce()
            throws Exception {
        for (int i = 0; i < 200; i++) {
            TaskCancellation parent = new TaskCancellation();
            TaskCancellation child = parent.child();
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger ready = new AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<Boolean> immediate =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread sealing = new Thread(() -> {
                await(go);
                boolean noChildren = parent.sealAndObserveChildQuiescence(
                        ready::incrementAndGet);
                immediate.set(noChildren);
                if (noChildren) ready.incrementAndGet();
            });
            Thread retiring = new Thread(() -> {
                await(go);
                child.retire();
            });
            sealing.start(); retiring.start(); go.countDown();
            sealing.join(3000); retiring.join(3000);
            assertFalse(sealing.isAlive());
            assertFalse(retiring.isAlive());
            assertNotNull(immediate.get());
            assertEquals(1, ready.get(),
                    "either the caller or retired child must release join once");
            parent.retire();
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
