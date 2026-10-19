package dev.oreslang.runtime;

import java.net.Socket;

/** Fail-closed negative checks; real trust-chain handshake tests are a release gate. */
public final class UnaryTcpMutualTlsAuthenticatorCheck {
    private UnaryTcpMutualTlsAuthenticatorCheck() { }

    private static void check(boolean success, String what) {
        if (!success) throw new AssertionError(what);
    }

    private static void reject(
            UnaryTcpMutualTlsAuthenticator authenticator,
            Socket socket, long sessionId, long generation) throws Exception {
        try {
            authenticator.authenticateAndBind(socket, sessionId, generation);
            throw new AssertionError("unauthenticated or invalid TLS session accepted");
        } catch (SecurityException expected) {
            // Deliberate: never dispatch or bind a non-TLS/invalid socket.
        }
    }

    public static void main(String[] unused) throws Exception {
        boolean rejectedMissingPolicy = false;
        try {
            new UnaryTcpMutualTlsAuthenticator(null);
        } catch (NullPointerException expected) {
            rejectedMissingPolicy = true;
        }
        check(rejectedMissingPolicy,
                "production mTLS requires an explicit certificate authorizer");
        var authenticator = new UnaryTcpMutualTlsAuthenticator(
                leaf -> { throw new SecurityException("unapproved client"); });
        try (var plaintext = new Socket()) {
            reject(authenticator, plaintext, 1, 1);
            reject(authenticator, plaintext, 0, 1);
            reject(authenticator, plaintext, 1, 0);
        }
        System.out.println("PASS: mutual TLS rejects plaintext, invalid bindings and missing authorization policy");
    }
}
