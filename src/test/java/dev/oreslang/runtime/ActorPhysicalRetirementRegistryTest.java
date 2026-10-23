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

    @Test void actorOwnedNativeIoBlocksDoneThroughWorkerAndTerminalSubscriberExit()
            throws Exception {
        var entered = new CountDownLatch(1);
        var letWorkerExit = new CountDownLatch(1);
        var subscriberEntered = new CountDownLatch(1);
        var letSubscriberExit = new CountDownLatch(1);
        var admitted = new CountDownLatch(1);
        var operationRef = new AtomicReference<OresFuture<Integer>>();
        try (var io = new AsyncNativeIo(1); var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                var operation = io.submitCurrentActor(context.runtime(), "owned-physical-io", () -> {
                    entered.countDown();
                    while (letWorkerExit.getCount() != 0) {
                        try { letWorkerExit.await(20, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* uninterruptible native loan */ }
                    }
                    return 42;
                });
                operation.whenCompleteRuntime((value, failure) -> {
                    subscriberEntered.countDown();
                    while (letSubscriberExit.getCount() != 0) {
                        try { letSubscriberExit.await(20, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* retained native notifier */ }
                    }
                });
                operationRef.set(operation);
                admitted.countDown();
            });
            try {
                actor.send("begin");
                assertTrue(admitted.await(3, TimeUnit.SECONDS));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                        .get("NATIVE_IO_OPERATION"));
                assertTrue(actor.cancel());
                assertFalse(actor.done().isDone(),
                        "STOP must not release an actor arena while its worker owns a loan");
                io.close();
                assertFalse(operationRef.get().isDone());
                letWorkerExit.countDown();
                assertTrue(subscriberEntered.await(3, TimeUnit.SECONDS));
                assertFalse(actor.done().isDone(),
                        "visible terminal Future must not bypass blocked native subscriber");
                assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                        .get("NATIVE_IO_OPERATION"));
            } finally {
                letWorkerExit.countDown();
                letSubscriberExit.countDown();
                io.close();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, actor.pendingPhysicalRetirementsByKind().size());
            assertTrue(actor.physicalRetirementFailure().isEmpty());
            assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void failedNativeIoTerminalCallbackQuarantinesActualActorIncarnation()
            throws Exception {
        // This test intentionally leaves a broken actor unretired.
        var executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        var io = new AsyncNativeIo(1, executor);
        var runtime = new ActorRuntime();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var admitted = new CountDownLatch(1);
        var listenerFailure = new IllegalStateException("native notifier loan failed");
        var actor = runtime.<String>spawnShared(() -> (message, context) -> {
            var work = io.submitCurrentActor(context.runtime(), "native-notifier", () -> {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(20, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* native borrower */ }
                }
                return 5;
            });
            work.whenCompleteRuntime((value, failure) -> { throw listenerFailure; });
            admitted.countDown();
        });
        try {
            actor.send("start");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            io.close();
        } finally {
            release.countDown();
        }
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        assertSame(listenerFailure, io.physicalShutdownFailure());
        assertSame(listenerFailure, actor.physicalRetirementFailure().orElseThrow());
        assertFalse(actor.done().isDone(),
                "host notifier failure must quarantine the actor's physical arena");
        assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                .get("NATIVE_IO_OPERATION"));
        assertThrows(IllegalStateException.class, runtime::close,
                "runtime cannot free the actor after failed native notifier drain");
    }

    @Test void queuedNeverStartedNativeIoReleasesOnlyItsOwnActorTicket()
            throws Exception {
        var executor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var bothAdmitted = new CountDownLatch(1);
        var neverExecuted = new AtomicInteger();
        try (var io = new AsyncNativeIo(2, executor); var runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                io.submitCurrentActor(context.runtime(), "running", () -> {
                    firstEntered.countDown();
                    while (releaseFirst.getCount() != 0) {
                        try { releaseFirst.await(20, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* physical worker */ }
                    }
                    return 1;
                });
                io.submitCurrentActor(context.runtime(), "queued", () -> {
                    neverExecuted.incrementAndGet();
                    return 2;
                });
                bothAdmitted.countDown();
            });
            try {
                actor.send("begin");
                assertTrue(bothAdmitted.await(3, TimeUnit.SECONDS));
                assertTrue(firstEntered.await(3, TimeUnit.SECONDS));
                assertEquals(Integer.valueOf(2), actor.pendingPhysicalRetirementsByKind()
                        .get("NATIVE_IO_OPERATION"));
                assertTrue(actor.cancel());
                io.close();
                assertEquals(0, neverExecuted.get());
                assertEquals(Integer.valueOf(1), actor.pendingPhysicalRetirementsByKind()
                        .get("NATIVE_IO_OPERATION"),
                        "shutdownNow only retires the genuinely never-started ticket");
                assertFalse(actor.done().isDone());
            } finally {
                releaseFirst.countDown();
                io.close();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, neverExecuted.get());
            assertTrue(actor.physicalRetirementFailure().isEmpty());
            assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void replacementActorMayRetireWhileOldIncarnationStillOwnsNativeIo()
            throws Exception {
        var oldWorkerEntered = new CountDownLatch(1);
        var releaseOldWorker = new CountDownLatch(1);
        var oldAdmitted = new CountDownLatch(1);
        var replacementRan = new CountDownLatch(1);
        try (var io = new AsyncNativeIo(2); var runtime = new ActorRuntime()) {
            var oldActor = runtime.<String>spawnShared(() -> (message, context) -> {
                io.submitCurrentActor(context.runtime(), "old-incarnation-read", () -> {
                    oldWorkerEntered.countDown();
                    while (releaseOldWorker.getCount() != 0) {
                        try { releaseOldWorker.await(20, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* old physical loan */ }
                    }
                    return 17;
                });
                oldAdmitted.countDown();
            });
            try {
                oldActor.send("start");
                assertTrue(oldAdmitted.await(3, TimeUnit.SECONDS));
                assertTrue(oldWorkerEntered.await(3, TimeUnit.SECONDS));
                assertTrue(oldActor.cancel());
                assertEquals(Integer.valueOf(1), oldActor.pendingPhysicalRetirementsByKind()
                        .get("NATIVE_IO_OPERATION"));
                assertFalse(oldActor.done().isDone());
                var replacement = runtime.<String>spawnShared(() -> (message, context) -> {
                    io.submitCurrentActor(context.runtime(), "new-incarnation-io",
                            () -> 23);
                    replacementRan.countDown();
                });
                replacement.send("start");
                assertTrue(replacementRan.await(3, TimeUnit.SECONDS));
                assertTrue(replacement.cancel());
                assertTrue(replacement.awaitTermination(3, TimeUnit.SECONDS),
                        "new incarnation must not borrow the old physical ticket");
                assertNotEquals(oldActor.id(), replacement.id());
                assertFalse(oldActor.done().isDone(),
                        "replacement DONE cannot authorize release of old native memory");
                assertEquals(Integer.valueOf(1), oldActor.pendingPhysicalRetirementsByKind()
                        .get("NATIVE_IO_OPERATION"));
            } finally {
                releaseOldWorker.countDown();
            }
            assertTrue(oldActor.awaitTermination(3, TimeUnit.SECONDS));
            io.close();
            assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
        }
    }

    @Test void nativeIoOwnedReceiptCannotBeMintedFromRootOrForeignActorRuntime()
            throws Exception {
        try (var io = new AsyncNativeIo(1);
             var own = new ActorRuntime();
             var foreign = new ActorRuntime()) {
            assertThrows(SecurityException.class, () ->
                    io.submitCurrentActor(own, "forged", () -> 1));
            var denied = new CountDownLatch(1);
            var actor = own.<String>spawnShared(() -> (message, context) -> {
                assertThrows(SecurityException.class, () ->
                        io.submitCurrentActor(foreign, "foreign", () -> 1));
                denied.countDown();
            });
            actor.send("check");
            assertTrue(denied.await(3, TimeUnit.SECONDS));
            assertEquals(0, actor.pendingPhysicalRetirementsByKind().size());
            assertTrue(actor.cancel());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
        }
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
                ActorRuntime.ActorId.create(), 12, () -> { });
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
                ActorRuntime.ActorId.create(), 13, () -> { });
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
                ActorRuntime.ActorId.create(), 8L, () -> { });
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

    @Test void throwingLastHostWakeupMustLatchFailureAndDenyRegistryRetirement()
            throws Exception {
        var notifications = new AtomicInteger();
        var failure = new IllegalStateException("last host retirement wake failed");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 29, () -> {
                    if (notifications.incrementAndGet() == 2) throw failure;
                });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        registry.seal();
        assertSame(failure, assertThrows(IllegalStateException.class,
                receipt::completeAfterPhysicalRelease));
        assertEquals(2, notifications.get());
        assertSame(failure, registry.snapshot().failure());
        assertFalse(registry.snapshot().retired());
        assertThrows(IllegalStateException.class, registry::retireVerified,
                "the failed last host wake cannot be a successful arena release");
    }

    @Test void failedRuntimeCloseRetainsNativeLoansAndAllowsVerifiedRetry()
            throws Exception {
        // A STOP timeout must not dismantle the physical notifier lane:
        // the late FFI completion needs it to finalize its actor safely.
        var runtime = new ActorRuntime();
        var receipt = new AtomicReference<ActorPhysicalRetirementRegistry.Receipt>();
        var admitted = new CountDownLatch(1);
        var actor = runtime.<String>spawnShared(() -> (message, context) -> {
            receipt.set(context.runtime().registerCurrentActorPhysicalReceipt(
                    ActorPhysicalRetirementRegistry.Kind.FFI_LOAN));
            admitted.countDown();
        });
        try {
            actor.send("register");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            var timeout = assertThrows(IllegalStateException.class, runtime::close);
            assertTrue(timeout.getMessage().contains("quarantined"));
            assertTrue(ActorRuntime.physicallyQuarantined(runtime),
                    "a failed standalone close must retain the exact runtime");
            assertFalse(actor.done().isDone(), "failed global close cannot fabricate DONE");
            assertEquals(Integer.valueOf(1),
                    actor.pendingPhysicalRetirementsByKind().get("FFI_LOAN"));
            assertTrue(receipt.get().completeAfterPhysicalRelease(),
                    "the late physical receipt must still have a live host wake lane");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "native notifier must complete STOP after the first failed close");
            assertDoesNotThrow(runtime::close,
                    "retry should reclaim only after actor and notifier retirement");
            assertFalse(ActorRuntime.physicallyQuarantined(runtime),
                    "only successful verified close may release quarantine root");
        } finally {
            // If the assertions fail, settle the trusted test-only loan to
            // allow eventual cleanup instead of pinning the JUnit process.
            if (receipt.get() != null) receipt.get().completeAfterPhysicalRelease();
            try { runtime.close(); } catch (IllegalStateException ignored) { }
        }
    }

    @Test void actorPostDrainWakeIsDeferredAfterNativeNotifierExit() {
        var queued = new java.util.ArrayDeque<Runnable>();
        var notifications = new AtomicInteger();
        var callbackInFlight = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 51, () -> {
                    callbackInFlight.incrementAndGet();
                    try { notifications.incrementAndGet(); }
                    finally { callbackInFlight.decrementAndGet(); }
                }, queued::add);
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        registry.seal();
        assertTrue(receipt.completeAfterPhysicalRelease());
        assertEquals(1, notifications.get(), "native retirement notifier only runs once");
        assertEquals(0, callbackInFlight.get());
        assertEquals(1, queued.size(), "post-drain actor STOP is a separate host wake");
        assertEquals(1, registry.snapshot().inFlightNotifications(),
                "a queued wake is still an outstanding physical notification fence");
        assertThrows(IllegalStateException.class, registry::retireVerified,
                "STOP may not retire before its queued final wake executes");
        queued.removeFirst().run();
        assertEquals(0, registry.snapshot().inFlightNotifications());
        assertEquals(2, notifications.get());
        registry.retireVerified();
        assertTrue(registry.snapshot().retired());
    }

    @Test void actualActorDoneWaitsForQueuedHostPhysicalWake()
            throws Exception {
        var runtime = new ActorRuntime();
        var releaseLane = new CountDownLatch(1);
        var allLaneWorkers = new CountDownLatch(8);
        var ticket = new AtomicReference<ActorPhysicalRetirementRegistry.Receipt>();
        var admitted = new CountDownLatch(1);
        try {
            // Test-only lane saturation, via reflection: no guest/runtime
            // production accessor for the trusted native-close executor.
            var field = ActorRuntime.class.getDeclaredField("physicalCloseDispatcher");
            field.setAccessible(true);
            var lane = (java.util.concurrent.ThreadPoolExecutor) field.get(runtime);
            for (int i = 0; i < 8; i++) {
                lane.execute(() -> {
                    allLaneWorkers.countDown();
                    while (releaseLane.getCount() != 0) {
                        try { releaseLane.await(20, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* test holds host lane */ }
                    }
                });
            }
            assertTrue(allLaneWorkers.await(3, TimeUnit.SECONDS));
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                ticket.set(context.runtime().registerCurrentActorPhysicalReceipt(
                        ActorPhysicalRetirementRegistry.Kind.FFI_LOAN));
                admitted.countDown();
            });
            actor.send("begin");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertFalse(actor.done().isDone());
            assertTrue(ticket.get().completeAfterPhysicalRelease(),
                    "native ticket is physically released but host wake is queued");
            assertTrue(actor.pendingPhysicalRetirementsByKind().isEmpty(),
                    "the ticket itself must be removed after physical release");
            assertFalse(actor.done().isDone(),
                    "last ticket removal alone cannot publish DONE");
            assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                    "queued host-only STOP wake must run before actor retirement");
            releaseLane.countDown();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
            assertTrue(actor.physicalRetirementFailure().isEmpty());
        } finally {
            releaseLane.countDown();
            if (ticket.get() != null) ticket.get().completeAfterPhysicalRelease();
            try { runtime.close(); } catch (IllegalStateException ignored) { }
        }
    }

    @Test void rejectedPostDrainWakeCannotRaceConcurrentStopRetirement()
            throws Exception {
        var enteredDispatch = new CountDownLatch(1);
        var releaseDispatch = new CountDownLatch(1);
        var notifications = new AtomicInteger();
        var rejection = new java.util.concurrent.RejectedExecutionException(
                "STOP wake dispatch failed after the first notifier returned");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 56, notifications::incrementAndGet,
                wake -> {
                    enteredDispatch.countDown();
                    try {
                        if (!releaseDispatch.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("STOP wake admission did not resume");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    throw rejection;
                });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
        registry.seal();
        var failure = new AtomicReference<Throwable>();
        var settler = Thread.ofVirtual().start(() -> {
            try { receipt.completeAfterPhysicalRelease(); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        try {
            assertTrue(enteredDispatch.await(3, TimeUnit.SECONDS));
            assertEquals(0, registry.snapshot().pending());
            assertEquals(1, registry.snapshot().inFlightNotifications(),
                    "first notifier exit must not drop the wake admission fence");
            assertThrows(IllegalStateException.class, registry::retireVerified,
                    "a concurrent actor STOP cannot overtake rejected host dispatch");
        } finally {
            releaseDispatch.countDown();
        }
        settler.join(3_000);
        assertFalse(settler.isAlive());
        assertSame(rejection, failure.get());
        assertSame(rejection, registry.snapshot().failure());
        assertEquals(0, registry.snapshot().inFlightNotifications());
        assertEquals(1, notifications.get());
        assertThrows(IllegalStateException.class, registry::retireVerified,
                "dispatch rejection permanently quarantines this incarnation");
    }

    @Test void actualNioCompletionWrapperQuarantinesDuplicateAfterRelease()
            throws Exception {
        var queued = new java.util.ArrayDeque<Runnable>();
        var callbackCalls = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 61, () -> { }, queued::add);
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        java.nio.channels.CompletionHandler<Integer, Void> delegate =
                new java.nio.channels.CompletionHandler<>() {
                    @Override public void completed(Integer value, Void ignored) {
                        callbackCalls.incrementAndGet();
                    }
                    @Override public void failed(Throwable failure, Void ignored) {
                        throw new AssertionError(failure);
                    }
                };
        var wrapper = new ActorPhysicalIoCallbackLease<Integer, Void>(receipt, delegate);
        registry.seal();
        wrapper.completed(1, null);
        assertEquals(1, callbackCalls.get());
        assertEquals(0, registry.snapshot().pending());
        assertEquals(1, registry.snapshot().inFlightNotifications());
        var duplicated = assertThrows(IllegalStateException.class,
                () -> wrapper.completed(2, null));
        assertTrue(duplicated.getMessage().contains("duplicate native I/O callback"));
        assertEquals(1, registry.snapshot().pending(),
                "the real completion wrapper must restore the quarantine ticket");
        assertSame(duplicated, registry.snapshot().failure());
        queued.remove().run();
        assertEquals(1, callbackCalls.get(), "duplicate callback never enters delegate");
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

    @Test void duplicateNativeCallbackAfterReleaseBeforeDoneRevokesReceipt() {
        var queued = new java.util.ArrayDeque<Runnable>();
        var notices = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 59,
                notices::incrementAndGet, queued::add);
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        registry.seal();
        assertTrue(receipt.completeAfterPhysicalRelease());
        assertEquals(0, registry.snapshot().pending());
        assertEquals(1, registry.snapshot().inFlightNotifications());
        var duplicate = new IllegalStateException("second completion from native driver");
        assertTrue(receipt.failPhysicalRelease(duplicate),
                "late duplicate invalidates unretired physical-exit proof");
        assertFalse(receipt.failPhysicalRelease(duplicate),
                "failure must remain sticky without double accounting");
        assertEquals(1, registry.snapshot().pending());
        assertSame(duplicate, registry.snapshot().failure());
        queued.removeFirst().run();
        assertEquals(0, registry.snapshot().inFlightNotifications());
        assertThrows(IllegalStateException.class, registry::retireVerified,
                "post-drain notifier cannot override an intervening duplicate callback");
        assertTrue(notices.get() >= 2);
    }

    @Test void driverCallbackAfterVerifiedRetirementIsNotSilentlyAccepted() {
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 60, () -> { });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        registry.seal();
        assertTrue(receipt.completeAfterPhysicalRelease());
        registry.retireVerified();
        var late = new IllegalStateException("driver illegally called after retirement");
        var failure = assertThrows(IllegalStateException.class,
                () -> receipt.failPhysicalRelease(late));
        assertSame(late, failure.getCause());
        assertTrue(registry.snapshot().retired());
        // Native drivers capable of such a callback MUST register a distinct
        // callback-drain ticket before they can authorize actor reclamation.
    }

    @Test void deferredFinalWakeFailureSticksBeforeVerifiedRetirement() {
        var queued = new java.util.ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        var notificationFailure = new IllegalStateException("final host STOP recheck failed");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 58, () -> {
                    if (calls.incrementAndGet() == 2) throw notificationFailure;
                }, queued::add);
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.TCP_HTTP_HANDLE, false);
        registry.seal();
        assertTrue(receipt.completeAfterPhysicalRelease());
        assertEquals(1, registry.snapshot().inFlightNotifications());
        assertEquals(1, calls.get());
        assertThrows(IllegalStateException.class, registry::retireVerified);
        assertSame(notificationFailure, assertThrows(
                IllegalStateException.class, () -> queued.remove().run()));
        assertEquals(0, registry.snapshot().inFlightNotifications());
        assertSame(notificationFailure, registry.snapshot().failure(),
                "a failed final wake must permanently quarantine the generation");
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

    @Test void acceptedButNeverRunPostDrainWakeKeepsRetirementFenced() {
        var queued = new java.util.ArrayDeque<Runnable>();
        var notifications = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 57,
                notifications::incrementAndGet, queued::add);
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        registry.seal();
        assertTrue(receipt.completeAfterPhysicalRelease());
        assertEquals(1, notifications.get());
        assertEquals(1, queued.size());
        assertEquals(1, registry.snapshot().inFlightNotifications(),
                "the host can drop a queued wake but may never fake DONE");
        assertThrows(IllegalStateException.class, registry::retireVerified);
        queued.remove().run();
        assertEquals(2, notifications.get());
        assertEquals(0, registry.snapshot().inFlightNotifications());
        registry.retireVerified();
    }

    @Test void enqueueThenThrowCannotAuthorizeNativePostDrainWake() {
        var queued = new java.util.ArrayDeque<Runnable>();
        var notifications = new AtomicInteger();
        var rejection = new java.util.concurrent.RejectedExecutionException(
                "bounded host close lane rejected wake");
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 52, notifications::incrementAndGet,
                wake -> {
                    queued.add(wake);  // simulate an executor accepting then throwing
                    throw rejection;
                });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.FFI_LOAN, false);
        registry.seal();
        assertSame(rejection, assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                receipt::completeAfterPhysicalRelease));
        assertEquals(1, notifications.get());
        assertSame(rejection, registry.snapshot().failure());
        assertEquals(1, queued.size());
        queued.removeFirst().run();
        assertEquals(1, notifications.get(),
                "a queued-but-unapproved post-drain callback cannot finalize");
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

    @Test void fastAsyncFinalWakeWaitsForAcceptedDispatch() throws Exception {
        var enteredBeforeReturn = new CountDownLatch(1);
        var wakeCompleted = new CountDownLatch(1);
        var callbackCount = new AtomicInteger();
        var dispatchThread = new AtomicReference<Thread>();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 55, () -> {
                    if (callbackCount.incrementAndGet() == 2) wakeCompleted.countDown();
                }, wake -> {
                    Thread worker = Thread.ofVirtual().start(() -> {
                        enteredBeforeReturn.countDown();
                        wake.run();
                    });
                    dispatchThread.set(worker);
                    try {
                        assertTrue(enteredBeforeReturn.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interruption);
                    }
                    // Worker has started while execute() is still on stack,
                    // but must wait for explicit post-dispatch approval.
                    assertEquals(1, callbackCount.get());
                });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        registry.seal();
        assertTrue(receipt.completeAfterPhysicalRelease());
        try {
            assertTrue(wakeCompleted.await(3, TimeUnit.SECONDS));
        } finally {
            Thread worker = dispatchThread.get();
            if (worker != null) worker.join(3_000);
        }
        assertEquals(2, callbackCount.get());
        assertNull(registry.snapshot().failure());
        registry.retireVerified();
    }

    @Test void swallowedCallerRunsErrorStillQuarantinesFinalWake() {
        var notifications = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 54, notifications::incrementAndGet,
                wake -> {
                    try { wake.run(); }
                    catch (IllegalStateException deliberatelySwallowed) {
                        // A malicious executor must not convert inline dispatch
                        // rejection into a successful retirement admission.
                    }
                });
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        registry.seal();
        var rejected = assertThrows(IllegalStateException.class,
                receipt::completeAfterPhysicalRelease);
        assertTrue(rejected.getMessage().contains("asynchronous host dispatch"));
        assertEquals(1, notifications.get());
        assertSame(rejected, registry.snapshot().failure());
        assertThrows(IllegalStateException.class, registry::retireVerified);
    }

    @Test void callerRunsPhysicalPostDrainIsRejectedWithoutDeadlock() {
        var notifications = new AtomicInteger();
        var registry = new ActorPhysicalRetirementRegistry(
                ActorRuntime.ActorId.create(), 53, notifications::incrementAndGet,
                Runnable::run);
        var receipt = registry.register(
                ActorPhysicalRetirementRegistry.Kind.NATIVE_IO_OPERATION, false);
        registry.seal();
        var rejection = assertThrows(IllegalStateException.class,
                receipt::completeAfterPhysicalRelease);
        assertTrue(rejection.getMessage().contains("asynchronous host dispatch"));
        assertEquals(1, notifications.get(), "no inline final owner notification");
        assertSame(rejection, registry.snapshot().failure());
        assertThrows(IllegalStateException.class, registry::retireVerified);
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
