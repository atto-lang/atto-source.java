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
                io.close();

                assertTrue(
                        future.isDone(),
                        "I/O context close should make the Future terminal");
                assertFalse(
                        actor.done().isDone(),
                        "terminal I/O Future is not physical worker retirement");
                assertFalse(
                        actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "actor finalized while native worker was still running");
                assertFalse(
                        block.closed(),
                        "actor arena closed before native worker physical exit");
                assertEquals(1, runtime.actorCount());

                releaseWorker.countDown();

                assertTrue(
                        actor.awaitTermination(5, TimeUnit.SECONDS),
                        "actor did not finalize after native worker exit");
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


}
