package dev.oreslang.runtime;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * Single accepted-socket HTTP/1.1 -> RFC6455 upgrade boundary.
 *
 * <p>This is a host-only primitive. It does not bind a listener, authorize an
 * actor, register native-I/O resource loans, or publish a guest connection.
 * The caller must own the accepted socket and already have a scoped NET
 * capability; it must close/retire the socket after the resulting session.
 * On any upgrade failure this boundary closes the untrusted socket.</p>
 */
final class WebSocketRawUpgrade {
    private WebSocketRawUpgrade() { }

    record Limits(int maxHeaderBytes, int maxHeaderCount, int handshakeTimeoutMillis) {
        Limits {
            if (maxHeaderBytes < 256 || maxHeaderBytes > 32 * 1024
                    || maxHeaderCount < 1 || maxHeaderCount > 64
                    || handshakeTimeoutMillis < 100 || handshakeTimeoutMillis > 30_000) {
                throw new IllegalArgumentException("invalid bounded WebSocket handshake limits");
            }
        }
    }

    record Admission(String target, String origin) { }

    /**
     * Only the host I/O carrier may perform this blocking raw upgrade. The
     * socket must come from an authorized loopback listener; this method also
     * rejects any non-loopback accepted peer as a second boundary.
     *
     * @param expectedHost exact authority for the already-bound endpoint,
     *     including its port when non-default; prevents vhost/Host confusion
     * @param allowedOrigins exact Origin allowlist; missing origins fail closed
     */
    static Admission upgrade(
            Socket accepted,
            String expectedHost,
            Set<String> allowedOrigins,
            Limits limits) throws IOException {
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException("WebSocket raw socket upgrade is host I/O only");
        }
        Objects.requireNonNull(accepted, "accepted socket");
        Objects.requireNonNull(limits, "handshake limits");
        if (expectedHost == null || expectedHost.isBlank()
                || allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("explicit Host and Origin policy required");
        }
        try {
            if (!accepted.isConnected() || accepted.isClosed()
                    || accepted.getInetAddress() == null
                    || !accepted.getInetAddress().isLoopbackAddress()) {
                throw new SecurityException("raw WebSocket upgrade requires loopback peer");
            }
            accepted.setSoTimeout(limits.handshakeTimeoutMillis());
            WebSocketOpeningRequest.Request request = WebSocketOpeningRequest.read(
                    accepted.getInputStream(),
                    limits.maxHeaderBytes(), limits.maxHeaderCount());
            if (!request.host().equals(expectedHost)) {
                throw new SecurityException("WebSocket Host does not match listener authority");
            }
            String origin = request.origin().orElseThrow(() ->
                    new SecurityException("WebSocket Origin required for this listener"));
            WebSocketHandshake.requireAllowedOrigin(origin, allowedOrigins);
            String acceptKey = request.admit();
            // No extension or subprotocol is negotiated. These are mandatory
            // response fields; never echo untrusted request header text.
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            accepted.getOutputStream().write(
                    response.getBytes(StandardCharsets.US_ASCII));
            accepted.getOutputStream().flush();
            // Leave a bounded read timeout in force. A later connection owner
            // may narrow this bound but must not silently reset it to infinity.
            return new Admission(request.target(), origin);
        } catch (IOException | RuntimeException failure) {
            try { accepted.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}
