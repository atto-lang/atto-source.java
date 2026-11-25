package dev.oreslang.runtime;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real-socket proof that actor carriers cannot enter blocking IPC host APIs. */
public final class IpcBlockingGuardSelfTest {
    private IpcBlockingGuardSelfTest() { }
    private static int checks;

    private static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("IPC blocking guard invariant failed");
    }

    private static String digest(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload));
    }

    private static IpcReceiptCorrelation.Envelope envelope(
            String session, byte[] payload) throws Exception {
        return new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", session, digest(payload),
                1, 2, 3, 4, 1, 1);
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; ++i) key[i] = (byte) (31 + i);
        return key;
    }

    private static byte[] binding() {
        byte[] binding = new byte[16];
        for (int i = 0; i < binding.length; ++i) binding[i] = (byte) (71 + i);
        return binding;
    }

    private static IpcPhysicalConnectionBroker.Authority authority(
            long generation, byte[] key, byte[] binding) {
        byte[] expectedBinding = binding.clone();
        var broker = new IpcPhysicalConnectionBroker(target ->
                System.arraycopy(expectedBinding, 0, target, 0, target.length));
        return broker.mint(generation, key);
    }

    private static void actorCarrierDenied(Runnable operation, String operationName) {
        RuntimeBlockingGuard.enterActorCarrier();
        try {
            check(RuntimeBlockingGuard.actorCarrierMarked());
            try {
                operation.run();
                throw new AssertionError("actor carrier entered " + operationName);
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("cannot block an actor carrier"));
            }
        } finally {
            RuntimeBlockingGuard.exitActorCarrier();
        }
        check(!RuntimeBlockingGuard.actorCarrierMarked());
    }

    public static void main(String[] ignored) throws Exception {
        byte[] key = key();
        byte[] binding = binding();

        // Replay admission/diagnostics are host protocol operations too.
        // Hold the replay gate monitor and prove actor carriers fail before
        // monitor entry rather than parking behind a host parser.
        {
            byte[] payload = "guard-replay".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            var message = envelope("guardReplay", payload);
            var replay = new IpcAuthenticatedSocketTransport.InboundReplayGate();
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Throwable> holderFailure = new AtomicReference<>();
            Thread holder = new Thread(() -> {
                synchronized (replay) {
                    held.countDown();
                    try {
                        if (!release.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("replay monitor hold timed out");
                        }
                    } catch (Throwable failure) {
                        holderFailure.set(failure);
                    }
                }
            }, "ipc-test-replay-monitor-holder");
            holder.start();
            check(held.await(3, TimeUnit.SECONDS));

            AtomicInteger denials = new AtomicInteger();
            AtomicReference<Throwable> carrierFailure = new AtomicReference<>();
            Thread carrier = new Thread(() -> {
                RuntimeBlockingGuard.enterActorCarrier();
                try {
                    Runnable[] probes = {
                            () -> replay.accept(message),
                            () -> replay.lastMessageSequence(),
                            () -> replay.lastFrameSequence()
                    };
                    for (Runnable probe : probes) {
                        try {
                            probe.run();
                            throw new AssertionError(
                                    "actor carrier entered IPC replay monitor");
                        } catch (IllegalStateException expected) {
                            denials.incrementAndGet();
                        }
                    }
                } catch (Throwable failure) {
                    carrierFailure.set(failure);
                } finally {
                    RuntimeBlockingGuard.exitActorCarrier();
                }
            }, "ipc-test-replay-actor-carrier");
            carrier.start();
            carrier.join(500);
            check(!carrier.isAlive());
            check(carrierFailure.get() == null);
            check(denials.get() == 3);

            release.countDown();
            holder.join(3000);
            check(!holder.isAlive());
            check(holderFailure.get() == null);
            replay.accept(message);
            check(replay.lastMessageSequence() == 1);
            check(replay.lastFrameSequence() == 1);
        }

        {
            byte[] payload = "guard-construction".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            var message = envelope("guardConstruction", payload);
            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (ServerSocket server = new ServerSocket(
                        0, 8, InetAddress.getLoopbackAddress());
                 Socket client = new Socket(
                        InetAddress.getLoopbackAddress(), server.getLocalPort());
                 Socket peer = server.accept()) {
                check(peer.isConnected());
                var bridge = new IpcReceiptCompletionBridge(1);
                var constructionAuthority = authority(300, key, binding);

                actorCarrierDenied(() -> {
                    try {
                        new IpcAuthenticatedSocketTransport(
                                client, bridge, message, 1,
                                constructionAuthority, cleanup::execute);
                        throw new AssertionError(
                                "actor carrier constructed IPC socket transport");
                    } catch (IOException impossibleBeforeGuard) {
                        throw new AssertionError(impossibleBeforeGuard);
                    }
                }, "IPC socket transport construction");

                check(!constructionAuthority.consumedOrDestroyed());
                check(!client.isClosed());

                // The exact authority remains valid because actor rejection
                // happened before stream acquisition or ownership transfer.
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, message, 1,
                        constructionAuthority, cleanup::execute);
                check(constructionAuthority.consumedOrDestroyed());
                check(transport.requestCloseAndAwait(3000));
                check(transport.keyCloneZeroized());
                check(transport.connectionBindingCloneZeroized());
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        {
            byte[] payload = "guard-send".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var message = envelope("guardSend", payload);
            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                 Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                 Socket peer = server.accept()) {
                peer.setSoTimeout(150);
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, message, 1,
                        authority(301, key, binding), cleanup::execute);

                actorCarrierDenied(
                        () -> transport.send(message, payload),
                        "IPC socket send");
                check(bridge.counters().equals(
                        new IpcReceiptCorrelation.Counters(0, 0)));
                try {
                    peer.getInputStream().read();
                    throw new AssertionError("actor-carrier send wrote socket bytes");
                } catch (SocketTimeoutException expectedNoBytes) {
                    ++checks;
                }
                check(transport.snapshot().phase()
                        == IpcTransportSessionLease.Phase.OPEN);
                check(transport.requestCloseAndAwait(3000));
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        {
            byte[] payload = "guard-read".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var message = envelope("guardRead", payload);
            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                 Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                 Socket peer = server.accept()) {
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, message, 1,
                        authority(302, key, binding), cleanup::execute);

                DataOutputStream peerOut = new DataOutputStream(peer.getOutputStream());
                peerOut.writeInt(1);
                peerOut.flush();

                actorCarrierDenied(() -> {
                    try {
                        transport.readVerifiedReceipt();
                    } catch (IOException impossibleBeforeGuard) {
                        throw new AssertionError(impossibleBeforeGuard);
                    }
                }, "IPC receipt read");
                check(transport.snapshot().phase()
                        == IpcTransportSessionLease.Phase.OPEN);

                try {
                    transport.readVerifiedReceipt();
                    throw new AssertionError("invalid queued receipt accepted");
                } catch (IOException expectedQueuedFrame) {
                    check(expectedQueuedFrame.getMessage().contains(
                            "bounded IPC wire frame length"));
                }
                check(transport.requestCloseAndAwait(3000));
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        {
            byte[] payload = "guard-close".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var message = envelope("guardClose", payload);
            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                 Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                 Socket peer = server.accept()) {
                check(peer.isConnected());
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, message, 1,
                        authority(303, key, binding), cleanup::execute);

                actorCarrierDenied(
                        transport::requestClose,
                        "IPC transport close request");
                check(transport.snapshot().phase()
                        == IpcTransportSessionLease.Phase.OPEN);
                actorCarrierDenied(() -> {
                    try {
                        transport.requestCloseAndAwait(100);
                    } catch (InterruptedException impossibleBeforeGuard) {
                        throw new AssertionError(impossibleBeforeGuard);
                    }
                }, "IPC transport close wait");
                check(transport.snapshot().phase()
                        == IpcTransportSessionLease.Phase.OPEN);
                check(transport.requestCloseAndAwait(3000));
                check(transport.keyCloneZeroized());
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        System.out.println("JAVA_IPC_BLOCKING_GUARD_PASS checks=" + checks);
    }
}
