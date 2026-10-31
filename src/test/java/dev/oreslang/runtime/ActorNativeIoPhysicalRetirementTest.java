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
}
