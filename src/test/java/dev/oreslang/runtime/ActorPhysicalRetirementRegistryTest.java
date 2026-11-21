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

    @Test void realNioPipeChannelMustActuallyCloseBeforeActorDone()
            throws Exception {
        var pipe = java.nio.channels.Pipe.open();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            var lease = runtime.registerActorPhysicalChannelFromDriver(
                    actor, pipe.source(),
                    ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
            assertTrue(pipe.source().isOpen());
            assertTrue(actor.cancel());
            // STOP now schedules verified native-channel close asynchronously,
            // without any guest actor turn or caller close() invocation.
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertFalse(pipe.source().isOpen(), "STOP must close the real NIO descriptor");
            lease.close(); // idempotent
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.pendingPhysicalRetirementsByKind().isEmpty());
            assertThrows(ActorRuntime.ActorTerminatedException.class,
                    () -> runtime.registerActorPhysicalChannelFromDriver(
                            actor, pipe.sink(),
                            ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE));
        } finally {
            pipe.source().close();
            pipe.sink().close();
        }
    }

    @Test void blockedHostNioCloseCannotReleaseActorArenaEarly()
            throws Exception {
        var enteredClose = new CountDownLatch(1);
        var allowPhysicalRelease = new CountDownLatch(1);
        var open = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.nio.channels.Channel blockingNativeChannel = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return open.get(); }
            @Override public void close() throws java.io.IOException {
                enteredClose.countDown();
                try {
                    if (!allowPhysicalRelease.await(5, TimeUnit.SECONDS)) {
                        throw new java.io.IOException("test close callback deadline");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(interrupted);
                }
                open.set(false);
            }
        };
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            var lease = runtime.registerActorPhysicalChannelFromDriver(
                    actor, blockingNativeChannel,
                    ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
            assertTrue(actor.cancel());
            var failure = new AtomicReference<Throwable>();
            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    lease.close();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            try {
                assertTrue(enteredClose.await(3, TimeUnit.SECONDS));
                assertFalse(actor.done().isDone(),
                        "native close is running but no real physical release has occurred");
                assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS));
                assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                        .get("TCP_HTTP_HANDLE"));
            } finally {
                allowPhysicalRelease.countDown();
            }
            closer.join(3_000);
            assertFalse(closer.isAlive());
            assertNull(failure.get());
            assertFalse(blockingNativeChannel.isOpen());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void concurrentHostClosePublishesExactlyOnePhysicalReceipt()
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var open = new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls = new AtomicInteger();
        java.nio.channels.Channel channel = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return open.get(); }
            @Override public void close() throws java.io.IOException {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new java.io.IOException("test close deadline");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(interrupted);
                }
                open.set(false);
            }
        };
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(
                    () -> (message, context) -> { });
            var lease = runtime.registerActorPhysicalChannelFromDriver(
                    actor, channel, ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
            assertTrue(actor.cancel());
            var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
            Runnable close = () -> {
                try { lease.close(); }
                catch (Throwable t) { failures.add(t); }
            };
            Thread first = Thread.ofVirtual().start(close);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Thread second = Thread.ofVirtual().start(close);
            assertFalse(actor.done().isDone(),
                    "two pending close requests are still not a release receipt");
            release.countDown();
            first.join(3_000);
            second.join(3_000);
            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertTrue(failures.isEmpty(), () -> failures.toString());
            assertEquals(1, calls.get(), "native close must run only once");
            assertFalse(channel.isOpen());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void nioPhysicalCloseFailureQuarantinesActor() throws Exception {
        var closeFailure = new java.io.IOException("mock kernel failed channel close");
        java.nio.channels.Channel brokenChannel = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return true; }
            @Override public void close() throws java.io.IOException {
                throw closeFailure;
            }
        };
        var runtime = new ActorRuntime();
        var actor = runtime.<String>spawnShared(
                () -> (message, context) -> { });
        var lease = runtime.registerActorPhysicalChannelFromDriver(
                actor, brokenChannel,
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
        assertTrue(actor.cancel());
        assertSame(closeFailure, assertThrows(java.io.IOException.class, lease::close));
        assertSame(closeFailure, actor.physicalRetirementFailure().orElseThrow());
        assertFalse(actor.done().isDone());
        assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                .get("TCP_HTTP_HANDLE"));
        assertThrows(IllegalStateException.class, runtime::close,
                "failed real descriptor close may not be silently reported as retirement");
    }

    @Test void actualAsyncFileReadCallbackMustExitAfterStopBeforeActorDone()
            throws Exception {
        var file = java.nio.file.Files.createTempFile("ores-actor-physical-", ".bin");
        java.nio.file.Files.write(file, new byte[] { 9, 8, 7, 6 });
        var callbackEntered = new CountDownLatch(1);
        var allowCallbackExit = new CountDownLatch(1);
        var callbackFailure = new AtomicReference<Throwable>();
        try (var runtime = new ActorRuntime();
             var channel = java.nio.channels.AsynchronousFileChannel.open(
                     file, java.nio.file.StandardOpenOption.READ)) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            runtime.registerActorPhysicalChannelFromDriver(
                    actor, channel, ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
            java.nio.channels.CompletionHandler<Integer, Void> delegate =
                    new java.nio.channels.CompletionHandler<>() {
                        private void drain() {
                            callbackEntered.countDown();
                            try {
                                if (!allowCallbackExit.await(5, TimeUnit.SECONDS)) {
                                    callbackFailure.set(new IllegalStateException(
                                            "native read callback exit was withheld"));
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                callbackFailure.set(interrupted);
                            }
                        }
                        @Override public void completed(Integer n, Void ignored) { drain(); }
                        @Override public void failed(Throwable t, Void ignored) { drain(); }
                    };
            var receipt = runtime.<Integer, Void>registerActorIoCompletionFromDriver(
                    actor, ActorPhysicalRetirementRegistry.Kind.OTHER, delegate);
            var bytes = java.nio.ByteBuffer.allocate(4);
            channel.read(bytes, 0L, null, receipt);
            try {
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
                assertTrue(actor.cancel());
                assertFalse(actor.done().isDone(),
                        "Channel.close is not an in-flight read callback exit");
                assertEquals(Integer.valueOf(1),
                        actor.pendingPhysicalRetirementsByKind().get("OTHER"));
                assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS));
            } finally {
                allowCallbackExit.countDown();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertFalse(channel.isOpen());
            assertTrue(actor.pendingPhysicalRetirementsByKind().isEmpty());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @Test void failingNativeCallbackKeepsActorQuarantined() throws Exception {
        var runtime = new ActorRuntime();
        var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
        var callbackError = new IllegalStateException("host callback retained native pointer");
        java.nio.channels.CompletionHandler<Integer, Void> broken =
                new java.nio.channels.CompletionHandler<>() {
                    @Override public void completed(Integer n, Void ignored) {
                        throw callbackError;
                    }
                    @Override public void failed(Throwable t, Void ignored) {
                        throw callbackError;
                    }
                };
        var callback = runtime.<Integer, Void>registerActorIoCompletionFromDriver(
                actor, ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, broken);
        assertTrue(actor.cancel());
        assertSame(callbackError, assertThrows(IllegalStateException.class,
                () -> callback.completed(1, null)));
        assertSame(callbackError, actor.physicalRetirementFailure().orElseThrow());
        assertEquals(Integer.valueOf(1),
                actor.pendingPhysicalRetirementsByKind().get("FFI_LOAN"));
        assertFalse(actor.done().isDone());
        assertThrows(IllegalStateException.class, runtime::close);
    }

    @Test void exhaustedHostCloseQueueQuarantinesInsteadOfFreeingNativeLease() {
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 8L, () -> { }, () -> { });
        var ticket = registry.register(
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        java.nio.channels.Channel channel = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return true; }
            @Override public void close() {
                throw new AssertionError("rejected close must never run");
            }
        };
        var lease = new ActorPhysicalChannelLease(channel, ticket, ignored -> { });
        var saturation = new java.util.concurrent.RejectedExecutionException(
                "bounded close lane saturated");
        registry.seal();
        lease.requestCloseOnActorStop(command -> { throw saturation; });
        assertSame(saturation, registry.snapshot().failure());
        assertEquals(1, registry.snapshot().pending());
        assertEquals(0, registry.snapshot().inFlightNotifications());
        assertThrows(IllegalStateException.class, registry::retireVerified,
                "rejected host cleanup cannot retire a still-open channel");
        lease.requestCloseOnActorStop(Runnable::run); // exactly-once request
        assertEquals(1, registry.snapshot().pending());
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

    @Test void failedPhysicalLoanQuarantinesActualActorAndRuntimeCloseFailsClosed()
            throws Exception {
        // This test deliberately leaves one actor unretired; its runtime
        // close MUST report the quarantine instead of freeing the arena.
        var runtime = new ActorRuntime();
        var ticket = new AtomicReference<ActorPhysicalRetirementRegistry.Receipt>();
        var started = new CountDownLatch(1);
        var actor = runtime.<String>spawnShared(() -> (message, context) -> {
            ticket.set(context.runtime().registerCurrentActorPhysicalReceipt(
                    ActorPhysicalRetirementRegistry.Kind.FFI_LOAN));
            started.countDown();
        });
        actor.send("claim");
        assertTrue(started.await(3, TimeUnit.SECONDS));
        assertTrue(actor.cancel());
        var nativeFailure = new IllegalStateException("native handle still leased");
        assertTrue(ticket.get().failPhysicalRelease(nativeFailure));
        assertFalse(ticket.get().completeAfterPhysicalRelease(),
                "failed physical close cannot later masquerade as successful settlement");
        assertSame(nativeFailure, actor.physicalRetirementFailure().orElseThrow());
        assertEquals(Integer.valueOf(1),
                actor.pendingPhysicalRetirementsByKind().get("FFI_LOAN"));
        assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS));
        assertFalse(actor.done().isDone());
        assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                actor.failure().orElseThrow());
        assertThrows(IllegalStateException.class, runtime::close,
                "runtime close must fail rather than silently free a quarantined actor");
    }

    @Test void failedPhysicalReleaseIsStickyAndCannotBeReclassifiedAsClean() {
        var signals = new AtomicInteger();
        var owner = ActorRuntime.ActorId.create();
        var registry = new ActorPhysicalRetirementRegistry(owner, 22,
                signals::incrementAndGet, () -> { });
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
                "one notifier per physical event; finalization handoff is independent");
    }

    @Test void removedTicketCannotRetireWhileHostNotificationIsBlocked()
            throws Exception {
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var notifications = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 44, () -> {
                    if (notifications.incrementAndGet() != 1) return;
                    callbackEntered.countDown();
                    try {
                        if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "test stalled physical notification");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }, () -> { });
        var ticket = registry.register(
                ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
        registry.seal();
        var failure = new AtomicReference<Throwable>();
        Thread settler = Thread.ofVirtual().start(() -> {
            try {
                ticket.completeAfterPhysicalRelease();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
            assertEquals(0, registry.snapshot().pending());
            assertEquals(1, registry.snapshot().inFlightNotifications());
            assertThrows(IllegalStateException.class, registry::retireVerified,
                    "last ticket's removal is not a host callback retirement");
        } finally {
            releaseCallback.countDown();
        }
        settler.join(3_000);
        assertFalse(settler.isAlive());
        assertNull(failure.get());
        assertEquals(0, registry.snapshot().inFlightNotifications());
        assertEquals(1, notifications.get(), "physical notifier must not be invoked twice");
        registry.retireVerified();
        assertTrue(registry.snapshot().retired());
    }

    @Test void throwingHostNotificationFailsClosedInsteadOfHidingAReleaseFailure() {
        var failure = new IllegalStateException("native retirement notifier failed");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 1, () -> { throw failure; }, () -> { });
        var ticket = registry.register(
                ActorPhysicalRetirementRegistry.Kind.OTHER, false);
        registry.seal();
        assertSame(failure, assertThrows(IllegalStateException.class,
                ticket::completeAfterPhysicalRelease));
        assertEquals(0, registry.snapshot().pending());
        assertEquals(0, registry.snapshot().inFlightNotifications());
        assertSame(failure, registry.snapshot().failure());
        assertThrows(IllegalStateException.class, registry::retireVerified,
                "notifier failure must quarantine even when a handle physically closed");
    }

    @Test void concurrentlySettledPhysicalTicketCannotUnderflow() throws Exception {
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 1, () -> { }, () -> { });
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

    @Test void rejectedStopCloseWithThrowingNotifierDoesNotSkipSiblingClosures() {
        var notifierFailure = new IllegalStateException("actor retirement observer failed");
        var rejected = new java.util.concurrent.RejectedExecutionException(
                "close executor queue is saturated");
        var broken = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 1, () -> { throw notifierFailure; }, () -> { });
        var brokenReceipt = broken.register(
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        java.nio.channels.Channel stuck = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return true; }
            @Override public void close() {
                throw new AssertionError("rejected close must not execute");
            }
        };
        var failedLease = new ActorPhysicalChannelLease(stuck, brokenReceipt,
                ignored -> { });

        var good = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 2, () -> { }, () -> { });
        var goodReceipt = good.register(
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        var actuallyClosed = new java.util.concurrent.atomic.AtomicBoolean();
        java.nio.channels.Channel working = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return !actuallyClosed.get(); }
            @Override public void close() { actuallyClosed.set(true); }
        };
        var nextLease = new ActorPhysicalChannelLease(working, goodReceipt,
                ignored -> { });
        broken.seal();
        good.seal();

        // Simulates the sequential STOP loop: a notification failure on one
        // executor rejection may not prevent attempting the next close.
        assertDoesNotThrow(() -> {
            failedLease.requestCloseOnActorStop(command -> { throw rejected; });
            nextLease.requestCloseOnActorStop(Runnable::run);
        });
        assertTrue(actuallyClosed.get(), "remaining channel closure was skipped");
        assertEquals(0, good.snapshot().pending());
        assertSame(rejected, broken.snapshot().failure());
        assertEquals(1, broken.snapshot().pending());
        assertArrayEquals(new Throwable[] { notifierFailure }, rejected.getSuppressed());
        assertThrows(IllegalStateException.class, broken::retireVerified);
        good.retireVerified();
    }

    @Test void throwingSecondChannelProbeAfterRegistrationMustQuarantineActor() {
        var secondProbeFailed = new IllegalStateException(
                "host channel closed during admission");
        var probes = new AtomicInteger();
        java.nio.channels.Channel badProbe = new java.nio.channels.Channel() {
            @Override public boolean isOpen() {
                if (probes.incrementAndGet() == 2) throw secondProbeFailed;
                return true;
            }
            @Override public void close() { }
        };
        var runtime = new ActorRuntime();
        var actor = runtime.<String>spawnShared(
                () -> (message, context) -> { });
        assertSame(secondProbeFailed, assertThrows(IllegalStateException.class,
                () -> runtime.registerActorPhysicalChannelFromDriver(
                        actor, badProbe,
                        ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE)));
        assertEquals(2, probes.get());
        assertTrue(actor.cancel());
        assertSame(secondProbeFailed, actor.physicalRetirementFailure().orElseThrow());
        assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                .get("TCP_HTTP_HANDLE"));
        assertFalse(actor.done().isDone(), "unproven channel handoff cannot permit DONE");
        assertThrows(IllegalStateException.class, runtime::close,
                "host probe exceptions after ticket admission must fail closed");
    }

    @Test void throwingCloseNotifierMustPreserveOriginalIoFailure() {
        var observerFailure = new IllegalStateException("owner notifier");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 9, () -> { throw observerFailure; }, () -> { });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        var kernelFailure = new java.io.IOException("close failed");
        java.nio.channels.Channel channel = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return true; }
            @Override public void close() throws java.io.IOException {
                throw kernelFailure;
            }
        };
        var lease = new ActorPhysicalChannelLease(channel, receipt, ignored -> { });
        registry.seal();
        assertSame(kernelFailure, assertThrows(java.io.IOException.class, lease::close));
        assertSame(kernelFailure, registry.snapshot().failure());
        assertArrayEquals(new Throwable[] { observerFailure },
                kernelFailure.getSuppressed());
        assertEquals(1, registry.snapshot().pending());
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

    @Test void nativeCallbackExceptionMustRemainPrimaryWhenRetirementObserverThrows() {
        for (boolean failedPath : new boolean[] { false, true }) {
            var callbackFailure = new IllegalStateException("native delegate failed");
            var observerFailure = new IllegalStateException("retirement observer failed");
            var registry = new ActorPhysicalRetirementRegistry(
                    ActorRuntime.ActorId.create(), 77L,
                    () -> { throw observerFailure; }, () -> { });
            var receipt = registry.register(
                    ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
            java.nio.channels.CompletionHandler<Integer, Void> broken =
                    new java.nio.channels.CompletionHandler<>() {
                        @Override public void completed(Integer count, Void ignored) {
                            throw callbackFailure;
                        }
                        @Override public void failed(Throwable error, Void ignored) {
                            throw callbackFailure;
                        }
                    };
            var callback = new ActorPhysicalIoCallbackLease<>(receipt, broken);
            registry.seal();
            assertSame(callbackFailure, assertThrows(IllegalStateException.class,
                    () -> {
                        if (failedPath) callback.failed(new java.io.IOException("kernel"), null);
                        else callback.completed(1, null);
                    }));
            assertSame(callbackFailure, registry.snapshot().failure());
            assertArrayEquals(new Throwable[] { observerFailure },
                    callbackFailure.getSuppressed());
            assertEquals(1, registry.snapshot().pending());
            assertThrows(IllegalStateException.class, registry::retireVerified);
        }
    }

    @Test void malformedNullNativeFailureMustQuarantineAndNeverReachDelegate() {
        var invoked = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 78L, () -> { }, () -> { });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        java.nio.channels.CompletionHandler<Integer, Void> delegate =
                new java.nio.channels.CompletionHandler<>() {
                    @Override public void completed(Integer result, Void ignored) {
                        invoked.incrementAndGet();
                    }
                    @Override public void failed(Throwable error, Void ignored) {
                        invoked.incrementAndGet();
                    }
                };
        var callback = new ActorPhysicalIoCallbackLease<>(receipt, delegate);
        registry.seal();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> callback.failed(null, null));
        assertSame(failure, registry.snapshot().failure());
        assertEquals(0, invoked.get());
        assertEquals(1, registry.snapshot().pending());
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

    @Test void uncertainSubmissionFailureMustNotBeMaskedByBrokenOwnerObserver() {
        var observerFailure = new IllegalStateException("retirement observer failed");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 79L,
                () -> { throw observerFailure; }, () -> { });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.OTHER, false);
        var callback = new ActorPhysicalIoCallbackLease<Integer, Void>(
                receipt, new java.nio.channels.CompletionHandler<>() {
                    @Override public void completed(Integer result, Void ignored) { }
                    @Override public void failed(Throwable error, Void ignored) { }
                });
        registry.seal();
        var submissionFailure = new java.util.concurrent.RejectedExecutionException(
                "native submission ownership uncertain");
        assertDoesNotThrow(() ->
                callback.admissionFailedWithoutPhysicalProof(submissionFailure));
        assertSame(submissionFailure, registry.snapshot().failure());
        assertArrayEquals(new Throwable[] { observerFailure },
                submissionFailure.getSuppressed());
        assertEquals(1, registry.snapshot().pending());
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }
    @Test void verifiedNioCloseIsAlreadyVisibleToReentrantOwnerCallback()
            throws Exception {
        var closeCalls = new AtomicInteger();
        var open = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.nio.channels.Channel underlying = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return open.get(); }
            @Override public void close() {
                closeCalls.incrementAndGet();
                open.set(false);
            }
        };
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 12, () -> { }, () -> { });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        var observedClose = new java.util.concurrent.atomic.AtomicReference<
                ActorPhysicalChannelLease>();
        var entered = new AtomicInteger();
        var lease = new ActorPhysicalChannelLease(underlying, receipt, target -> {
            observedClose.set(target);
            entered.incrementAndGet();
            try {
                // Owner bookkeeping is synchronous. When it calls back into
                // close(), the real descriptor is already physically closed.
                target.close();
            } catch (java.io.IOException failure) {
                throw new AssertionError("reentrant verified close failed", failure);
            }
        });
        registry.seal();
        lease.close();
        lease.close();
        assertSame(lease, observedClose.get());
        assertEquals(1, entered.get(), "one close => one detach");
        assertEquals(1, closeCalls.get(), "must not close underlying twice");
        assertEquals(0, registry.snapshot().pending());
        registry.retireVerified();
    }

    @Test void throwingNioDetachRetainsQuarantineEvenAfterPhysicalClose()
            throws Exception {
        var failure = new IllegalStateException(
                "host handle-detach callback was unable to release ownership");
        var open = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.nio.channels.Channel underlying = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return open.get(); }
            @Override public void close() { open.set(false); }
        };
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 13, () -> { }, () -> { });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        var lease = new ActorPhysicalChannelLease(underlying, receipt,
                ignored -> { throw failure; });
        registry.seal();
        assertSame(failure, assertThrows(IllegalStateException.class, lease::close));
        assertFalse(underlying.isOpen(), "physical close already occurred");
        assertSame(failure, registry.snapshot().failure());
        assertEquals(1, registry.snapshot().pending(),
                "failed bookkeeping must not masquerade as actor retirement");
        lease.close(); // a physically closed channel is not closed twice
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

    @Test void channelRegistrationRacingWithStopDoesNotLeakNativeDescriptor()
            throws Exception {
        for (int i = 0; i < 8; i++) {
            var pipe = java.nio.channels.Pipe.open();
            try (var runtime = new ActorRuntime()) {
                var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
                var barrier = new CountDownLatch(1);
                var lease = new AtomicReference<ActorPhysicalChannelLease>();
                var error = new AtomicReference<Throwable>();
                Thread driver = Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        lease.set(runtime.registerActorPhysicalChannelFromDriver(
                                actor, pipe.source(),
                                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE));
                    } catch (Throwable failure) {
                        error.set(failure);
                    }
                });
                barrier.countDown();
                actor.cancel();
                driver.join(3_000);
                assertFalse(driver.isAlive());
                if (lease.get() == null) {
                    assertInstanceOf(ActorRuntime.ActorTerminatedException.class,
                            error.get());
                    pipe.source().close();
                } else {
                    assertNull(error.get());
                }
                assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
                assertFalse(pipe.source().isOpen());
            } finally {
                pipe.source().close();
                pipe.sink().close();
            }
        }
    }
    @Test void oneFailedChannelCloseStillRequestsPhysicalReleaseForSibling()
            throws Exception {
        // STOP must dispatch cleanup for EVERY host channel even if one
        // driver fails. A broken descriptor is quarantined; the sibling
        // cannot be abandoned merely because another close threw.
        var good = java.nio.channels.Pipe.open();
        var closeFailure = new java.io.IOException("broken NIO close");
        var brokenCloseCalled = new CountDownLatch(1);
        java.nio.channels.Channel broken = new java.nio.channels.Channel() {
            @Override public boolean isOpen() { return true; }
            @Override public void close() throws java.io.IOException {
                brokenCloseCalled.countDown();
                throw closeFailure;
            }
        };
        var runtime = new ActorRuntime();
        try {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            runtime.registerActorPhysicalChannelFromDriver(
                    actor, broken, ActorPhysicalRetirementRegistry.Kind.FFI_LOAN);
            runtime.registerActorPhysicalChannelFromDriver(
                    actor, good.source(), ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE);
            assertTrue(actor.cancel());
            assertTrue(brokenCloseCalled.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (good.source().isOpen() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(good.source().isOpen(),
                    "healthy sibling channel must be physically closed despite failure");
            assertSame(closeFailure, actor.physicalRetirementFailure().orElseThrow());
            assertEquals(Integer.valueOf(1),
                    actor.pendingPhysicalRetirementsByKind().get("FFI_LOAN"));
            assertFalse(actor.done().isDone(),
                    "failed sibling retains actor and native memory quarantine");
            assertThrows(IllegalStateException.class, runtime::close);
        } finally {
            good.source().close();
            good.sink().close();
        }
    }

    @Test void duplicateNativeCallbackDuringFirstDelegateMustQuarantineBothPaths()
            throws Exception {
        for (boolean useFailedPath : new boolean[] { false, true }) {
            var firstEntered = new CountDownLatch(1);
            var allowFirstExit = new CountDownLatch(1);
            var delegateCalls = new AtomicInteger();
            var threadFailure = new AtomicReference<Throwable>();
            var registry = new ActorPhysicalRetirementRegistry(
                    ActorRuntime.ActorId.create(), useFailedPath ? 81L : 80L, () -> { }, () -> { });
            var receipt = registry.register(
                    ActorPhysicalRetirementRegistry.Kind.OTHER, false);
            java.nio.channels.CompletionHandler<Integer, Void> delegate =
                    new java.nio.channels.CompletionHandler<>() {
                        private void entered() {
                            delegateCalls.incrementAndGet();
                            firstEntered.countDown();
                            try {
                                if (!allowFirstExit.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "native callback did not receive exit signal");
                                }
                            } catch (InterruptedException interruption) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interruption);
                            }
                        }
                        @Override public void completed(Integer result, Void ignored) {
                            entered();
                        }
                        @Override public void failed(Throwable error, Void ignored) {
                            entered();
                        }
                    };
            var callback = new ActorPhysicalIoCallbackLease<>(receipt, delegate);
            registry.seal();
            Thread winner = Thread.ofVirtual().start(() -> {
                try {
                    if (useFailedPath) {
                        callback.failed(new java.io.IOException("real completion failed"), null);
                    } else {
                        callback.completed(1, null);
                    }
                } catch (Throwable error) {
                    threadFailure.set(error);
                }
            });
            try {
                assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
                var duplicate = assertThrows(IllegalStateException.class,
                        () -> {
                            if (useFailedPath) {
                                callback.failed(new java.io.IOException("duplicate"), null);
                            } else {
                                callback.completed(2, null);
                            }
                        });
                assertEquals("duplicate native I/O callback", duplicate.getMessage());
                assertSame(duplicate, registry.snapshot().failure(),
                        "duplicate before physical callback exit must quarantine receipt");
                assertEquals(1, registry.snapshot().pending());
                assertThrows(IllegalStateException.class, registry::retireVerified);
            } finally {
                allowFirstExit.countDown();
            }
            winner.join(3_000);
            assertFalse(winner.isAlive());
            assertNull(threadFailure.get(), "original callback may finish bookkeeping");
            assertEquals(1, delegateCalls.get(), "second native callback must not dispatch");
            assertEquals(1, registry.snapshot().pending(),
                    "an earlier successful delegate cannot undo duplicate quarantine");
            assertThrows(IllegalStateException.class, registry::retireVerified);
        }
    }

    @Test void duplicateCallbackDuringPhysicalExecutionQuarantinesNativeLoan()
            throws Exception {
        // An asynchronous provider must not notify the same operation twice.
        // If it does while the first callback remains physically executing,
        // the actor must never receive a clean retirement receipt.
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var callbackCount = new AtomicInteger();
        var runtime = new ActorRuntime();
        var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
        java.nio.channels.CompletionHandler<Integer, Void> delegate =
                new java.nio.channels.CompletionHandler<>() {
                    @Override public void completed(Integer value, Void ignored) {
                        callbackCount.incrementAndGet();
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("held native callback expired");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                    @Override public void failed(Throwable failure, Void ignored) {
                        throw new AssertionError("unexpected failed operation", failure);
                    }
                };
        var io = runtime.<Integer, Void>registerActorIoCompletionFromDriver(
                actor, ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, delegate);
        Thread initial = Thread.ofVirtual().start(() -> io.completed(4, null));
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertThrows(IllegalStateException.class, () -> io.completed(5, null));
            assertTrue(actor.physicalRetirementFailure().isPresent());
            assertFalse(actor.done().isDone());
            assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                    .get("FFI_LOAN"));
        } finally {
            release.countDown();
        }
        initial.join(3_000);
        assertFalse(initial.isAlive());
        assertEquals(1, callbackCount.get(), "duplicate callback cannot reenter delegate");
        assertFalse(actor.done().isDone());
        assertThrows(IllegalStateException.class, runtime::close);
    }


    @Test void finalPhysicalNotifierMustExitBeforeFinalizationHandoff() throws Exception {
        var entered = new CountDownLatch(1);
        var allowExit = new CountDownLatch(1);
        var notifierActive = new java.util.concurrent.atomic.AtomicBoolean();
        var notifications = new AtomicInteger();
        var finalizations = new AtomicInteger();
        var registryRef = new AtomicReference<ActorPhysicalRetirementRegistry>();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 910L,
                () -> {
                    notifierActive.set(true);
                    notifications.incrementAndGet();
                    entered.countDown();
                    try {
                        assertTrue(allowExit.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    } finally {
                        notifierActive.set(false);
                    }
                },
                () -> {
                    assertFalse(notifierActive.get(),
                            "DONE handoff may not overlap the last physical notifier");
                    registryRef.get().retireVerified();
                    finalizations.incrementAndGet();
                });
        registryRef.set(registry);
        var ticket = registry.register(ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
        registry.seal();
        var failure = new AtomicReference<Throwable>();
        Thread driver = Thread.ofVirtual().start(() -> {
            try { assertTrue(ticket.completeAfterPhysicalRelease()); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(1, registry.snapshot().inFlightNotifications());
            assertEquals(0, registry.snapshot().pending());
            assertThrows(IllegalStateException.class, registry::retireVerified);
            assertEquals(0, finalizations.get());
        } finally {
            allowExit.countDown();
        }
        driver.join(3_000);
        assertFalse(driver.isAlive());
        assertNull(failure.get());
        assertEquals(1, notifications.get(), "never notify twice");
        assertEquals(1, finalizations.get(), "exactly one post-exit handoff");
        assertTrue(registry.snapshot().retired());
    }

    @Test void notifierFailureBeforeLastHandoffMustQuarantine() {
        var notifierFailure = new IllegalStateException("notifier failed before exit");
        var finalizations = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 911L,
                () -> { throw notifierFailure; },
                finalizations::incrementAndGet);
        var ticket = registry.register(ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
        registry.seal();
        assertSame(notifierFailure, assertThrows(IllegalStateException.class,
                ticket::completeAfterPhysicalRelease));
        assertSame(notifierFailure, registry.snapshot().failure());
        assertEquals(0, registry.snapshot().inFlightNotifications());
        assertEquals(0, finalizations.get(), "failed notifier cannot authorize DONE");
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

    @Test void competingPhysicalNotifiersWaitForBothExits() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var finalizations = new AtomicInteger();
        var registryRef = new AtomicReference<ActorPhysicalRetirementRegistry>();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 912L,
                () -> {
                    active.incrementAndGet();
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    } finally {
                        active.decrementAndGet();
                    }
                },
                () -> {
                    assertEquals(0, active.get(),
                            "last physical callback must exit before finalization");
                    registryRef.get().retireVerified();
                    finalizations.incrementAndGet();
                });
        registryRef.set(registry);
        var first = registry.register(ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
        var second = registry.register(ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        registry.seal();
        var failure = new AtomicReference<Throwable>();
        Thread a = Thread.ofVirtual().start(() -> {
            try { assertTrue(first.completeAfterPhysicalRelease()); }
            catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        });
        Thread b = Thread.ofVirtual().start(() -> {
            try { assertTrue(second.completeAfterPhysicalRelease()); }
            catch (Throwable thrown) { failure.compareAndSet(null, thrown); }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(2, registry.snapshot().inFlightNotifications());
            assertEquals(0, finalizations.get());
        } finally {
            release.countDown();
        }
        a.join(3_000);
        b.join(3_000);
        assertFalse(a.isAlive());
        assertFalse(b.isAlive());
        assertNull(failure.get());
        assertEquals(1, finalizations.get());
        assertTrue(registry.snapshot().retired());
    }

    @Test void failingFinalizationHandoffDoesNotClaimCleanRetirement() {
        var failure = new IllegalStateException("finalization handoff rejected");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 913L,
                () -> { },
                () -> { throw failure; });
        var ticket = registry.register(ActorPhysicalRetirementRegistry.Kind.OTHER, false);
        registry.seal();
        assertSame(failure, assertThrows(IllegalStateException.class,
                ticket::completeAfterPhysicalRelease));
        assertSame(failure, registry.snapshot().failure());
        assertFalse(registry.snapshot().retired());
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }


    @Test void concurrentStopCannotOvertakeFailedFinalizationHandoff()
            throws Exception {
        var handoffEntered = new CountDownLatch(1);
        var allowHandoffFailure = new CountDownLatch(1);
        var failure = new IllegalStateException("finalization handoff failed");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 914L,
                () -> { },
                () -> {
                    handoffEntered.countDown();
                    try {
                        assertTrue(allowHandoffFailure.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interruption);
                    }
                    throw failure;
                });
        var receipt = registry.register(ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
        registry.seal();
        var actualFailure = new AtomicReference<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                receipt.completeAfterPhysicalRelease();
            } catch (Throwable throwable) {
                actualFailure.set(throwable);
            }
        });
        try {
            assertTrue(handoffEntered.await(3, TimeUnit.SECONDS));
            var snapshot = registry.snapshot();
            assertEquals(0, snapshot.pending());
            assertEquals(0, snapshot.inFlightNotifications(),
                    "physical notifier exited before the handoff began");
            assertTrue(snapshot.finalizationHandoffInFlight(),
                    "the finalization handoff must remain reserved");
            assertFalse(registry.isCurrentFinalizationHandoffThread(),
                    "a foreign STOP caller must not impersonate the handoff owner");
            assertThrows(IllegalStateException.class, registry::retireVerified,
                    "a second STOP must not publish DONE before handoff failure");
            assertFalse(registry.snapshot().retired());
        } finally {
            allowHandoffFailure.countDown();
        }
        worker.join(3_000);
        assertFalse(worker.isAlive());
        assertSame(failure, actualFailure.get());
        assertSame(failure, registry.snapshot().failure());
        assertFalse(registry.snapshot().finalizationHandoffInFlight());
        assertFalse(registry.snapshot().retired());
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

}
