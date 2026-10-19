package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    private static byte[] binding(int seed) {
        byte[] binding = new byte[16];
        for (int i = 0; i < binding.length; ++i) {
            binding[i] = (byte) (seed + i + 1);
        }
        return binding;
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

    private static void offCallerCleanup(Runnable cleanup) {
        Thread worker = new Thread(cleanup, "ipc-test-cleanup-off-caller");
        worker.start();
        try {
            worker.join(3000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cleanup test interrupted", interrupted);
        }
        if (worker.isAlive()) {
            throw new IllegalStateException("cleanup test worker did not finish");
        }
    }

    private static byte[] peerLocalVerdictWithValidMac(
            IpcReceiptCorrelation.Envelope message,
            long physicalGeneration,
            IpcReceiptCorrelation.Stage localStage,
            byte[] connectionBinding,
            byte[] key) throws Exception {
        byte[] frame = IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                message, physicalGeneration,
                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                connectionBinding, key);
        int bodyLength = frame.length - 32;
        // Receipt code is deliberately the final authenticated body byte.
        // Local enum ordinals have no valid v3 wire meaning; re-MAC the frame
        // to prove authentication alone cannot authorize a host-local verdict.
        frame[bodyLength - 1] = (byte) localStage.ordinal();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] tag = mac.doFinal(Arrays.copyOf(frame, bodyLength));
        System.arraycopy(tag, 0, frame, bodyLength, tag.length);
        return frame;
    }

    public static void main(String[] ignored) throws Exception {
        byte[] key = key();
        byte[] connectionBinding = binding(1);

        // TCP stream publication is single-file. Physical write concurrency
        // greater than one is rejected; outstanding receipt lifetimes are a
        // separate bridge/correlation concern.
        byte[] configPayload = "config".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var configMessage = envelope(1, "config", 3, 4, configPayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            var bridge = new IpcReceiptCompletionBridge(2);
            try {
                new IpcAuthenticatedSocketTransport(
                        client, bridge, configMessage, 10, 2, key, connectionBinding,
                        IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
                throw new AssertionError("parallel TCP physical writers accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("exactly one"));
            }
            check(!client.isClosed());
        }

        // Invalid close deadlines are side-effect free: validation occurs
        // before close intent or physical abort is submitted.
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    client, bridge, configMessage, 10, 1, key, connectionBinding,
                    IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
            try {
                transport.requestCloseAndAwait(-1);
                throw new AssertionError("invalid close deadline accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("invalid IPC close wait"));
            }
            check(transport.snapshot().phase() == IpcTransportSessionLease.Phase.OPEN);
            check(!client.isClosed());

            // Blocking supervisor waits are forbidden on actor carriers. The
            // guard must fail before close intent, socket abort, or key erasure.
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                try {
                    transport.requestCloseAndAwait(1);
                    throw new AssertionError("actor carrier was allowed to block on IPC close");
                } catch (IllegalStateException expected) {
                    check(expected.getMessage().contains("cannot block an actor carrier"));
                }
                check(transport.snapshot().phase() == IpcTransportSessionLease.Phase.OPEN);
                check(!client.isClosed());
                check(!transport.keyCloneZeroized());
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
            check(!RuntimeBlockingGuard.actorCarrierMarked());
            check(transport.requestCloseAndAwait(3000));
            check(transport.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
        }

        // Successful real localhost DATA -> ACK -> terminal receipt exchange.
        byte[] payload = "hello authenticated ores ipc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var message = envelope(1, "socketA", 3, 4, payload);
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(3000);
                    byte[] wire = IpcAuthenticatedSocketTransport.readDataWireFrame(
                            accepted.getInputStream());
                    var decoded = IpcAuthenticatedSocketTransport.decodeDataFrame(
                            wire, message, 11,
                            new IpcAuthenticatedSocketTransport.InboundReplayGate(), key);
                    check(decoded.envelope().equals(message));
                    check(decoded.physicalGeneration() == 11);
                    check(Arrays.equals(decoded.payload(), payload));
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    message, 11,
                                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, key));
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    message, 11,
                                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED, key));
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
                        client, bridge, message, 11, 1, key, connectionBinding, cleanup::execute);
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
                check(transport.keyCloneZeroized());
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
                        client, bridge, oldEpoch, 71, 1, key, connectionBinding, cleanup::execute);
                try {
                    transport.send(upgraded, epochPayload);
                    throw new AssertionError("upgraded route used stale transport authority");
                } catch (IllegalArgumentException expected) {
                    ++checks;
                }
                check(bridge.counters().equals(new IpcReceiptCorrelation.Counters(0, 0)));
                transport.requestClose();
                awaitPhase(transport, IpcTransportSessionLease.Phase.RETIRED);
                check(transport.keyCloneZeroized());
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
                        client, bridge, rejectedMessage, 72, 1, key, connectionBinding, cleanup::execute);
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
                            IpcAuthenticatedSocketTransport.readDataWireFrame(accepted.getInputStream()),
                            maliciousMessage, 73,
                            new IpcAuthenticatedSocketTransport.InboundReplayGate(), key);
                    check(data.envelope().equals(maliciousMessage));
                    byte[] malicious = peerLocalVerdictWithValidMac(
                            maliciousMessage,
                            73,
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
                        client, bridge, maliciousMessage, 73, 1, key, connectionBinding, cleanup::execute);
                var submission = transport.send(maliciousMessage, maliciousPayload);
                check(!submission.writeAttemptFailed());
                try {
                    transport.readVerifiedReceipt();
                    throw new AssertionError("authenticated peer forged local verdict");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("invalid IPC receipt code"));
                }
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

        // Correctly authenticated receipts are also bound to the
        // physical connection generation, preventing reconnect replay.
        byte[] replayPayload = "generation replay".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var replayMessage = envelope(1, "generationReplay", 3, 4, replayPayload);
        peerFailure.set(null);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    IpcAuthenticatedSocketTransport.readDataWireFrame(accepted.getInputStream());
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    replayMessage, 80,
                                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, key));
                    while (accepted.getInputStream().read() != -1) { }
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                }
            }, "ipc-test-peer-generation-replay");
            peer.start();

            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(3000);
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, replayMessage, 81, 1, key, connectionBinding, cleanup::execute);
                var submission = transport.send(replayMessage, replayPayload);
                check(!submission.writeAttemptFailed());
                try {
                    transport.readVerifiedReceipt();
                    throw new AssertionError("old physical generation receipt accepted");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("physical transport generation"));
                }
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

        // Receiver-side DATA verification is safe-by-default: a captured
        // valid-MAC frame cannot be reused on another physical generation or
        // a route/grant epoch sharing the same session text.
        byte[] replayDataPayload = "replayed data".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var replayDataMessage = envelope(1, "dataReplay", 3, 4, replayDataPayload);
        byte[] capturedData = IpcAuthenticatedSocketTransport.encodeDataFrame(
                replayDataMessage, replayDataPayload, 82, key);
        // v2 changed receipt wire codes away from Java enum ordinals.
        // Even with a valid MAC, a captured v1-version header is rejected
        // rather than interpreted under the new state-code mapping.
        byte[] oldVersionData = capturedData.clone();
        int oldVersionBodyLength = oldVersionData.length - 32;
        oldVersionData[4] = 1; // magic is four bytes; version follows.
        Mac oldVersionMac = Mac.getInstance("HmacSHA256");
        oldVersionMac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] oldVersionTag = oldVersionMac.doFinal(
                Arrays.copyOf(oldVersionData, oldVersionBodyLength));
        System.arraycopy(oldVersionTag, 0, oldVersionData,
                oldVersionBodyLength, oldVersionTag.length);
        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    oldVersionData, replayDataMessage, 82,
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(), key);
            throw new AssertionError("authenticated v1 DATA accepted by v2 transport");
        } catch (IOException expected) {
            check(expected.getMessage().contains("invalid IPC frame header"));
        }

        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    capturedData, replayDataMessage, 83,
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(), key);
            throw new AssertionError("old physical generation DATA accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("transport epoch/generation"));
        }
        var changedDataEpoch = envelope(2, "dataReplay", 4, 5, replayDataPayload);
        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    capturedData, changedDataEpoch, 82,
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(), key);
            throw new AssertionError("old route/grant DATA accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("transport epoch/generation"));
        }
        var replayGate = new IpcAuthenticatedSocketTransport.InboundReplayGate();
        var verifiedData = IpcAuthenticatedSocketTransport.decodeDataFrame(
                capturedData, replayDataMessage, 82, replayGate, key);
        check(Arrays.equals(verifiedData.payload(), replayDataPayload));
        check(verifiedData.physicalGeneration() == 82);
        check(replayGate.lastMessageSequence() == 1);
        check(replayGate.lastFrameSequence() == 1);
        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    capturedData, replayDataMessage, 82, replayGate, key);
            throw new AssertionError("exact authenticated DATA replay accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("replayed/reordered"));
        }
        var nextDataMessage = envelope(2, "dataReplay", 3, 4, replayDataPayload);
        byte[] nextData = IpcAuthenticatedSocketTransport.encodeDataFrame(
                nextDataMessage, replayDataPayload, 82, key);
        IpcAuthenticatedSocketTransport.decodeDataFrame(
                nextData, replayDataMessage, 82, replayGate, key);
        check(replayGate.lastMessageSequence() == 2);
        check(replayGate.lastFrameSequence() == 2);
        var rolledFrame = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "dataReplay", digest(replayDataPayload),
                1, 2, 3, 4, 3, 2);
        byte[] rolledFrameData = IpcAuthenticatedSocketTransport.encodeDataFrame(
                rolledFrame, replayDataPayload, 82, key);
        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    rolledFrameData, replayDataMessage, 82, replayGate, key);
            throw new AssertionError("rolled-back authenticated frame sequence accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("replayed/reordered"));
        }

        // Receipt framing has a small pre-authentication allocation cap.
        // A hostile length prefix must be rejected before attempting to
        // allocate/read the advertised body.
        ByteArrayOutputStream oversizedPrefix = new ByteArrayOutputStream();
        try (DataOutputStream oversized = new DataOutputStream(oversizedPrefix)) {
            oversized.writeInt(4096);
        }
        try {
            IpcAuthenticatedSocketTransport.readReceiptWireFrame(
                    new ByteArrayInputStream(oversizedPrefix.toByteArray()));
            throw new AssertionError("oversized unauthenticated receipt accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("bounded IPC wire frame length"));
        }

        // MAC tampering is rejected before receipt state mutation.
        byte[] tamperPayload = "tamper".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var tamperMessage = envelope(1, "tamper", 3, 4, tamperPayload);
        peerFailure.set(null);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    IpcAuthenticatedSocketTransport.readDataWireFrame(accepted.getInputStream());
                    byte[] receipt = IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                            tamperMessage, 74,
                            IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, key);
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
                        client, bridge, tamperMessage, 74, 1, key, connectionBinding, cleanup::execute);
                var submission = transport.send(tamperMessage, tamperPayload);
                try {
                    transport.readVerifiedReceipt();
                    throw new AssertionError("tampered receipt authenticated");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("authentication failed"));
                }
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

        // A caller-runs cleanup scheduler is invalid for a physical transport.
        // The lease detects it before socket close/key destruction and fails closed.
        byte[] inlinePayload = "inline cleanup".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var inlineMessage = envelope(1, "inlineCleanupAdapter", 3, 4, inlinePayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            peer.setSoTimeout(3000);
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    client, bridge, inlineMessage, 88, 1, key, connectionBinding, Runnable::run);
            var submission = transport.send(inlineMessage, inlinePayload);
            check(!submission.writeAttemptFailed());
            IpcAuthenticatedSocketTransport.readDataWireFrame(peer.getInputStream());
            transport.requestClose();
            check(transport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!transport.keyCloneZeroized());
            check(!submission.outcome().toCompletableFuture().isDone());
            check(bridge.invariant());
        }

        // Cleanup scheduler rejection quarantines the connection. Only an
        // explicit supervisor retry may finish physical-close confirmation and
        // trusted receipt retirement; no automatic reopen or replay occurs.
        byte[] quarantinePayload = "quarantine adapter".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var quarantineMessage = envelope(1, "adapterQuarantine", 3, 4, quarantinePayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            peer.setSoTimeout(3000);
            var bridge = new IpcReceiptCompletionBridge(2);
            AtomicInteger schedules = new AtomicInteger();
            IpcTransportSessionLease.CleanupScheduler scheduler = cleanup -> {
                if (schedules.incrementAndGet() == 1) {
                    throw new IllegalStateException("cleanup queue saturated");
                }
                offCallerCleanup(cleanup); // deterministic host-supervisor retry fixture
            };
            var transport = new IpcAuthenticatedSocketTransport(
                    client, bridge, quarantineMessage, 89, 1, key, connectionBinding, scheduler);
            var submission = transport.send(quarantineMessage, quarantinePayload);
            check(!submission.writeAttemptFailed());
            var decoded = IpcAuthenticatedSocketTransport.decodeDataFrame(
                    IpcAuthenticatedSocketTransport.readDataWireFrame(peer.getInputStream()),
                    quarantineMessage, 89,
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(), key);
            check(decoded.physicalGeneration() == 89);
            transport.requestClose();
            check(transport.snapshot().phase() == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!transport.keyCloneZeroized());
            check(!submission.outcome().toCompletableFuture().isDone());
            transport.retryQuarantinedClose();
            check(transport.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
            check(transport.keyCloneZeroized());
            check(submission.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(schedules.get() == 2);
            check(bridge.invariant());
        }

        // Close must actively interrupt a silent peer read; receipt
        // retirement still waits for the trusted physical close worker.
        byte[] silentPayload = "silent peer".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var silentMessage = envelope(1, "silentPeer", 3, 4, silentPayload);
        peerFailure.set(null);
        CountDownLatch peerSawData = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(3000);
                    IpcAuthenticatedSocketTransport.readDataWireFrame(accepted.getInputStream());
                    peerSawData.countDown();
                    while (accepted.getInputStream().read() != -1) { }
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                }
            }, "ipc-test-peer-silent-read");
            peer.start();

            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, silentMessage, 90, 1, key, connectionBinding, cleanup::execute);
                var submission = transport.send(silentMessage, silentPayload);
                check(!submission.writeAttemptFailed());
                check(peerSawData.await(2, TimeUnit.SECONDS));
                AtomicReference<Throwable> readFailure = new AtomicReference<>();
                Thread reader = new Thread(() -> {
                    try {
                        transport.readVerifiedReceipt();
                        readFailure.compareAndSet(null,
                                new AssertionError("silent peer unexpectedly produced receipt"));
                    } catch (IOException expected) {
                        readFailure.compareAndSet(null, expected);
                    }
                }, "ipc-test-blocked-receipt-reader");
                reader.start();
                Thread.sleep(30);
                check(reader.isAlive());
                check(transport.requestCloseAndAwait(3000));
                reader.join(3000);
                check(!reader.isAlive());
                check(readFailure.get() instanceof IOException);
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

        // A peer that never reads must not pin a synchronous DATA writer
        // forever. Closing the adapter aborts the Java Socket; the send lease
        // is not retired until the writer unwinds.
        byte[] blockedPayload = new byte[8 * 1024 * 1024];
        Arrays.fill(blockedPayload, (byte) 0x5a);
        var blockedMessage = envelope(1, "blockedWrite", 3, 4, blockedPayload);
        peerFailure.set(null);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            client.setSendBufferSize(1024);
            peer.setReceiveBufferSize(1024);
            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try {
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, blockedMessage, 91, 1, key, connectionBinding, cleanup::execute);
                AtomicReference<IpcAuthenticatedSocketTransport.SendSubmission> sendResult =
                        new AtomicReference<>();
                AtomicReference<Throwable> sendFailure = new AtomicReference<>();
                Thread sender = new Thread(() -> {
                    try {
                        sendResult.set(transport.send(blockedMessage, blockedPayload));
                    } catch (Throwable failure) {
                        sendFailure.compareAndSet(null, failure);
                    }
                }, "ipc-test-blocked-data-writer");
                sender.start();
                boolean observedPinnedWrite = false;
                for (int i = 0; i < 400; ++i) {
                    if (transport.snapshot().activeWrites() == 1 && sender.isAlive()) {
                        observedPinnedWrite = true;
                        break;
                    }
                    Thread.sleep(5);
                }
                check(observedPinnedWrite);
                Thread.sleep(40);
                check(sender.isAlive());
                check(transport.requestCloseAndAwait(3000));
                sender.join(1000);
                check(!sender.isAlive());
                check(sendFailure.get() == null);
                check(sendResult.get() != null);
                check(sendResult.get().writeAttemptFailed());
                awaitPhase(transport, IpcTransportSessionLease.Phase.RETIRED);
                check(transport.snapshot().activeWrites() == 0);
                check(sendResult.get().outcome().toCompletableFuture().join()
                        == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
                check(bridge.invariant());
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
        }

        // If active-write abort scheduling itself is rejected, the
        // generation is quarantined. A host supervisor can explicitly retry
        // the abort after capacity returns, but receipt retirement remains a
        // separate explicit recovery after the write pin has drained.
        var retryAbortMessage = envelope(1, "retryAbort", 3, 4, blockedPayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            client.setSendBufferSize(1024);
            peer.setReceiveBufferSize(1024);
            AtomicInteger scheduled = new AtomicInteger();
            IpcTransportSessionLease.CleanupScheduler scheduler = cleanup -> {
                if (scheduled.incrementAndGet() == 1) {
                    throw new IllegalStateException("abort queue saturated");
                }
                offCallerCleanup(cleanup);
            };
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    client, bridge, retryAbortMessage, 915, 1, key, connectionBinding, scheduler);
            AtomicReference<IpcAuthenticatedSocketTransport.SendSubmission> result =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try {
                    result.set(transport.send(retryAbortMessage, blockedPayload));
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }, "ipc-test-retry-abort-writer");
            sender.start();
            boolean pinned = false;
            for (int i = 0; i < 400; ++i) {
                if (transport.snapshot().activeWrites() == 1 && sender.isAlive()) {
                    pinned = true;
                    break;
                }
                Thread.sleep(5);
            }
            check(pinned);
            check(!transport.requestCloseAndAwait(3000));
            check(transport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(transport.snapshot().activeWrites() == 1);
            check(sender.isAlive());
            transport.retryQuarantinedAbort();
            sender.join(3000);
            check(!sender.isAlive());
            check(failure.get() == null);
            check(result.get() != null && result.get().writeAttemptFailed());
            check(transport.snapshot().activeWrites() == 0);
            check(transport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            transport.retryQuarantinedClose();
            check(transport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(result.get().outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(transport.keyCloneZeroized());
            check(scheduled.get() == 3);
            check(bridge.invariant());
        }

        // If the bounded host cleanup domain accepts but does not run the
        // active-write abort before the supervisor deadline, shutdown returns
        // false and the physical generation is quarantined. No retry/replay is
        // inferred; an explicit supervisor recovery is required after the pin drains.
        var deadlineMessage = envelope(1, "blockedDeadline", 3, 4, blockedPayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            client.setSendBufferSize(1024);
            peer.setReceiveBufferSize(1024);
            AtomicInteger scheduled = new AtomicInteger();
            AtomicReference<Runnable> withheldAbort = new AtomicReference<>();
            IpcTransportSessionLease.CleanupScheduler scheduler = cleanup -> {
                if (scheduled.incrementAndGet() == 1) {
                    withheldAbort.set(cleanup);
                    return;
                }
                offCallerCleanup(cleanup);
            };
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    client, bridge, deadlineMessage, 92, 1, key, connectionBinding, scheduler);
            AtomicReference<IpcAuthenticatedSocketTransport.SendSubmission> result =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try {
                    result.set(transport.send(deadlineMessage, blockedPayload));
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                }
            }, "ipc-test-deadline-data-writer");
            sender.start();
            boolean pinned = false;
            for (int i = 0; i < 400; ++i) {
                if (transport.snapshot().activeWrites() == 1 && sender.isAlive()) {
                    pinned = true;
                    break;
                }
                Thread.sleep(5);
            }
            check(pinned);
            check(!transport.requestCloseAndAwait(40));
            check(transport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(transport.snapshot().activeWrites() == 1);
            check(sender.isAlive());
            check(withheldAbort.get() != null);
            offCallerCleanup(withheldAbort.get());
            sender.join(3000);
            check(!sender.isAlive());
            check(failure.get() == null);
            check(result.get() != null && result.get().writeAttemptFailed());
            check(transport.snapshot().activeWrites() == 0);
            check(transport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!result.get().outcome().toCompletableFuture().isDone());
            transport.retryQuarantinedClose();
            check(transport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(result.get().outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(transport.keyCloneZeroized());
            check(scheduled.get() == 2);
            check(bridge.invariant());
        }

        System.out.println("JAVA_IPC_AUTHENTICATED_SOCKET_HOST_PASS checks=" + checks);
    }
}
