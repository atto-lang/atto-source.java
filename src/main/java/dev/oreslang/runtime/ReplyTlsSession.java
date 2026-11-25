package dev.oreslang.runtime;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Runtime-side reply ingress for a real, mutually authenticated TLS channel.
 *
 * <p>The caller supplies a pinned peer certificate fingerprint and the
 * supervisor-authorized responding endpoint. Both must come from trusted host
 * configuration; no wire message can choose the expected responder, decoder,
 * response ABI or origin. JSSE validates the certificate chain and TLS frame
 * integrity, then this class additionally pins the authenticated leaf.
 * Servers MUST require a TLS client certificate; non-mutual TLS is rejected
 * on server-side sockets. The ALPN protocol is mandatory.</p>
 *
 * <p>This class only attaches the reply half of remote RPC. A request sender,
 * caller protocol negotiation and remote actor execution are separate gates.
 * Never call readOne() from an actor turn: it blocks an I/O worker and must not
 * execute guest callbacks. Normal actor Future waiters only enqueue a
 * continuation through their original scheduler.</p>
 */
final class ReplyTlsSession<T> implements AutoCloseable {
    private static final String ALPN = "ores-reply/1";
    private static final int HEADER = Integer.BYTES + 1 + Long.BYTES
            + ReplyTicketAuthenticator.TICKET_BYTES + Integer.BYTES;
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();
    private final SSLSocket socket;
    private final ReplySlotRegistry slots;
    private final ReplySlotRegistry.AuthenticatedPeer verifiedPeer;
    private final ReplyTransportGateway<T> gateway;
    private final String responseAbi;
    private final int maxFrameBytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean reading = new AtomicBoolean();
    /** Linearizes close/disconnect against pending request registration. */
    private final Object lifecycleLock = new Object();

    private ReplyTlsSession(
            SSLSocket socket, ReplySlotRegistry slots,
            ReplySlotRegistry.AuthenticatedPeer verifiedPeer,
            String responseAbi, Function<byte[], T> trustedDecoder,
            int maxFrameBytes) {
        this.socket = socket;
        this.slots = slots;
        this.verifiedPeer = verifiedPeer;
        this.responseAbi = responseAbi;
        this.maxFrameBytes = maxFrameBytes;
        this.gateway = new ReplyTransportGateway<>(
                slots, verifiedPeer, responseAbi, trustedDecoder, maxFrameBytes);
    }

    /**
     * TLS handshake, ALPN negotiation and certificate pinning occur BEFORE
     * exposing a pending-reply registration. The transport supplies the
     * verified peer; the incoming reply does not carry an authority-bearing
     * endpoint identity.
     */
    static <T> ReplyTlsSession<T> open(
            SSLSocket socket, ReplySlotRegistry slots,
            ReplySlotRegistry.Endpoint expectedResponder,
            byte[] pinnedLeafCertificateSha256,
            String responseAbi,
            Function<byte[], T> trustedDecoder,
            int maxFrameBytes, int timeoutMillis) throws IOException {
        Objects.requireNonNull(socket, "socket");
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(expectedResponder, "expectedResponder");
        Objects.requireNonNull(trustedDecoder, "trustedDecoder");
        if (pinnedLeafCertificateSha256 == null
                || pinnedLeafCertificateSha256.length != 32) {
            throw new IllegalArgumentException("SHA-256 leaf certificate pin required");
        }
        if (timeoutMillis < 1 || timeoutMillis > 120_000) {
            throw new IllegalArgumentException("bounded TLS read timeout required");
        }
        // The framing gateway validates the same bounded maxFrameBytes.
        if (maxFrameBytes < HEADER || maxFrameBytes > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("bounded TLS reply frame required");
        }
        if (responseAbi == null || responseAbi.isBlank()
                || responseAbi.length() > 128) {
            throw new IllegalArgumentException("response ABI digest required");
        }
        if (ActorRuntime.inActorExecution()) {
            throw new IllegalStateException("TLS handshake cannot block an actor lane");
        }
        try {
            if (!socket.getUseClientMode() && !socket.getNeedClientAuth()) {
                throw new SecurityException(
                        "server-side reply TLS must require mutual client authentication");
            }
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setApplicationProtocols(new String[]{ALPN});
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(timeoutMillis);
            socket.startHandshake();

            SSLSession session = socket.getSession();
            if (!("TLSv1.3".equals(session.getProtocol())
                    || "TLSv1.2".equals(session.getProtocol()))
                    || !ALPN.equals(socket.getApplicationProtocol())
                    || session.getCipherSuite().contains("_NULL_")) {
                throw new SecurityException("unsafe reply TLS version/cipher/ALPN");
            }
            Certificate[] verifiedChain = session.getPeerCertificates();
            if (verifiedChain.length == 0) {
                throw new SecurityException("no authenticated reply peer certificate");
            }
            byte[] actualPin = MessageDigest.getInstance("SHA-256")
                    .digest(verifiedChain[0].getEncoded());
            if (!MessageDigest.isEqual(
                    pinnedLeafCertificateSha256, actualPin)) {
                throw new SecurityException("reply peer certificate pin mismatch");
            }
            long sessionNonce;
            do {
                sessionNonce = SESSION_RANDOM.nextLong();
            } while (sessionNonce == 0L);
            return new ReplyTlsSession<>(socket, slots,
                    new ReplySlotRegistry.AuthenticatedPeer(
                            expectedResponder, sessionNonce),
                    responseAbi, trustedDecoder, maxFrameBytes);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IOException("TLS reply session certificate verification failed", failure);
        }
    }

