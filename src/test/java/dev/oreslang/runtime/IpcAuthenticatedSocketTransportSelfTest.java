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

    private static IpcPhysicalConnectionBroker.Authority authority(
            long generation, byte[] key, byte[] binding) {
        byte[] expectedBinding = binding.clone();
        var broker = new IpcPhysicalConnectionBroker(target -> {
            if (target.length != expectedBinding.length) {
                throw new AssertionError("unexpected broker binding width");
            }
            System.arraycopy(expectedBinding, 0, target, 0, target.length);
        });
        return broker.mint(generation, key);
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

    private static final class FirstFrameGateInputStream extends InputStream {
        private final byte[] bytes;
        private final int firstFrameBytes;
        private final CountDownLatch firstFrameCopied;
        private final CountDownLatch releaseFirstFrame;
        private int offset;
        private boolean gated;

        FirstFrameGateInputStream(
                byte[] bytes,
                int firstFrameBytes,
                CountDownLatch firstFrameCopied,
                CountDownLatch releaseFirstFrame) {
            this.bytes = bytes.clone();
            if (firstFrameBytes <= 0 || firstFrameBytes >= bytes.length) {
                throw new IllegalArgumentException("invalid first-frame boundary");
            }
            this.firstFrameBytes = firstFrameBytes;
            this.firstFrameCopied = firstFrameCopied;
            this.releaseFirstFrame = releaseFirstFrame;
        }

        private void gateAtFirstFrame() throws IOException {
            if (gated || offset != firstFrameBytes) return;
            gated = true;
            firstFrameCopied.countDown();
            try {
                if (!releaseFirstFrame.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("first receipt frame gate timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("first receipt frame gate interrupted", interrupted);
            }
        }

        @Override
        public int read() throws IOException {
            if (offset >= bytes.length) return -1;
            int value = bytes[offset++] & 0xff;
            gateAtFirstFrame();
            return value;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) throws IOException {
            if (offset >= bytes.length) return -1;
            int boundary = offset < firstFrameBytes ? firstFrameBytes : bytes.length;
            int count = Math.min(length, boundary - offset);
            System.arraycopy(bytes, offset, target, targetOffset, count);
            offset += count;
            gateAtFirstFrame();
            return count;
        }

        int bytesRead() { return offset; }
    }

    private static final class ConstructionFailureSocket extends Socket {
        private final boolean failOutput;
        private final boolean failClose;
        private final ByteArrayInputStream input =
                new ByteArrayInputStream(new byte[0]);
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicInteger closes = new AtomicInteger();

        ConstructionFailureSocket(boolean failOutput, boolean failClose) {
            this.failOutput = failOutput;
            this.failClose = failClose;
        }

        @Override public InputStream getInputStream() { return input; }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (failOutput) throw new IOException("construction output acquisition failed");
            return output;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (failClose) {
                throw new IllegalStateException("construction close failed");
            }
        }

        int closeCount() { return closes.get(); }
    }

    private static final class BlockingAbortOutputStream extends OutputStream {
        private final CountDownLatch writeEntered;
        private final CountDownLatch abortWrite;
        private volatile boolean aborted;

        BlockingAbortOutputStream(
                CountDownLatch writeEntered,
                CountDownLatch abortWrite) {
            this.writeEntered = writeEntered;
            this.abortWrite = abortWrite;
        }

        void abort() {
            aborted = true;
            abortWrite.countDown();
        }

        private void awaitAbort() throws IOException {
            writeEntered.countDown();
            try {
                if (!abortWrite.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test physical write did not receive abort");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test physical write interrupted", interrupted);
            }
            if (aborted) throw new IOException("test physical write aborted");
        }

        @Override public void write(int value) throws IOException {
            awaitAbort();
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            if (length == 0) return;
            awaitAbort();
        }
    }

    private static final class AbortControlledSocket extends Socket {
        private final InputStream input = new ByteArrayInputStream(new byte[0]);
        private final BlockingAbortOutputStream output;
        private final CountDownLatch firstCloseEntered;
        private final CountDownLatch releaseFirstClose;
        private final AtomicInteger closeCount = new AtomicInteger();

        AbortControlledSocket(
                BlockingAbortOutputStream output,
                CountDownLatch firstCloseEntered,
                CountDownLatch releaseFirstClose) {
            this.output = output;
            this.firstCloseEntered = firstCloseEntered;
            this.releaseFirstClose = releaseFirstClose;
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }

        @Override
        public void close() throws IOException {
            int count = closeCount.incrementAndGet();
            if (count == 1) {
                firstCloseEntered.countDown();
                // Wake the pinned writer before this close invocation returns.
                // This deliberately creates the writer-drain vs close-in-flight
                // interleaving that previously allowed finalization to invoke
                // Socket.close() a second time.
                output.abort();
                try {
                    if (!releaseFirstClose.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test first physical close did not release");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test first physical close interrupted", interrupted);
                }
                return;
            }
            output.abort();
        }

        int closeCount() { return closeCount.get(); }
    }

    private static final class FailingAbortSocket extends Socket {
        private final InputStream input = new ByteArrayInputStream(new byte[0]);
        private final BlockingAbortOutputStream output;
        private final AtomicInteger closes = new AtomicInteger();

        FailingAbortSocket(BlockingAbortOutputStream output) {
            this.output = output;
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            output.abort();
            throw new IOException("test physical close outcome unconfirmed");
        }

        int closeCount() { return closes.get(); }
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

        // Constructor preparation is reversible until the final one-shot
        // receipt-bridge claim. A stream acquisition failure must close the
        // socket and leave the bridge available for a later legitimate owner.
        byte[] constructionPayload = "construction".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var constructionEpoch = envelope(
                1, "constructionTxn", 3, 4, constructionPayload);
        var constructionBridge = new IpcReceiptCompletionBridge(1);
        var constructionAuthority = authority(
                901, key, connectionBinding);
        var streamFailureSocket = new ConstructionFailureSocket(true, false);
        try {
            new IpcAuthenticatedSocketTransport(
                    streamFailureSocket, constructionBridge,
                    constructionEpoch, 1, constructionAuthority,
                    IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
            throw new AssertionError("constructor stream failure was swallowed");
        } catch (IOException expected) {
            check(expected.getMessage().contains("output acquisition"));
        }
        check(streamFailureSocket.closeCount() == 1);
        check(!constructionAuthority.consumedOrDestroyed());

        // Pre-consumption failure leaves the exact same authority retryable.
        var recoverySocket = new ConstructionFailureSocket(false, false);
        var recoveredTransport = new IpcAuthenticatedSocketTransport(
                recoverySocket, constructionBridge, constructionEpoch, 1,
                constructionAuthority,
                IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
        check(constructionAuthority.consumedOrDestroyed());
        check(recoveredTransport.requestCloseAndAwait(3000));
        check(recoverySocket.closeCount() == 1);
        check(recoveredTransport.keyCloneZeroized());
        check(recoveredTransport.connectionBindingCloneZeroized());
        check(constructionBridge.invariant());

        // A consumed authority cannot create a second physical transport.
        var reusedAuthoritySocket = new ConstructionFailureSocket(false, false);
        try {
            new IpcAuthenticatedSocketTransport(
                    reusedAuthoritySocket, new IpcReceiptCompletionBridge(1),
                    constructionEpoch, 1, constructionAuthority,
                    IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
            throw new AssertionError("consumed physical authority reused");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("already consumed"));
        }
        check(reusedAuthoritySocket.closeCount() == 1);

        // The final irreversible bridge claim may itself fail (for example
        // because another physical transport already owns the bridge).
        // Credentials already transferred from the broker are destroyed and
        // that authority remains permanently spent.
        var occupiedBridge = new IpcReceiptCompletionBridge(1);
        Object occupiedOwner = new Object();
        occupiedBridge.claimPhysicalTransport(
                occupiedOwner, constructionEpoch, 902);
        var claimAuthority = authority(902, key, connectionBinding);
        var claimFailureSocket = new ConstructionFailureSocket(false, true);
        try {
            new IpcAuthenticatedSocketTransport(
                    claimFailureSocket, occupiedBridge,
                    constructionEpoch, 1, claimAuthority,
                    IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
            throw new AssertionError("duplicate physical bridge claim succeeded");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("already bound"));
            check(expected.getSuppressed().length == 1);
            check(expected.getSuppressed()[0].getMessage().contains(
                    "construction close failed"));
        }
        check(claimAuthority.consumedOrDestroyed());
        check(claimFailureSocket.closeCount() == 1);
        check(occupiedBridge.transportLost(
                occupiedOwner, constructionEpoch, 902) == 0);
        check(occupiedBridge.invariant());

        var burnedAuthoritySocket = new ConstructionFailureSocket(false, false);
        try {
            new IpcAuthenticatedSocketTransport(
                    burnedAuthoritySocket, new IpcReceiptCompletionBridge(1),
                    constructionEpoch, 1, claimAuthority,
                    IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
            throw new AssertionError("post-claim-failure authority reused");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("already consumed"));
        }
        check(burnedAuthoritySocket.closeCount() == 1);

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
                authority(10, key, new byte[16]);
                throw new AssertionError("all-zero connection binding accepted");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("all-zero"));
            }
            var invalidPolicyAuthority =
                    authority(10, key, connectionBinding);
            try {
                new IpcAuthenticatedSocketTransport(
                        client, bridge, configMessage, 2,
                        invalidPolicyAuthority,
                        IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
                throw new AssertionError("parallel TCP physical writers accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("exactly one"));
                check(!invalidPolicyAuthority.consumedOrDestroyed());
            } finally {
                invalidPolicyAuthority.close();
            }
            check(invalidPolicyAuthority.consumedOrDestroyed());
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
                    client, bridge, configMessage, 1,
                    authority(10, key, connectionBinding), IpcAuthenticatedSocketTransportSelfTest::offCallerCleanup);
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
                check(!client.isClosed());
                check(!transport.keyCloneZeroized());
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
            check(!RuntimeBlockingGuard.actorCarrierMarked());
            check(transport.snapshot().phase() == IpcTransportSessionLease.Phase.OPEN);
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
                    client, bridge, message, 1,
                    authority(11, key, connectionBinding), cleanup::execute);
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
                check(transport.connectionBindingCloneZeroized());
            } finally {
                cleanup.shutdownNow();
                cleanup.awaitTermination(2, TimeUnit.SECONDS);
            }
            peer.join(3000);
            check(!peer.isAlive());
            check(peerFailure.get() == null);
        }

        // Terminal completion callbacks must not execute while readGate is
        // held. The callback performs a full close-and-wait; if publication
        // still occurred under readGate, physical close would block trying to
        // acquire that same gate for key/binding destruction and this callback
        // would time out instead of observing RETIRED.
        byte[] callbackPayload = "callback outside read gate".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var callbackMessage = envelope(
                1, "callbackOutsideReadGate", 3, 4, callbackPayload);
        ByteArrayOutputStream callbackWire = new ByteArrayOutputStream();
        IpcAuthenticatedSocketTransport.writeWireFrame(
                callbackWire,
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        callbackMessage, 111,
                        IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                        connectionBinding, key));
        IpcAuthenticatedSocketTransport.writeWireFrame(
                callbackWire,
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        callbackMessage, 111,
                        IpcReceiptCorrelation.Stage.HANDLER_COMMITTED,
                        connectionBinding, key));
        CountDownLatch callbackCloseCalled = new CountDownLatch(1);
        var callbackSocket = new ControlledSocket(
                new ByteArrayInputStream(callbackWire.toByteArray()),
                callbackCloseCalled);
        ExecutorService callbackCleanup = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ipc-test-callback-close-cleanup"));
        try {
            var callbackBridge = new IpcReceiptCompletionBridge(1);
            var callbackTransport = new IpcAuthenticatedSocketTransport(
                    callbackSocket, callbackBridge, callbackMessage, 1,
                    authority(111, key, connectionBinding), callbackCleanup::execute);
            var callbackSubmission =
                    callbackTransport.send(callbackMessage, callbackPayload);
            check(!callbackSubmission.writeAttemptFailed());

            CountDownLatch callbackDone = new CountDownLatch(1);
            AtomicReference<Boolean> callbackCloseResult = new AtomicReference<>();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            callbackSubmission.outcome().thenAccept(stage -> {
                Thread closer = new Thread(() -> {
                    try {
                        callbackCloseResult.set(
                                callbackTransport.requestCloseAndAwait(1500));
                    } catch (Throwable failure) {
                        callbackFailure.compareAndSet(null, failure);
                    }
                }, "ipc-test-close-from-terminal-callback");
                closer.start();
                try {
                    closer.join(2500);
                    if (closer.isAlive()) {
                        callbackFailure.compareAndSet(null,
                                new AssertionError(
                                        "terminal callback close blocked on readGate"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callbackFailure.compareAndSet(null, interrupted);
                } finally {
                    callbackDone.countDown();
                }
            });

            check(callbackTransport.readVerifiedReceipt()
                    == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            check(callbackTransport.readVerifiedReceipt()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(callbackDone.await(3, TimeUnit.SECONDS));
            check(callbackFailure.get() == null);
            check(Boolean.TRUE.equals(callbackCloseResult.get()));
            check(callbackCloseCalled.getCount() == 0);
            check(callbackTransport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(callbackTransport.keyCloneZeroized());
            check(callbackTransport.connectionBindingCloneZeroized());
            check(callbackSubmission.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(callbackBridge.invariant());
        } finally {
            callbackSocket.close();
            callbackCleanup.shutdownNow();
            callbackCleanup.awaitTermination(2, TimeUnit.SECONDS);
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
                    client, bridge, orderedEpoch, 1,
                    authority(12, key, connectionBinding), cleanup::execute);

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
                    controlledSocket, bridge, drainMessage, 1,
                    authority(13, key, connectionBinding), drainCleanup::execute);
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
            check(!transport.connectionBindingCloneZeroized());
            check(reader.isAlive());

            releaseFinalRead.countDown();
            reader.join(3000);
            check(!reader.isAlive());
            check(readFailure.get() == null);
            check(readStage.get() == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            check(transport.requestCloseAndAwait(3000));
            check(transport.keyCloneZeroized());
            check(transport.connectionBindingCloneZeroized());
            check(submission.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(bridge.invariant());
        } finally {
            releaseFinalRead.countDown();
            controlledSocket.close();
            drainCleanup.shutdownNow();
            drainCleanup.awaitTermination(2, TimeUnit.SECONDS);
        }

        // Close intent fences receipt admission, not merely key erasure.
        // Reader A already owns readGate and may finish its authenticated ACK.
        // Reader B starts before close but is queued behind A; after close
        // begins it must recheck the fence inside readGate and consume none of
        // the already-buffered terminal frame.
        byte[] readFencePayload = "receipt read admission fence".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var readFenceMessage = envelope(
                1, "receiptReadFence", 3, 4, readFencePayload);
        byte[] readFenceAck = IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                readFenceMessage, 133,
                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                connectionBinding, key);
        byte[] readFenceTerminal =
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        readFenceMessage, 133,
                        IpcReceiptCorrelation.Stage.HANDLER_COMMITTED,
                        connectionBinding, key);
        ByteArrayOutputStream firstReadFenceWire = new ByteArrayOutputStream();
        IpcAuthenticatedSocketTransport.writeWireFrame(
                firstReadFenceWire, readFenceAck);
        ByteArrayOutputStream readFenceWire = new ByteArrayOutputStream();
        IpcAuthenticatedSocketTransport.writeWireFrame(
                readFenceWire, readFenceAck);
        IpcAuthenticatedSocketTransport.writeWireFrame(
                readFenceWire, readFenceTerminal);

        CountDownLatch firstFenceFrameCopied = new CountDownLatch(1);
        CountDownLatch releaseFirstFenceFrame = new CountDownLatch(1);
        CountDownLatch readFenceCloseCalled = new CountDownLatch(1);
        CountDownLatch secondReaderStarted = new CountDownLatch(1);
        var readFenceInput = new FirstFrameGateInputStream(
                readFenceWire.toByteArray(),
                firstReadFenceWire.size(),
                firstFenceFrameCopied,
                releaseFirstFenceFrame);
        var readFenceSocket = new ControlledSocket(
                readFenceInput, readFenceCloseCalled);
        ExecutorService readFenceCleanup = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ipc-test-receipt-read-fence"));
        try {
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    readFenceSocket, bridge, readFenceMessage, 1,
                    authority(133, key, connectionBinding),
                    readFenceCleanup::execute);
            var submission = transport.send(readFenceMessage, readFencePayload);
            check(!submission.writeAttemptFailed());

            AtomicReference<IpcReceiptCorrelation.Stage> firstStage =
                    new AtomicReference<>();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            Thread firstReader = new Thread(() -> {
                try {
                    firstStage.set(transport.readVerifiedReceipt());
                } catch (Throwable failure) {
                    firstFailure.set(failure);
                }
            }, "ipc-test-read-fence-first");
            firstReader.start();
            check(firstFenceFrameCopied.await(2, TimeUnit.SECONDS));

            AtomicReference<IpcReceiptCorrelation.Stage> secondStage =
                    new AtomicReference<>();
            AtomicReference<Throwable> secondFailure = new AtomicReference<>();
            Thread secondReader = new Thread(() -> {
                secondReaderStarted.countDown();
                try {
                    secondStage.set(transport.readVerifiedReceipt());
                } catch (Throwable failure) {
                    secondFailure.set(failure);
                }
            }, "ipc-test-read-fence-second");
            secondReader.start();
            check(secondReaderStarted.await(2, TimeUnit.SECONDS));

            transport.requestClose();
            check(readFenceCloseCalled.await(2, TimeUnit.SECONDS));
            check(readFenceInput.bytesRead() == firstReadFenceWire.size());
            check(!transport.keyCloneZeroized());

            releaseFirstFenceFrame.countDown();
            firstReader.join(3000);
            secondReader.join(3000);
            check(!firstReader.isAlive());
            check(!secondReader.isAlive());
            check(firstFailure.get() == null);
            check(firstStage.get()
                    == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            check(secondStage.get() == null);
            check(secondFailure.get() instanceof IOException);
            check(secondFailure.get().getMessage().contains(
                    "receipt read after close request"));
            check(readFenceInput.bytesRead() == firstReadFenceWire.size());

            check(transport.requestCloseAndAwait(3000));
            check(transport.keyCloneZeroized());
            check(transport.connectionBindingCloneZeroized());
            check(submission.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(bridge.counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(bridge.invariant());
        } finally {
            releaseFirstFenceFrame.countDown();
            readFenceSocket.close();
            readFenceCleanup.shutdownNow();
            readFenceCleanup.awaitTermination(2, TimeUnit.SECONDS);
        }

        // Authenticated terminal receipt vs trusted close has one winner
        // through the full socket adapter, not just the bridge abstraction.
        // First prove a terminal receipt that is already consumed under
        // readGate wins before close can erase the key/retire correlations.
        byte[] terminalRacePayload = "terminal close race".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var terminalRaceMessage = envelope(
                1, "terminalCloseRace", 3, 4, terminalRacePayload);
        ByteArrayOutputStream terminalRaceWire = new ByteArrayOutputStream();
        IpcAuthenticatedSocketTransport.writeWireFrame(
                terminalRaceWire,
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        terminalRaceMessage, 131,
                        IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                        connectionBinding, key));
        IpcAuthenticatedSocketTransport.writeWireFrame(
                terminalRaceWire,
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        terminalRaceMessage, 131,
                        IpcReceiptCorrelation.Stage.HANDLER_COMMITTED,
                        connectionBinding, key));
        CountDownLatch terminalFrameCopied = new CountDownLatch(1);
        CountDownLatch releaseTerminalRead = new CountDownLatch(1);
        CountDownLatch terminalCloseCalled = new CountDownLatch(1);
        var terminalRaceSocket = new ControlledSocket(
                new FinalReadGateInputStream(
                        terminalRaceWire.toByteArray(),
                        terminalFrameCopied,
                        releaseTerminalRead),
                terminalCloseCalled);
        ExecutorService terminalRaceCleanup = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ipc-test-terminal-close-race"));
        try {
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    terminalRaceSocket, bridge, terminalRaceMessage, 1,
                    authority(131, key, connectionBinding), terminalRaceCleanup::execute);
            var submission = transport.send(terminalRaceMessage, terminalRacePayload);
            check(!submission.writeAttemptFailed());
            check(transport.readVerifiedReceipt()
                    == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);

            AtomicReference<IpcReceiptCorrelation.Stage> terminalRead =
                    new AtomicReference<>();
            AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
            Thread terminalReader = new Thread(() -> {
                try {
                    terminalRead.set(transport.readVerifiedReceipt());
                } catch (Throwable failure) {
                    terminalFailure.set(failure);
                }
            }, "ipc-test-terminal-before-loss");
            terminalReader.start();

            check(terminalFrameCopied.await(2, TimeUnit.SECONDS));
            transport.requestClose();
            check(terminalCloseCalled.await(2, TimeUnit.SECONDS));
            check(!transport.keyCloneZeroized());
            check(!submission.outcome().toCompletableFuture().isDone());

            releaseTerminalRead.countDown();
            terminalReader.join(3000);
            check(!terminalReader.isAlive());
            check(terminalFailure.get() == null);
            check(terminalRead.get()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(submission.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            awaitPhase(transport, IpcTransportSessionLease.Phase.RETIRED);
            check(transport.keyCloneZeroized());
            check(bridge.counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(bridge.invariant());
        } finally {
            releaseTerminalRead.countDown();
            terminalRaceSocket.close();
            terminalRaceCleanup.shutdownNow();
            terminalRaceCleanup.awaitTermination(2, TimeUnit.SECONDS);
        }

        // Then prove trusted loss can win first after ACK: the host completion
        // settles OUTCOME_UNKNOWN exactly once and a correctly MACed terminal
        // frame still buffered in the test input cannot resurrect it.
        var lossWinsMessage = envelope(
                1, "lossWinsTerminal", 3, 4, terminalRacePayload);
        ByteArrayOutputStream lossWinsWire = new ByteArrayOutputStream();
        IpcAuthenticatedSocketTransport.writeWireFrame(
                lossWinsWire,
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        lossWinsMessage, 132,
                        IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                        connectionBinding, key));
        IpcAuthenticatedSocketTransport.writeWireFrame(
                lossWinsWire,
                IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                        lossWinsMessage, 132,
                        IpcReceiptCorrelation.Stage.HANDLER_FAILED,
                        connectionBinding, key));
        CountDownLatch lossCloseCalled = new CountDownLatch(1);
        var lossWinsSocket = new ControlledSocket(
                new ByteArrayInputStream(lossWinsWire.toByteArray()),
                lossCloseCalled);
        ExecutorService lossWinsCleanup = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ipc-test-loss-before-terminal"));
        try {
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    lossWinsSocket, bridge, lossWinsMessage, 1,
                    authority(132, key, connectionBinding), lossWinsCleanup::execute);
            var submission = transport.send(lossWinsMessage, terminalRacePayload);
            check(!submission.writeAttemptFailed());
            check(transport.readVerifiedReceipt()
                    == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            check(transport.requestCloseAndAwait(3000));
            check(lossCloseCalled.getCount() == 0);
            check(transport.keyCloneZeroized());
            check(submission.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);

            try {
                transport.readVerifiedReceipt();
                throw new AssertionError(
                        "late terminal receipt resurrected retired completion");
            } catch (IOException | IllegalArgumentException expected) {
                ++checks;
            }
            check(submission.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(bridge.counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(bridge.invariant());
        } finally {
            lossWinsSocket.close();
            lossWinsCleanup.shutdownNow();
            lossWinsCleanup.awaitTermination(2, TimeUnit.SECONDS);
        }

        // A partially-enqueued physical abort from attempt N must become
        // inert after explicit retry N+1. Once N+1 atomically claims Socket.close,
        // another retry is rejected until that descriptor operation finishes.
        byte[] abortPayload = "physical abort retry".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        var abortMessage = envelope(1, "physicalAbortRetry", 3, 4, abortPayload);
        CountDownLatch abortWriteEntered = new CountDownLatch(1);
        CountDownLatch abortWriteRelease = new CountDownLatch(1);
        CountDownLatch firstAbortCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAbortClose = new CountDownLatch(1);
        var abortOutput = new BlockingAbortOutputStream(
                abortWriteEntered, abortWriteRelease);
        var abortSocket = new AbortControlledSocket(
                abortOutput, firstAbortCloseEntered, releaseFirstAbortClose);
        AtomicInteger abortSchedules = new AtomicInteger();
        AtomicReference<Runnable> staleAbort = new AtomicReference<>();
        AtomicReference<Runnable> currentAbort = new AtomicReference<>();
        AtomicReference<Runnable> finalClose = new AtomicReference<>();
        IpcTransportSessionLease.CleanupScheduler abortScheduler = cleanup -> {
            int schedule = abortSchedules.incrementAndGet();
            if (schedule == 1) {
                staleAbort.set(cleanup);
                throw new IllegalStateException("abort enqueue admission uncertain");
            }
            if (schedule == 2) {
                currentAbort.set(cleanup);
                return;
            }
            if (schedule == 3) {
                finalClose.set(cleanup);
                return;
            }
            throw new IllegalStateException("unexpected abort cleanup schedule " + schedule);
        };

        var abortBridge = new IpcReceiptCompletionBridge(1);
        var abortTransport = new IpcAuthenticatedSocketTransport(
                    abortSocket, abortBridge, abortMessage, 1,
                    authority(14, key, connectionBinding), abortScheduler);
        AtomicReference<IpcAuthenticatedSocketTransport.SendSubmission> abortSubmission =
                new AtomicReference<>();
        AtomicReference<Throwable> abortSenderFailure = new AtomicReference<>();
        Thread abortSender = new Thread(() -> {
            try {
                abortSubmission.set(abortTransport.send(abortMessage, abortPayload));
            } catch (Throwable failure) {
                abortSenderFailure.set(failure);
            }
        }, "ipc-test-physical-abort-sender");
        abortSender.start();
        check(abortWriteEntered.await(2, TimeUnit.SECONDS));

        abortTransport.requestClose();
        check(abortTransport.snapshot().phase()
                == IpcTransportSessionLease.Phase.QUARANTINED);
        check(abortTransport.snapshot().activeWrites() == 1);
        check(staleAbort.get() != null);
        check(abortSocket.closeCount() == 0);

        abortTransport.retryQuarantinedAbort();
        check(currentAbort.get() != null);

        Thread staleAbortWorker = new Thread(
                staleAbort.get(), "ipc-test-stale-physical-abort");
        staleAbortWorker.start();
        staleAbortWorker.join(1000);
        check(!staleAbortWorker.isAlive());
        check(abortSocket.closeCount() == 0);
        check(abortSender.isAlive());

        AtomicReference<Throwable> currentAbortFailure = new AtomicReference<>();
        Thread currentAbortWorker = new Thread(() -> {
            try {
                currentAbort.get().run();
            } catch (Throwable failure) {
                currentAbortFailure.set(failure);
            }
        }, "ipc-test-current-physical-abort");
        currentAbortWorker.start();
        check(firstAbortCloseEntered.await(2, TimeUnit.SECONDS));
        check(abortSocket.closeCount() == 1);

        // The descriptor close is deliberately still blocked, but it has
        // already woken the writer. Writer drain must not let finalization
        // steal physical-close ownership.
        abortSender.join(1500);
        check(!abortSender.isAlive());
        check(currentAbortWorker.isAlive());
        check(abortTransport.snapshot().activeWrites() == 0);
        check(abortSocket.closeCount() == 1);

        try {
            abortTransport.retryQuarantinedAbort();
            throw new AssertionError("retry superseded executing physical abort");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("already executing"));
        }
        try {
            abortTransport.retryQuarantinedClose();
            throw new AssertionError("finalization retried an executing physical close");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("unconfirmed"));
        }
        check(abortSocket.closeCount() == 1);

        releaseFirstAbortClose.countDown();
        currentAbortWorker.join(3000);
        check(!currentAbortWorker.isAlive());
        check(currentAbortFailure.get() == null);
        check(abortSenderFailure.get() == null);
        check(abortSubmission.get() != null);
        check(abortSubmission.get().writeAttemptFailed());
        check(abortTransport.snapshot().phase()
                == IpcTransportSessionLease.Phase.QUARANTINED);
        check(abortTransport.snapshot().activeWrites() == 0);
        check(abortSocket.closeCount() == 1);
        check(abortTransport.keyCloneZeroized());
        check(abortTransport.connectionBindingCloneZeroized());
        check(!abortSubmission.get().outcome().toCompletableFuture().isDone());

        abortTransport.retryQuarantinedClose();
        check(finalClose.get() != null);
        AtomicReference<Throwable> finalCloseFailure = new AtomicReference<>();
        Thread finalCloseWorker = new Thread(() -> {
            try {
                finalClose.get().run();
            } catch (Throwable failure) {
                finalCloseFailure.set(failure);
            }
        }, "ipc-test-finalize-after-abort");
        finalCloseWorker.start();
        finalCloseWorker.join(3000);
        check(!finalCloseWorker.isAlive());
        check(finalCloseFailure.get() == null);
        check(abortTransport.snapshot().phase()
                == IpcTransportSessionLease.Phase.RETIRED);
        // Abort already confirmed the exact physical descriptor close.
        // Finalization retires receipts only and must not invoke Socket.close()
        // a second time.
        check(abortSocket.closeCount() == 1);
        check(abortTransport.keyCloneZeroized());
        check(abortTransport.connectionBindingCloneZeroized());
        check(abortSubmission.get().outcome().toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(abortBridge.invariant());

        // A delayed copy of attempt 1 stays inert even after retirement.
        staleAbort.get().run();
        check(abortSocket.closeCount() == 1);

        // If the one actual abort close invocation throws after waking the
        // writer, the physical descriptor outcome is ambiguous. The generation
        // stays quarantined permanently and neither abort retry nor finalization
        // may invoke Socket.close() again.
        byte[] failedAbortPayload = new byte[8 * 1024 * 1024];
        Arrays.fill(failedAbortPayload, (byte) 0x33);
        var failedAbortMessage = envelope(
                1, "failedAbortClose", 3, 4, failedAbortPayload);
        CountDownLatch failedWriteEntered = new CountDownLatch(1);
        CountDownLatch failedAbortWrite = new CountDownLatch(1);
        var failingOutput = new BlockingAbortOutputStream(
                failedWriteEntered, failedAbortWrite);
        var failingSocket = new FailingAbortSocket(failingOutput);
        ExecutorService failingCleanup = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ipc-test-failing-abort-close"));
        try {
            var bridge = new IpcReceiptCompletionBridge(1);
            var transport = new IpcAuthenticatedSocketTransport(
                    failingSocket, bridge, failedAbortMessage, 1,
                    authority(141, key, connectionBinding),
                    failingCleanup::execute);
            AtomicReference<IpcAuthenticatedSocketTransport.SendSubmission> result =
                    new AtomicReference<>();
            AtomicReference<Throwable> senderFailure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try {
                    result.set(transport.send(
                            failedAbortMessage, failedAbortPayload));
                } catch (Throwable failure) {
                    senderFailure.set(failure);
                }
            }, "ipc-test-failing-abort-writer");
            sender.start();
            check(failedWriteEntered.await(2, TimeUnit.SECONDS));

            check(!transport.requestCloseAndAwait(3000));
            sender.join(3000);
            check(!sender.isAlive());
            check(senderFailure.get() == null);
            check(result.get() != null && result.get().writeAttemptFailed());
            check(failingSocket.closeCount() == 1);
            check(transport.snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(transport.snapshot().activeWrites() == 0);
            check(transport.keyCloneZeroized());
            check(transport.connectionBindingCloneZeroized());
            check(!result.get().outcome().toCompletableFuture().isDone());

            try {
                transport.retryQuarantinedAbort();
                throw new AssertionError(
                        "abort retry repeated an unconfirmed physical close");
            } catch (IllegalStateException expected) {
                ++checks;
            }
            try {
                transport.retryQuarantinedClose();
                throw new AssertionError(
                        "close retry repeated an unconfirmed physical close");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().contains("unconfirmed"));
            }
            check(failingSocket.closeCount() == 1);
            check(bridge.invariant());
        } finally {
            failingCleanup.shutdownNow();
            failingCleanup.awaitTermination(2, TimeUnit.SECONDS);
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
                    client, bridge, oldEpoch, 1,
                    authority(71, key, connectionBinding), cleanup::execute);
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
                    client, bridge, rejectedMessage, 1,
                    authority(72, key, connectionBinding), cleanup::execute);
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
                    client, bridge, maliciousMessage, 1,
                    authority(73, key, connectionBinding), cleanup::execute);
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
                    client, bridge, replayMessage, 1,
                    authority(81, key, connectionBinding), cleanup::execute);
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
                    client, bridge, reboundMessage, 1,
                    authority(821, key, replacementBinding), cleanup::execute);
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
                    client, bridge, tamperMessage, 1,
                    authority(74, key, connectionBinding), cleanup::execute);
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
                    client, bridge, inlineMessage, 1,
                    authority(88, key, connectionBinding), Runnable::run);
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
                    client, bridge, quarantineMessage, 1,
                    authority(89, key, connectionBinding), scheduler);
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
                    client, bridge, silentMessage, 1,
                    authority(90, key, connectionBinding), cleanup::execute);
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
                    client, bridge, blockedMessage, 1,
                    authority(91, key, connectionBinding), cleanup::execute);
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
            IpcTransportSessionLease.CleanupScheduler scheduler = cleanup -> {
                if (scheduled.incrementAndGet() == 1) {
                    Thread invalidCarrier = new Thread(() -> {
                        RuntimeBlockingGuard.enterActorCarrier();
                        try {
                            cleanup.run();
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
                    client, bridge, retryAbortMessage, 1,
                    authority(915, key, connectionBinding), scheduler);
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
                    client, bridge, deadlineMessage, 1,
                    authority(92, key, connectionBinding), scheduler);
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
