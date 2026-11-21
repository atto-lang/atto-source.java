package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorNativeIoPhysicalRetirementTest {

    @Test
    void terminalIoFutureCannotReleaseActorArenaBeforeWorkerPhysicallyExits()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
             AsyncNativeIo io = new AsyncNativeIo(1)) {
            CountDownLatch workerStarted = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            AtomicReference<ActorRuntime.PrivateMemoryBlock> memory =
                    new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> operation =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                actorContext.privateMemory()
                                        .orElseThrow()
                                        .allocatePrivateBytes(32);
                        block.writeInt(0, 117);
                        memory.set(block);

                        return (message, context) -> {
                            OresFuture<Integer> future = io.submit(
                                    "held-native-worker",
                                    () -> {
                                        workerStarted.countDown();
                                        boolean released = false;
                                        while (!released) {
                                            try {
                                                released = releaseWorker.await(
                                                        5, TimeUnit.SECONDS);
                                            } catch (InterruptedException ignored) {
                                                // AsyncNativeIo.close() interrupts
                                                // workers. Ignore it deliberately
                                                // so Future settlement can race
                                                // ahead of physical worker exit.
                                            }
                                        }
                                        return 7;
                                    });
                            operation.set(future);
                            context.runtime().ownCurrentActorFuture(future);
                        };
                    });

            try {
                actor.send("launch");
                assertTrue(
                        workerStarted.await(5, TimeUnit.SECONDS),
                        "native worker did not start");

                ActorRuntime.PrivateMemoryBlock block = memory.get();
                OresFuture<Integer> future = operation.get();
                assertNotNull(block);
                assertNotNull(future);
                assertFalse(block.closed());

                actor.stop();

                IllegalStateException incomplete = assertThrows(
                        IllegalStateException.class,
                        io::close);
                assertTrue(
                        incomplete.getMessage().contains(
                                "physical cleanup incomplete"));
                assertFalse(
                        future.isDone(),
                        "join timeout must not manufacture terminal completion");
                assertFalse(
                        actor.done().isDone(),
                        "join timeout is not physical worker retirement");
                assertFalse(
                        actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "actor finalized while native worker was still running");
                assertFalse(
                        block.closed(),
                        "actor arena closed before native worker physical exit");
                assertEquals(1, runtime.actorCount());

                releaseWorker.countDown();

                assertEquals(7, future.get(5, TimeUnit.SECONDS));
                assertTrue(
                        actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor did not finalize after native worker exit");
                io.close(); // retry the already-sealed physical join
                assertTrue(block.closed());
                assertTrue(actor.done().isDone());
                assertEquals(0, runtime.actorCount());
            } finally {
                releaseWorker.countDown();
                actor.stop();
            }
        }
    }

    @Test
    void registeredNativeHandlePinsArenaUntilPhysicalCloseCompletes()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
             AsyncNativeIo io = new AsyncNativeIo(1)) {
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch closeEntered = new CountDownLatch(1);
            CountDownLatch releaseClose = new CountDownLatch(1);
            AtomicReference<ActorRuntime.PrivateMemoryBlock> memory =
                    new AtomicReference<>();

            AutoCloseable resource = () -> {
                closeEntered.countDown();
                releaseClose.await();
            };

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                actorContext.privateMemory()
                                        .orElseThrow()
                                        .allocatePrivateBytes(32);
                        block.writeInt(0, 211);
                        memory.set(block);

                        return (message, context) -> {
                            io.register(resource);
                            registered.countDown();
                        };
                    });

            Thread closer = null;
            try {
                actor.send("register");
                assertTrue(registered.await(5, TimeUnit.SECONDS));

                ActorRuntime.PrivateMemoryBlock block = memory.get();
                assertNotNull(block);
                assertFalse(block.closed());

                actor.stop();

                closer = Thread.ofVirtual()
                        .name("native-registration-close-test")
                        .start(io::close);
                assertTrue(
                        closeEntered.await(5, TimeUnit.SECONDS),
                        "registered native handle was not physically closed");

                closer.join(100);
                assertTrue(
                        closer.isAlive(),
                        "I/O close returned before registered handle closed");
                assertFalse(
                        actor.done().isDone(),
                        "registered native handle must withhold actor DONE");
                assertFalse(
                        actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "actor finalized while native handle close was blocked");
                assertFalse(
                        block.closed(),
                        "actor arena closed before registered handle physical close");

                releaseClose.countDown();
                closer.join(5_000);
                assertFalse(closer.isAlive(), "I/O close did not finish");

                assertTrue(
                        actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor did not finalize after registered handle closed");
                assertTrue(block.closed());
                assertTrue(actor.done().isDone());
                assertEquals(0, runtime.actorCount());
            } finally {
                releaseClose.countDown();
                if (closer != null && closer.isAlive()) {
                    closer.join(5_000);
                }
                actor.stop();
            }
        }
    }



    @Test
    void workerCreatedSocketInheritsActorLifetimeAndBareUnregisterCannotForgeClose()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
             AsyncNativeIo io = new AsyncNativeIo(1)) {
            CountDownLatch workerRegistered = new CountDownLatch(1);
            AtomicReference<java.net.Socket> socket = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> operation =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.PrivateMemoryBlock> memory =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                actorContext.privateMemory()
                                        .orElseThrow()
                                        .allocatePrivateBytes(32);
                        block.writeInt(0, 313);
                        memory.set(block);

                        return (message, context) -> {
                            OresFuture<Integer> future = io.submit(
                                    "register-socket-on-worker",
                                    () -> {
                                        java.net.Socket handle =
                                                new java.net.Socket();
                                        io.register(handle);
                                        socket.set(handle);
                                        workerRegistered.countDown();
                                        return 19;
                                    });
                            operation.set(future);
                            context.runtime().ownCurrentActorFuture(future);
                        };
                    });

            try {
                actor.send("launch");
                assertTrue(workerRegistered.await(5, TimeUnit.SECONDS));
                assertEquals(19, operation.get().get(5, TimeUnit.SECONDS));

                java.net.Socket handle = socket.get();
                ActorRuntime.PrivateMemoryBlock block = memory.get();
                assertNotNull(handle);
                assertNotNull(block);
                assertFalse(handle.isClosed());

                actor.stop();

                assertThrows(
                        SecurityException.class,
                        () -> io.unregister(handle),
                        "bare unregister must not forge physical socket retirement");
                assertFalse(actor.done().isDone());
                assertFalse(block.closed());
                assertEquals(1, runtime.actorCount());

                handle.close();
                io.unregister(handle);

                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(block.closed());
                assertTrue(actor.done().isDone());
                assertEquals(0, runtime.actorCount());
            } finally {
                java.net.Socket handle = socket.get();
                if (handle != null && !handle.isClosed()) handle.close();
                actor.stop();
            }
        }
    }



    @Test
    void actorOwnedGenericHandleCannotUnregisterWithoutVerifiableCloseWitness()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                    IsolatePolicy.developer(),
                    new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
             AsyncNativeIo io = new AsyncNativeIo(1)) {
            CountDownLatch registered = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger closeCount =
                    new java.util.concurrent.atomic.AtomicInteger();
            AutoCloseable resource = closeCount::incrementAndGet;

            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                    actorContext -> (message, context) -> {
                        io.register(resource);
                        registered.countDown();
                    });

            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            actor.stop();

            assertThrows(
                    SecurityException.class,
                    () -> io.unregister(resource),
                    "generic actor-owned handle has no verifiable close witness");
            assertFalse(actor.done().isDone());

            io.close();

            assertEquals(1, closeCount.get());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.done().isDone());
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void failedSocketCloseKeepsReceiptPinnedUntilExplicitRetrySucceeds()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
        AsyncNativeIo io = new AsyncNativeIo(1);
        java.util.concurrent.atomic.AtomicInteger closeAttempts =
                new java.util.concurrent.atomic.AtomicInteger();

        java.net.Socket socket = new java.net.Socket() {
            @Override
            public synchronized void close() throws java.io.IOException {
                int attempt = closeAttempts.incrementAndGet();
                if (attempt <= 2) {
                    throw new java.io.IOException(
                            "injected physical close failure " + attempt);
                }
                super.close();
            }
        };

        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<ActorRuntime.PrivateMemoryBlock> memory =
                new AtomicReference<>();
        ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                actorContext -> {
                    ActorRuntime.PrivateMemoryBlock block =
                            actorContext.privateMemory()
                                    .orElseThrow()
                                    .allocatePrivateBytes(32);
                    block.writeInt(0, 401);
                    memory.set(block);
                    return (message, context) -> {
                        io.register(socket);
                        registered.countDown();
                    };
                });

        try {
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            actor.stop();

            IllegalStateException failedClose = assertThrows(
                    IllegalStateException.class,
                    io::close);
            assertTrue(
                    failedClose.getMessage().contains(
                            "resource close failed"));
            assertEquals(2, closeAttempts.get(),
                    "shutdown wakeup + post-join close should both have failed");
            assertFalse(socket.isClosed());
            assertFalse(actor.done().isDone());
            assertFalse(memory.get().closed());
            assertEquals(1, runtime.actorCount());

            io.close();

            assertEquals(3, closeAttempts.get());
            assertTrue(socket.isClosed());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(memory.get().closed());
            assertTrue(actor.done().isDone());
            assertEquals(0, runtime.actorCount());
        } finally {
            try { io.close(); } catch (RuntimeException ignored) { }
            try { socket.close(); } catch (java.io.IOException ignored) { }
            actor.stop();
            runtime.close();
        }
    }

    @Test
    void contextJoinClosesNativeRegistrationBeforePublishingActorDone()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
        AsyncNativeIo io = new AsyncNativeIo(1);
        java.util.concurrent.atomic.AtomicInteger closeCount =
                new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable resource = closeCount::incrementAndGet;
        CountDownLatch registered = new CountDownLatch(1);

        ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                actorContext -> (message, context) -> {
                    io.register(resource);
                    registered.countDown();
                });

        try {
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));

            OresContext.joinActorsAndNativeIo(runtime, io);

            assertEquals(1, closeCount.get());
            assertTrue(actor.done().isDone(),
                    "context join must not return before actor DONE");
            assertEquals(0, runtime.actorCount());
        } finally {
            try { io.close(); } catch (RuntimeException ignored) { }
            runtime.close();
        }
    }



    @Test
    void preShutdownWorkerMayTransferLifetimeToLateSocketAfterSeal()
            throws Exception {
        ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64));
        AsyncNativeIo io = new AsyncNativeIo(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch createLateSocket = new CountDownLatch(1);
        CountDownLatch registrationAttempted = new CountDownLatch(1);
        AtomicReference<java.net.Socket> lateSocket = new AtomicReference<>();
        AtomicReference<ActorRuntime.PrivateMemoryBlock> memory =
                new AtomicReference<>();
        AtomicReference<OresFuture<Integer>> operation =
                new AtomicReference<>();

        ActorRuntime.ActorRef<String> actor = runtime.spawnPrivateTrusted(
                actorContext -> {
                    ActorRuntime.PrivateMemoryBlock block =
                            actorContext.privateMemory()
                                    .orElseThrow()
                                    .allocatePrivateBytes(32);
                    block.writeInt(0, 509);
                    memory.set(block);

                    return (message, context) -> {
                        OresFuture<Integer> future = io.submit(
                                "late-registration",
                                () -> {
                                    workerStarted.countDown();
                                    boolean proceed = false;
                                    while (!proceed) {
                                        try {
                                            proceed = createLateSocket.await(
                                                    5, TimeUnit.SECONDS);
                                        } catch (InterruptedException ignored) {
                                            // Shutdown interrupt is only a
                                            // wakeup request; this worker was
                                            // already physically admitted.
                                        }
                                    }

                                    java.net.Socket socket =
                                            new java.net.Socket();
                                    lateSocket.set(socket);
                                    try {
                                        io.register(socket);
                                        throw new AssertionError(
                                                "late registration unexpectedly published");
                                    } catch (IllegalStateException expected) {
                                        registrationAttempted.countDown();
                                        throw expected;
                                    }
                                });
                        operation.set(future);
                        context.runtime().ownCurrentActorFuture(future);
                    };
                });

        try {
            actor.send("launch");
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            actor.stop();
            io.requestShutdown();
            assertFalse(actor.done().isDone());
            assertFalse(memory.get().closed());

            createLateSocket.countDown();
            assertTrue(registrationAttempted.await(5, TimeUnit.SECONDS));

            OresFuture<Integer> future = operation.get();
            assertNotNull(future);
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));

            java.net.Socket socket = lateSocket.get();
            assertNotNull(socket);
            assertFalse(
                    actor.done().isDone(),
                    "parent worker retirement must not outrun late socket descendant");
            assertFalse(memory.get().closed());
            assertEquals(1, runtime.actorCount());

            io.close();

            assertTrue(socket.isClosed());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(memory.get().closed());
            assertTrue(actor.done().isDone());
            assertEquals(0, runtime.actorCount());
        } finally {
            createLateSocket.countDown();
            java.net.Socket socket = lateSocket.get();
            try { io.close(); } catch (RuntimeException ignored) { }
            if (socket != null && !socket.isClosed()) {
                try { socket.close(); } catch (java.io.IOException ignored) { }
            }
            actor.stop();
            runtime.close();
        }
    }


}
