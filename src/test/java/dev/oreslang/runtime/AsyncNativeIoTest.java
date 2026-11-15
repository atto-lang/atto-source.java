package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AsyncNativeIoTest {
    @TempDir Path root;

    private String run(String program, RuntimePermissions permissions, OutputStream output) throws Exception {
        Path source = root.resolve("test.ores");
        Files.writeString(source, program);
        LinkedProgramRunner.run(source, IsolatePolicy.developer().withCapabilities(
                IsolatePolicy.Capability.FILESYSTEM_READ, IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.NETWORK), permissions, PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(), Set.of(), System.getenv(), output, new ByteArrayOutputStream());
        return output.toString();
    }

    @Test void filesystemAwaitsReturnValuesAndPropagatePermissionFailures() throws Exception {
        String path = root.resolve("item.txt").toString();
        String output = run("""
            pub async routine main(): void {
              await fs.mkdir_all("%s/sub");
              await fs.write_text("%s", "hello");
              await fs.append_text("%s", " world");
              stdio.println(await fs.exists("%s"));
              stdio.println(await fs.read_text("%s"));
              await fs.remove("%s");
              stdio.println(await fs.exists("%s"));
              try { await fs.read_text("%s"); }
              catch (failure) { stdio.println("missing-caught"); }
              try { await fs.write_text("/tmp/ores-async-denied.txt", "blocked"); }
              catch (failure) { stdio.println("denied-caught"); }
              return;
            }
            """.formatted(root, path, path, path, path, path, path, path),
            RuntimePermissions.denyAll().withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString()), new ByteArrayOutputStream());
        assertEquals("true\nhello world\nfalse\nmissing-caught\ndenied-caught\n", output.replace("\r", ""));
        assertFalse(Files.exists(Path.of("/tmp/ores-async-denied.txt")));
    }

    @Test void untrustedActorCannotLaunderParentFilesystemCapabilityThroughWorker() throws Exception {
        Path path = root.resolve("blocked.txt");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
            define untrusted actor Worker as
              receive(ActorMail<String> mail): void {
                try {
                  val operation = fs.write_text(mail.value, "escaped");
                  self.send("unexpected-admission");
                } catch (failure) { self.send("denied"); }
                self.end(); return;
              }
            end
            pub async routine main(): void {
              val worker = spawn Worker(); await worker.get_ready();
              worker.send("%s");
              for await const output of worker.outputs { stdio.println(output.value); }
              await worker.get_done(); return;
            }
            """.formatted(path), RuntimePermissions.denyAll().withAllowed(
                RuntimePermissions.Permission.WRITE, root.toString()), new ByteArrayOutputStream()));
        assertTrue(failure.getMessage().contains("restricted capability"), failure.getMessage());
        assertFalse(Files.exists(path));
    }

    @Test void asyncCanonicalizationRejectsSymlinkEscape() throws Exception {
        Path permitted = Files.createDirectory(root.resolve("permitted"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(permitted.resolve("escape"), outside);
        String output = run("""
            pub async routine main(): void {
              try { await fs.write_text("%s/escape/secret.txt", "escaped"); }
              catch (failure) { stdio.println("denied"); }
              return;
            }
            """.formatted(permitted), RuntimePermissions.denyAll().withAllowed(
                RuntimePermissions.Permission.WRITE, permitted.toString()), new ByteArrayOutputStream());
        assertEquals("denied\n", output.replace("\r", ""));
        assertFalse(Files.exists(outside.resolve("secret.txt")));
    }

    @Test void concurrentActorStopAndContextRetirementAreIdempotent()
            throws Exception {
        for (int round = 0; round < 16; round++) {
            var runtime = new ActorRuntime();
            var io = new AsyncNativeIo(2);
            var closeFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var stopFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var go = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            try {
                actor.ready().get(3, TimeUnit.SECONDS);
                Thread explicitStop = Thread.ofVirtual().start(() -> {
                    try {
                        if (!go.await(3, TimeUnit.SECONDS)) throw new AssertionError("missing start");
                        actor.cancel();
                    } catch (Throwable failure) {
                        stopFailure.compareAndSet(null, failure);
                    }
                });
                Thread contextClose = Thread.ofVirtual().start(() -> {
                    try {
                        if (!go.await(3, TimeUnit.SECONDS)) throw new AssertionError("missing start");
                        OresContext.joinActorsAndNativeIo(runtime, io);
                    } catch (Throwable failure) {
                        closeFailure.compareAndSet(null, failure);
                    }
                });
                go.countDown();
                explicitStop.join(3000);
                contextClose.join(3000);
                assertFalse(explicitStop.isAlive(), "host STOP hung in round " + round);
                assertFalse(contextClose.isAlive(), "context close hung in round " + round);
                assertNull(stopFailure.get(), () -> String.valueOf(stopFailure.get()));
                assertNull(closeFailure.get(), () -> String.valueOf(closeFailure.get()));
                assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
                OresContext.joinActorsAndNativeIo(runtime, io);
            } finally {
                go.countDown();
                runtime.close();
                io.close();
            }
        }
    }

    @Test void sealedRuntimeRetainsUnretiredActorAndCanFinishAfterPhysicalReceipt()
            throws Exception {
        var runtime = new ActorRuntime();
        var physicalLoanRetired = new java.util.concurrent.atomic.AtomicBoolean();
        runtime.setNativeIoLoanRetirementGate(id -> physicalLoanRetired.get());
        var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
        try {
            actor.ready().get(3, TimeUnit.SECONDS);
            runtime.sealShutdownAdmission();
            assertThrows(RuntimeException.class, () -> actor.send("sealed"));
            assertThrows(IllegalStateException.class, runtime::close,
                    "a missing physical receipt must quarantine the unretired ActorCell");
            assertFalse(actor.done().isDone(),
                    "arena and DONE cannot be released by dispatcher shutdown");
            physicalLoanRetired.set(true);
            runtime.onNativeIoLoanRetired(actor.id());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "the last physical receipt must unblock retirement on a retry");
            runtime.close();
        } finally {
            physicalLoanRetired.set(true);
            runtime.onNativeIoLoanRetired(actor.id());
            runtime.close();
        }
    }

    @Test void contextShutdownSealsActorMailBeforeWakingNativeSocket()
            throws Exception {
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2);
        var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
        var socketWake = new CountDownLatch(1);
        var observedSealed = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            actor.ready().get(3, TimeUnit.SECONDS);
            // The socket's close callback happens in requestShutdown(), BEFORE
            // ActorRuntime.close() enters any user cleanup hooks.
            Socket socket = new Socket() {
                @Override public synchronized void close() throws IOException {
                    assertThrows(RuntimeException.class,
                            () -> actor.send("forbidden-after-context-seal"));
                    observedSealed.set(true);
                    socketWake.countDown();
                    super.close();
                }
            };
            io.register(socket);
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertTrue(socketWake.await(2, TimeUnit.SECONDS));
            assertTrue(observedSealed.get(),
                    "actor mail must already be fenced when transport revocation runs");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(socket.isClosed());
            assertTrue(io.submit("closed", () -> 1).isDone());
        } finally {
            runtime.close();
            io.close();
        }
    }

    @Test void contextShutdownJoinsAfterPhysicalLoanAndSupportsQuarantinedRetry()
            throws Exception {
        var runtime = new ActorRuntime();
        var io = new AsyncNativeIo(2, 1, 2);
        var closeCount = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        try {
            io.register(closeCount::incrementAndGet);
            var pending = io.submit("uncooperative-native-work", () -> {
                entered.countDown();
                try {
                    while (!release.await(25, TimeUnit.MILLISECONDS)) { }
                } catch (InterruptedException interrupted) {
                    while (release.getCount() != 0) {
                        try { release.await(25, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { }
                    }
                } finally {
                    exited.countDown();
                }
                return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> OresContext.joinActorsAndNativeIo(runtime, io),
                    "a running physical I/O worker must force quarantine");
            assertEquals(0, closeCount.get(),
                    "incomplete native worker cannot release registered resources");
            assertFalse(pending.isDone());
            release.countDown();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> pending.get(2, TimeUnit.SECONDS)).getCause());
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertEquals(1, closeCount.get(),
                    "verified retry must release resource exactly once");
        } finally {
            release.countDown();
            runtime.close();
            io.close();
        }
    }

    @Test void differentActorIncarnationsCannotReleaseEachOthersNativeLoans()
            throws Exception {
        try (var io = new AsyncNativeIo(4);
             var runtime = new ActorRuntime()) {
            var registeredClosed = new java.util.concurrent.atomic.AtomicInteger();
            AutoCloseable resource = registeredClosed::incrementAndGet;
            io.register(resource);
            runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
            io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);

            var firstLoan = new java.util.concurrent.atomic.AtomicReference<AsyncNativeIo.IoLoan>();
            var secondLoan = new java.util.concurrent.atomic.AtomicReference<AsyncNativeIo.IoLoan>();
            var admitted = new CountDownLatch(2);
            var a = runtime.<String>spawnShared(() -> (message, ctx) -> {
                firstLoan.set(io.borrow(resource));
                admitted.countDown();
            });
            var b = runtime.<String>spawnShared(() -> (message, ctx) -> {
                secondLoan.set(io.borrow(resource));
                admitted.countDown();
            });
            a.send("borrow");
            b.send("borrow");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            assertNotEquals(a.id(), b.id());
            assertEquals(a.id(), firstLoan.get().identity().actorId());
            assertEquals(b.id(), secondLoan.get().identity().actorId());
            assertEquals(1, io.outstandingActorOperations(a.id()));
            assertEquals(1, io.outstandingActorOperations(b.id()));

            a.cancel();
            b.cancel();
            assertFalse(a.done().isDone());
            assertFalse(b.done().isDone());
            firstLoan.get().close();
            assertTrue(a.awaitTermination(3, TimeUnit.SECONDS),
                    "first arena must be released by its own final physical loan");
            assertFalse(b.done().isDone(),
                    "a stale or foreign generation receipt cannot finalize another actor");
            assertEquals(0, io.outstandingActorOperations(a.id()));
            assertEquals(1, io.outstandingActorOperations(b.id()));
            secondLoan.get().close();
            assertTrue(b.awaitTermination(3, TimeUnit.SECONDS));
            io.close();
            assertEquals(1, registeredClosed.get());
        }
    }

    @Test void actorNativeOperationAdmissionBlocksDoneBeforePhysicalWorkerExit()
            throws Exception {
        try (var io = new AsyncNativeIo(2);
             var runtime = new ActorRuntime()) {
            runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
            io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var admitted = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, ctx) -> {
                io.submit("actor-owned-native", () -> {
                    entered.countDown();
                    release.await();
                    return 3;
                });
                admitted.countDown();
            });
            try {
                actor.send("start");
                assertTrue(admitted.await(3, TimeUnit.SECONDS));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(1, io.outstandingActorOperations(actor.id()));
                actor.cancel();
                assertFalse(actor.done().isDone(),
                        "task completion alone cannot free the actor while native work runs");
            } finally {
                release.countDown();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        }
    }

    @Test void executorQueueOverflowRefundsExactPrivateActorLoanAndPhysicalWorkStillPinsMemory()
            throws Exception {
        // Three admission slots, but only one physical worker and one queued
        // work slot. The third operation reaches the real executor rejection
        // path after the actor loan has already been reserved.
        try (var io = new AsyncNativeIo(3, 1, 1);
             var runtime = new ActorRuntime()) {
            runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
            io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);
            CountDownLatch releaseSecond = new CountDownLatch(1);
            CountDownLatch submissionsFinished = new CountDownLatch(1);
            var rejected = new java.util.concurrent.atomic.AtomicReference<OresFuture<Integer>>();
            var actor = runtime.<String>spawnPrivateTrusted(factory -> (mail, turn) -> {
                if ("first".equals(mail)) {
                    io.submit("physical-first", () -> {
                        firstEntered.countDown();
                        releaseFirst.await();
                        return 1;
                    });
                } else if ("second".equals(mail)) {
                    // This separate actor message is sent only AFTER the
                    // physical first worker enters. The second operation is
                    // deterministically queued and the third must reject.
                    io.submit("queued-second", () -> {
                        secondEntered.countDown();
                        releaseSecond.await();
                        return 2;
                    });
                    rejected.set(io.submit("reject-overflow", () -> {
                        throw new AssertionError("rejected native work started");
                    }));
                    submissionsFinished.countDown();
                }
            });
            try {
                actor.ready().get(3, TimeUnit.SECONDS);
                actor.send("first");
                assertTrue(firstEntered.await(3, TimeUnit.SECONDS),
                        "worker must own the physical first slot before overflow admission");
                actor.send("second");
                assertTrue(submissionsFinished.await(3, TimeUnit.SECONDS));
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class,
                                () -> rejected.get().get(3, TimeUnit.SECONDS)).getCause());
                assertEquals(2, io.outstandingActorOperations(actor.id()),
                        "only physically admitted/queued work may retain private actor state");
                assertEquals(1L, secondEntered.getCount(),
                        "queued second native I/O must not start while first worker is blocked");
                actor.cancel();
                assertFalse(actor.done().isDone(),
                        "executor rejection must not prematurely reclaim actor memory");
                releaseFirst.countDown();
                assertTrue(secondEntered.await(3, TimeUnit.SECONDS));
                assertFalse(actor.done().isDone(),
                        "the queued native operation remains a physical owner");
                releaseSecond.countDown();
                assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
                assertEquals(0, io.outstandingActorOperations(actor.id()));
                assertTrue(io.actorPhysicallyRetired(actor.id()));
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
        }
    }

    @Test void privateActorFinalizationRequiresBothPhysicalCarrierAndNativeIoRetirement()
            throws Exception {
        for (boolean nativeFinishesFirst : new boolean[] { true, false }) {
            CountDownLatch carrierInsideExecutor = new CountDownLatch(1);
            CountDownLatch releaseCarrier = new CountDownLatch(1);
            CountDownLatch nativeInsideWorker = new CountDownLatch(1);
            CountDownLatch releaseNative = new CountDownLatch(1);
            CountDownLatch nativeRetired = new CountDownLatch(1);
            CountDownLatch admittedNative = new CountDownLatch(1);
            // TurnExecutor runs synchronously on the same physical carrier
            // as the guest turn. Tag that exact turn, not a global "next"
            // flag that a READY/control/housekeeping turn could consume.
            ThreadLocal<Boolean> nativeAdmittingTurn = new ThreadLocal<>();
            var heldTurns = new java.util.concurrent.atomic.AtomicInteger();
            ActorRuntime.TurnExecutor turnExecutor = turn -> {
                try {
                    turn.run();
                } finally {
                    boolean hold = Boolean.TRUE.equals(nativeAdmittingTurn.get());
                    nativeAdmittingTurn.remove();
                    if (hold) {
                        heldTurns.incrementAndGet();
                        carrierInsideExecutor.countDown();
                        try {
                            assertTrue(releaseCarrier.await(5, TimeUnit.SECONDS),
                                    "physical carrier release never arrived");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                    }
                }
            };
            try (var io = new AsyncNativeIo(2);
                 var runtime = new ActorRuntime(
                         IsolatePolicy.developer(),
                         new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                         turnExecutor)) {
                runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
                io.setActorLoanRetiredListener(id -> {
                    runtime.onNativeIoLoanRetired(id);
                    nativeRetired.countDown();
                });
                var actor = runtime.<String>spawnPrivateTrusted(
                        factory -> (message, context) -> {
                            io.submit("native-held-for-physical-join", () -> {
                                nativeInsideWorker.countDown();
                                releaseNative.await();
                                return 17;
                            });
                            nativeAdmittingTurn.set(Boolean.TRUE);
                            admittedNative.countDown();
                        });
                try {
                    actor.ready().get(3, TimeUnit.SECONDS);
                    actor.send("run");
                    assertTrue(admittedNative.await(3, TimeUnit.SECONDS));
                    assertTrue(nativeInsideWorker.await(3, TimeUnit.SECONDS));
                    assertTrue(carrierInsideExecutor.await(3, TimeUnit.SECONDS));
                    assertEquals(1, io.outstandingActorOperations(actor.id()));
                    actor.cancel();
                    assertFalse(actor.done().isDone(),
                            "STOP must not reclaim a private actor with two physical owners");
                    if (nativeFinishesFirst) {
                        releaseNative.countDown();
                        // Wait for the exact native actor-loan retirement
                        // callback, not an unrelated CONTROL worker probe.
                        assertTrue(nativeRetired.await(3, TimeUnit.SECONDS));
                        assertEquals(0, io.outstandingActorOperations(actor.id()));
                        assertFalse(actor.done().isDone(),
                                "native I/O completion must not bypass the occupied carrier");
                        releaseCarrier.countDown();
                    } else {
                        releaseCarrier.countDown();
                        assertFalse(actor.done().isDone(),
                                "carrier exit must not bypass an outstanding native I/O loan");
                        releaseNative.countDown();
                    }
                    assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                            "actor DONE must follow BOTH physical completions");
                    assertEquals(0, io.outstandingActorOperations(actor.id()));
                    assertTrue(io.actorPhysicallyRetired(actor.id()));
                    assertEquals(1, heldTurns.get(),
                            "only the I/O-admitting physical actor turn may be held");
                } finally {
                    releaseCarrier.countDown();
                    releaseNative.countDown();
                }
            }
        }
    }

    @Test void equalButDistinctNativeResourcesRetireIndependently()
            throws Exception {
        var io = new AsyncNativeIo(2);
        var firstClosed = new java.util.concurrent.atomic.AtomicInteger();
        var secondClosed = new java.util.concurrent.atomic.AtomicInteger();
        final class EqualResource implements AutoCloseable {
            final java.util.concurrent.atomic.AtomicInteger counter;
            EqualResource(java.util.concurrent.atomic.AtomicInteger counter) {
                this.counter = counter;
            }
            @Override public boolean equals(Object other) {
                return other instanceof EqualResource;
            }
            @Override public int hashCode() { return 17; }
            @Override public void close() { counter.incrementAndGet(); }
        }
        AutoCloseable first = new EqualResource(firstClosed);
        AutoCloseable second = new EqualResource(secondClosed);
        try {
            io.register(first);
            io.register(second);
            var firstLoan = io.borrow(first);
            var secondLoan = io.borrow(second);
            assertNotEquals(firstLoan.identity().resourceGeneration(),
                    secondLoan.identity().resourceGeneration());
            firstLoan.close();
            secondLoan.close();
            io.close();
            assertEquals(1, firstClosed.get(),
                    "the first handle must retain its own identity and cleanup");
            assertEquals(1, secondClosed.get(),
                    "a distinct equals()-equivalent handle cannot disappear");
        } finally {
            io.close();
        }
    }

    @Test void nativeLoanGenerationAndDuplicateReceiptAreFailClosed()
            throws Exception {
        var io = new AsyncNativeIo(2);
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable resource = closed::incrementAndGet;
        try {
            io.register(resource);
            AsyncNativeIo.IoLoan first = io.borrow(resource);
            AsyncNativeIo.IoLoan second = io.borrow(resource);
            assertEquals(first.identity().resourceGeneration(),
                    second.identity().resourceGeneration());
            assertNotEquals(first.identity().loanGeneration(),
                    second.identity().loanGeneration());
            assertNull(first.identity().actorId(),
                    "host/ROOT loans do not invent an actor incarnation");

            io.requestShutdown();
            assertThrows(RejectedExecutionException.class, () -> io.borrow(resource),
                    "logical shutdown must seal new per-resource admission");
            assertThrows(IllegalStateException.class, io::close,
                    "an outstanding physical resource loan must keep context quarantined");
            assertEquals(0, closed.get(),
                    "resource close is not proof that a loan has physically exited");

            first.close();
            assertThrows(IllegalStateException.class, first::close,
                    "the same physical receipt cannot decrement the loan count twice");
            assertThrows(IllegalStateException.class, io::close,
                    "the second independent loan must remain outstanding");
            second.close();
            io.close();
            assertEquals(1, closed.get(),
                    "resource must close exactly once after the final loan");
        } finally {
            try { io.close(); } catch (IllegalStateException quarantined) { }
        }
    }

    @Test void retiredResourceHandleCannotBeReusedUnderStaleGeneration()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable resource = closed::incrementAndGet;
        try {
            io.register(resource);
            AsyncNativeIo.IoLoan older = io.borrow(resource);
            io.unregister(resource);
            assertThrows(RejectedExecutionException.class, () -> io.borrow(resource));
            assertThrows(IllegalStateException.class, () -> io.register(resource),
                    "a handle with an outstanding old-generation loan cannot be reused");
            older.close();
            io.register(resource);
            AsyncNativeIo.IoLoan newer = io.borrow(resource);
            assertTrue(newer.identity().resourceGeneration()
                    > older.identity().resourceGeneration());
            assertNotEquals(older.identity().loanGeneration(),
                    newer.identity().loanGeneration());
            assertThrows(IllegalStateException.class, older::close,
                    "an old receipt may never retire a fresh generation");
            newer.close();
            io.close();
            assertEquals(1, closed.get());
        } finally {
            io.close();
        }
    }

    @Test void requestShutdownUnblocksRegisteredSocketReadBeforePhysicalJoin()
            throws Exception {
        var io = new AsyncNativeIo(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var socket = new Socket(InetAddress.getLoopbackAddress(),
                     server.getLocalPort());
             var peer = server.accept()) {
            io.register(socket);
            var readEntered = new CountDownLatch(1);
            var readExited = new CountDownLatch(1);
            var read = io.submit("socket.read", () -> {
                try (AsyncNativeIo.IoLoan lease = io.borrow(socket)) {
                    readEntered.countDown();
                    try {
                        return socket.getInputStream().read();
                    } finally {
                        readExited.countDown();
                    }
                }
            });
            assertTrue(readEntered.await(2, TimeUnit.SECONDS));
            assertFalse(read.isDone());
            io.requestShutdown();
            assertTrue(readExited.await(2, TimeUnit.SECONDS),
                    "transport wakeup must exit the blocking read without a peer write");
            assertInstanceOf(Exception.class,
                    assertThrows(ExecutionException.class,
                            () -> read.get(2, TimeUnit.SECONDS)).getCause());
            io.close();
            assertTrue(socket.isClosed());
        } finally {
            io.close();
        }
    }

    @Test void queuedOperationDiscardIsDistinctFromRunningPhysicalRetirement()
            throws Exception {
        var io = new AsyncNativeIo(3, 1, 2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstExited = new CountDownLatch(1);
        var closedResources = new java.util.concurrent.atomic.AtomicInteger();
        var neverStarted = new java.util.concurrent.atomic.AtomicInteger();
        try {
            io.register(closedResources::incrementAndGet);
            var running = io.submit("blocking", () -> {
                entered.countDown();
                try {
                    while (!release.await(50, TimeUnit.MILLISECONDS)) { }
                } catch (InterruptedException interrupted) {
                    // This fake native driver ignores interruption and keeps
                    // its physical loan until the host releases the gate.
                    while (release.getCount() != 0) {
                        try { release.await(50, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { }
                    }
                } finally {
                    firstExited.countDown();
                }
                return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = io.submit("never-started", () -> {
                neverStarted.incrementAndGet();
                return 2;
            });
            assertThrows(IllegalStateException.class, io::close,
                    "physical shutdown cannot claim a live worker retired");
            assertEquals(0, closedResources.get(),
                    "registered resources must remain quarantined while any loan is running");
            assertFalse(running.isDone(),
                    "even after shutdownNow, a running Future is not a physical receipt");
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class,
                            () -> queued.get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(0, neverStarted.get());
            release.countDown();
            assertTrue(firstExited.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> running.get(2, TimeUnit.SECONDS)).getCause());
            io.close(); // retry after actual physical retirement
            assertEquals(1, closedResources.get(),
                    "registered resources close exactly once after all physical loans");
        } finally {
            release.countDown();
            io.close();
        }
    }

    @Test void lateResourceRegistrationAfterShutdownRemainsRetainedUntilWorkerExit()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var entered = new CountDownLatch(1);
        var permitRegistration = new CountDownLatch(1);
        var attemptedRegistration = new CountDownLatch(1);
        var closedResource = new java.util.concurrent.atomic.AtomicInteger();
        var deniedRegistration = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var future = io.submit("late-connect", () -> {
                entered.countDown();
                while (permitRegistration.getCount() != 0) {
                    try { permitRegistration.await(50, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { }
                }
                try {
                    io.register(closedResource::incrementAndGet);
                } catch (IllegalStateException contextClosed) {
                    deniedRegistration.incrementAndGet();
                } finally {
                    attemptedRegistration.countDown();
                }
                return 7;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, io::close);
            assertEquals(0, closedResource.get());
            permitRegistration.countDown();
            assertTrue(attemptedRegistration.await(2, TimeUnit.SECONDS));
            assertEquals(1, deniedRegistration.get());
            assertEquals(0, closedResource.get(),
                    "registration on a closing context must not close an active native handle");
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> future.get(2, TimeUnit.SECONDS)).getCause());
            io.close();
            assertEquals(1, closedResource.get(),
                    "the post-join resource sweep must close the late resource exactly once");
        } finally {
            permitRegistration.countDown();
            io.close();
        }
    }

    @Test void blockedTerminalSubscriberPreventsNativeResourceReclamation()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var started = new CountDownLatch(1);
        var finishWork = new CountDownLatch(1);
        var subscriberEntered = new CountDownLatch(1);
        var releaseSubscriber = new CountDownLatch(1);
        var resourcesClosed = new java.util.concurrent.atomic.AtomicInteger();
        try {
            io.register(resourcesClosed::incrementAndGet);
            var future = io.submit("terminal", () -> {
                started.countDown();
                finishWork.await();
                return 17;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            future.whenCompleteRuntime((value, failure) -> {
                subscriberEntered.countDown();
                try {
                    while (!releaseSubscriber.await(50, TimeUnit.MILLISECONDS)) { }
                } catch (InterruptedException interrupted) {
                    while (releaseSubscriber.getCount() != 0) {
                        try { releaseSubscriber.await(50, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { }
                    }
                }
            });
            finishWork.countDown();
            assertTrue(subscriberEntered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, io::close,
                    "terminal state alone is not a drained notification receipt");
            assertEquals(0, resourcesClosed.get());
            releaseSubscriber.countDown();
            io.close();
            assertEquals(1, resourcesClosed.get());
        } finally {
            finishWork.countDown();
            releaseSubscriber.countDown();
            io.close();
        }
    }

    @Test void blockedNeverStartedNotificationQuarantinesResourcesWithoutBlockingClose()
            throws Exception {
        var io = new AsyncNativeIo(2, 1, 1);
        var entered = new CountDownLatch(1);
        var subscriberEntered = new CountDownLatch(1);
        var releaseSubscriber = new CountDownLatch(1);
        var released = new java.util.concurrent.atomic.AtomicInteger();
        var closeFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try {
            io.register(released::incrementAndGet);
            var running = io.submit("running", () -> {
                entered.countDown();
                new CountDownLatch(1).await();
                return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = io.submit("discard", () -> 2);
            queued.whenCompleteRuntime((value, failure) -> {
                subscriberEntered.countDown();
                while (releaseSubscriber.getCount() != 0) {
                    try { releaseSubscriber.await(50, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { }
                }
            });

            Thread closer = Thread.ofVirtual().start(() -> {
                try {
                    assertThrows(IllegalStateException.class, io::close);
                } catch (Throwable problem) {
                    closeFailure.compareAndSet(null, problem);
                }
            });
            try {
                assertTrue(subscriberEntered.await(2, TimeUnit.SECONDS),
                        "never-started notification must run independently of close");
                closer.join(2000);
                assertFalse(closer.isAlive(),
                        "blocked terminal subscriber may quarantine but cannot hang the close caller");
                assertNull(closeFailure.get(), () -> String.valueOf(closeFailure.get()));
                assertEquals(0, released.get(),
                        "resources remain held until every callback physically drains");
                assertInstanceOf(IllegalStateException.class,
                        assertThrows(ExecutionException.class,
                                () -> running.get(2, TimeUnit.SECONDS)).getCause());
            } finally {
                releaseSubscriber.countDown();
            }
            io.close();
            assertEquals(1, released.get());
        } finally {
            releaseSubscriber.countDown();
            io.close();
        }
    }

    @Test void resourceRegisteredAfterVerifiedRetirementRemainsCallerOwned()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var firstClosed = new java.util.concurrent.atomic.AtomicInteger();
        var lateClosed = new java.util.concurrent.atomic.AtomicInteger();
        try {
            io.register(firstClosed::incrementAndGet);
            io.close();
            assertEquals(1, firstClosed.get());
            AutoCloseable callerOwned = lateClosed::incrementAndGet;
            IllegalStateException rejection =
                    assertThrows(IllegalStateException.class,
                            () -> io.register(callerOwned));
            assertTrue(rejection.getMessage().contains("caller retains resource ownership"),
                    "fully retired context cannot silently assume ownership of a late handle");
            io.close();
            assertEquals(0, lateClosed.get(),
                    "a rejected post-retirement transfer is owned by the caller");
            callerOwned.close();
            assertEquals(1, lateClosed.get(), "caller explicitly retires rejected handle");
            assertEquals(1, firstClosed.get(), "former context resources close once");
        } finally {
            io.close();
        }
    }

    @Test void lateRegistrationDuringResourceCloseKeepsContextQuarantinedUntilRetry()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var firstClosed = new java.util.concurrent.atomic.AtomicInteger();
        var lateClosed = new java.util.concurrent.atomic.AtomicInteger();
        try {
            io.register(() -> {
                firstClosed.incrementAndGet();
                // A callback may swallow the expected registration failure.
                // Its late handle is still retained by register(), but was
                // absent from the closing resources snapshot.
                assertThrows(IllegalStateException.class,
                        () -> io.register(lateClosed::incrementAndGet));
            });
            IllegalStateException incomplete =
                    assertThrows(IllegalStateException.class, io::close);
            assertTrue(incomplete.getMessage().contains("context quarantined"));
            assertInstanceOf(IllegalStateException.class, incomplete.getCause());
            assertTrue(incomplete.getCause().getMessage()
                    .contains("resources remain after post-join close sweep"));
            assertEquals(1, firstClosed.get());
            assertEquals(0, lateClosed.get(),
                    "the retained handle must not be silently abandoned");
            io.close();
            assertEquals(1, firstClosed.get(), "retired resource cannot close twice");
            assertEquals(1, lateClosed.get(), "retry must close the late handle");
            io.close();
            assertEquals(1, lateClosed.get(), "fully retired close is idempotent");
        } finally {
            io.close();
        }
    }

    @Test void resourceCloseCallbackCannotRecursivelyCloseItsNativeContext()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        try {
            io.register(() -> {
                closes.incrementAndGet();
                IllegalStateException denied =
                        assertThrows(IllegalStateException.class, io::close);
                assertTrue(denied.getMessage().contains("cannot recursively close"));
            });
            io.close();
            assertEquals(1, closes.get(),
                    "a reentrant host callback cannot re-close the same resource");
            io.close();
            assertEquals(1, closes.get(), "a successful close is idempotent");
        } finally {
            io.close();
        }
    }

    @Test void uncaughtResourceCloseReentryQuarantinesAndCanBeRetried()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        var recurseOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            io.register(() -> {
                closes.incrementAndGet();
                if (recurseOnce.getAndSet(false)) io.close();
            });
            IllegalStateException incomplete =
                    assertThrows(IllegalStateException.class, io::close);
            assertTrue(incomplete.getMessage().contains("resource close failed"));
            assertInstanceOf(IllegalStateException.class, incomplete.getCause());
            assertTrue(incomplete.getCause().getMessage()
                    .contains("cannot recursively close"));
            assertEquals(1, closes.get(),
                    "a failed reentrant close must retain, not recursively release, the resource");
            io.close();
            assertEquals(2, closes.get(),
                    "the retained resource must be closed by the next verified sweep");
            io.close();
            assertEquals(2, closes.get(), "the retired resource must close exactly once");
        } finally {
            io.close();
        }
    }

    @Test void nativeCloseRejectsReentrantJoinFromItsOwnWorker()
            throws Exception {
        try (var io = new AsyncNativeIo(1)) {
            var blocked = io.submit("self-close", () -> {
                IllegalStateException rejected =
                        assertThrows(IllegalStateException.class, io::close);
                assertTrue(rejected.getMessage().contains("cannot join its own"));
                return 11;
            });
            assertEquals(11, blocked.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void boundedAdmissionNeverRunsOnCallerAndCloseSettlesPendingWork() throws Exception {
        try (var io = new AsyncNativeIo(1)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch hold = new CountDownLatch(1);
            Thread caller = Thread.currentThread();
            var first = io.submit("slow", () -> {
                assertNotSame(caller, Thread.currentThread());
                entered.countDown(); hold.await(); return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var rejected = io.submit("overflow", () -> fail("must not execute"));
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS)).getCause());
            assertEquals(7, io.submitControl("close", () -> 7).get(1, TimeUnit.SECONDS),
                    "socket close must remain available when data admission is full");
            assertFalse(first.cancel(true), "mutations do not promise cancellable rollback");
            io.close();
            assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            assertTrue(io.submit("closed", () -> 1).isDone());
        }
    }

    @Test void coreNumericLoggingDoesNotChangeIoFailuresOrLeakGuestExceptionText() throws Exception {
        CoreLog.configure(CoreLog.Level.OFF);
        CoreLog.configure(CoreLog.Level.WARN);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var io = new AsyncNativeIo(1)) {
            var blocked = io.submit("slow", () -> {
                started.countDown();
                release.await();
                return 19;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var rejected = io.submit("private-operation-name", () -> fail("must not run"));
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class,
                            () -> rejected.get(2, TimeUnit.SECONDS)).getCause());
            release.countDown();
            assertEquals(19, blocked.get(2, TimeUnit.SECONDS));

            var failed = io.submit("another-secret-operation", () -> {
                throw new IllegalStateException("PRIVATE-GUEST-EXCEPTION-TEXT");
            });
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> failed.get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(7, io.submit("normal", () -> 7).get(2, TimeUnit.SECONDS),
                    "diagnostic publication must not change later admissions");

            var output = new ByteArrayOutputStream();
            CoreLog.dumpRetained(new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
            String json = output.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.contains("\"event\":\"async.io.rejected\""));
            assertTrue(json.contains("\"event\":\"async.io.failed\""));
            assertFalse(json.contains("PRIVATE-GUEST-EXCEPTION-TEXT"));
            assertFalse(json.contains("private-operation-name"));
            assertFalse(json.contains("another-secret-operation"));
            assertFalse(json.contains("\"value\":0"));
        } finally {
            release.countDown();
            CoreLog.configure(CoreLog.Level.OFF);
        }
        var afterDisable = new ByteArrayOutputStream();
        CoreLog.dumpRetained(new PrintStream(afterDisable));
        assertEquals("", afterDisable.toString(),
                "explicitly disabled diagnostics must not export prior I/O events");
    }

    @Test void delayedTcpReadReleasesSingleActorCarrierAndSupportsWriteAndEof() throws Exception {
        String previous = System.getProperty("ores.runtime.actor.shared.parallelism");
        System.setProperty("ores.runtime.actor.shared.parallelism", "1");
        CountDownLatch probeRan = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                if (toString().contains("probe")) probeRan.countDown();
            }
        };
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var peer = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var exchange = peer.submit(() -> {
                try (var socket = server.accept()) {
                    assertTrue(probeRan.await(5, TimeUnit.SECONDS), "await must release the only actor carrier");
                    socket.getOutputStream().write("reply\n".getBytes());
                    socket.shutdownOutput();
                    assertEquals("ack", new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine());
                }
                return null;
            });
            String program = """
                define actor Probe as
                  receive(ActorMail<String> mail): void { stdio.println("probe"); self.end(); return; }
                end
                define actor Reader as
                  receive(ActorMail<String> mail): void {
                    val socket = await network.connect("127.0.0.1", %d);
                    val reading = socket.read_line();
                    self.send("reading");
                    val line = await reading;
                    stdio.println(line);
                    stdio.println(await socket.read_line());
                    await socket.write_text("ack\\n");
                    await socket.close();
                    self.end(); return;
                  }
                end
                pub async routine main(): void {
                  val reader = spawn Reader(); await reader.get_ready();
                  val probe = spawn Probe(); await probe.get_ready();
                  reader.send("go");
                  await reader.outputs.next();
                  probe.send("go");
                  await reader.get_done(); await probe.get_done(); return;
                }
                """.formatted(server.getLocalPort());
            run(program, RuntimePermissions.denyAll().withAllowed(RuntimePermissions.Permission.NET,
                    "127.0.0.1:" + server.getLocalPort()), output);
            exchange.get(2, TimeUnit.SECONDS);
            assertTrue(output.toString().contains("reply"));
            assertTrue(output.toString().contains("None"));
        } finally {
            if (previous == null) System.clearProperty("ores.runtime.actor.shared.parallelism");
            else System.setProperty("ores.runtime.actor.shared.parallelism", previous);
        }
    }

    @Test void httpClientAwaitsGetPostAndDoesNotFollowRedirects() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestMethod().equals("POST") ? exchange.getRequestBody().readAllBytes() : "hello".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
            exchange.sendResponseHeaders(302, 0); exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            String output = run("""
                pub async routine main(): void {
                  stdio.println(await http.get_text("%s/"));
                  stdio.println(await http.post_text("%s/", "posted"));
                  await http.get_text("%s/redirect");
                  stdio.println("redirect-not-followed");
                  return;
                }
                """.formatted(url, url, url), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getAddress().getPort()), new ByteArrayOutputStream());
            assertEquals("hello\nposted\nredirect-not-followed\n", output.replace("\r", ""));
        } finally { server.stop(0); }
    }
    @Test void blockingSuffixIsStillAwaitableAndLegacyAsyncSuffixIsRejected() throws Exception {
        String path = root.resolve("blocking.txt").toString();
        String output = run("""
            pub async routine main(): void {
              await fs.write_text_blocking("%s", "blocking");
              stdio.println(await fs.read_text_blocking("%s"));
              return;
            }
            """.formatted(path, path),
            RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString())
                .withAllowed(RuntimePermissions.Permission.WRITE, root.toString()),
            new ByteArrayOutputStream());
        assertEquals("blocking\n", output.replace("\r", ""));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
            pub async routine main(): void {
              await fs.read_text_async("%s");
              return;
            }
            """.formatted(path),
            RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString()),
            new ByteArrayOutputStream()));
        assertTrue(String.valueOf(failure).contains("read_text_async"), String.valueOf(failure));

        RuntimeException missingAwait = assertThrows(RuntimeException.class, () -> run("""
            pub routine main(): void {
              if fs.exists("%s"); then
                stdio.println("unexpected");
              fi
              return;
            }
            """.formatted(path),
            RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, root.toString()),
            new ByteArrayOutputStream()));
        assertTrue(String.valueOf(missingAwait).contains("Future")
                        || String.valueOf(missingAwait).contains("bool"),
                String.valueOf(missingAwait));
    }

}