    ReplySlotRegistry.AuthenticatedPeer authenticatedPeer() { return verifiedPeer; }

    /** Caller-only ticket issuance. The request sender transports the ticket. */
    <R> ReplySlotRegistry.Pending<R> reservePending(Class<R> responseType) {
        synchronized (lifecycleLock) {
            if (closed.get()) throw new IllegalStateException("reply TLS session closed");
            return slots.reserve(responseType, responseAbi, verifiedPeer);
        }
    }

    byte[] ticketFor(long handle) {
        synchronized (lifecycleLock) {
            if (closed.get()) throw new IllegalStateException("reply TLS session closed");
            return slots.issueRemoteTicket(handle, verifiedPeer, responseAbi);
        }
    }

    /**
     * Read ONE reply on a dedicated I/O worker. Never run an actor callback on
     * this thread. A malicious length cannot allocate more than the configured
     * frame limit. Rejected authentic-but-unauthorized replies do not settle
     * unrelated Futures; transport disconnect fails only this session's calls.
     */
    boolean readOne() throws IOException {
        if (ActorRuntime.inActorExecution()) {
            throw new IllegalStateException("reply TLS I/O cannot block an actor lane");
        }
        if (closed.get()) throw new IOException("reply TLS session closed");
        if (!reading.compareAndSet(false, true)) {
            throw new IllegalStateException("concurrent reply reads are forbidden");
        }
        try {
            byte[] header = new byte[HEADER];
            DataInputStream in = new DataInputStream(socket.getInputStream());
            in.readFully(header);
            int length = ByteBuffer.wrap(header, HEADER - Integer.BYTES,
                    Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt();
            if (length < 0 || length > maxFrameBytes - HEADER) {
                throw new IOException("invalid or oversized TLS reply payload");
            }
            byte[] frame = Arrays.copyOf(header, HEADER + length);
            in.readFully(frame, HEADER, length);
            return gateway.acceptAuthenticatedFrame(frame);
        } catch (IOException failure) {
            abort(failure);
            throw failure;
        } finally {
            reading.set(false);
        }
    }

    private void abort(Throwable cause) {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            // No reservation can escape between the disconnect sweep and
            // marking this session closed. Other sessions are unaffected.
            slots.failDisconnectedSession(verifiedPeer, cause);
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // The pending calls already settled with the original failure.
        }
    }

    @Override public void close() {
        abort(new EOFException("reply TLS session closed"));
    }
}
