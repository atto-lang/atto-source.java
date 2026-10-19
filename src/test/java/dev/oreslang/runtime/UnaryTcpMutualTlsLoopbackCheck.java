package dev.oreslang.runtime;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * JDK-only real TLS 1.3 loopback validation with disposable test certificates.
 * Invoke with the path containing separately generated server/client keystores
 * and truststores; never load production certificates or trust anchors.
 */
public final class UnaryTcpMutualTlsLoopbackCheck {
    private UnaryTcpMutualTlsLoopbackCheck() { }

    static SSLContext context(Path key, Path trust) throws Exception {
        char[] password = "changeit".toCharArray(); // Disposable CI fixtures only.
        KeyStore keys = KeyStore.getInstance("PKCS12");
        KeyStore trusts = KeyStore.getInstance("PKCS12");
        if (key != null) {
            try (var stream = java.nio.file.Files.newInputStream(key)) {
                keys.load(stream, password);
            }
        }
        try (var stream = java.nio.file.Files.newInputStream(trust)) {
            trusts.load(stream, password);
        }
        KeyManagerFactory km =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        if (key != null) km.init(keys, password);
        TrustManagerFactory tm =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trusts);
        SSLContext ssl = SSLContext.getInstance("TLSv1.3");
        ssl.init(key == null ? null : km.getKeyManagers(), tm.getTrustManagers(), null);
        return ssl;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void exercise(Path fixtures, boolean authorize,
                         boolean presentClientCertificate) throws Exception {
        SSLContext server = context(
                fixtures.resolve("server.p12"), fixtures.resolve("server-trust.p12"));
        SSLContext client = context(
                presentClientCertificate ? fixtures.resolve("client.p12") : null,
                fixtures.resolve("client-trust.p12"));
        try (SSLServerSocket listener = (SSLServerSocket)
                server.getServerSocketFactory().createServerSocket(
                        0, 4, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setNeedClientAuth(true);
            var serverRun = pool.submit(() -> {
                try (SSLSocket peer = (SSLSocket) listener.accept()) {
                    var auth = new UnaryTcpMutualTlsAuthenticator(cert -> {
                        if (!authorize)
                            throw new SecurityException("client is not authorized");
                        if (!cert.getSubjectX500Principal().getName()
                                .contains("oreslang-test-client")) {
                            throw new SecurityException("incorrect authorized identity");
                        }
                        return "oreslang-allowed";
                    });
                    if (authorize && presentClientCertificate) {
                        var result = auth.authenticateAndBind(peer, 72, 9);
                        require(result.principal().equals("oreslang-allowed"),
                                "authorized principal mismatch");
                    } else {
                        try {
                            auth.authenticateAndBind(peer, 72, 9);
                            throw new AssertionError("unauthorized client was accepted");
                        } catch (javax.net.ssl.SSLException | SecurityException expected) {
                            // TLS client auth or application authorization rejected.
                        }
                    }
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            try (SSLSocket clientSocket = (SSLSocket)
                    client.getSocketFactory().createSocket(
                            InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                clientSocket.setSoTimeout(3000);
                if (presentClientCertificate && authorize) {
                    DataInputStream binding =
                            new DataInputStream(clientSocket.getInputStream());
                    require(binding.readLong() == 72,
                            "TLS-authenticated channel session binding mismatch");
                    require(binding.readLong() == 9,
                            "TLS-authenticated channel generation mismatch");
                } else {
                    try {
                        int value = clientSocket.getInputStream().read();
                        require(value == -1, "unauthorized client received session binding");
                    } catch (javax.net.ssl.SSLException expected) {
                        // JSSE may terminate a failed client-auth handshake with an alert.
                    }
                }
            }
            serverRun.get(8, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1)
            throw new IllegalArgumentException("expected disposable TLS fixture directory");
        Path fixtures = Path.of(args[0]);
        exercise(fixtures, true, true);
        exercise(fixtures, false, true);
        exercise(fixtures, true, false);
        System.out.println("PASS: TLS 1.3 mTLS + authorization + bound IDs; rejects unauthorized/missing cert");
    }
}
