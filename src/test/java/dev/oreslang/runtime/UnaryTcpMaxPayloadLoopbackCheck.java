package dev.oreslang.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Full-wire-budget loopback through UnaryTcpSessionAdapter. */
public final class UnaryTcpMaxPayloadLoopbackCheck {
    private UnaryTcpMaxPayloadLoopbackCheck() { }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ImmediateHandle
            implements UnaryTcpSessionAdapter.DispatchHandle {
        private final CompletableFuture<UnaryTcpSessionAdapter.Reply> completion;

        ImmediateHandle(UnaryTcpSessionAdapter.Reply reply) {
            completion = CompletableFuture.completedFuture(reply);
        }

        @Override
        public void whenComplete(
                BiConsumer<? super UnaryTcpSessionAdapter.Reply, ? super Throwable> callback) {
            completion.whenComplete(callback);
        }

        @Override
        public boolean cancel() {
            return completion.cancel(false);
        }
    }

    public static void main(String[] unused) throws Exception {
        byte[] payload = new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES];
        payload[0] = (byte) 255;
        payload[payload.length / 2] = (byte) 128;
        payload[payload.length - 1] = 7;

        var limits = new UnaryTcpSessionAdapter.Limits(
                1,
                1,
                UnaryTransportFrame.MAX_PAYLOAD_BYTES,
                1,
                UnaryTransportFrame.MAX_PAYLOAD_BYTES,
                UnaryTransportFrame.MAX_PAYLOAD_BYTES,
                5000,
                5000,
                5000);

        UnaryTcpSessionAdapter.Authenticator auth = (socket, sessionId, generation) -> {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeLong(sessionId);
            out.writeLong(generation);
            out.flush();
            return new UnaryTcpSessionAdapter.PeerIdentity("loopback:max-payload");
        };

        UnaryTcpSessionAdapter.Dispatcher echo = request -> {
            check(request.payload().length == UnaryTransportFrame.MAX_PAYLOAD_BYTES,
                    "adapter dispatched truncated maximum payload");
            Object actorPayload = request.actorMessage().get("payload");
            check(actorPayload instanceof OresBytes,
                    "adapter request must expose compact OresBytes actor payload");
            OresBytes bytes = (OresBytes) actorPayload;
            check(bytes.size() == UnaryTransportFrame.MAX_PAYLOAD_BYTES,
                    "compact actor payload lost maximum wire size");
            check(bytes.unsignedAt(0) == 255
                            && bytes.unsignedAt(bytes.size() / 2) == 128
                            && bytes.unsignedAt(bytes.size() - 1) == 7,
                    "compact actor payload changed request bytes");
            return new ImmediateHandle(
                    UnaryTcpSessionAdapter.Reply.success(request.payload()));
        };

        try (var adapter = new UnaryTcpSessionAdapter(limits, auth, echo);
             var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = workers.submit(() -> {
                try {
                    adapter.serve(server.accept());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });

            try (Socket client = new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(7000);
                DataInputStream binding = new DataInputStream(client.getInputStream());
                long sessionId = binding.readLong();
                long generation = binding.readLong();

                UnaryTransportFrame.writeTcp(
                        client.getOutputStream(),
                        new UnaryTransportFrame.Envelope(
                                UnaryTransportFrame.Kind.REQUEST,
                                sessionId,
                                generation,
                                1,
                                payload));
                client.getOutputStream().flush();

                UnaryTransportFrame.Envelope reply =
                        UnaryTransportFrame.readTcp(
                                client.getInputStream(),
                                UnaryTransportFrame.MAX_PAYLOAD_BYTES);
                check(reply.kind() == UnaryTransportFrame.Kind.REPLY,
                        "maximum payload did not receive REPLY");
                check(reply.correlationId() == 1,
                        "maximum payload reply lost correlation");
                check(Arrays.equals(reply.payload(), payload),
                        "maximum payload reply was not byte-exact");
            }

            served.get(7, TimeUnit.SECONDS);
            check(adapter.activeConnections() == 0,
                    "maximum payload loopback leaked connection credit");
            check(adapter.activeGlobalWorkItems() == 0,
                    "maximum payload loopback leaked work credit");
            check(adapter.activeGlobalPayloadBytes() == 0,
                    "maximum payload loopback leaked byte credit");
        }

        System.out.println(
                "PASS: full 1 MiB unary TCP session request/reply and credit reclamation");
    }
}
