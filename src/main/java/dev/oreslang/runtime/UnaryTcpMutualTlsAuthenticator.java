package dev.oreslang.runtime;

import java.io.DataOutputStream;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Objects;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * Opt-in, host-owned mutual TLS 1.3 authenticator for unary TCP sessions.
 *
 * <p>The host must build its SSLContext with a reviewed client-certificate trust
 * manager, serve only accepted server-mode SSLSockets, and supply an explicit
 * certificate-to-principal authorization decision. This class does not trust
 * certificate subjects automatically or accept plaintext sockets.</p>
 *
 * <p>After the authenticated TLS handshake and authorization, the two positive
 * supervisor-minted signed 64-bit session/generation values are sent in network
 * byte order over that same authenticated TLS channel. No unary request envelope
 * is read before this step completes.</p>
 */
final class UnaryTcpMutualTlsAuthenticator
        implements UnaryTcpSessionAdapter.Authenticator {
    @FunctionalInterface
    interface CertificateAuthorizer {
        /**
         * Return a nonblank application principal only if this particular
         * validated client certificate is authorized to dispatch unary RPC.
         * Reject all unrecognized identities by throwing SecurityException.
         */
        String authorize(X509Certificate leaf) throws Exception;
    }

    private final CertificateAuthorizer certificateAuthorizer;

    UnaryTcpMutualTlsAuthenticator(CertificateAuthorizer certificateAuthorizer) {
        this.certificateAuthorizer =
                Objects.requireNonNull(certificateAuthorizer, "certificateAuthorizer");
    }

    @Override
    public UnaryTcpSessionAdapter.PeerIdentity authenticateAndBind(
            Socket socket, long sessionId, long generation) throws Exception {
        if (sessionId <= 0 || generation <= 0)
            throw new SecurityException("invalid supervisor-minted TLS session binding");
        if (!(socket instanceof SSLSocket tls) || tls.getUseClientMode())
            throw new SecurityException("unary TCP mTLS requires a server-mode SSLSocket");

        tls.setUseClientMode(false);
        tls.setNeedClientAuth(true);
        // Do not negotiate legacy TLS versions or downgrade when the
        // configured provider cannot support TLS 1.3.
        tls.setEnabledProtocols(new String[] {"TLSv1.3"});
        tls.startHandshake();

        SSLSession established = tls.getSession();
        if (!established.isValid() || !"TLSv1.3".equals(established.getProtocol()))
            throw new SecurityException("unary TCP requires a verified TLS 1.3 session");
        Certificate[] presented = established.getPeerCertificates();
        if (presented.length == 0 || !(presented[0] instanceof X509Certificate leaf))
            throw new SecurityException("verified client X.509 certificate required");
        leaf.checkValidity();
        // Trust-chain verification is performed by the SSLContext trust manager;
        // authorization is a distinct, mandatory application policy decision.
        UnaryTcpSessionAdapter.PeerIdentity peer =
                new UnaryTcpSessionAdapter.PeerIdentity(certificateAuthorizer.authorize(leaf));

        DataOutputStream output = new DataOutputStream(tls.getOutputStream());
        output.writeLong(sessionId);
        output.writeLong(generation);
        output.flush();
        return peer;
    }
}
