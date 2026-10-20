package dev.oreslang.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    private static final class FinalReadGateInputStream extends InputStream {
        private final byte[] bytes;
        private final CountDownLatch finalChunkCopied;
        private final CountDownLatch releaseFinalRead;
        private int offset;

        FinalReadGateInputStream(
                byte[] bytes,
                CountDownLatch finalChunkCopied,
                CountDownLatch releaseFinalRead) {
            this.bytes = bytes.clone();
            this.finalChunkCopied = finalChunkCopied;
            this.releaseFinalRead = releaseFinalRead;
        }

        @Override
        public int read() {
            if (offset >= bytes.length) return -1;
            return bytes[offset++] & 0xff;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) throws IOException {
            if (offset >= bytes.length) return -1;
            int count = Math.min(length, bytes.length - offset);
            System.arraycopy(bytes, offset, target, targetOffset, count);
            offset += count;
            if (offset == bytes.length) {
                finalChunkCopied.countDown();
                try {
                    if (!releaseFinalRead.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("final receipt read gate timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("final receipt read gate interrupted", interrupted);
                }
            }
            return count;
        }
    }

    private static final class ControlledSocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch closeCalled;
        private volatile boolean closed;

        ControlledSocket(InputStream input, CountDownLatch closeCalled) {
            this.input = input;
            this.closeCalled = closeCalled;
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public synchronized void close() {
            closed = true;
            closeCalled.countDown();
        }
        @Override public boolean isClosed() { return closed; }
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

        // Protocol-v3 cross-runtime golden vectors. These bytes deliberately
        // pin Java DataOutputStream big-endian framing, token order, the
        // 128-bit connection binding, receipt wire code and HMAC input.
        byte[] goldenKey = new byte[32];
        for (int i = 0; i < goldenKey.length; ++i) {
            goldenKey[i] = (byte) (0x20 + i);
        }
        byte[] goldenBinding = new byte[16];
        for (int i = 0; i < goldenBinding.length; ++i) {
            goldenBinding[i] = (byte) (i + 1);
        }
        byte[] goldenPayload = "hello-v3".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var goldenEnvelope = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "v3golden",
                digest(goldenPayload), 1, 2, 3, 4, 5, 6);
        String goldenDataHex =
                "4f524553030100000000000000070102030405060708090a0b0c0d0e0f10"
                + "0674656e616e740673656e64657206776f726b6572087633676f6c64656e40"
                + "3030303566313261366361383237376261363333363461643961386132316534"
                + "3362656465613666323039333733653237623636326663623761393362313161"
                + "0000000000000001000000000000000200000000000000030000000000000004"
                + "000000000000000500000000000000060000000868656c6c6f2d7633"
                + "f155c8abe25e56699de661eeb6df46bbc65e026f4a3d42cd449553adcac7ab60";
        String goldenReceiptHex =
                "4f524553030200000000000000070102030405060708090a0b0c0d0e0f10"
                + "0674656e616e740673656e64657206776f726b6572087633676f6c64656e40"
                + "3030303566313261366361383237376261363333363461643961386132316534"
                + "3362656465613666323039333733653237623636326663623761393362313161"
                + "0000000000000001000000000000000200000000000000030000000000000004"
                + "0000000000000005000000000000000601"
                + "c928561e7dd8ed11e10c09aaa07012ab052b1d88d5c5c6362bd8f3161553a840";
        check(HexFormat.of().formatHex(
                IpcAuthenticatedSocketTransport.encodeDataFrame(
                        goldenEnvelope, goldenPayload, 7,
                        goldenBinding, goldenKey)).equals(goldenDataHex));
        check(HexFormat.of().formatHex(
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        goldenEnvelope, 7,
                        IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                        goldenBinding, goldenKey)).equals(goldenReceiptHex));
        var goldenDecoded = IpcAuthenticatedSocketTransport.decodeDataFrame(
                HexFormat.of().parseHex(goldenDataHex),
                goldenEnvelope,
                7,
                new IpcAuthenticatedSocketTransport.InboundReplayGate(),
                goldenBinding,
                goldenKey);
        check(goldenDecoded.envelope().equals(goldenEnvelope));
        check(Arrays.equals(goldenDecoded.payload(), goldenPayload));

        // TCP stream publication is single-file. Physical write concurrency
        // greater than one is rejected; outstanding receipt lifetimes are a
        // separate bridge/correlation concern.
        byte[] configPayload = "config".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var configMessage = envelope(1, "config", 3, 4, configPayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            check(peer.isConnected());
            var bridge = new IpcReceiptCompletionBridge(2);
            try {
                new IpcAuthenticatedSocketTransport(
                        client, bridge, configMessage, 10, 1, key, new byte[16],
                        IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
                throw new AssertionError("all-zero connection binding accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("must not be all zero"));
            }
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
            check(peer.isConnected());
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
                            new IpcAuthenticatedSocketTransport.InboundReplayGate(), connectionBinding, key);
                    check(decoded.envelope().equals(message));
                    check(decoded.physicalGeneration() == 11);
                    check(Arrays.equals(decoded.payload(), payload));
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    message, 11,
                                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, connectionBinding, key));
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(),
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    message, 11,
                                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED, connectionBinding, key));
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

        // Concurrent host receipt readers must preserve TCP protocol order
        // through ledger application, not merely through socket consumption.
        // Each peer response is ACK -> COMMIT; two racing readers may return
        // in either thread order, but must never apply COMMIT before ACK.
        byte[] orderedPayload = "ordered concurrent receipts".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var orderedEpoch = envelope(1, "orderedReaders", 3, 4, orderedPayload);
        peerFailure.set(null);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                var replayGate = new IpcAuthenticatedSocketTransport.InboundReplayGate();
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(5000);
                    for (int sequence = 1; sequence <= 128; ++sequence) {
                        var decoded = IpcAuthenticatedSocketTransport.decodeDataFrame(
                                IpcAuthenticatedSocketTransport.readDataWireFrame(
                                        accepted.getInputStream()),
                                orderedEpoch, 12, replayGate, connectionBinding, key);
                        IpcAuthenticatedSocketTransport.writeWireFrame(
                                accepted.getOutputStream(),
                                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                        decoded.envelope(), 12,
                                        IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                                        connectionBinding, key));
                        IpcAuthenticatedSocketTransport.writeWireFrame(
                                accepted.getOutputStream(),
                                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                        decoded.envelope(), 12,
                                        IpcReceiptCorrelation.Stage.HANDLER_COMMITTED,
                                        connectionBinding, key));
                    }
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                }
            }, "ipc-test-peer-concurrent-receipt-order");
            peer.start();

            ExecutorService cleanup = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "ipc-test-cleanup-receipt-order"));
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(5000);
                var bridge = new IpcReceiptCompletionBridge(2);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, orderedEpoch, 12, 1,
                        key, connectionBinding, cleanup::execute);

                for (int sequence = 1; sequence <= 128; ++sequence) {
                    var orderedMessage = envelope(
                            sequence, "orderedReaders", 3, 4, orderedPayload);
                    var submission = transport.send(orderedMessage, orderedPayload);
                    check(!submission.writeAttemptFailed());

                    CountDownLatch fireReaders = new CountDownLatch(1);
                    AtomicReference<IpcReceiptCorrelation.Stage> firstStage =
                            new AtomicReference<>();
                    AtomicReference<IpcReceiptCorrelation.Stage> secondStage =
                            new AtomicReference<>();
                    AtomicReference<Throwable> readFailure = new AtomicReference<>();

                    Thread firstReader = new Thread(() -> {
                        try {
                            fireReaders.await();
                            firstStage.set(transport.readVerifiedReceipt());
                        } catch (Throwable failure) {
                            readFailure.compareAndSet(null, failure);
                        }
                    }, "ipc-test-receipt-reader-a");
                    Thread secondReader = new Thread(() -> {
                        try {
                            fireReaders.await();
                            secondStage.set(transport.readVerifiedReceipt());
                        } catch (Throwable failure) {
                            readFailure.compareAndSet(null, failure);
                        }
                    }, "ipc-test-receipt-reader-b");

                    firstReader.start();
                    secondReader.start();
                    fireReaders.countDown();
                    firstReader.join(5000);
                    secondReader.join(5000);

                    check(!firstReader.isAlive());
                    check(!secondReader.isAlive());
                    check(readFailure.get() == null);
                    var a = firstStage.get();
                    var b = secondStage.get();
                    check((a == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED
                                    && b == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED)
                            || (b == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED
                                    && a == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED));
                    check(submission.outcome().toCompletableFuture().join()
                            == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                    check(bridge.invariant());
                }

                check(transport.requestCloseAndAwait(3000));
                check(transport.keyCloneZeroized());
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
            peer.join(5000);
            check(!peer.isAlive());
            check(peerFailure.get() == null);
        }

        // Key destruction must wait for an already-consumed authenticated
        // receipt to finish decode + ledger application. The controlled stream
        // pauses after copying the complete wire frame while readVerifiedReceipt
        // still owns readGate. Close may interrupt kernel I/O first, but must
        // not zero the MAC key until that receive transaction exits.
        byte[] drainPayload = "receipt key drain".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var drainMessage = envelope(1, "receiptKeyDrain", 3, 4, drainPayload);
        byte[] drainReceipt = IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                drainMessage, 13,
                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                connectionBinding, key);
        ByteArrayOutputStream drainWireBytes = new ByteArrayOutputStream();
        IpcAuthenticatedSocketTransport.writeWireFrame(
                drainWireBytes, drainReceipt);
        CountDownLatch finalChunkCopied = new CountDownLatch(1);
        CountDownLatch releaseFinalRead = new CountDownLatch(1);
        CountDownLatch closeCalled = new CountDownLatch(1);
        var controlledSocket = new ControlledSocket(
                new FinalReadGateInputStream(
                        drainWireBytes.toByteArray(),
                        finalChunkCopied,
                        releaseFinalRead),
                closeCalled);
        ExecutorService drainCleanup = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ipc-test-cleanup-key-drain"));
        try {
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    controlledSocket, bridge, drainMessage, 13, 1,
                    key, connectionBinding, drainCleanup::execute);
            var submission = transport.send(drainMessage, drainPayload);
            check(!submission.writeAttemptFailed());

            AtomicReference<IpcReceiptCorrelation.Stage> readStage =
                    new AtomicReference<>();
            AtomicReference<Throwable> readFailure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    readStage.set(transport.readVerifiedReceipt());
                } catch (Throwable failure) {
                    readFailure.set(failure);
                }
            }, "ipc-test-key-drain-reader");
            reader.start();

            check(finalChunkCopied.await(2, TimeUnit.SECONDS));
            transport.requestClose();
            check(closeCalled.await(2, TimeUnit.SECONDS));
            check(!transport.keyCloneZeroized());
            check(reader.isAlive());

            releaseFinalRead.countDown();
            reader.join(3000);
            check(!reader.isAlive());
            check(readFailure.get() == null);
            check(readStage.get() == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            check(transport.requestCloseAndAwait(3000));
            check(transport.keyCloneZeroized());
            check(submission.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(bridge.invariant());
        } finally {
            releaseFinalRead.countDown();
            controlledSocket.close();
            drainCleanup.shutdownNow();
            drainCleanup.awaitTermination(2, TimeUnit.SECONDS);
        }

        // Terminal receipt publication must not execute dependent callbacks
        // while readGate is held. A completion callback may synchronously ask
        // the host supervisor to close and await the transport; cleanup needs
        // readGate to finish key destruction after Socket.close().
        byte[] reentrantPayload = "receipt completion reentry".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var reentrantMessage = envelope(1, "receiptReentry", 3, 4, reentrantPayload);
        ByteArrayOutputStream reentrantWire = new ByteArrayOutputStream();
        IpcAuthenticatedSocketTransport.writeWireFrame(
                reentrantWire,
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        reentrantMessage, 14,
                        IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                        connectionBinding, key));
        IpcAuthenticatedSocketTransport.writeWireFrame(
                reentrantWire,
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        reentrantMessage, 14,
                        IpcReceiptCorrelation.Stage.HANDLER_COMMITTED,
                        connectionBinding, key));
        CountDownLatch reentrantCloseCalled = new CountDownLatch(1);
        var reentrantSocket = new ControlledSocket(
                new java.io.ByteArrayInputStream(reentrantWire.toByteArray()),
                reentrantCloseCalled);
        ExecutorService reentrantCleanup = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ipc-test-cleanup-reentrant-receipt"));
        try {
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    reentrantSocket, bridge, reentrantMessage, 14, 1,
                    key, connectionBinding, reentrantCleanup::execute);
            var submission = transport.send(reentrantMessage, reentrantPayload);
            check(!submission.writeAttemptFailed());
            check(transport.readVerifiedReceipt()
                    == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);

            CountDownLatch completionReturned = new CountDownLatch(1);
            AtomicReference<Boolean> closeResult = new AtomicReference<>();
            AtomicReference<Throwable> completionFailure = new AtomicReference<>();
            submission.outcome().whenComplete((result, failure) -> {
                try {
                    if (failure != null) throw new AssertionError(failure);
                    if (result != IpcReceiptCorrelation.Stage.HANDLER_COMMITTED) {
                        throw new AssertionError("unexpected terminal receipt " + result);
                    }
                    closeResult.set(transport.requestCloseAndAwait(2000));
                } catch (Throwable callbackFailure) {
                    completionFailure.set(callbackFailure);
                } finally {
                    completionReturned.countDown();
                }
            });

            check(transport.readVerifiedReceipt()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(completionReturned.await(3, TimeUnit.SECONDS));
            check(completionFailure.get() == null);
            check(Boolean.TRUE.equals(closeResult.get()));
            check(reentrantCloseCalled.await(2, TimeUnit.SECONDS));
            check(transport.keyCloneZeroized());
            check(bridge.invariant());
        } finally {
            reentrantSocket.close();
            reentrantCleanup.shutdownNow();
            reentrantCleanup.awaitTermination(2, TimeUnit.SECONDS);
        }

        // Full route/grant epoch must be validated before receipt admission or wire I/O.
        byte[] epochPayload = "epoch".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var oldEpoch = envelope(1, "reused", 3, 4, epochPayload);
        var upgraded = envelope(2, "reused", 4, 5, epochPayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            check(peer.isConnected());
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
            check(peer.isConnected());
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
                            new IpcAuthenticatedSocketTransport.InboundReplayGate(), connectionBinding, key);
                    check(data.envelope().equals(maliciousMessage));
                    byte[] malicious = peerLocalVerdictWithValidMac(
                            maliciousMessage,
                            73,
                            IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN,
                            connectionBinding,
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
                                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, connectionBinding, key));
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
                replayDataMessage, replayDataPayload, 82, connectionBinding, key);

        // A replacement physical connection must carry a fresh authenticated
        // connection binding. Reusing key + epoch + physical generation alone
        // is insufficient to make a captured old-connection frame valid.
        byte[] replacementBinding = binding(91);
        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    capturedData, replayDataMessage, 82,
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(),
                    replacementBinding, key);
            throw new AssertionError(
                    "captured DATA crossed replacement connection binding");
        } catch (IOException expected) {
            check(expected.getMessage().contains("connection binding"));
        }
        try {
            IpcAuthenticatedSocketTransport.encodeDataFrame(
                    replayDataMessage, replayDataPayload, 82,
                    new byte[16], key);
            throw new AssertionError("all-zero IPC connection binding accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("must not be all zero"));
        }
        // v3 retains explicit receipt wire codes and adds a connection binding.
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
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(), connectionBinding, key);
            throw new AssertionError("authenticated v1 DATA accepted by v3 transport");
        } catch (IOException expected) {
            check(expected.getMessage().contains("invalid IPC frame header"));
        }

        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    capturedData, replayDataMessage, 83,
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(), connectionBinding, key);
            throw new AssertionError("old physical generation DATA accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("transport epoch/generation"));
        }
        var changedDataEpoch = envelope(2, "dataReplay", 4, 5, replayDataPayload);
        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    capturedData, changedDataEpoch, 82,
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(), connectionBinding, key);
            throw new AssertionError("old route/grant DATA accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("transport epoch/generation"));
        }
        var replayGate = new IpcAuthenticatedSocketTransport.InboundReplayGate();
        var verifiedData = IpcAuthenticatedSocketTransport.decodeDataFrame(
                capturedData, replayDataMessage, 82, replayGate, connectionBinding, key);
        check(Arrays.equals(verifiedData.payload(), replayDataPayload));
        check(verifiedData.physicalGeneration() == 82);
        check(replayGate.lastMessageSequence() == 1);
        check(replayGate.lastFrameSequence() == 1);
        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    capturedData, replayDataMessage, 82, replayGate, connectionBinding, key);
            throw new AssertionError("exact authenticated DATA replay accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("replayed/reordered"));
        }
        var nextDataMessage = envelope(2, "dataReplay", 3, 4, replayDataPayload);
        byte[] nextData = IpcAuthenticatedSocketTransport.encodeDataFrame(
                nextDataMessage, replayDataPayload, 82, connectionBinding, key);
        IpcAuthenticatedSocketTransport.decodeDataFrame(
                nextData, replayDataMessage, 82, replayGate, connectionBinding, key);
        check(replayGate.lastMessageSequence() == 2);
        check(replayGate.lastFrameSequence() == 2);
        var rolledFrame = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "dataReplay", digest(replayDataPayload),
                1, 2, 3, 4, 3, 2);
        byte[] rolledFrameData = IpcAuthenticatedSocketTransport.encodeDataFrame(
                rolledFrame, replayDataPayload, 82, connectionBinding, key);
        try {
            IpcAuthenticatedSocketTransport.decodeDataFrame(
                    rolledFrameData, replayDataMessage, 82, replayGate, connectionBinding, key);
            throw new AssertionError("rolled-back authenticated frame sequence accepted");
        } catch (IOException expected) {
            check(expected.getMessage().contains("replayed/reordered"));
        }

        // A valid-HMAC receipt from the prior physical connection is
        // rejected when the replacement transport has a fresh binding, even
        // if key, logical epoch and physical generation were otherwise reused.
        byte[] reboundPayload = "rebound receipt".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var reboundMessage = envelope(1, "reboundReceipt", 3, 4, reboundPayload);
        peerFailure.set(null);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    IpcAuthenticatedSocketTransport.readDataWireFrame(
                            accepted.getInputStream());
                    byte[] staleReceipt =
                            IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                    reboundMessage, 821,
                                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                                    connectionBinding, key);
                    IpcAuthenticatedSocketTransport.writeWireFrame(
                            accepted.getOutputStream(), staleReceipt);
                    while (accepted.getInputStream().read() != -1) { }
                } catch (Throwable failure) {
                    peerFailure.compareAndSet(null, failure);
                }
            }, "ipc-test-peer-rebound-receipt");
            peer.start();

            ExecutorService cleanup = Executors.newSingleThreadExecutor();
            try (Socket client = new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(3000);
                var bridge = new IpcReceiptCompletionBridge(1);
                var transport = new IpcAuthenticatedSocketTransport(
                        client, bridge, reboundMessage, 821, 1, key,
                        replacementBinding, cleanup::execute);
                var submission = transport.send(reboundMessage, reboundPayload);
                check(!submission.writeAttemptFailed());
                try {
                    transport.readVerifiedReceipt();
                    throw new AssertionError(
                            "prior-connection receipt binding authenticated");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("connection binding"));
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
                            IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED, connectionBinding, key);
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
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(), connectionBinding, key);
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

        // If the cleanup domain runs an accepted active-write abort on an
        // actor carrier, the generation is quarantined before Socket.close().
        // A host supervisor can explicitly retry the abort on a real host
        // worker; receipt retirement remains a separate recovery after drain.
        var retryAbortMessage = envelope(1, "retryAbort", 3, 4, blockedPayload);
        try (ServerSocket server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket peer = server.accept()) {
            client.setSendBufferSize(1024);
            peer.setReceiveBufferSize(1024);
            AtomicInteger scheduled = new AtomicInteger();
            AtomicReference<Throwable> illegalAbortFailure = new AtomicReference<>();
            IpcTransportSessionLease.CleanupScheduler scheduler = cleanup -> {
                if (scheduled.incrementAndGet() == 1) {
                    Thread invalidCarrier = new Thread(() -> {
                        RuntimeBlockingGuard.enterActorCarrier();
                        try {
                            cleanup.run();
                        } catch (Throwable failure) {
                            illegalAbortFailure.set(failure);
                        } finally {
                            RuntimeBlockingGuard.exitActorCarrier();
                        }
                    }, "ipc-test-illegal-actor-abort");
                    invalidCarrier.start();
                    try {
                        invalidCarrier.join(3000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "actor-carrier abort fixture interrupted", interrupted);
                    }
                    if (invalidCarrier.isAlive()) {
                        throw new IllegalStateException(
                                "actor-carrier abort fixture did not finish");
                    }
                    return;
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
            check(illegalAbortFailure.get() instanceof IllegalStateException);
            check(illegalAbortFailure.get().getMessage().contains(
                    "cannot block an actor carrier"));
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
