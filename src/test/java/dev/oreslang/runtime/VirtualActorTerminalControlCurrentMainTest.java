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
