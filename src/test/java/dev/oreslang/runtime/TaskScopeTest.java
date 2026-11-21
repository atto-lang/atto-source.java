package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task scope retirement is stronger than a cancelled/settled Future: the
 * executing carrier must have left the guest turn before parent resumption.
 */
final class TaskScopeTest {
    @Test
    void compilerRetirementNotificationRequiresPhysicalDrainEvenAfterFailure() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        OresFuture<Integer> child = new OresFuture<>();
        ticket.attach(child);
        java.util.concurrent.atomic.AtomicInteger notifications =
                new java.util.concurrent.atomic.AtomicInteger();
        scope.afterPhysicalDrain(notifications::incrementAndGet);
        scope.close();
        child.failFromRuntime(new IllegalStateException("child failed"));
        assertEquals(0, notifications.get(), "logical failure is not retirement");
        ticket.quiesced();
        assertEquals(1, notifications.get());
        scope.afterPhysicalDrain(notifications::incrementAndGet);
        assertEquals(2, notifications.get(), "late observer is notified exactly once");
        assertThrows(NullPointerException.class, () -> scope.afterPhysicalDrain(null));
    }

    @Test
    void emptyScopeIsAlreadyQuiescentAndCannotReopen() {
        TaskScope scope = new TaskScope();
        OresFuture<Void> joined = scope.close();
        assertTrue(joined.isDone());
        assertDoesNotThrow(joined::join);
        assertThrows(IllegalStateException.class, scope::reserve);
    }

    @Test
    void attachmentDoesNotAcquireScopeMonitorWhileHoldingTicket() throws Exception {
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        OresFuture<Integer> child = new OresFuture<>();
        CountDownLatch attached = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                ticket.attach(child);
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                attached.countDown();
            }
        }, "task-scope-attach-lock-order");
        worker.setDaemon(true);

        // reserve(parent) may need scope -> registration while a scope is
        // closing. Attachment must therefore never take registration -> scope.
        // Holding the scope monitor here makes the old inversion deterministic:
        // the old attach() blocks; the hardened volatile cancellation read does not.
        synchronized (scope) {
            worker.start();
            assertTrue(attached.await(2, TimeUnit.SECONDS),
                    "attachment must not wait for the scope monitor");
        }
        worker.join(2_000L);
        assertFalse(worker.isAlive());
        assertNull(failure.get());

        assertTrue(child.cancel(false));
        ticket.quiesced();
        assertTrue(scope.close().isDone());
    }

    @Test
    void pendingChildMaySpawnGrandchildAfterParentBeginsJoining() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration parent = scope.reserve();
        parent.beganExecution();
        OresFuture<Void> joined = scope.close();
        assertThrows(IllegalStateException.class, scope::reserve,
                "an unrelated caller cannot reopen a closing scope");
        TaskScope.Registration grandchild = scope.reserve(parent);

        parent.attach(OresFuture.completed(null));
        parent.quiesced();
        assertFalse(joined.isDone());

        grandchild.attach(OresFuture.completed(null));
        grandchild.quiesced();
        assertTrue(joined.isDone());
        assertDoesNotThrow(joined::join);
        assertThrows(IllegalStateException.class, scope::reserve);
    }

    @Test
    void cancelledFutureDoesNotUnlockLiveCarrier() throws Exception {
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> running = scheduler.start(resume -> {
                executing.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test release was not signalled");
                }
                return OresScheduler.done(42);
            }, ticket::quiesced);
            ticket.attach(running);
            try {
                assertTrue(executing.await(5, TimeUnit.SECONDS));
                OresFuture<Void> joined = scope.close();
                assertTrue(running.cancel(false));
                assertTrue(running.isDone(), "cancelled Future is already terminal");
                assertFalse(joined.isDone(), "live carrier still owns child memory");
            } finally {
                release.countDown();
            }
            // The cancellation may have won the Future race, but retirement
            // must not be published until the carrier actually returns.
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> scope.close().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void childFailureWaitsForOtherChildQuiescenceBeforePropagating() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration failed = scope.reserve();
        TaskScope.Registration stillRunning = scope.reserve();
        OresFuture<Void> joined = scope.close();
        failed.attach(OresFuture.failed(new IllegalStateException("child failed")));
        failed.quiesced();
        assertFalse(joined.isDone());
        stillRunning.attach(OresFuture.completed(null));
        stillRunning.quiesced();
        assertTrue(joined.isDone());
        assertThrows(RuntimeException.class, joined::join);
    }

    @Test
    void exactFutureMayAttachAfterTerminalAndPhysicalRetirementRace() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        OresFuture<Integer> child = OresFuture.completed(42);

        // Model the fast-child race in source lowering: scheduler terminal
        // authority and physical-quiescence callbacks can both happen before
        // start(...) returns the Future to Registration.attach(...).
        ticket.terminalReady(child);
        ticket.quiesced();

        OresFuture<Void> joined = scope.close();
        assertTrue(joined.isDone());
        assertDoesNotThrow(joined::join);

        assertDoesNotThrow(() -> ticket.attach(child),
                "late attachment of the exact terminal-witness Future is benign");
        assertThrows(IllegalStateException.class,
                () -> ticket.attach(OresFuture.completed(43)),
                "retired ticket must not accept a different Future identity");
    }

    @Test
    void failedAdmissionTicketStillRejectsLateFutureAttachment() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        IllegalStateException admission = new IllegalStateException("rejected");
        ticket.failedAdmission(admission);

        OresFuture<Void> joined = scope.close();
        assertTrue(joined.isDone());
        assertThrows(RuntimeException.class, joined::join);
        assertThrows(IllegalStateException.class,
                () -> ticket.attach(OresFuture.completed(1)),
                "failed admission has no scheduler Future identity to bind");
    }

    @Test
    void retiredTicketRejectsDifferentTerminalFutureIdentity() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        OresFuture<Integer> exact = OresFuture.completed(42);
        ticket.terminalReady(exact);
        ticket.quiesced();

        assertDoesNotThrow(() -> ticket.terminalReady(exact),
                "duplicate terminal callback from exact Future is idempotent");
        assertThrows(IllegalStateException.class,
                () -> ticket.terminalReady(OresFuture.completed(43)),
                "retired ticket must reject a different terminal authority");
    }

    @Test
    void failedAdmissionRejectsAnyLaterTerminalFutureAuthority() {
        TaskScope scope = new TaskScope();
        TaskScope.Registration ticket = scope.reserve();
        ticket.failedAdmission(new IllegalStateException("rejected"));

        assertThrows(IllegalStateException.class,
                () -> ticket.terminalReady(OresFuture.completed(1)),
                "failed admission must never acquire scheduler Future authority");
    }


}
