package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class PinnedHttpTransportTest {
    private record TlsContexts(
            SSLContext client,
            SSLContext server) { }

    @Test
    void httpConnectsToPinnedAddressWithoutResolvingHostnameBelowPolicy()
            throws Exception {
        try (ServerSocket server = listen()) {
            AtomicReference<String> requestHead = new AtomicReference<>();
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    requestHead.set(readRequestHead(socket.getInputStream()));
                    socket.getOutputStream().write((
                            "HTTP/1.1 201 Created\r\n"
                            + "Content-Length: 6\r\n"
                            + "Set-Cookie: one=1\r\n"
                            + "Set-Cookie: two=2\r\n"
                            + "X-Remove: secret\r\n"
                            + "Connection: close, X-Remove\r\n"
                            + "\r\n"
                            + "abcdef").getBytes(StandardCharsets.ISO_8859_1));
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            AtomicInteger resolutions = new AtomicInteger();
            OutboundEgressPolicy.Resolver resolver = host -> {
                assertEquals("origin.example", host);
                resolutions.incrementAndGet();
                return List.of(InetAddress.getByName("127.0.0.1"));
            };

            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    SSLContext.getDefault(),
                    1024)) {
                PinnedHttpTransport.Response response = transport.request(
                        URI.create(
                                "http://origin.example:"
                                        + server.getLocalPort()
                                        + "/a%2Fb?q=x%2Fy"),
                        "GET",
                        List.of(Map.entry("X-Test", "one")));
                assertEquals(201, response.status());
                assertEquals(
                        List.of("one=1", "two=2"),
                        response.headerValues("Set-Cookie"));
                assertTrue(response.headerValues("Connection").isEmpty());
                assertTrue(response.headerValues("Content-Length").isEmpty());
                assertTrue(response.headerValues("X-Remove").isEmpty());

                ByteArrayOutputStream body = new ByteArrayOutputStream();
                assertEquals(
                        6,
                        response.streamBody(body, () -> false));
                assertEquals("abcdef", body.toString(StandardCharsets.UTF_8));
                assertEquals(0, transport.activeSockets());
                assertEquals(0, transport.activeOrigins());
            }

            peer.join();
            if (serverFailure.get() != null) {
                throw new AssertionError(serverFailure.get());
            }
            assertEquals(1, resolutions.get());
            assertNotNull(requestHead.get());
            assertTrue(
                    requestHead.get().startsWith(
                            "GET /a%2Fb?q=x%2Fy HTTP/1.1\r\n"),
                    requestHead.get());
            assertTrue(
                    requestHead.get().contains(
                            "Host: origin.example:"
                                    + server.getLocalPort()
                                    + "\r\n"),
                    requestHead.get());
            assertTrue(
                    requestHead.get().contains(
                            "Connection: close\r\n"),
                    requestHead.get());
        }
    }

    @Test
    void eachPhysicalConnectionGetsFreshDnsAuthorization()
            throws Exception {
        try (ServerSocket server = listen()) {
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread peer = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        try (Socket socket = server.accept()) {
                            readRequestHead(socket.getInputStream());
                            socket.getOutputStream().write((
                                    "HTTP/1.1 204 No Content\r\n"
                                    + "Connection: close\r\n"
                                    + "\r\n").getBytes(
                                            StandardCharsets.ISO_8859_1));
                        }
                    }
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            AtomicInteger resolutions = new AtomicInteger();
            OutboundEgressPolicy.Resolver resolver = host -> {
                resolutions.incrementAndGet();
                return List.of(InetAddress.getByName("127.0.0.1"));
            };
            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    SSLContext.getDefault(),
                    1024)) {
                URI target = URI.create(
                        "http://origin.example:"
                                + server.getLocalPort()
                                + "/");
                try (var first = transport.request(
                        target, "GET", List.of())) {
                    assertEquals(204, first.status());
                }
                try (var second = transport.request(
                        target, "GET", List.of())) {
                    assertEquals(204, second.status());
                }
            }

            peer.join();
            if (serverFailure.get() != null) {
                throw new AssertionError(serverFailure.get());
            }
            assertEquals(
                    2,
                    resolutions.get(),
                    "Connection: close means every request is a new physical connection and must re-resolve");
        }
    }

    @Test
    void httpsConnectsPinnedIpButUsesOriginalHostnameForSniAndVerification()
            throws Exception {
        TlsContexts tls = tlsContexts();
        AtomicReference<String> requestedSni = new AtomicReference<>();
        AtomicReference<String> requestHead = new AtomicReference<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();

        try (SSLServerSocket server =
                (SSLServerSocket) tls.server()
                        .getServerSocketFactory()
                        .createServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread peer = Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.setUseClientMode(false);
                    socket.startHandshake();
                    if (socket.getSession() instanceof ExtendedSSLSession session) {
                        for (SNIServerName name : session.getRequestedServerNames()) {
                            if (name instanceof SNIHostName hostName) {
                                requestedSni.set(hostName.getAsciiName());
                            }
                        }
                    }
                    requestHead.set(readRequestHead(socket.getInputStream()));
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 2\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
                            + "ok").getBytes(StandardCharsets.ISO_8859_1));
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            AtomicInteger resolutions = new AtomicInteger();
            OutboundEgressPolicy.Resolver resolver = host -> {
                assertEquals("origin.example", host);
                resolutions.incrementAndGet();
                return List.of(InetAddress.getByName("127.0.0.1"));
            };

            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    tls.client(),
                    1024)) {
                PinnedHttpTransport.Response response = transport.request(
                        URI.create(
                                "https://origin.example:"
                                        + server.getLocalPort()
                                        + "/secure"),
                        "GET",
                        List.of());
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                response.streamBody(body, () -> false);
                assertEquals("ok", body.toString(StandardCharsets.UTF_8));
            }

            peer.join();
            if (serverFailure.get() != null) {
                throw new AssertionError(serverFailure.get());
            }
            assertEquals(1, resolutions.get());
            assertEquals("origin.example", requestedSni.get());
            assertTrue(
                    requestHead.get().contains(
                            "Host: origin.example:"
                                    + server.getLocalPort()
                                    + "\r\n"),
                    requestHead.get());
        }
    }

    @Test
    void httpsHostnameMismatchFailsClosedAfterPinnedTcpConnect()
            throws Exception {
        TlsContexts tls = tlsContexts();
        CountDownLatch accepted = new CountDownLatch(1);

        try (SSLServerSocket server =
                (SSLServerSocket) tls.server()
                        .getServerSocketFactory()
                        .createServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread peer = Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    accepted.countDown();
                    socket.setUseClientMode(false);
                    socket.startHandshake();
                } catch (IOException expectedWhenClientRejectsCertificateName) {
                    // Client-side endpoint identification aborts the connection.
                }
            });

            OutboundEgressPolicy.Resolver resolver = host ->
                    List.of(InetAddress.getByName("127.0.0.1"));
            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    tls.client(),
                    1024)) {
                IOException failure = assertThrows(
                        IOException.class,
                        () -> transport.request(
                                URI.create(
                                        "https://wrong.example:"
                                                + server.getLocalPort()
                                                + "/"),
                                "GET",
                                List.of()));
                assertTrue(
                        failure instanceof SSLHandshakeException
                                || containsCause(
                                        failure,
                                        SSLHandshakeException.class),
                        failure.toString());
                assertEquals(0, transport.activeSockets());
                assertEquals(0, transport.activeOrigins());
            }

            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            peer.join();
        }
    }

    @Test
    void perOriginCapacityIsHeldUntilResponsePhysicallyCloses()
            throws Exception {
        var limits = new OutboundEgressPolicy.Limits(
                4,
                0,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                1,
                2);
        var policy = new OutboundEgressPolicy.Policy(
                Set.of(OutboundEgressPolicy.AddressClass.LOOPBACK),
                false,
                limits);

        try (ServerSocket server = listen()) {
            CountDownLatch firstAccepted = new CountDownLatch(1);
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread peer = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        try (Socket socket = server.accept()) {
                            readRequestHead(socket.getInputStream());
                            if (i == 0) firstAccepted.countDown();
                            socket.getOutputStream().write((
                                    "HTTP/1.1 200 OK\r\n"
                                    + "Content-Length: 1\r\n"
                                    + "Connection: close\r\n"
                                    + "\r\n"
                                    + "x").getBytes(
                                            StandardCharsets.ISO_8859_1));
                        }
                    }
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            AtomicInteger capacityResolutions = new AtomicInteger();
            OutboundEgressPolicy.Resolver resolver = host -> {
                capacityResolutions.incrementAndGet();
                return List.of(InetAddress.getByName("127.0.0.1"));
            };
            PinnedHttpTransport.ConnectionBudget budget =
                    new PinnedHttpTransport.ConnectionBudget(limits);
            try (PinnedHttpTransport firstTransport = new PinnedHttpTransport(
                            OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                            policy,
                            resolver,
                            SSLContext.getDefault(),
                            1024,
                            budget);
                    PinnedHttpTransport secondTransport = new PinnedHttpTransport(
                            OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                            policy,
                            resolver,
                            SSLContext.getDefault(),
                            1024,
                            budget)) {
                URI target = URI.create(
                        "http://origin.example:"
                                + server.getLocalPort()
                                + "/");
                PinnedHttpTransport.Response first =
                        firstTransport.request(target, "GET", List.of());
                assertTrue(firstAccepted.await(2, TimeUnit.SECONDS));
                assertEquals(1, firstTransport.activeSockets());
                assertEquals(1, firstTransport.activeOrigins());

                assertThrows(
                        RejectedExecutionException.class,
                        () -> secondTransport.request(
                                target,
                                "GET",
                                List.of()),
                        "independent request transports must share the context budget");
                assertEquals(
                        1,
                        capacityResolutions.get(),
                        "capacity rejection must happen before another DNS resolution");

                first.close();
                assertEquals(0, firstTransport.activeSockets());
                assertEquals(0, firstTransport.activeOrigins());

                try (var second = secondTransport.request(
                        target,
                        "GET",
                        List.of())) {
                    assertEquals(200, second.status());
                }
                assertEquals(2, capacityResolutions.get());
            }

            peer.join();
            if (serverFailure.get() != null) {
                throw new AssertionError(serverFailure.get());
            }
        }
    }

    @Test
    void headerDeadlineFailureClosesSocketAndReleasesCapacity()
            throws Exception {
        var limits = new OutboundEgressPolicy.Limits(
                4,
                0,
                Duration.ofSeconds(1),
                Duration.ofMillis(75),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                1,
                1);
        var policy = new OutboundEgressPolicy.Policy(
                Set.of(OutboundEgressPolicy.AddressClass.LOOPBACK),
                false,
                limits);

        try (ServerSocket server = listen()) {
            CountDownLatch accepted = new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    accepted.countDown();
                    readRequestHead(socket.getInputStream());
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Client deadline closes the socket.
                }
            });

            OutboundEgressPolicy.Resolver resolver = host ->
                    List.of(InetAddress.getByName("127.0.0.1"));
            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    policy,
                    resolver,
                    SSLContext.getDefault(),
                    1024)) {
                IOException failure = assertThrows(
                        IOException.class,
                        () -> transport.request(
                                URI.create(
                                        "http://origin.example:"
                                                + server.getLocalPort()
                                                + "/"),
                                "GET",
                                List.of()));
                assertTrue(
                        failure.getMessage().contains("deadline"),
                        failure.toString());
                assertEquals(0, transport.activeSockets());
                assertEquals(0, transport.activeOrigins());
            }

            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            peer.join();
        }
    }

    @Test
    void bodyIdleDeadlineClosesResponseAndReleasesCapacity()
            throws Exception {
        var limits = new OutboundEgressPolicy.Limits(
                4,
                0,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(75),
                Duration.ofSeconds(2),
                1,
                1);
        var policy = new OutboundEgressPolicy.Policy(
                Set.of(OutboundEgressPolicy.AddressClass.LOOPBACK),
                false,
                limits);

        try (ServerSocket server = listen()) {
            CountDownLatch firstByteSent = new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 2\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
                            + "x").getBytes(
                                    StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    firstByteSent.countDown();
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Body-idle timeout closes the socket.
                }
            });

            OutboundEgressPolicy.Resolver resolver = host ->
                    List.of(InetAddress.getByName("127.0.0.1"));
            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    policy,
                    resolver,
                    SSLContext.getDefault(),
                    1024)) {
                PinnedHttpTransport.Response response = transport.request(
                        URI.create(
                                "http://origin.example:"
                                        + server.getLocalPort()
                                        + "/"),
                        "GET",
                        List.of());
                assertTrue(firstByteSent.await(2, TimeUnit.SECONDS));
                IOException failure = assertThrows(
                        IOException.class,
                        () -> response.streamBody(
                                new ByteArrayOutputStream(),
                                () -> false));
                assertTrue(
                        failure.getMessage().contains("deadline"),
                        failure.toString());
                assertEquals(0, transport.activeSockets());
                assertEquals(0, transport.activeOrigins());
            }

            peer.join();
        }
    }

    @Test
    void ambiguousResponseFramingFailsClosedBeforeBodyHandoff()
            throws Exception {
        try (ServerSocket server = listen()) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 1\r\n"
                            + "Transfer-Encoding: chunked\r\n"
                            + "Connection: close\r\n"
                            + "\r\n").getBytes(
                                    StandardCharsets.ISO_8859_1));
                } catch (IOException ignored) {
                    // The client rejects ambiguous framing and closes.
                }
            });

            OutboundEgressPolicy.Resolver resolver = host ->
                    List.of(InetAddress.getByName("127.0.0.1"));
            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    SSLContext.getDefault(),
                    1024)) {
                IOException failure = assertThrows(
                        IOException.class,
                        () -> transport.request(
                                URI.create(
                                        "http://origin.example:"
                                                + server.getLocalPort()
                                                + "/"),
                                "GET",
                                List.of()));
                assertTrue(
                        failure.getMessage().contains(
                                "both Transfer-Encoding and Content-Length"),
                        failure.toString());
                assertEquals(0, transport.activeSockets());
                assertEquals(0, transport.activeOrigins());
            }

            peer.join();
        }
    }

    @Test
    void resetContentIsBodylessEvenWhenPeerAdvertisesAndStallsABody()
            throws Exception {
        try (ServerSocket server = listen()) {
            CountDownLatch headersSent = new CountDownLatch(1);
            CountDownLatch releasePeer = new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write((
                            "HTTP/1.1 205 Reset Content\r\n"
                            + "Content-Length: 999\r\n"
                            + "Connection: keep-alive\r\n"
                            + "\r\n").getBytes(
                                    StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    headersSent.countDown();
                    releasePeer.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // The bodyless response closes the client side immediately.
                }
            });

            OutboundEgressPolicy.Resolver resolver = host ->
                    List.of(InetAddress.getByName("127.0.0.1"));
            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    SSLContext.getDefault(),
                    1024)) {
                PinnedHttpTransport.Response response = transport.request(
                        URI.create(
                                "http://origin.example:"
                                        + server.getLocalPort()
                                        + "/"),
                        "GET",
                        List.of());
                assertTrue(headersSent.await(2, TimeUnit.SECONDS));
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                assertEquals(
                        0,
                        response.streamBody(body, () -> false),
                        "205 must not wait for or consume a response body");
                assertEquals(0, body.size());
                assertEquals(0, transport.activeSockets());
                assertEquals(0, transport.activeOrigins());
            } finally {
                releasePeer.countDown();
            }

            peer.join();
        }
    }

    @Test
    void strictChunkedBodyAndResponseBudgetAreEnforced()
            throws Exception {
        try (ServerSocket server = listen()) {
            AtomicReference<Throwable> serverFailure = new AtomicReference<>();
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n"
                            + "Transfer-Encoding: chunked\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
                            + "4\r\nabcd\r\n"
                            + "2\r\nef\r\n"
                            + "0\r\n\r\n").getBytes(
                                    StandardCharsets.ISO_8859_1));
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            OutboundEgressPolicy.Resolver resolver = host ->
                    List.of(InetAddress.getByName("127.0.0.1"));
            try (PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    SSLContext.getDefault(),
                    6)) {
                PinnedHttpTransport.Response response = transport.request(
                        URI.create(
                                "http://origin.example:"
                                        + server.getLocalPort()
                                        + "/"),
                        "GET",
                        List.of());
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                assertEquals(
                        6,
                        response.streamBody(body, () -> false));
                assertEquals(
                        "abcdef",
                        body.toString(StandardCharsets.UTF_8));
            }

            peer.join();
            if (serverFailure.get() != null) {
                throw new AssertionError(serverFailure.get());
            }
        }
    }

    @Test
    void transportCloseInterruptsBlockedBodyAndReleasesOriginLease()
            throws Exception {
        try (ServerSocket server = listen()) {
            CountDownLatch bodyStarted = new CountDownLatch(1);
            CountDownLatch releasePeer = new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 4096\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
                            + "x").getBytes(
                                    StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    bodyStarted.countDown();
                    releasePeer.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Transport shutdown is expected to close the peer socket.
                }
            });

            OutboundEgressPolicy.Resolver resolver = host ->
                    List.of(InetAddress.getByName("127.0.0.1"));
            PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    SSLContext.getDefault(),
                    8192);
            PinnedHttpTransport.Response response = transport.request(
                    URI.create(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/"),
                    "GET",
                    List.of());
            assertTrue(bodyStarted.await(2, TimeUnit.SECONDS));

            AtomicReference<Throwable> readerFailure = new AtomicReference<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    response.streamBody(
                            new ByteArrayOutputStream(),
                            () -> false);
                } catch (Throwable failure) {
                    readerFailure.set(failure);
                }
            });

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(2);
            while (transport.activeSockets() != 1
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            transport.close();
            reader.join();

            assertNotNull(
                    readerFailure.get(),
                    "closing the transport must revoke the blocked body read");
            assertEquals(0, transport.activeSockets());
            assertEquals(0, transport.activeOrigins());

            releasePeer.countDown();
            peer.join();
        }
    }

    @Test
    void transportCloseRevokesDnsButKeepsLateResolverReceiptPinned()
            throws Exception {
        AsyncNativeIo nativeIo = new AsyncNativeIo(4, 1, 4);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OutboundEgressPolicy.Resolver delegate = host -> {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return List.of(InetAddress.getByName("127.0.0.1"));
        };
        BoundedDnsResolver resolver = new BoundedDnsResolver(
                nativeIo,
                delegate,
                Duration.ofSeconds(5),
                1,
                1);

        PinnedHttpTransport transport = new PinnedHttpTransport(
                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                OutboundEgressPolicy.Policy.loopbackOnly(),
                resolver,
                SSLContext.getDefault(),
                1024);

        AtomicReference<Throwable> requestFailure =
                new AtomicReference<>();
        Thread request = Thread.ofVirtual().start(() -> {
            try {
                transport.request(
                        URI.create("http://origin.example:80/"),
                        "GET",
                        List.of());
            } catch (Throwable failure) {
                requestFailure.set(failure);
            }
        });

        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals(
                PinnedHttpTransport.PhysicalPhase.RESOLVING,
                transport.phase());

        transport.close();
        request.join();

        assertNotNull(
                requestFailure.get(),
                "cancelling during DNS must revoke request authorization");
        assertEquals(
                PinnedHttpTransport.PhysicalPhase.RETIRED,
                transport.phase());
        assertEquals(0, transport.activeSockets());
        assertEquals(0, transport.activeOrigins());
        assertEquals(
                1,
                resolver.activeResolutions(),
                "late system resolver remains a physical native-I/O receipt until its worker exits");

        release.countDown();
        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(2);
        while (resolver.activeResolutions() != 0
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(0, resolver.activeResolutions());
        assertFalse(
                resolver.closed(),
                "request transport cancellation must not close the context resolver");
        assertEquals(
                "127.0.0.1",
                resolver.resolve("second.example")
                        .getFirst()
                        .getHostAddress(),
                "the context resolver must remain reusable by later requests");
        resolver.close();
        nativeIo.close();
    }

    @Test
    void workerInterruptRetiresSelectorHeaderWait()
            throws Exception {
        try (ServerSocket server = listen()) {
            CountDownLatch requestSeen = new CountDownLatch(1);
            CountDownLatch releasePeer = new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    readRequestHead(socket.getInputStream());
                    requestSeen.countDown();
                    releasePeer.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Interrupted client closes the physical connection.
                }
            });

            OutboundEgressPolicy.Resolver resolver = host ->
                    List.of(InetAddress.getByName("127.0.0.1"));
            PinnedHttpTransport transport = new PinnedHttpTransport(
                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                    OutboundEgressPolicy.Policy.loopbackOnly(),
                    resolver,
                    SSLContext.getDefault(),
                    1024);
            AtomicReference<Throwable> requestFailure =
                    new AtomicReference<>();
            Thread request = Thread.ofVirtual().start(() -> {
                try {
                    transport.request(
                            URI.create(
                                    "http://origin.example:"
                                            + server.getLocalPort()
                                            + "/"),
                            "GET",
                            List.of());
                } catch (Throwable failure) {
                    requestFailure.set(failure);
                }
            });

            try {
                assertTrue(requestSeen.await(2, TimeUnit.SECONDS));
                request.interrupt();
                request.join(2000);
                assertFalse(
                        request.isAlive(),
                        "native worker interruption must retire a selector wait");
                assertNotNull(requestFailure.get());
                assertEquals(0, transport.activeSockets());
                assertEquals(0, transport.activeOrigins());
                assertEquals(
                        PinnedHttpTransport.PhysicalPhase.RETIRED,
                        transport.phase());
            } finally {
                transport.close();
                releasePeer.countDown();
                request.interrupt();
                request.join(2000);
                peer.join();
            }
        }
    }

    private static ServerSocket listen() throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        return server;
    }

    private static String readRequestHead(InputStream input)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 32_768) {
            int value = input.read();
            if (value < 0) throw new IOException("request closed before headers");
            bytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> 4;
            };
            if (matched == 4) {
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("request header budget exceeded");
    }

    private static TlsContexts tlsContexts() throws Exception {
        X509Certificate certificate = loadCertificate();
        PrivateKey key = loadPrivateKey();

        char[] password = "ores-test".toCharArray();
        KeyStore identity = KeyStore.getInstance(
                KeyStore.getDefaultType());
        identity.load(null, null);
        identity.setKeyEntry(
                "origin",
                key,
                password,
                new java.security.cert.Certificate[] { certificate });
        KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(identity, password);

        KeyStore trust = KeyStore.getInstance(
                KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("origin", certificate);
        TrustManagerFactory trustManagers =
                TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);

        SSLContext server = SSLContext.getInstance("TLS");
        server.init(
                keyManagers.getKeyManagers(),
                null,
                new SecureRandom());
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(
                null,
                trustManagers.getTrustManagers(),
                new SecureRandom());
        return new TlsContexts(client, server);
    }

    private static X509Certificate loadCertificate()
            throws Exception {
        try (InputStream input = resource(
                "/tls/origin-example-cert.pem")) {
            return (X509Certificate) CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(input);
        }
    }

    private static PrivateKey loadPrivateKey()
            throws Exception {
        String pem;
        try (InputStream input = resource(
                "/tls/origin-example-key.pem")) {
            pem = new String(
                    input.readAllBytes(),
                    StandardCharsets.US_ASCII);
        }
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] encoded = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(encoded));
    }

    private static InputStream resource(String path) {
        InputStream input =
                PinnedHttpTransportTest.class.getResourceAsStream(path);
        if (input == null) {
            throw new IllegalStateException(
                    "missing test TLS resource " + path);
        }
        return input;
    }

    private static boolean containsCause(
            Throwable failure,
            Class<? extends Throwable> type) {
        for (Throwable cursor = failure;
                cursor != null;
                cursor = cursor.getCause()) {
            if (type.isInstance(cursor)) return true;
        }
        return false;
    }
}
