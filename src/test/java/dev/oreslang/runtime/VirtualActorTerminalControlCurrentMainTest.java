package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only automatic terminal CONTROL transport tests on current Java main.
 * These are not compiled .atto guests and not durable cross-process receipts.
 */
@Timeout(30)
final class VirtualActorTerminalControlCurrentMainTest {
    private static final VirtualActorActivationRegistry.Identity ID =
            new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "cart-control");

    /** A VM-owned dispatcher: CONTROL never runs inline on a retiring actor. */
    private static ActorRuntime runtimeWithControl(ExecutorService control) {
        return new ActorRuntime(
                IsolatePolicy.developer(),
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                turn -> {
                    OresFuture<Void> result = new OresFuture<>();
                    control.execute(() -> {
                        try {
                            turn.run();
                            result.completeFromRuntime(null);
                        } catch (Throwable failure) {
                            result.failFromRuntime(failure);
                        }
                    });
                    return result;
                });
    }

    private static ActorRuntime.ActorRef<String> failingWorker(
            ActorRuntime.ActorGroup group) throws Exception {
        var worker = group.spawnWorker(
                ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                factory -> (message, context) -> {
                    throw new IllegalStateException("PRIVATE GUEST DATA must not cross CONTROL");
                });
        worker.getReady().get(5, TimeUnit.SECONDS);
        return worker;
    }

    private static void failActor(ActorRuntime.ActorRef<String> worker) throws Exception {
        worker.send("fail");
        assertThrows(ExecutionException.class,
                () -> worker.getDone().get(5, TimeUnit.SECONDS));
    }

    private static long awaitNextGeneration(
            ActorRuntime.ActorGroup group,
            VirtualActorActivationRegistry.Identity identity,
            long generation) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                return group.reserveNextVirtualActorGeneration(identity);
            } catch (IllegalStateException blocked) {
                Thread.sleep(10);
            }
        }
        fail("CONTROL never settled generation " + generation);
        return -1L;
    }

    @Test
    void exactRetiredReceiptRunsOnVmControlAndWaitsForAsyncPolicy() throws Exception {
        try (ExecutorService control = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = runtimeWithControl(control)) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Void> finishPolicy = new CompletableFuture<>();
            AtomicReference<ActorRuntime.VirtualActorTerminalNotice> notice =
                    new AtomicReference<>();
            AtomicReference<Boolean> actorContext = new AtomicReference<>();
            AtomicInteger delivered = new AtomicInteger();
            group.installVirtualActorTerminalHandler(value -> {
                actorContext.set(ActorRuntime.inActorExecution()
                        || ActorRuntime.isActorCarrierThread());
                notice.set(value);
                delivered.incrementAndGet();
                entered.countDown();
                return finishPolicy;
            });
            var old = failingWorker(group);
            assertEquals(1L, group.reserveNextVirtualActorGeneration(ID));
            group.bindVirtualActorIdentity(old, ID, 1L);
            failActor(old);
            assertTrue(entered.await(5, TimeUnit.SECONDS), "terminal CONTROL must be delivered");
            assertEquals(Boolean.FALSE, actorContext.get(), "policy must not run on actor carrier");
            var receipt = notice.get();
            assertEquals(group.id(), receipt.groupId());
            assertEquals(group.supervisorId(), receipt.supervisorId());
            assertEquals(old.id(), receipt.actorId());
            assertEquals(ID, receipt.identity());
            assertEquals(1L, receipt.generation());
            assertEquals(ActorRuntime.VirtualActorTerminalKind.TERMINAL_FAILURE, receipt.kind());
            assertEquals("java.lang.IllegalStateException", receipt.failureClass());
            assertFalse(receipt.toString().contains("PRIVATE GUEST DATA"));
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID),
                    "incomplete asynchronous policy must block replacement");
            finishPolicy.complete(null);
            assertEquals(2L, awaitNextGeneration(group, ID, 2L));
            group.abortVirtualActorGeneration(ID, 2L);
            assertEquals(1, delivered.get(), "one retired actor has one CONTROL notice");
            group.close();
        }
    }

    @Test
    void rejectedControlDispatcherQuarantinesFailedVirtualActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            AtomicInteger called = new AtomicInteger();
            group.installVirtualActorTerminalHandler(notice -> {
                called.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            var old = failingWorker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(old, ID, 1L);
            failActor(old);
            assertEquals(0, called.get(), "unconfigured CONTROL must not run callback inline");
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID),
                    "missing CONTROL runner must quarantine the logical identity");
            group.close();
        }
    }

    @Test
    void failedAsynchronousPolicyQuarantinesLogicalIdentity() throws Exception {
        try (ExecutorService control = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = runtimeWithControl(control)) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Void> failedPolicy = new CompletableFuture<>();
            group.installVirtualActorTerminalHandler(receipt -> {
                entered.countDown();
                return failedPolicy;
            });
            var actor = failingWorker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(actor, ID, 1L);
            failActor(actor);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            failedPolicy.completeExceptionally(new IllegalStateException("policy did not commit"));
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID),
                    "failed supervisor policy must not permit replacement");
            group.close();
        }
    }

    @Test
    void inlineDispatcherCannotInvokeTerminalHandler() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                turn -> {
                    turn.run(); // Intentionally invalid CONTROL dispatcher.
                    return OresFuture.completed(null);
                })) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            AtomicInteger called = new AtomicInteger();
            group.installVirtualActorTerminalHandler(notice -> {
                called.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            var actor = failingWorker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(actor, ID, 1L);
            failActor(actor);
            assertEquals(0, called.get(), "inline dispatcher must never execute user policy");
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID));
            group.close();
        }
    }

    @Test
    void groupCloseBeforeDelayedControlReceiptQuarantinesInsteadOfRehoming()
            throws Exception {
        try (ExecutorService control = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = runtimeWithControl(control)) {
            var supervisor = runtime.createSupervisor();
            var first = runtime.createActorGroup(supervisor);
            var next = runtime.createActorGroup(supervisor);
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<Void> policy = new CompletableFuture<>();
            first.installVirtualActorTerminalHandler(receipt -> {
                entered.countDown();
                return policy;
            });
            var actor = failingWorker(first);
            first.reserveVirtualActorGeneration(ID, 1L);
            first.bindVirtualActorIdentity(actor, ID, 1L);
            failActor(actor);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            first.close();
            assertThrows(IllegalStateException.class,
                    () -> next.reserveNextVirtualActorGeneration(ID),
                    "group close must not release an in-flight CONTROL decision");
            policy.complete(null);
            assertEquals(2L, awaitNextGeneration(next, ID, 2L));
            next.abortVirtualActorGeneration(ID, 2L);
            next.close();
        }
    }

    @Test
    @Timeout(40)
    void suspendedTerminalPolicySerializesNoticesWithinSameActorGroup()
            throws Exception {
        try (ExecutorService control = Executors.newFixedThreadPool(3);
             ActorRuntime runtime = runtimeWithControl(control)) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var firstId = new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "first");
            var secondId = new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "second");
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);
            CompletableFuture<Void> finishFirst = new CompletableFuture<>();
            AtomicInteger enteredCount = new AtomicInteger();
            group.installVirtualActorTerminalHandler(notice -> {
                int ordinal = enteredCount.incrementAndGet();
                if (ordinal == 1) {
                    firstEntered.countDown();
                    return finishFirst;
                }
                secondEntered.countDown();
                return CompletableFuture.completedFuture(null);
            });
            var first = failingWorker(group);
            var second = failingWorker(group);
            group.reserveVirtualActorGeneration(firstId, 1L);
            group.reserveVirtualActorGeneration(secondId, 1L);
            group.bindVirtualActorIdentity(first, firstId, 1L);
            group.bindVirtualActorIdentity(second, secondId, 1L);
            failActor(first);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            failActor(second);
            assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS),
                    "second CONTROL policy must not reenter before first stage settles");
            assertEquals(1, enteredCount.get());
            finishFirst.complete(null);
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertEquals(2, enteredCount.get());
            assertEquals(2L, awaitNextGeneration(group, firstId, 2L));
            group.abortVirtualActorGeneration(firstId, 2L);
            assertEquals(2L, awaitNextGeneration(group, secondId, 2L));
            group.abortVirtualActorGeneration(secondId, 2L);
            group.close();
        }
    }

    @Test
    @Timeout(40)
    void unrelatedSupervisorGroupsContinueWhileOnePolicyIsSuspended()
            throws Exception {
        try (ExecutorService control = Executors.newFixedThreadPool(3);
             ActorRuntime runtime = runtimeWithControl(control)) {
            var blocked = runtime.createActorGroup(runtime.createSupervisor());
            var independent = runtime.createActorGroup(runtime.createSupervisor());
            var blockedId = new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "blocked");
            var independentId = new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "independent");
            CountDownLatch blockedEntered = new CountDownLatch(1);
            CountDownLatch independentEntered = new CountDownLatch(1);
            CompletableFuture<Void> resume = new CompletableFuture<>();
            blocked.installVirtualActorTerminalHandler(notice -> {
                blockedEntered.countDown();
                return resume;
            });
            independent.installVirtualActorTerminalHandler(notice -> {
                independentEntered.countDown();
                return CompletableFuture.completedFuture(null);
            });
            var first = failingWorker(blocked);
            var second = failingWorker(independent);
            blocked.reserveVirtualActorGeneration(blockedId, 1L);
            independent.reserveVirtualActorGeneration(independentId, 1L);
            blocked.bindVirtualActorIdentity(first, blockedId, 1L);
            independent.bindVirtualActorIdentity(second, independentId, 1L);
            failActor(first);
            assertTrue(blockedEntered.await(5, TimeUnit.SECONDS));
            failActor(second);
            assertTrue(independentEntered.await(5, TimeUnit.SECONDS),
                    "unrelated group must not wait on suspended CONTROL policy");
            assertThrows(IllegalStateException.class,
                    () -> blocked.reserveNextVirtualActorGeneration(blockedId));
            assertEquals(2L, awaitNextGeneration(independent, independentId, 2L));
            independent.abortVirtualActorGeneration(independentId, 2L);
            resume.complete(null);
            assertEquals(2L, awaitNextGeneration(blocked, blockedId, 2L));
            blocked.abortVirtualActorGeneration(blockedId, 2L);
            blocked.close();
            independent.close();
        }
    }

    @Test
    @Timeout(40)
    void distinctGroupsOfOneSupervisorRemainSingleFlightAcrossAwait()
            throws Exception {
        try (ExecutorService control = Executors.newFixedThreadPool(3);
             ActorRuntime runtime = runtimeWithControl(control)) {
            var supervisor = runtime.createSupervisor();
            var groupA = runtime.createActorGroup(supervisor);
            var groupB = runtime.createActorGroup(supervisor);
            var firstId = new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "supervised-a");
            var secondId = new VirtualActorActivationRegistry.Identity("tenant-a", "Cart", "supervised-b");
            CountDownLatch aEntered = new CountDownLatch(1);
            CountDownLatch bEntered = new CountDownLatch(1);
            CompletableFuture<Void> resumeA = new CompletableFuture<>();
            groupA.installVirtualActorTerminalHandler(notice -> {
                aEntered.countDown();
                return resumeA;
            });
            groupB.installVirtualActorTerminalHandler(notice -> {
                bEntered.countDown();
                return CompletableFuture.completedFuture(null);
            });
            var first = failingWorker(groupA);
            var second = failingWorker(groupB);
            groupA.reserveVirtualActorGeneration(firstId, 1L);
            groupB.reserveVirtualActorGeneration(secondId, 1L);
            groupA.bindVirtualActorIdentity(first, firstId, 1L);
            groupB.bindVirtualActorIdentity(second, secondId, 1L);
            failActor(first);
            assertTrue(aEntered.await(5, TimeUnit.SECONDS));
            failActor(second);
            assertFalse(bEntered.await(250, TimeUnit.MILLISECONDS),
                    "same supervisor must remain nonreentrant across group boundaries");
            resumeA.complete(null);
            assertTrue(bEntered.await(5, TimeUnit.SECONDS));
            assertEquals(2L, awaitNextGeneration(groupA, firstId, 2L));
            groupA.abortVirtualActorGeneration(firstId, 2L);
            assertEquals(2L, awaitNextGeneration(groupB, secondId, 2L));
            groupB.abortVirtualActorGeneration(secondId, 2L);
            groupA.close();
            groupB.close();
        }
    }

    @Test
    void cancellationReceiptIsSanitizedAndRequiresPhysicalRetirement()
            throws Exception {
        try (ExecutorService control = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = runtimeWithControl(control)) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<ActorRuntime.VirtualActorTerminalNotice> captured =
                    new AtomicReference<>();
            group.installVirtualActorTerminalHandler(notice -> {
                captured.set(notice);
                delivered.countDown();
                return CompletableFuture.completedFuture(null);
            });
            var worker = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    factory -> (message, context) -> { });
            worker.getReady().get(5, TimeUnit.SECONDS);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(worker, ID, 1L);
            assertTrue(worker.cancel());
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            try {
                worker.getDone().get(5, TimeUnit.SECONDS);
            } catch (ExecutionException | java.util.concurrent.CancellationException ignored) {
                // Cancellation may be surfaced as a failed or cancelled Future.
            }
            assertEquals(ActorRuntime.VirtualActorTerminalKind.CANCELLED,
                    captured.get().kind());
            assertEquals(worker.id(), captured.get().actorId());
            assertFalse(captured.get().toString().contains("PRIVATE GUEST DATA"));
            assertEquals(2L, awaitNextGeneration(group, ID, 2L));
            group.abortVirtualActorGeneration(ID, 2L);
            group.close();
        }
    }

    @Test
    void throwingSynchronousControlPolicyPermanentlyQuarantines() throws Exception {
        try (ExecutorService control = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = runtimeWithControl(control)) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            CountDownLatch entered = new CountDownLatch(1);
            group.installVirtualActorTerminalHandler(notice -> {
                entered.countDown();
                throw new IllegalStateException("policy failure");
            });
            var old = failingWorker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(old, ID, 1L);
            failActor(old);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID));
            group.close();
        }
    }

    @Test
    void failedPhysicalExitCannotDispatchControlOrReleaseQuarantinedIdentity()
            throws Exception {
        try (ExecutorService control = Executors.newSingleThreadExecutor();
             ActorRuntime runtime = runtimeWithControl(control)) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            AtomicInteger deliveries = new AtomicInteger();
            group.installVirtualActorTerminalHandler(notice -> {
                deliveries.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            var worker = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    factory -> (message, context) -> { });
            worker.getReady().get(5, TimeUnit.SECONDS);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(worker, ID, 1L);
            runtime.setActorExitHook(domain -> {
                throw new IllegalStateException("exit cleanup failed");
            });
            worker.stop();
            assertThrows(ExecutionException.class,
                    () -> worker.getDone().get(5, TimeUnit.SECONDS));
            assertEquals(0, deliveries.get(),
                    "failed physical cleanup cannot forge a terminal receipt");
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID),
                    "unclean actor remains quarantined despite group deregistration");
            group.close();
        }
    }

    @Test
    void canceledControlAdmissionCannotLaterExecuteItsQueuedRunnable() throws Exception {
        AtomicReference<Runnable> delayed = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                turn -> {
                    delayed.set(turn);
                    // A dishonest/failed dispatcher still holds a runnable.
                    return OresFuture.failed(new IllegalStateException("runner admission failed"));
                })) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            AtomicInteger invoked = new AtomicInteger();
            group.installVirtualActorTerminalHandler(notice -> {
                invoked.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            var actor = failingWorker(group);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(actor, ID, 1L);
            failActor(actor);
            assertNotNull(delayed.get());
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID));
            delayed.get().run(); // Simulated malicious late executor callback.
            delayed.get().run(); // Duplicate must also be rejected.
            assertEquals(0, invoked.get(),
                    "a canceled/failed CONTROL lane must never run policy");
            assertThrows(IllegalStateException.class,
                    () -> group.reserveNextVirtualActorGeneration(ID));
            group.close();
        }
    }

    @Test
    void guestActorCannotInstallSupervisorTerminalControlHandler() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            var guest = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    factory -> (message, context) -> {
                        try {
                            group.installVirtualActorTerminalHandler(
                                    notice -> CompletableFuture.completedFuture(null));
                        } catch (Throwable failure) {
                            rejected.set(failure);
                        } finally {
                            attempted.countDown();
                        }
                    });
            guest.getReady().get(5, TimeUnit.SECONDS);
            guest.send("unauthorized");
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, rejected.get(),
                    "guest code must never install CONTROL policy authority");
            guest.stop();
            guest.getDone().get(5, TimeUnit.SECONDS);
            group.close();
        }
    }

    @Test
    void terminalHandlerMustBeInstalledBeforeAnyPhysicalVirtualBinding()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            var worker = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    factory -> (message, context) -> { });
            worker.getReady().get(5, TimeUnit.SECONDS);
            group.reserveVirtualActorGeneration(ID, 1L);
            group.bindVirtualActorIdentity(worker, ID, 1L);
            assertThrows(IllegalStateException.class,
                    () -> group.installVirtualActorTerminalHandler(
                            notice -> CompletableFuture.completedFuture(null)),
                    "late subscription must not silently miss already-bound workers");
            worker.stop();
            worker.getDone().get(5, TimeUnit.SECONDS);
            group.close();
        }
    }

    @Test
    void installingTerminalHandlerTwiceIsRejected() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup(runtime.createSupervisor());
            group.installVirtualActorTerminalHandler(
                    notice -> CompletableFuture.completedFuture(null));
            assertThrows(IllegalStateException.class,
                    () -> group.installVirtualActorTerminalHandler(
                            notice -> CompletableFuture.completedFuture(null)));
            group.close();
        }
    }
}
