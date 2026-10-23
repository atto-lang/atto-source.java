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
              await fs.mkdir_all_async("%s/sub");
              await fs.write_text_async("%s", "hello");
              await fs.append_text_async("%s", " world");
              stdio.println(await fs.exists_async("%s"));
              stdio.println(await fs.read_text_async("%s"));
              await fs.remove_async("%s");
              stdio.println(await fs.exists_async("%s"));
              try { await fs.read_text_async("%s"); }
              catch (failure) { stdio.println("missing-caught"); }
              try { await fs.write_text_async("/tmp/ores-async-denied.txt", "blocked"); }
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
                  val operation = fs.write_text_async(mail.value, "escaped");
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
              try { await fs.write_text_async("%s/escape/secret.txt", "escaped"); }
              catch (failure) { stdio.println("denied"); }
              return;
            }
            """.formatted(permitted), RuntimePermissions.denyAll().withAllowed(
                RuntimePermissions.Permission.WRITE, permitted.toString()), new ByteArrayOutputStream());
        assertEquals("denied\n", output.replace("\r", ""));
        assertFalse(Files.exists(outside.resolve("secret.txt")));
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

    @Test void closingContextCannotPublishFutureBeforeUncooperativeWorkerExits()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var actualExit = new CountDownLatch(1);
        var interruptSeen = new CountDownLatch(1);
        var operation = io.submit("physical-loan", () -> {
            entered.countDown();
            try {
                // Simulate an in-flight native operation that ignores host
                // interruption while still holding physical memory/handles.
                while (release.getCount() != 0) {
                    try {
                        release.await(30, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        interruptSeen.countDown();
                    }
                }
                return 23;
            } finally {
                actualExit.countDown();
            }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            io.close(); // requests interruption, not physical loan retirement
            assertFalse(operation.isDone(),
                    "logical context close cannot fake worker-exit receipt");
            assertEquals(1L, actualExit.getCount(),
                    "native host callback is still physically executing");
            assertTrue(interruptSeen.await(3, TimeUnit.SECONDS),
                    "shutdown should request cooperative host interruption");
        } finally {
            release.countDown();
        }
        assertTrue(actualExit.await(3, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class,
                        () -> operation.get(3, TimeUnit.SECONDS)).getCause(),
                "context closed while worker ran: result retires only afterward");
        io.close();
    }

    @Test void failingUncooperativeIoCannotPublishFailureBeforePhysicalExit()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finalizerExited = new CountDownLatch(1);
        var worker = io.submit("host-failure-after-close", () -> {
            entered.countDown();
            try {
                while (release.getCount() != 0) {
                    try {
                        release.await(30, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Some native calls cannot be interrupted.
                    }
                }
                throw new IllegalStateException("host I/O failure");
            } finally {
                finalizerExited.countDown();
            }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            io.close();
            assertFalse(worker.isDone(),
                    "logical context close cannot fabricate physical failure");
        } finally {
            release.countDown();
        }
        assertTrue(finalizerExited.await(3, TimeUnit.SECONDS));
        ExecutionException settled = assertThrows(ExecutionException.class,
                () -> worker.get(3, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, settled.getCause());
    }

    @Test void rejectedExecutorRetiresProvenNeverStartedIo()
            throws Exception {
        var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        executor.shutdownNow();
        var io = new AsyncNativeIo(1, executor);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var operation = io.submit("never-started-worker", () -> {
                calls.incrementAndGet();
                return 1;
            });
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class,
                            () -> operation.get(3, TimeUnit.SECONDS)).getCause());
            assertEquals(0, calls.get(), "a rejected worker must not perform native I/O");
            io.close();
            assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS),
                    "the never-started Future and receipt must retire fully");
        } finally {
            io.close();
        }
    }

    @Test void executorThrowAfterActualWorkerStartCannotCancelRunningIo()
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var running = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override public void execute(Runnable command) {
                super.execute(command);
                try {
                    if (!entered.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("worker did not start during test");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                // Deliberately invalid executor behavior: it started the
                // work but reported rejection. The operation phase CAS,
                // not the exception, must decide physical ownership.
                throw new RejectedExecutionException("worker already running");
            }
        };
        var io = new AsyncNativeIo(1, running);
        var exited = new CountDownLatch(1);
        var resultRef = new java.util.concurrent.atomic.AtomicReference<OresFuture<Integer>>();
        try {
            resultRef.set(io.submit("ambiguous-running-loan", () -> {
                entered.countDown();
                try {
                    while (release.getCount() != 0) {
                        try { release.await(20, TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ignored) { /* uninterruptible */ }
                    }
                    return 17;
                } finally {
                    exited.countDown();
                }
            }));
            assertFalse(resultRef.get().isDone(),
                    "an invalid execute() rejection cannot forge a worker-exit receipt");
            io.close();
            assertFalse(resultRef.get().isDone(), "STOP is not native loan retirement");
            assertFalse(io.physicalShutdown().isDone());
        } finally {
            release.countDown();
        }
        assertTrue(exited.await(3, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class,
                        () -> resultRef.get().get(3, TimeUnit.SECONDS)).getCause());
        assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
    }

    @Test void discardedBeforeStartIsCancelledAndCannotRunGuestHostIo()
            throws Exception {
        var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        var io = new AsyncNativeIo(2, executor);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closeRequested = new CountDownLatch(1);
        var neverStarted = new java.util.concurrent.atomic.AtomicInteger();
        io.register(() -> closeRequested.countDown());
        var running = io.submit("blocked", () -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* simulates native I/O */ }
            }
            return 7;
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var queued = io.submit("queued", () -> {
                neverStarted.incrementAndGet();
                return 11;
            });
            assertFalse(queued.isDone(), "queued I/O has not entered its Callable");
            io.close();
            assertTrue(closeRequested.await(3, TimeUnit.SECONDS),
                    "resource close must be requested even while worker is active");
            assertEquals(0, neverStarted.get());
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class,
                            () -> queued.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(running.isDone(),
                    "shutdownNow cannot certify the uninterruptible worker exited");
            assertFalse(io.physicalShutdown().isDone(),
                    "physical context receipt joins worker and close callbacks");
        } finally {
            release.countDown();
        }
        assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class,
                        () -> running.get(3, TimeUnit.SECONDS)).getCause());
        assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
        assertEquals(0, neverStarted.get());
    }

    @Test void swallowedRuntimeSubscriberExceptionMustQuarantinePhysicalRetirement()
            throws Exception {
        var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        var io = new AsyncNativeIo(1, executor);
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var healthySiblingCalled = new CountDownLatch(1);
        var callbackFailure = new IllegalStateException(
                "trusted native release notifier failed");
        var operation = io.submit("notifier-physical-failure", () -> {
            entered.countDown();
            while (exit.getCount() != 0) {
                try { exit.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* physical worker */ }
            }
            return 1;
        });
        operation.whenCompleteRuntime((value, failure) -> {
            throw callbackFailure;
        });
        operation.whenCompleteRuntime((value, failure) -> healthySiblingCalled.countDown());
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            io.close();
        } finally {
            exit.countDown();
        }
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        assertTrue(healthySiblingCalled.await(3, TimeUnit.SECONDS),
                "a throwing notifier cannot suppress healthy sibling subscribers");
        assertSame(callbackFailure, operation.runtimeNotificationFailure());
        assertSame(callbackFailure, io.physicalShutdownFailure());
        assertFalse(io.physicalShutdown().isDone(),
                "a swallowed host cleanup failure cannot forge clean context retirement");
    }

    @Test void physicalShutdownWaitsForTerminalSubscriberDrain()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var permitWorkExit = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var work = io.submit("callback-ownership", () -> {
            started.countDown();
            permitWorkExit.await();
            return 5;
        });
        assertTrue(started.await(3, TimeUnit.SECONDS));
        work.whenCompleteRuntime((value, failure) -> {
            callbackEntered.countDown();
            while (releaseCallback.getCount() != 0) {
                try {
                    releaseCallback.await(30, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Physical host subscriber deliberately ignores STOP
                    // interruption until the test releases its native loan.
                }
            }
        });
        permitWorkExit.countDown();
        try {
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
            io.close();
            assertFalse(io.physicalShutdown().isDone(),
                    "settled Future is not a drained inline subscriber receipt");
        } finally {
            releaseCallback.countDown();
        }
        assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
    }

    @Test void racedLateResourceCloseCannotBlockContextMonitorOrForgeRetirement()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var workerEntered = new CountDownLatch(1);
        var letWorkerExit = new CountDownLatch(1);
        var closeEntered = new CountDownLatch(1);
        var letCloseExit = new CountDownLatch(1);
        var operation = io.submit("ongoing", () -> {
            workerEntered.countDown();
            while (letWorkerExit.getCount() != 0) {
                try { letWorkerExit.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* native I/O is uncooperative */ }
            }
            return 99;
        });
        assertTrue(workerEntered.await(3, TimeUnit.SECONDS));
        io.close(); // seals further resource admission without a worker receipt
        var closeFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread rejected = Thread.ofVirtual().start(() -> {
            try {
                io.register(() -> {
                    closeEntered.countDown();
                    if (!letCloseExit.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("test native close deadline");
                    }
                });
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });
        try {
            assertTrue(closeEntered.await(3, TimeUnit.SECONDS));
            letWorkerExit.countDown();
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> operation.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(io.physicalShutdown().isDone(),
                    "late rejected resource close still owns a physical host callback");
            assertNull(io.physicalShutdownFailure());
        } finally {
            letWorkerExit.countDown();
            letCloseExit.countDown();
        }
        rejected.join(3_000);
        assertFalse(rejected.isAlive());
        assertInstanceOf(IllegalStateException.class, closeFailure.get(),
                "registration must reject after successfully closing late handle");
        assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
    }

    @Test void retiredContextCannotInitiateNewNativeCloseCallbacks()
            throws Exception {
        var io = new AsyncNativeIo(1);
        io.close();
        assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
        var attemptedClose = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class,
                () -> io.register(attemptedClose::incrementAndGet));
        assertEquals(0, attemptedClose.get(),
                "fully retired context has no authority to run new close callback");
    }

    @Test void failedLateRegistrationCloseQuarantinesContext()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pending = io.submit("inflight", () -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* native work */ }
            }
            return 1;
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var failure = new IOException("rejected native handle did not physically close");
        try {
            io.close();
            assertSame(failure, assertThrows(IOException.class,
                    () -> io.register(() -> { throw failure; })));
            assertSame(failure, io.physicalShutdownFailure());
            assertFalse(io.physicalShutdown().isDone());
        } finally {
            release.countDown();
        }
        assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
        assertFalse(io.physicalShutdown().isDone(),
                "failed late handle close must keep physical shutdown quarantined");
    }

    @Test void failedHostCloseDoesNotSkipSiblingOrPublishCleanShutdown()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var siblingClosed = new CountDownLatch(1);
        var fatalClose = new AssertionError("untrusted host close callback failed");
        io.register(() -> { throw fatalClose; });
        io.register(siblingClosed::countDown);
        io.close();
        assertTrue(siblingClosed.await(3, TimeUnit.SECONDS),
                "each resource close request must be attempted independently");
        assertSame(fatalClose, io.physicalShutdownFailure());
        assertFalse(io.physicalShutdown().isDone(),
                "a host close that threw has no trustworthy release receipt");
    }

    @Test void failedResourceCloseQuarantinesPhysicalContextRetirement()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var failedClose = new java.io.IOException("driver close cannot prove release");
        io.register(() -> { throw failedClose; });
        io.close();
        assertSame(failedClose, io.physicalShutdownFailure());
        assertFalse(io.physicalShutdown().isDone(),
                "uncertain resource close is not a physical-retirement proof");
    }

    @Test void physicalTcpLoanFencesExplicitCloseUntilReaderReleasesIt()
            throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var socket = new Socket(InetAddress.getLoopbackAddress(),
                     server.getLocalPort());
             var peer = server.accept()) {
            var owner = new NativeIo.SocketStopThenRelease(socket);
            var borrowed = owner.borrowOperation();
            assertEquals(1, owner.activeOperationCount());
            owner.requestStop();
            assertTrue(socket.isInputShutdown());
            assertFalse(socket.isClosed());
            assertThrows(IOException.class, owner::close,
                    "an active read/write loan must prevent premature descriptor closure");
            assertEquals(1, owner.activeOperationCount());
            assertFalse(socket.isClosed());
            assertThrows(IllegalStateException.class, owner::borrowOperation,
                    "logical STOP seals new socket I/O admission");
            borrowed.close();
            assertEquals(0, owner.activeOperationCount());
            owner.close();
            assertTrue(socket.isClosed());
            owner.close();
            assertThrows(IllegalStateException.class, owner::borrowOperation);
        }
    }

    @Test void duplicateOrForeignTcpLoanReleaseCannotConsumeSiblingBorrow()
            throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var socket = new Socket(InetAddress.getLoopbackAddress(),
                     server.getLocalPort());
             var peer = server.accept()) {
            var owner = new NativeIo.SocketStopThenRelease(socket);
            var first = owner.borrowOperation();
            var second = owner.borrowOperation();
            assertEquals(2, owner.activeOperationCount());
            var foreignFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread foreign = Thread.ofVirtual().start(() -> {
                try { first.close(); }
                catch (Throwable failure) { foreignFailure.set(failure); }
            });
            foreign.join(3_000);
            assertFalse(foreign.isAlive());
            assertInstanceOf(SecurityException.class, foreignFailure.get());
            assertEquals(2, owner.activeOperationCount());
            first.close();
            first.close();
            assertEquals(1, owner.activeOperationCount(),
                    "duplicate cleanup must not release another worker's loan");
            assertThrows(IOException.class, owner::close);
            second.close();
            assertEquals(0, owner.activeOperationCount());
            owner.close();
            assertTrue(socket.isClosed());
        }
    }

    @Test void racingTcpFinalClosesDoNotReleaseActivePhysicalBorrow()
            throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var socket = new Socket(InetAddress.getLoopbackAddress(),
                     server.getLocalPort());
             var peer = server.accept()) {
            var owner = new NativeIo.SocketStopThenRelease(socket);
            var borrowed = owner.borrowOperation();
            var start = new CountDownLatch(1);
            var finished = new CountDownLatch(2);
            var rejected = new java.util.concurrent.atomic.AtomicInteger();
            Runnable concurrentClose = () -> {
                try {
                    if (!start.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("close race failed to start");
                    }
                    owner.close();
                } catch (IOException expectedWhileBorrowed) {
                    rejected.incrementAndGet();
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interruption);
                } finally {
                    finished.countDown();
                }
            };
            Thread left = Thread.ofVirtual().start(concurrentClose);
            Thread right = Thread.ofVirtual().start(concurrentClose);
            start.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            left.join(3_000); right.join(3_000);
            assertEquals(2, rejected.get());
            assertFalse(socket.isClosed());
            borrowed.close();
            owner.close();
            assertTrue(socket.isClosed());
        }
    }

    @Test void actualTcpSocketStopDefersFinalCloseUntilReadObserverExit()
            throws Exception {
        var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        var io = new AsyncNativeIo(1, executor);
        var callbackEntered = new CountDownLatch(1);
        var allowCallbackExit = new CountDownLatch(1);
        var workerEntered = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var socket = new Socket(InetAddress.getLoopbackAddress(),
                     server.getLocalPort());
             var peer = server.accept()) {
            // Open the Java stream before the STOP/interrupt race.
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            io.register(new NativeIo.SocketStopThenRelease(socket));
            var read = io.submit("tcp-native-read", () -> {
                workerEntered.countDown();
                return reader.readLine();
            });
            read.whenCompleteRuntime((value, failure) -> {
                callbackEntered.countDown();
                while (allowCallbackExit.getCount() != 0) {
                    try { allowCallbackExit.await(20, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException ignored) { /* retained callback loan */ }
                }
            });
            try {
                assertTrue(workerEntered.await(3, TimeUnit.SECONDS));
                io.close();
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS),
                        "input half-close must unblock pending socket read");
                assertTrue(socket.isInputShutdown(),
                        "STOP requested stream cancellation");
                assertFalse(socket.isClosed(),
                        "final Socket.close is withheld through the callback loan");
                assertFalse(io.physicalShutdown().isDone());
            } finally {
                allowCallbackExit.countDown();
                if (!socket.isClosed() && !socket.isInputShutdown()) socket.shutdownInput();
            }
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> read.get(3, TimeUnit.SECONDS)).getCause());
            assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
            assertTrue(socket.isClosed(),
                    "socket final close follows tracked worker/callback exit");
        } finally {
            allowCallbackExit.countDown();
            io.close();
        }
    }

    @Test void contextReclamationGateRequiresActualWorkerAndReceiptExit()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var started = new CountDownLatch(1);
        var allowExit = new CountDownLatch(1);
        var pending = io.submit("pinned-context-worker", () -> {
            started.countDown();
            while (allowExit.getCount() != 0) {
                try { allowExit.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* uninterruptible I/O */ }
            }
            return 21;
        });
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            io.close();
            assertFalse(pending.isDone());
            assertThrows(IllegalStateException.class,
                    () -> OresContext.requireNativeIoPhysicalRetirement(io),
                    "context must not release actor arenas while the native worker runs");
        } finally {
            allowExit.countDown();
        }
        assertThrows(ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
        assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> OresContext.requireNativeIoPhysicalRetirement(io),
                "the verified host retirement receipt permits the next cleanup stage");
    }

    @Test void contextReclamationGateDeniesFailedResourceReceipt()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var failed = new IOException("native buffer ownership unresolved");
        io.register(() -> { throw failed; });
        io.close();
        var rejection = assertThrows(IllegalStateException.class,
                () -> OresContext.requireNativeIoPhysicalRetirement(io));
        assertSame(failed, rejection.getCause());
        assertFalse(io.physicalShutdown().isDone());
    }

    @Test void stagedNativeReleaseWaitsForPhysicalWorkerAndCloseCallback()
            throws Exception {
        var executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        var io = new AsyncNativeIo(1, executor);
        var entered = new CountDownLatch(1);
        var letWorkerExit = new CountDownLatch(1);
        var stopRequested = new CountDownLatch(1);
        var releaseEntered = new CountDownLatch(1);
        var letReleaseExit = new CountDownLatch(1);
        var finalCloseCount = new java.util.concurrent.atomic.AtomicInteger();
        io.register(new AsyncNativeIo.StopThenRelease() {
            @Override public void requestStop() {
                stopRequested.countDown();
            }
            @Override public void close() throws Exception {
                finalCloseCount.incrementAndGet();
                releaseEntered.countDown();
                if (!letReleaseExit.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test final release deadline");
                }
            }
        });
        var operation = io.submit("uninterruptible-pinned-buffer", () -> {
            entered.countDown();
            while (letWorkerExit.getCount() != 0) {
                try { letWorkerExit.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* native owner still active */ }
            }
            return 7;
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            io.close();
            assertTrue(stopRequested.await(3, TimeUnit.SECONDS));
            assertEquals(0, finalCloseCount.get(),
                    "STOP is a cancellation request, not memory reclamation");
            assertFalse(io.physicalShutdown().isDone());
            letWorkerExit.countDown();
            assertInstanceOf(IllegalStateException.class,
                    assertThrows(ExecutionException.class,
                            () -> operation.get(3, TimeUnit.SECONDS)).getCause());
            assertTrue(releaseEntered.await(3, TimeUnit.SECONDS));
            assertEquals(1, finalCloseCount.get());
            assertFalse(io.physicalShutdown().isDone(),
                    "the final host release callback must exit before receipt");
        } finally {
            letWorkerExit.countDown();
            letReleaseExit.countDown();
        }
        assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
        assertEquals(1, finalCloseCount.get(),
                "a staged release must execute exactly once");
    }

    @Test void stagedReleaseCannotOvertakeBlockedTerminalSubscriber()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var workerEntered = new CountDownLatch(1);
        var finishWork = new CountDownLatch(1);
        var callbackEntered = new CountDownLatch(1);
        var allowCallbackExit = new CountDownLatch(1);
        var releaseCount = new java.util.concurrent.atomic.AtomicInteger();
        var stopRequested = new CountDownLatch(1);
        io.register(new AsyncNativeIo.StopThenRelease() {
            @Override public void requestStop() { stopRequested.countDown(); }
            @Override public void close() { releaseCount.incrementAndGet(); }
        });
        var future = io.submit("held-terminal-native-listener", () -> {
            workerEntered.countDown();
            while (finishWork.getCount() != 0) {
                try { finishWork.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* simulated native wait */ }
            }
            return 3;
        });
        future.whenCompleteRuntime((v, f) -> {
            callbackEntered.countDown();
            while (allowCallbackExit.getCount() != 0) {
                try { allowCallbackExit.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { /* retained callback loan */ }
            }
        });
        try {
            assertTrue(workerEntered.await(3, TimeUnit.SECONDS));
            io.close();
            assertTrue(stopRequested.await(3, TimeUnit.SECONDS));
            finishWork.countDown();
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
            assertEquals(0, releaseCount.get(),
                    "isDone alone cannot permit native final release");
            assertFalse(io.physicalShutdown().isDone());
        } finally {
            finishWork.countDown();
            allowCallbackExit.countDown();
        }
        assertNull(io.physicalShutdown().get(3, TimeUnit.SECONDS));
        assertEquals(1, releaseCount.get());
    }

    @Test void failedStagedStopStillAttemptsFinalCloseAndQuarantines()
            throws Exception {
        var io = new AsyncNativeIo(1);
        var requestFailure = new IOException("stop failed before native quiescence");
        var finalCloses = new java.util.concurrent.atomic.AtomicInteger();
        io.register(new AsyncNativeIo.StopThenRelease() {
            @Override public void requestStop() throws IOException {
                throw requestFailure;
            }
            @Override public void close() { finalCloses.incrementAndGet(); }
        });
        io.close();
        assertEquals(1, finalCloses.get(),
                "failed cancellation must not skip eventual safe physical release");
        assertSame(requestFailure, io.physicalShutdownFailure());
        assertFalse(io.physicalShutdown().isDone(),
                "even a successful final close cannot erase a failed stop request");
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
                    val socket = await network.connect_async("127.0.0.1", %d);
                    val reading = socket.read_line_async();
                    self.send("reading");
                    val line = await reading;
                    stdio.println(line);
                    stdio.println(await socket.read_line_async());
                    await socket.write_text_async("ack\\n");
                    await socket.close_async();
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
                  stdio.println(await http.get_text_async("%s/"));
                  stdio.println(await http.post_text_async("%s/", "posted"));
                  await http.get_text_async("%s/redirect");
                  stdio.println("redirect-not-followed");
                  return;
                }
                """.formatted(url, url, url), RuntimePermissions.denyAll().withAllowed(
                    RuntimePermissions.Permission.NET, "127.0.0.1:" + server.getAddress().getPort()), new ByteArrayOutputStream());
            assertEquals("hello\nposted\nredirect-not-followed\n", output.replace("\r", ""));
        } finally { server.stop(0); }
    }
}
