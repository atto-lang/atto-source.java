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
        String requestedCarriers = System.getProperty(
                "ores.runtime.carriers", "auto").trim()
                .toLowerCase(java.util.Locale.ROOT);

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

            if ("native".equals(requestedCarriers)) {
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
                            submission.outcome().whenComplete((terminal, completionFailure) -> {
                                if (RuntimeBlockingGuard.actorCarrierMarked()) {
                                    asynchronousFailure.compareAndSet(
                                            null,
                                            new AssertionError(
                                                    "IPC completion callback ran on actor carrier"));
                                }
                                if (completionFailure != null) {
                                    asynchronousFailure.compareAndSet(
                                            null, completionFailure);
                                    self.send("failure");
                                    return;
                                }
                                self.send("terminal:" + terminal.name());
                            });
                            if (transport.readVerifiedReceipt()
                                    != IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED) {
                                throw new AssertionError("missing authenticated remote ACK");
                            }
                            if (transport.readVerifiedReceipt()
                                    != IpcReceiptCorrelation.Stage.HANDLER_COMMITTED) {
                                throw new AssertionError("missing authenticated handler COMMIT");
                            }
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

        }
    }

    @Test
    void acknowledgedThenPeerEofProjectsOutcomeUnknownBackToActor() throws Exception {
        String requestedCarriers = System.getProperty(
                "ores.runtime.carriers", "auto").trim()
                .toLowerCase(java.util.Locale.ROOT);

        ExecutorService hostIo = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ipc-actor-eof-host-io");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorService cleanup = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "ipc-actor-eof-cleanup");
            thread.setDaemon(true);
            return thread;
        });

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x6b);
        byte[] connectionBinding = new byte[16];
        Arrays.fill(connectionBinding, (byte) 0x3e);
        byte[] payload = "actor-runtime-ipc-eof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));
        var envelope = new IpcReceiptCorrelation.Envelope(
                "tenant", "actor-source", "worker-destination",
                "actor-runtime-eof-session", digest,
                11, 22, 33, 44, 1, 1);
        long physicalGeneration = 902L;

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

            if ("native".equals(requestedCarriers)) {
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
                    peer.close();
                } catch (Throwable failure) {
                    asynchronousFailure.compareAndSet(null, failure);
                    try {
                        peer.close();
                    } catch (Exception ignoredClose) {
                        // Preserve the first peer failure.
                    }
                }
            }, "ipc-actor-eof-loopback-peer");
            peerWorker.setDaemon(true);
            peerWorker.start();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                if ("start".equals(message)) {
                    ActorRuntime.ActorRef<String> self = context.self();
                    hostIo.execute(() -> {
                        try {
                            var submission = transport.send(envelope, payload);
                            if (submission.writeAttemptFailed()) {
                                throw new AssertionError(
                                        "host IPC DATA write failed",
                                        submission.error());
                            }
                            submission.outcome().whenComplete((terminal, completionFailure) -> {
                                if (RuntimeBlockingGuard.actorCarrierMarked()) {
                                    asynchronousFailure.compareAndSet(
                                            null,
                                            new AssertionError(
                                                    "loss completion callback ran on actor carrier"));
                                }
                                if (completionFailure != null) {
                                    asynchronousFailure.compareAndSet(
                                            null, completionFailure);
                                    self.send("failure");
                                    return;
                                }
                                self.send("terminal:" + terminal.name());
                            });
                            assertEquals(
                                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                                    transport.readVerifiedReceipt());
                            try {
                                transport.readVerifiedReceipt();
                                throw new AssertionError(
                                        "peer EOF unexpectedly produced terminal receipt");
                            } catch (java.io.IOException expectedEof) {
                                // Physical loss after ACK must retire as OUTCOME_UNKNOWN.
                            }
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
                                        "loss result did not re-enter on actor carrier"));
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
                }
            });

            actor.send("start");
            assertTrue(actorDone.await(10, TimeUnit.SECONDS),
                    "actor did not observe IPC loss result");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "actor did not terminate after IPC loss result");
            peerWorker.join(5000);
            assertFalse(peerWorker.isAlive(), "EOF peer remained blocked");
            assertNull(asynchronousFailure.get(),
                    () -> "actor IPC EOF integration failure: "
                            + asynchronousFailure.get());
            assertEquals(
                    IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN,
                    actorObserved.get());
            assertTrue(transport.requestCloseAndAwait(3000));
            assertTrue(transport.keyCloneZeroized());
            assertTrue(bridge.invariant());
        } finally {
            hostIo.shutdownNow();
            cleanup.shutdownNow();
            hostIo.awaitTermination(2, TimeUnit.SECONDS);
            cleanup.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

}
