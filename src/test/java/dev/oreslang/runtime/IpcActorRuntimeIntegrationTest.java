package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime -> trusted host I/O -> authenticated socket -> actor-mailbox
 * integration. This is still one JVM/loopback host evidence, not a remote
 * deployment, broker trust-root proof, Graal Native Image, or LLVM parity.
 */
final class IpcActorRuntimeIntegrationTest {

    @Test
    void actorHandsBlockingIpcToHostWorkerAndReceivesTerminalOutcome() throws Exception {
        String previousCarriers = System.getProperty("ores.runtime.carriers");
        String previousAffinity = System.getProperty("ores.actor.affinity");
        // Respect an explicitly requested CI/production carrier backend.
        // Use the portable Java carrier only when the caller did not select
        // one, while disabling affinity so this test does not require CPU
        // pinning configuration.
        if (previousCarriers == null) {
            System.setProperty("ores.runtime.carriers", "java");
        }
        System.setProperty("ores.actor.affinity", "none");

        ExecutorService hostIo = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ipc-actor-host-io");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService cleanup = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ipc-actor-cleanup");
            thread.setDaemon(true);
            return thread;
        });

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5a);
        byte[] connectionBinding = new byte[16];
        Arrays.fill(connectionBinding, (byte) 0x2d);
        byte[] payload = "actor-runtime-ipc".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));
        var envelope = new IpcReceiptCorrelation.Envelope(
                "tenant", "actor-source", "worker-destination",
                "actor-runtime-session", digest,
                11, 22, 33, 44, 1, 1);
        long physicalGeneration = 901L;

        AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        AtomicReference<IpcReceiptCorrelation.Stage> actorObserved = new AtomicReference<>();
        CountDownLatch actorDone = new CountDownLatch(1);

        try (ServerSocket server =
                     new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client =
                     new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept();
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(1, 1, 8, 64))) {

            if ("native".equals(previousCarriers)) {
                assertEquals(
                        ActorRuntime.CarrierBackend.NATIVE_PTHREAD,
                        runtime.carrierBackend(),
                        "native-carrier verification must exercise pthread actor carriers");
            }

            var bridge = new IpcReceiptCompletionBridge(4);
            var transport = new IpcAuthenticatedSocketTransport(
                    client,
                    bridge,
                    envelope,
                    physicalGeneration,
                    1,
                    key,
                    connectionBinding,
                    cleanup::execute);

            Thread peerWorker = new Thread(() -> {
                try {
                    byte[] wire = IpcAuthenticatedSocketTransport.readDataWireFrame(
                            peer.getInputStream());
                    var decoded = IpcAuthenticatedSocketTransport.decodeDataFrame(
                            wire,
                            envelope,
                            physicalGeneration,
                            new IpcAuthenticatedSocketTransport.InboundReplayGate(),
                            connectionBinding,
                            key);
                    if (!decoded.envelope().equals(envelope)
                            || !Arrays.equals(decoded.payload(), payload)) {
                        throw new AssertionError(
                                "peer decoded a different actor IPC envelope/payload");
                    }

                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            peer.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    envelope,
                                    physicalGeneration,
                                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                                    connectionBinding,
                                    key));
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            peer.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    envelope,
                                    physicalGeneration,
                                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED,
                                    connectionBinding,
                                    key));
                } catch (Throwable failure) {
                    asynchronousFailure.compareAndSet(null, failure);
                    try {
                        peer.close();
                    } catch (Exception ignored) {
                        // Preserve the first peer failure.
                    }
                }
            }, "ipc-actor-loopback-peer");
            peerWorker.setDaemon(true);
            peerWorker.start();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("start".equals(message)) {
                    if (!RuntimeBlockingGuard.actorCarrierMarked()) {
                        asynchronousFailure.compareAndSet(
                                null,
                                new AssertionError("actor turn was not carrier-marked"));
                        actorDone.countDown();
                        context.self().stop();
                        return;
                    }

                    try {
                        transport.send(envelope, payload);
                        asynchronousFailure.compareAndSet(
                                null,
                                new AssertionError(
                                        "actor carrier entered blocking IPC send"));
                        actorDone.countDown();
                        context.self().stop();
                        return;
                    } catch (IllegalStateException expected) {
                        if (!expected.getMessage().contains(
                                "cannot block an actor carrier")) {
                            asynchronousFailure.compareAndSet(null, expected);
                            actorDone.countDown();
                            context.self().stop();
                            return;
                        }
                    }

                    ActorRuntime.ActorRef<String> self = context.self();
                    hostIo.execute(() -> {
                        try {
                            if (RuntimeBlockingGuard.actorCarrierMarked()) {
                                throw new AssertionError(
                                        "host IPC worker inherited actor-carrier authority");
                            }
                            var submission = transport.send(envelope, payload);
                            if (submission.writeAttemptFailed()) {
                                throw new AssertionError(
                                        "host IPC DATA write failed",
                                        submission.error());
                            }
                            if (transport.readVerifiedReceipt()
                                    != IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED) {
                                throw new AssertionError("missing authenticated remote ACK");
                            }
                            if (transport.readVerifiedReceipt()
                                    != IpcReceiptCorrelation.Stage.HANDLER_COMMITTED) {
                                throw new AssertionError("missing authenticated handler COMMIT");
                            }
                            var terminal = submission.outcome()
                                    .toCompletableFuture()
                                    .join();
                            self.send("terminal:" + terminal.name());
                        } catch (Throwable failure) {
                            asynchronousFailure.compareAndSet(null, failure);
                            self.send("failure");
                        }
                    });
                    return;
                }

                if (message.startsWith("terminal:")) {
                    if (!RuntimeBlockingGuard.actorCarrierMarked()) {
                        asynchronousFailure.compareAndSet(
                                null,
                                new AssertionError(
                                        "terminal result did not re-enter on actor carrier"));
                    }
                    actorObserved.set(IpcReceiptCorrelation.Stage.valueOf(
                            message.substring("terminal:".length())));
                    actorDone.countDown();
                    context.self().stop();
                    return;
                }

                if ("failure".equals(message)) {
                    actorDone.countDown();
                    context.self().stop();
                    return;
                }

                asynchronousFailure.compareAndSet(
                        null,
                        new AssertionError("unexpected actor IPC integration message"));
                actorDone.countDown();
                context.self().stop();
            });

            actor.send("start");

            assertTrue(actorDone.await(10, TimeUnit.SECONDS),
                    "actor did not observe IPC terminal result");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "actor did not terminate after observing IPC result");
            peerWorker.join(5000);
            assertFalse(peerWorker.isAlive(), "loopback peer remained blocked");
            assertNull(asynchronousFailure.get(),
                    () -> "actor IPC integration failure: " + asynchronousFailure.get());
            assertEquals(
                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED,
                    actorObserved.get());
            assertTrue(bridge.invariant());

            assertTrue(transport.requestCloseAndAwait(3000),
                    "transport did not retire after terminal actor integration");
            assertTrue(transport.keyCloneZeroized());
            assertTrue(bridge.invariant());
        } finally {
            hostIo.shutdownNow();
            cleanup.shutdownNow();
            hostIo.awaitTermination(2, TimeUnit.SECONDS);
            cleanup.awaitTermination(2, TimeUnit.SECONDS);

            if (previousCarriers == null) {
                System.clearProperty("ores.runtime.carriers");
            } else {
                System.setProperty("ores.runtime.carriers", previousCarriers);
            }
            if (previousAffinity == null) {
                System.clearProperty("ores.actor.affinity");
            } else {
                System.setProperty("ores.actor.affinity", previousAffinity);
            }
        }
    }
}
