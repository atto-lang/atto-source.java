package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Forked-JVM authenticated IPC proof. The peer runs in a separate OS process,
 * so frame bytes cross a kernel socket/process boundary rather than only
 * crossing threads in one VM. This is still Java<->Java host evidence, not
 * broker identity/key establishment or LLVM parity.
 */
public final class IpcAuthenticatedSocketTwoProcessSelfTest {
    private static final byte[] PAYLOAD =
            "forked authenticated ores ipc".getBytes(StandardCharsets.UTF_8);
    private static final long ROUTE_EPOCH = 13;
    private static final long CAPABILITY_GENERATION = 17;
    private static int checks;

    private IpcAuthenticatedSocketTwoProcessSelfTest() { }

    private static void check(boolean value) {
        ++checks;
        if (!value) {
            throw new AssertionError("forked IPC transport invariant failed");
        }
    }

    private static byte[] key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; ++i) {
            key[i] = (byte) (71 + i);
        }
        return key;
    }

    private static byte[] binding() {
        byte[] binding = new byte[16];
        for (int i = 0; i < binding.length; ++i) {
            binding[i] = (byte) (41 + i);
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

    private static IpcReceiptCorrelation.Envelope envelope(String session) {
        return new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", session, digest(PAYLOAD),
                1, 2, ROUTE_EPOCH, CAPABILITY_GENERATION, 1, 1);
    }

    private static Process launchPeer(int port, String mode) throws IOException {
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                        .contains("win") ? "java.exe" : "java").toString();
        return new ProcessBuilder(
                executable,
                "-cp",
                System.getProperty("java.class.path"),
                IpcAuthenticatedSocketTwoProcessSelfTest.class.getName(),
                "--peer",
                Integer.toString(port),
                mode)
                .redirectErrorStream(true)
                .start();
    }

    private static void terminatePeer(Process peer) throws InterruptedException {
        if (!peer.isAlive()) {
            return;
        }
        peer.destroy();
        if (!peer.waitFor(1, TimeUnit.SECONDS)) {
            peer.destroyForcibly();
            if (!peer.waitFor(2, TimeUnit.SECONDS)) {
                throw new AssertionError("forked IPC peer could not be terminated");
            }
        }
    }

    private static String finishPeer(Process peer) throws Exception {
        if (!peer.waitFor(5, TimeUnit.SECONDS)) {
            terminatePeer(peer);
            throw new AssertionError("forked IPC peer did not exit before deadline");
        }
        ++checks;
        String output = new String(peer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (peer.exitValue() != 0) {
            throw new AssertionError(
                    "forked IPC peer failed with exit " + peer.exitValue() + ": " + output);
        }
        ++checks;
        return output;
    }

    private static void runPeer(int port, String mode) throws Exception {
        if (!mode.equals("success") && !mode.equals("stale-generation")) {
            throw new IllegalArgumentException("invalid forked IPC peer mode");
        }
        byte[] key = key();
        byte[] binding = binding();
        long generation = mode.equals("success") ? 501 : 502;
        var message = envelope(mode.equals("success") ? "forkedSuccess" : "forkedStale");

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(5000);
            byte[] frame = IpcAuthenticatedSocketTransport.readDataWireFrame(
                    socket.getInputStream());
            var decoded = IpcAuthenticatedSocketTransport.decodeDataFrame(
                    frame,
                    message,
                    generation,
                    new IpcAuthenticatedSocketTransport.InboundReplayGate(),
                    binding,
                    key);
            if (!decoded.envelope().equals(message)
                    || !java.util.Arrays.equals(decoded.payload(), PAYLOAD)) {
                throw new AssertionError("forked peer decoded wrong DATA");
            }

            long receiptGeneration = mode.equals("success")
                    ? generation
                    : generation - 1;
            IpcAuthenticatedSocketTransport.writeWireFrame(
                    socket.getOutputStream(),
                    IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                            message,
                            receiptGeneration,
                            IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED,
                            binding,
                            key));
            if (mode.equals("success")) {
                IpcAuthenticatedSocketTransport.writeWireFrame(
                        socket.getOutputStream(),
                        IpcAuthenticatedSocketTransport.encodeReceiptFrame(
                                message,
                                generation,
                                IpcReceiptCorrelation.Stage.HANDLER_COMMITTED,
                                binding,
                                key));
            }
        }
        System.out.println("FORKED_IPC_PEER_PASS mode=" + mode);
    }

    private static void runSuccess() throws Exception {
        byte[] key = key();
        byte[] binding = binding();
        var message = envelope("forkedSuccess");
        ExecutorService cleanup = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "ipc-forked-success-cleanup"));

        try (ServerSocket server = new ServerSocket(
                    0, 8, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Process peer = launchPeer(server.getLocalPort(), "success");
            try {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var bridge = new IpcReceiptCompletionBridge(1);
                    var transport = new IpcAuthenticatedSocketTransport(
                            socket, bridge, message, 501, 1,
                            key, binding, cleanup::execute);

                    var submission = transport.send(message, PAYLOAD);
                    check(!submission.writeAttemptFailed());
                    check(transport.readVerifiedReceipt()
                            == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
                    check(transport.readVerifiedReceipt()
                            == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                    check(submission.outcome().toCompletableFuture().join()
                            == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                    check(bridge.invariant());
                    check(transport.requestCloseAndAwait(3000));
                    check(transport.keyCloneZeroized());
                }

                String output = finishPeer(peer);
                check(output.contains("FORKED_IPC_PEER_PASS mode=success"));
            } finally {
                terminatePeer(peer);
            }
        } finally {
            cleanup.shutdownNow();
            cleanup.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void runStaleGenerationFailure() throws Exception {
        byte[] key = key();
        byte[] binding = binding();
        var message = envelope("forkedStale");
        ExecutorService cleanup = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "ipc-forked-stale-cleanup"));

        try (ServerSocket server = new ServerSocket(
                    0, 8, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            Process peer = launchPeer(server.getLocalPort(), "stale-generation");
            try {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var bridge = new IpcReceiptCompletionBridge(1);
                    var transport = new IpcAuthenticatedSocketTransport(
                            socket, bridge, message, 502, 1,
                            key, binding, cleanup::execute);

                    var submission = transport.send(message, PAYLOAD);
                    check(!submission.writeAttemptFailed());
                    try {
                        transport.readVerifiedReceipt();
                        throw new AssertionError("forked stale-generation receipt accepted");
                    } catch (IOException expected) {
                        check(expected.getMessage().contains(
                                "physical transport generation"));
                    }
                    check(transport.requestCloseAndAwait(3000));
                    check(submission.outcome().toCompletableFuture().join()
                            == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
                    check(bridge.invariant());
                    check(transport.keyCloneZeroized());
                }

                String output = finishPeer(peer);
                check(output.contains(
                        "FORKED_IPC_PEER_PASS mode=stale-generation"));
            } finally {
                terminatePeer(peer);
            }
        } finally {
            cleanup.shutdownNow();
            cleanup.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--peer")) {
            runPeer(Integer.parseInt(args[1]), args[2]);
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("invalid forked IPC test arguments");
        }

        runSuccess();
        runStaleGenerationFailure();
        System.out.println("JAVA_IPC_FORKED_PROCESS_PASS checks=" + checks);
    }
}
