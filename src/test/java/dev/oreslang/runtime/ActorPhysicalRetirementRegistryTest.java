package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Physical native/IO loan retirement is distinct from logical Future.cancel
 * and from guest defer. These are trusted host-adapter tests, not Oreslang
 * guest-language API tests.
 */
@Timeout(20)
final class ActorPhysicalRetirementRegistryTest {
    @Test void actorStopWaitsForRealPhysicalIOAndFfiReceipts() throws Exception {
        try (var runtime = new ActorRuntime()) {
            AtomicReference<ActorPhysicalRetirementRegistry.Receipt> tcp =
                    new AtomicReference<>();
            AtomicReference<ActorPhysicalRetirementRegistry.Receipt> ffi =
                    new AtomicReference<>();
            CountDownLatch registered = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                tcp.set(context.runtime().registerCurrentActorPhysicalReceipt(
                        ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE));
                ffi.set(context.runtime().registerCurrentActorPhysicalReceipt(
                        ActorPhysicalRetirementRegistry.Kind.FFI_LOAN));
                registered.countDown();
            });

            assertThrows(SecurityException.class, () ->
                    runtime.registerCurrentActorPhysicalReceipt(
                            ActorPhysicalRetirementRegistry.Kind.FFI_LOAN));
            actor.send("register");
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertEquals(actor.id(), tcp.get().actorId());
            assertEquals(actor.generation(), tcp.get().generation());
            assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind().get("TCP_HTTP_HANDLE"));
            assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind().get("FFI_LOAN"));

            assertTrue(actor.cancel());
            assertFalse(actor.done().isDone(), "logical STOP cannot retire a TCP handle");
            assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS));
            assertTrue(tcp.get().completeAfterPhysicalRelease());
            assertFalse(tcp.get().completeAfterPhysicalRelease(),
                    "double settlement must be exactly once");
            assertFalse(actor.done().isDone(), "FFI loan still physically outstanding");
            assertFalse(actor.pendingPhysicalRetirementsByKind().containsKey("TCP_HTTP_HANDLE"));
            assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind().get("FFI_LOAN"));
            Thread foreignDriver = Thread.ofVirtual().start(
                    () -> assertTrue(ffi.get().completeAfterPhysicalRelease()));
            foreignDriver.join(3_000);
            assertFalse(foreignDriver.isAlive());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.physicalRetirementFailure().isEmpty());
            ExecutionException cancelled = assertThrows(ExecutionException.class,
                    () -> actor.done().get(3, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    cancelled.getCause());
        }
    }

    @Test void hostDriverCanPreRegisterButCannotRegisterAfterStop() throws Exception {
        try (var runtime = new ActorRuntime();
             var foreignRuntime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() ->
                    (message, context) -> { });
            assertThrows(IllegalArgumentException.class,
                    () -> foreignRuntime.registerActorPhysicalReceiptFromDriver(
                            actor, ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE));

            // A transport acceptor may run on its own host driver thread
            // before delivering this connection's first actor message.
            var ticket = runtime.registerActorPhysicalReceiptFromDriver(
                    actor, ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
            assertEquals(actor.id(), ticket.actorId());
            assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                    .get("TCP_HTTP_HANDLE"));

            assertTrue(actor.cancel());
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> runtime.registerActorPhysicalReceiptFromDriver(
                            actor, ActorPhysicalRetirementRegistry.Kind.OTHER));
            assertFalse(actor.done().isDone(),
                    "STOP cannot discard a resource accepted by the host transport");
            assertTrue(ticket.completeAfterPhysicalRelease());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> runtime.registerActorPhysicalReceiptFromDriver(
                            actor, ActorPhysicalRetirementRegistry.Kind.OTHER));
        }
    }

    @Test void supervisorCannotJoinUntilChildPhysicalLoanRetires() throws Exception {
        try (var runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.ActorRef<String>> childRef =
                    new AtomicReference<>();
            AtomicReference<ActorPhysicalRetirementRegistry.Receipt> childLoan =
                    new AtomicReference<>();
            CountDownLatch acquired = new CountDownLatch(1);
            var parent = runtime.<String>spawnShared(() -> (message, context) -> {
                var child = context.runtime().<String>spawnShared(() ->
                        (childMessage, childContext) -> {
                            childLoan.set(childContext.runtime()
                                    .registerCurrentActorPhysicalReceipt(
                                            ActorPhysicalRetirementRegistry.Kind.FFI_LOAN));
                            acquired.countDown();
                        });
                childRef.set(child);
                child.send("acquire");
            });
            parent.send("spawn");
            assertTrue(acquired.await(3, TimeUnit.SECONDS));
            assertNotNull(childRef.get());
            assertEquals(parent.id(), childRef.get().parentId().orElseThrow());

            assertTrue(parent.cancel());
            assertFalse(childRef.get().done().isDone());
            assertFalse(parent.done().isDone(),
                    "parent supervisor awaits child's actual release, not cancel request");
            assertFalse(parent.awaitTermination(100, TimeUnit.MILLISECONDS));
            assertTrue(childLoan.get().completeAfterPhysicalRelease());
            assertTrue(childRef.get().awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
            assertNotEquals(parent.id(), childLoan.get().actorId());
        }
    }

    @Test void stoppedSourceTaskCanMintOnlyCleanupPhysicalLoan() throws Exception {
        var awaiting = new OresFuture<Integer>();
        var cleanupStage = new OresFuture<Integer>();
        var entered = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var priorTicket = new AtomicReference<ActorPhysicalRetirementRegistry.Receipt>();
        var cleanupTicket = new AtomicReference<ActorPhysicalRetirementRegistry.Receipt>();
        var sourceResult = new AtomicReference<OresFuture<Void>>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                sourceResult.set(context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            int state;
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                if (state++ == 0) {
                                    priorTicket.set(context.runtime()
                                            .registerCurrentActorPhysicalReceipt(
                                                    ActorPhysicalRetirementRegistry.Kind.FFI_LOAN));
                                    entered.countDown();
                                    return OresScheduler.await(awaiting);
                                }
                                if (state == 2) {
                                    assertTrue(OresScheduler.cancellationCleanupTurn());
                                    cleanupTicket.set(context.runtime()
                                            .registerCurrentActorPhysicalReceipt(
                                                    ActorPhysicalRetirementRegistry.Kind.ASYNC_ITERATOR));
                                    cleanupEntered.countDown();
                                    return OresScheduler.await(cleanupStage);
                                }
                                return OresScheduler.done(null);
                            }
                        }));
            });
            actor.send("begin");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS),
                    "authentic scheduler cleanup must retain registration authority");
            assertEquals(actor.id(), cleanupTicket.get().actorId());
            assertEquals(actor.generation(), cleanupTicket.get().generation());
            assertFalse(actor.done().isDone());
            cleanupStage.completeFromRuntime(17);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!sourceResult.get().isDone() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(sourceResult.get().isDone());
            assertFalse(actor.done().isDone(), "finished defer is not native loan release");
            assertTrue(priorTicket.get().completeAfterPhysicalRelease());
            assertFalse(actor.done().isDone(), "cleanup-created iterator loan still outstanding");
            assertTrue(cleanupTicket.get().completeAfterPhysicalRelease());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void finalCleanupMayRegisterPhysicalCloseWithoutPrematureDone() throws Exception {
        var cleanupRegistered = new CountDownLatch(1);
        var ticket = new AtomicReference<ActorPhysicalRetirementRegistry.Receipt>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) ->
                    context.runtime().endCurrentActorWithCleanup(() -> {
                        ticket.set(context.runtime().registerCurrentActorPhysicalReceipt(
                                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE));
                        cleanupRegistered.countDown();
                    }));
            actor.send("end");
            assertTrue(cleanupRegistered.await(3, TimeUnit.SECONDS));
            assertFalse(actor.done().isDone());
            assertTrue(ticket.get().completeAfterPhysicalRelease());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            actor.done().get(3, TimeUnit.SECONDS);
        }
    }

    @Test void failedPhysicalReleaseIsStickyAndCannotBeReclassifiedAsClean() {
        var signals = new AtomicInteger();
        var owner = ActorRuntime.ActorId.create();
        var registry = new ActorPhysicalRetirementRegistry(owner, 22,
                signals::incrementAndGet);
        var ticket = registry.register(
                ActorPhysicalRetirementRegistry.Kind.MUTEX_HANDOFF, false);
        assertEquals(owner, ticket.actorId());
        assertEquals(22L, ticket.generation());
        registry.seal();
        assertThrows(IllegalStateException.class,
                () -> registry.register(ActorPhysicalRetirementRegistry.Kind.OTHER, false));
        var cleanupTicket = registry.register(
                ActorPhysicalRetirementRegistry.Kind.ASYNC_ITERATOR, true);
        var failure = new IllegalStateException("native close did not complete");
        assertTrue(ticket.failPhysicalRelease(failure));
        assertFalse(ticket.failPhysicalRelease(new IllegalStateException("duplicate")));
        assertFalse(ticket.completeAfterPhysicalRelease());
        assertEquals(2, registry.snapshot().pending());
        assertEquals(Integer.valueOf(1), registry.snapshot().byKind().get(
                ActorPhysicalRetirementRegistry.Kind.MUTEX_HANDOFF));
        assertEquals(Integer.valueOf(1), registry.snapshot().byKind().get(
                ActorPhysicalRetirementRegistry.Kind.ASYNC_ITERATOR));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.snapshot().byKind().put(
                        ActorPhysicalRetirementRegistry.Kind.OTHER, 1));
        assertSame(failure, registry.snapshot().failure());
        assertTrue(cleanupTicket.completeAfterPhysicalRelease());
        assertEquals(1, registry.snapshot().pending());
        assertThrows(IllegalStateException.class, registry::retireVerified,
                "the failed native loan must keep the arena quarantined");
        assertEquals(2, signals.get(),
                "failure and successful retirement each notify lifecycle once");
    }

    @Test void concurrentlySettledPhysicalTicketCannotUnderflow() throws Exception {
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 1, () -> { });
        var ticket = registry.register(ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE,
                false);
        var entered = new CountDownLatch(2);
        var begin = new CountDownLatch(1);
        var success = new AtomicInteger();
        Runnable settle = () -> {
            entered.countDown();
            try {
                if (!begin.await(3, TimeUnit.SECONDS)) {
                    throw new AssertionError("test barrier was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            if (ticket.completeAfterPhysicalRelease()) success.incrementAndGet();
        };
        Thread a = Thread.ofVirtual().start(settle);
        Thread b = Thread.ofVirtual().start(settle);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        begin.countDown();
        a.join(3_000);
        b.join(3_000);
        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
        assertEquals(1, success.get());
        assertEquals(0, registry.snapshot().pending());
        registry.seal();
        registry.retireVerified();
        assertTrue(registry.snapshot().retired());
        assertThrows(IllegalStateException.class, () ->
                registry.register(ActorPhysicalRetirementRegistry.Kind.OTHER, true),
                "late physical receipt must not resurrect a finalized generation");
    }
}
