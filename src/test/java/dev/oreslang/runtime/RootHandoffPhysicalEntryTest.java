package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic physical root admission checks. These do not claim Truffle
 * context-close refinement: they verify the VM gate's real runnable boundary.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class RootHandoffPhysicalEntryTest {
    @Test
    void hostCannotClaimEntryWhileRootTurnIsPhysicallyExecuting() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            OresFuture<Integer> turn = vm.rootScheduler().startSync(() -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released root entry");
                }
                return 7;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS),
                    "root must physically start before host attempts admission");
            assertThrows(RejectedExecutionException.class, vm::beginHostHandoff,
                    "active root turn owns entry, even after executor accepted it");
            // Host handoff scopes are distinct from running root tasks.
            // The root scheduler is the physical-drain authority at close.
            vm.requireHostHandoffReleased();
            release.countDown();
            assertEquals(7, turn.get(5, TimeUnit.SECONDS));
            // The public Future can settle immediately before the scheduler
            // carrier exits the gated physical guest turn. A host lease is
            // permitted ONLY after that physical turn releases its claim.
            // Waiting on requireHostHandoffReleased() was incorrect: that API
            // checks scoped host handoffs, not physically executing root turns.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            OresVM.HostHandoffLease admitted = null;
            while (admitted == null) {
                try {
                    admitted = vm.beginHostHandoff();
                } catch (RejectedExecutionException stillPhysicallyRunning) {
                    assertTrue(System.nanoTime() < deadline,
                            "root gate did not physically relinquish its entry");
                    Thread.onSpinWait();
                }
            }
            try (OresVM.HostHandoffLease host = admitted) {
                assertThrows(IllegalStateException.class, vm::requireHostHandoffReleased);
            }
            vm.requireHostHandoffReleased();
        } finally {
            release.countDown();
            vm.close();
        }
    }

    @Test
    void settledFutureCannotReopenHostEntryUntilSchedulerCarrierPhysicallyExits()
            throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finishGuest = new CountDownLatch(1);
        CountDownLatch terminalCallbackEntered = new CountDownLatch(1);
        CountDownLatch unblockTerminalCallback = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            OresFuture<Integer> future = vm.rootScheduler().startSync(() -> {
                started.countDown();
                if (!finishGuest.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release source guest turn");
                }
                return 51;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // Registration precedes result settlement. Settle() first publishes
            // the Future's terminal state, then synchronously notifies runtime
            // waiters. Block that callback so the physical root-gate runnable
            // demonstrably has NOT returned despite a visible public result.
            future.whenCompleteRuntime((value, failure) -> {
                terminalCallbackEntered.countDown();
                try {
                    if (!unblockTerminalCallback.await(5, TimeUnit.SECONDS)) {
                        callbackFailure.set(new AssertionError(
                                "host did not release terminal callback"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callbackFailure.set(interrupted);
                }
            });
            finishGuest.countDown();
            assertTrue(terminalCallbackEntered.await(5, TimeUnit.SECONDS),
                    "Future settlement callback did not reach physical-exit rendezvous");
            assertEquals(51, future.get(5, TimeUnit.SECONDS),
                    "public Future is already terminal at this rendezvous");
            assertThrows(RejectedExecutionException.class, vm::beginHostHandoff,
                    "terminal Future must not be mistaken for physical root DONE");
            unblockTerminalCallback.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            OresVM.HostHandoffLease admitted = null;
            while (admitted == null) {
                try {
                    admitted = vm.beginHostHandoff();
                } catch (RejectedExecutionException stillExiting) {
                    assertTrue(System.nanoTime() < deadline,
                            "root did not release guest-entry ownership after terminal callback");
                    Thread.onSpinWait();
                }
            }
            try (OresVM.HostHandoffLease host = admitted) {
                assertThrows(IllegalStateException.class, vm::requireHostHandoffReleased);
            }
            assertNull(callbackFailure.get(), "runtime callback encountered an unexpected error");
        } finally {
            finishGuest.countDown();
            unblockTerminalCallback.countDown();
            vm.close();
        }
    }

    @Test
    void foreignThreadCannotReleaseHostPhysicalEntryLease() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        OresVM.HostHandoffLease ownerLease = vm.beginHostHandoff();
        try {
            CountDownLatch rootEntered = new CountDownLatch(1);
            OresFuture<Integer> turn = vm.rootScheduler().startSync(() -> {
                rootEntered.countDown();
                return 19;
            });
            java.util.concurrent.atomic.AtomicReference<Throwable> foreignFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread foreign = Thread.ofPlatform().start(() -> {
                try {
                    ownerLease.close();
                } catch (Throwable failure) {
                    foreignFailure.set(failure);
                }
            });
            foreign.join(5000);
            assertFalse(foreign.isAlive());
            assertInstanceOf(SecurityException.class, foreignFailure.get(),
                    "foreign release must not surrender host physical ownership");
            assertEquals(1L, rootEntered.getCount(),
                    "foreign release must not dispatch root guest work");
            assertThrows(IllegalStateException.class, vm::requireHostHandoffReleased);
            ownerLease.close();
            assertEquals(19, turn.get(5, TimeUnit.SECONDS));
            assertTrue(rootEntered.await(5, TimeUnit.SECONDS));
            ownerLease.close(); // duplicate owner close is inert
        } finally {
            ownerLease.close();
            vm.close();
        }
    }

    @Test
    void hostLeaseDefersRootEntryUntilOuterScopeReleases() throws Exception {
        OresVM vm = OresVM.create(Runnable::run);
        try {
            CountDownLatch entered = new CountDownLatch(1);
            OresFuture<Integer> task;
            try (OresVM.HostHandoffLease host = vm.beginHostHandoff()) {
                task = vm.rootScheduler().startSync(() -> {
                    entered.countDown();
                    return 9;
                });
                assertEquals(1L, entered.getCount(),
                        "root guest turn must not begin under host entry");
                assertThrows(IllegalStateException.class, vm::requireHostHandoffReleased);
            }
            assertEquals(9, task.get(5, TimeUnit.SECONDS));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
        } finally {
            vm.close();
        }
    }
}
