package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Real localhost socket integration for the trusted Java IPC host substrate.
 * This executes kernel socket I/O and HMAC framing, but is not Oreslang guest
 * code, TLS/Noise identity, remote deployment or LLVM parity.
 */
public final class IpcAuthenticatedSocketTransportSelfTest {
    private IpcAuthenticatedSocketTransportSelfTest() { }
    private static int checks;

    private static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("authenticated IPC socket invariant failed");
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; ++i) key[i] = (byte) (17 + i);
        return key;
    }

    private static String digest(byte[] payload) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (GeneralSecurityException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static IpcReceiptCorrelation.Envelope envelope(
            long sequence, String session, long route, long grant, byte[] payload) {
        return new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", session, digest(payload),
                1, 2, route, grant, sequence, sequence);
    }

    private static void awaitPhase(
            IpcAuthenticatedSocketTransport transport,
            IpcTransportSessionLease.Phase phase) throws InterruptedException {
        for (int i = 0; i < 400; ++i) {
            if (transport.snapshot().phase() == phase) return;
            Thread.sleep(5);
        }
        throw new AssertionError("transport did not reach phase " + phase
                + ": " + transport.snapshot());
    }

    private static byte[] peerLocalVerdictWithValidMac(
            IpcReceiptCorrelation.Envelope message,
            IpcReceiptCorrelation.Stage localStage,
            byte[] key) throws Exception {
        byte[] frame = IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, key);
        int bodyLength = frame.length - 32;
        // Receipt stage is deliberately the final authenticated body byte.
        frame[bodyLength - 1] = (byte) localStage.ordinal();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] tag = mac.doFinal(Arrays.copyOf(frame, bodyLength));
        System.arraycopy(tag, 0, frame, bodyLength, tag.length);
        return frame;
    }

    public static void main(String[] ignored) throws Exception {
        byte[] key = key();

        // Successful real localhost DATA -> ACK -> terminal receipt exchange.
        byte[] payload = "hello authenticated ores ipc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var message = envelope(1, "socketA", 3, 4, payload);
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(3000);
                    byte[] wire = IpcAuthenticatedSocketTransport.readWireFrame(
                            accepted.getInputStream());
                    var decoded = IpcAuthenticatedSocketTransport.decodeDataFrame(wire, key);
                    check(decoded.envelope().equals(message));
                    check(Arrays.equals(decoded.payload(), payload));
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, key));
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    message, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED, key));
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                }
            }, "ipc-test-peer-success");
            peer.start();

            ExecutorService cleanup = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "ipc-test-cleanup-success"));
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(3000);
                var bridge = new IpcReceiptCompletionBridge(4);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, message, 11, 1, key, cleanup::execute);
                var submission = transport.send(message, payload);
                check(!submission.writeAttemptFailed());
                check(!submission.outcome().toCompletableFuture().isDone());
                check(transport.readVerifiedReceipt()
                        == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
                check(transport.readVerifiedReceipt()
                        == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                check(submission.outcome().toCompletableFuture().join()
                        == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                check(bridge.invariant());
                transport.requestClose();
                awaitPhase(transport, IpcTransportSessionLease.Phase.RETIRED);
                check(transport.snapshot().activeWrites() == 0);
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
            peer.join(3000);
            check(!peer.isAlive());
            check(peerFailure.get() == null);
        }

        // Full route/grant epoch must be validated before receipt admission or wire I/O.
        byte[] epochPayload = "epoch".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var oldEpoch = envelope(1, "reused", 3, 4, epochPayload);
        var upgraded = envelope(2, "reused", 4, 5, epochPayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try {
                var bridge = new IpcReceiptCompletionBridge(2);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, oldEpoch, 71, 1, key, cleanup::execute);
                try {
                    transport.send(upgraded, epochPayload);
                    throw new AssertionError("upgraded route used stale transport authority");
                } catch (IllegalArgumentException expected) {
                    ++checks;
                }
                check(bridge.counters().equals(new IpcReceiptCorrelation.Counters(0, 0)));
                transport.requestClose();
                awaitPhase(transport, IpcTransportSessionLease.Phase.RETIRED);
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        // Closing before send proves no physical write and completes local rejection.
        byte[] rejectedPayload = "never sent".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var rejectedMessage = envelope(1, "closedBeforeSend", 1, 1, rejectedPayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try {
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, rejectedMessage, 72, 1, key, cleanup::execute);
                transport.requestClose();
                awaitPhase(transport, IpcTransportSessionLease.Phase.RETIRED);
                var submission = transport.send(rejectedMessage, rejectedPayload);
                check(submission.writeAttemptFailed());
                check(submission.outcome().toCompletableFuture().join()
                        == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
                check(bridge.counters().equals(new IpcReceiptCorrelation.Counters(0, 0)));
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        // An authenticated peer still cannot assert host-local uncertainty.
        byte[] maliciousPayload = "peer-local-verdict".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var maliciousMessage = envelope(1, "malicious", 3, 4, maliciousPayload);
        peerFailure.set(null);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(3000);
                    var data = IpcAuthenticatedSocketTransport.decodeDataFrame(
                            IpcAuthenticatedSocketTransport.readWireFrame(accepted.getInputStream()),
                            key);
                    check(data.envelope().equals(maliciousMessage));
                    byte[] malicious = peerLocalVerdictWithValidMac(
                            maliciousMessage,
                            IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN,
                            key);
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(), malicious);
                    // Wait for trusted local side to close after rejecting the frame.
                    while (accepted.getInputStream().read() != -1) { }
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                }
            }, "ipc-test-peer-malicious-local-verdict");
            peer.start();

            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(3000);
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, maliciousMessage, 73, 1, key, cleanup::execute);
                var submission = transport.send(maliciousMessage, maliciousPayload);
                check(!submission.writeAttemptFailed());
                try {
                    transport.readVerifiedReceipt();
                    throw new AssertionError("authenticated peer forged local verdict");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("local-only"));
                }
                check(!submission.outcome().toCompletableFuture().isDone());
                transport.requestClose();
                awaitPhase(transport, IpcTransportSessionLease.Phase.RETIRED);
                check(submission.outcome().toCompletableFuture().join()
                        == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
                check(bridge.invariant());
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
            peer.join(3000);
            check(!peer.isAlive());
            check(peerFailure.get() == null);
        }

        // MAC tampering is rejected before receipt state mutation.
        byte[] tamperPayload = "tamper".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var tamperMessage = envelope(1, "tamper", 3, 4, tamperPayload);
        peerFailure.set(null);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    IpcAuthenticatedSocketTransport.readWireFrame(accepted.getInputStream());
                    byte[] receipt = IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                            tamperMessage, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, key);
                    receipt[receipt.length - 1] ^= 0x01;
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(), receipt);
                    while (accepted.getInputStream().read() != -1) { }
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                }
            }, "ipc-test-peer-tamper");
            peer.start();

            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(3000);
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, tamperMessage, 74, 1, key, cleanup::execute);
                var submission = transport.send(tamperMessage, tamperPayload);
                try {
                    transport.readVerifiedReceipt();
                    throw new AssertionError("tampered receipt authenticated");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("authentication failed"));
                }
                check(!submission.outcome().toCompletableFuture().isDone());
                transport.requestClose();
                awaitPhase(transport, IpcTransportSessionLease.Phase.RETIRED);
                check(submission.outcome().toCompletableFuture().join()
                        == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
            peer.join(3000);
            check(!peer.isAlive());
            check(peerFailure.get() == null);
        }

        System.out.println("JAVA_IPC_AUTHENTICATED_SOCKET_HOST_PASS checks=" + checks);
    }
}
