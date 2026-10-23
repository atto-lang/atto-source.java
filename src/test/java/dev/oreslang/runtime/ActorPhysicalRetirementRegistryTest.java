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
        assertEquals(3, signals.get(),
                "failed release notifies once; successful receipt also wakes after callback retirement");
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
                });
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
        assertEquals(2, notifications.get(), "last receipt must recheck after callback exit");
        registry.retireVerified();
        assertTrue(registry.snapshot().retired());
    }

    @Test void throwingHostNotificationFailsClosedInsteadOfHidingAReleaseFailure() {
        var failure = new IllegalStateException("native retirement notifier failed");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 1, () -> { throw failure; });
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
