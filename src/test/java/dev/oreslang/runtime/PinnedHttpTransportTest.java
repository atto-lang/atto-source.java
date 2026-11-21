package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class PinnedHttpTransportTest {
    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(
                    new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static OutboundEgressPolicy.Policy loopbackPolicy() {
        return OutboundEgressPolicy.Policy.loopbackOnly();
    }

    private static OutboundEgressPolicy.Policy loopbackPolicy(
            OutboundEgressPolicy.Limits limits) {
        return new OutboundEgressPolicy.Policy(
                Set.of(OutboundEgressPolicy.AddressClass.LOOPBACK),
                false,
                limits);
    }

    private static OutboundEgressPolicy.Resolver resolver(
            AtomicInteger calls) {
        return host -> {
            calls.incrementAndGet();
            return List.of(loopback());
        };
    }

    @Test
    void hostnameRequestConnectsToPinnedIpWithoutSecondResolution()
            throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        String request = readRequestHead(socket);
                        assertTrue(
                                request.startsWith(
                                        "GET /a%2Fb?q=x%2Fy HTTP/1.1\r\n"),
                                request);
                        assertTrue(
                                request.contains(
                                        "\r\nHost: origin.example:"
                                                + server.getLocalPort()
                                                + "\r\n"),
                                request);
                        socket.getOutputStream().write(
                                ("HTTP/1.1 201 Created\r\n"
                                        + "Set-Cookie: one=1\r\n"
                                        + "Set-Cookie: two=2\r\n"
                                        + "Content-Length: 6\r\n"
                                        + "Connection: close\r\n"
                                        + "\r\n"
                                        + "abcdef")
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(),
                                resolver(resolutions))) {
                    var call = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/a%2Fb?q=x%2Fy",
                            "GET",
                            List.of(Map.entry(
                                    "X-Test",
                                    "pinned")));
                    var response = call.execute();
                    assertEquals(201, response.status());
                    assertEquals("origin.example", response.host());
                    assertEquals(loopback(), response.connectedAddress());
                    assertEquals(1, resolutions.get());

                    List<String> cookies = response.headers().stream()
                            .filter(field -> field.getKey()
                                    .equalsIgnoreCase("Set-Cookie"))
                            .map(Map.Entry::getValue)
                            .toList();
                    assertEquals(
                            List.of("one=1", "two=2"),
                            cookies);
                    assertTrue(response.headers().stream().noneMatch(
                            field -> field.getKey()
                                    .equalsIgnoreCase("Content-Length")));

                    ByteArrayOutputStream body =
                            new ByteArrayOutputStream();
                    assertEquals(
                            6L,
                            response.streamBody(
                                    body,
                                    () -> false));
                    assertEquals(
                            "abcdef",
                            body.toString(StandardCharsets.UTF_8));
                    assertEquals(0, transport.inFlight());
                    assertEquals(0, transport.trackedOrigins());
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void eachNewPhysicalConnectionResolvesAgain()
            throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    for (int i = 0; i < 2; i++) {
                        try (Socket socket = server.accept()) {
                            readRequestHead(socket);
                            socket.getOutputStream().write(
                                    ("HTTP/1.1 204 No Content\r\n"
                                            + "Connection: close\r\n"
                                            + "\r\n")
                                            .getBytes(StandardCharsets.ISO_8859_1));
                            socket.getOutputStream().flush();
                        }
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(),
                                resolver(resolutions))) {
                    var first = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/one",
                            "GET",
                            List.of());
                    long generationOne;
                    try (var response = first.execute()) {
                        generationOne = response.resolutionGeneration();
                    }

                    var second = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/two",
                            "GET",
                            List.of());
                    long generationTwo;
                    try (var response = second.execute()) {
                        generationTwo = response.resolutionGeneration();
                    }

                    assertEquals(2, resolutions.get());
                    assertNotEquals(generationOne, generationTwo);
                    assertEquals(0, transport.inFlight());
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void connectionLimitsRejectInsteadOfQueueingBehindStalledOrigin()
            throws Exception {
        var limits = new OutboundEgressPolicy.Limits(
                4,
                0,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                1,
                1);
        AtomicInteger resolutions = new AtomicInteger();
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readRequestHead(socket);
                        accepted.countDown();
                        release.await(5, TimeUnit.SECONDS);
                    } catch (SocketException expectedOnCancel) {
                        // Client cancellation may close while server unwinds.
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(limits),
                                resolver(resolutions))) {
                    var first = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/slow",
                            "GET",
                            List.of());
                    var firstFuture = executor.submit(first::execute);
                    assertTrue(accepted.await(2, TimeUnit.SECONDS));
                    assertEquals(1, transport.inFlight());

                    var second = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/second",
                            "GET",
                            List.of());
                    RejectedExecutionException rejected = assertThrows(
                            RejectedExecutionException.class,
                            second::execute);
                    assertTrue(
                            rejected.getMessage().contains("global")
                                    || rejected.getMessage().contains("origin"),
                            rejected.getMessage());

                    first.cancel();
                    ExecutionException failed = assertThrows(
                            ExecutionException.class,
                            () -> firstFuture.get(2, TimeUnit.SECONDS));
                    assertTrue(
                            failed.getCause() instanceof IOException
                                    || failed.getCause()
                                            instanceof java.util.concurrent.CancellationException,
                            String.valueOf(failed.getCause()));
                    assertEquals(0, transport.inFlight());
                    assertEquals(0, transport.trackedOrigins());
                } finally {
                    release.countDown();
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void headerDeadlineRetiresStalledConnection()
            throws Exception {
        var limits = new OutboundEgressPolicy.Limits(
                4,
                0,
                Duration.ofSeconds(2),
                Duration.ofMillis(150),
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                2,
                2);
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readRequestHead(socket);
                        accepted.countDown();
                        release.await(3, TimeUnit.SECONDS);
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(limits),
                                host -> List.of(loopback()))) {
                    var call = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/headers",
                            "GET",
                            List.of());
                    SocketTimeoutException timeout = assertThrows(
                            SocketTimeoutException.class,
                            call::execute);
                    assertTrue(
                            timeout.getMessage().contains("Read timed out")
                                    || timeout.getMessage().contains("deadline"),
                            timeout.getMessage());
                    assertTrue(accepted.await(1, TimeUnit.SECONDS));
                    assertEquals(0, transport.inFlight());
                } finally {
                    release.countDown();
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void bodyIdleDeadlineClosesAndReleasesPermit()
            throws Exception {
        var limits = new OutboundEgressPolicy.Limits(
                4,
                0,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofMillis(150),
                Duration.ofSeconds(3),
                2,
                2);
        CountDownLatch firstByte = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readRequestHead(socket);
                        socket.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\n"
                                        + "Content-Length: 2\r\n"
                                        + "Connection: close\r\n"
                                        + "\r\n"
                                        + "x")
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                        firstByte.countDown();
                        release.await(3, TimeUnit.SECONDS);
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(limits),
                                host -> List.of(loopback()))) {
                    var response = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/body",
                            "GET",
                            List.of()).execute();
                    assertTrue(firstByte.await(1, TimeUnit.SECONDS));
                    SocketTimeoutException timeout = assertThrows(
                            SocketTimeoutException.class,
                            () -> response.streamBody(
                                    new ByteArrayOutputStream(),
                                    () -> false));
                    assertNotNull(timeout.getMessage());
                    assertEquals(0, transport.inFlight());
                    assertEquals(0, transport.trackedOrigins());
                } finally {
                    release.countDown();
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void chunkedBodyIsDecodedAndFramingHeadersStayTransportOwned()
            throws Exception {
        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readRequestHead(socket);
                        socket.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\n"
                                        + "Transfer-Encoding: chunked\r\n"
                                        + "Set-Cookie: a=1\r\n"
                                        + "Connection: close\r\n"
                                        + "\r\n"
                                        + "3\r\nabc\r\n"
                                        + "3\r\ndef\r\n"
                                        + "0\r\n"
                                        + "X-Trailer: ignored\r\n"
                                        + "\r\n")
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(),
                                host -> List.of(loopback()))) {
                    var response = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/chunked",
                            "GET",
                            List.of()).execute();
                    assertEquals(
                            List.of(Map.entry("Set-Cookie", "a=1")),
                            response.headers());
                    ByteArrayOutputStream body =
                            new ByteArrayOutputStream();
                    assertEquals(
                            6L,
                            response.streamBody(
                                    body,
                                    () -> false));
                    assertEquals(
                            "abcdef",
                            body.toString(StandardCharsets.UTF_8));
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void transferEncodingAndContentLengthTogetherFailClosed()
            throws Exception {
        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readRequestHead(socket);
                        socket.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\n"
                                        + "Transfer-Encoding: chunked\r\n"
                                        + "Content-Length: 3\r\n"
                                        + "Connection: close\r\n"
                                        + "\r\n"
                                        + "0\r\n\r\n")
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(),
                                host -> List.of(loopback()))) {
                    IOException failure = assertThrows(
                            IOException.class,
                            () -> transport.newCall(
                                    "http://origin.example:"
                                            + server.getLocalPort()
                                            + "/smuggle",
                                    "GET",
                                    List.of()).execute());
                    assertTrue(
                            failure.getMessage().contains(
                                    "Transfer-Encoding")
                                    && failure.getMessage().contains(
                                            "Content-Length"),
                            failure.getMessage());
                    assertEquals(0, transport.inFlight());
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void transportCloseCancelsAHeaderReadAndRetiresThePermit()
            throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readRequestHead(socket);
                        accepted.countDown();
                        release.await(3, TimeUnit.SECONDS);
                    }
                    return null;
                });

                PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(),
                                host -> List.of(loopback()));
                var call = transport.newCall(
                        "http://origin.example:"
                                + server.getLocalPort()
                                + "/shutdown",
                        "GET",
                        List.of());
                var client = executor.submit(call::execute);
                assertTrue(accepted.await(2, TimeUnit.SECONDS));

                transport.close();
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> client.get(2, TimeUnit.SECONDS));
                assertTrue(
                        failure.getCause() instanceof IOException
                                || failure.getCause()
                                        instanceof java.util.concurrent.CancellationException,
                        String.valueOf(failure.getCause()));
                assertEquals(0, transport.inFlight());
                assertEquals(0, transport.trackedOrigins());

                release.countDown();
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void ambiguousStatusDelimiterFailsClosed()
            throws Exception {
        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readRequestHead(socket);
                        socket.getOutputStream().write(
                                ("HTTP/1.1 200X smuggled\r\n"
                                        + "Content-Length: 0\r\n"
                                        + "\r\n")
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(),
                                host -> List.of(loopback()))) {
                    IOException failure = assertThrows(
                            IOException.class,
                            () -> transport.newCall(
                                    "http://origin.example:"
                                            + server.getLocalPort()
                                            + "/status",
                                    "GET",
                                    List.of()).execute());
                    assertTrue(
                            failure.getMessage().contains("status"),
                            failure.getMessage());
                    assertEquals(0, transport.inFlight());
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void chunkTrailerCannotRedefineFraming()
            throws Exception {
        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        readRequestHead(socket);
                        socket.getOutputStream().write(
                                ("HTTP/1.1 200 OK\r\n"
                                        + "Transfer-Encoding: chunked\r\n"
                                        + "\r\n"
                                        + "1\r\nx\r\n"
                                        + "0\r\n"
                                        + "Content-Length: 999\r\n"
                                        + "\r\n")
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    }
                    return null;
                });

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(),
                                host -> List.of(loopback()))) {
                    var response = transport.newCall(
                            "http://origin.example:"
                                    + server.getLocalPort()
                                    + "/trailer",
                            "GET",
                            List.of()).execute();
                    IOException failure = assertThrows(
                            IOException.class,
                            () -> response.streamBody(
                                    new ByteArrayOutputStream(),
                                    () -> false));
                    assertTrue(
                            failure.getMessage().contains("trailer"),
                            failure.getMessage());
                    assertEquals(0, transport.inFlight());
                }
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void tlsIdentityUsesOriginalHostWhileTcpSocketIsAlreadyPinned()
            throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AtomicInteger wraps = new AtomicInteger();

        try (ServerSocket server = listen()) {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var serverFuture = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        String request = readRequestHead(socket);
                        assertTrue(
                                request.contains(
                                        "\r\nHost: secure.example:"
                                                + server.getLocalPort()
                                                + "\r\n"),
                                request);
                        socket.getOutputStream().write(
                                ("HTTP/1.1 204 No Content\r\n"
                                        + "Connection: close\r\n"
                                        + "\r\n")
                                        .getBytes(StandardCharsets.ISO_8859_1));
                        socket.getOutputStream().flush();
                    }
                    return null;
                });

                PinnedHttpTransport.TlsLayer proofLayer =
                        (connected, target, deadline) -> {
                            wraps.incrementAndGet();
                            assertEquals(
                                    loopback(),
                                    connected.getInetAddress());
                            assertEquals(
                                    "secure.example",
                                    target.host());
                            assertTrue(deadline > System.nanoTime());
                            // Test seam only: returning the already-connected
                            // socket proves the transport does not reconnect or
                            // resolve below the policy boundary.
                            return connected;
                        };

                try (PinnedHttpTransport transport =
                        new PinnedHttpTransport(
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                loopbackPolicy(),
                                resolver(resolutions),
                                proofLayer,
                                1024)) {
                    try (var response = transport.newCall(
                            "https://secure.example:"
                                    + server.getLocalPort()
                                    + "/",
                            "GET",
                            List.of()).execute()) {
                        assertEquals(204, response.status());
                    }
                }

                assertEquals(1, resolutions.get());
                assertEquals(1, wraps.get());
                serverFuture.get(2, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void tlsParametersRequireHttpsEndpointIdentityAndSniForDnsNames() {
        SSLParameters dns = new SSLParameters();
        PinnedHttpTransport.configureTlsIdentity(
                dns,
                "origin.example");
        assertEquals(
                "HTTPS",
                dns.getEndpointIdentificationAlgorithm());
        assertNotNull(dns.getServerNames());
        assertEquals(1, dns.getServerNames().size());
        assertInstanceOf(
                SNIHostName.class,
                dns.getServerNames().getFirst());
        assertEquals(
                "origin.example",
                ((SNIHostName) dns.getServerNames().getFirst())
                        .getAsciiName());

        SSLParameters literal = new SSLParameters();
        literal.setServerNames(
                List.of(new SNIHostName("stale.example")));
        PinnedHttpTransport.configureTlsIdentity(
                literal,
                "127.0.0.1");
        assertEquals(
                "HTTPS",
                literal.getEndpointIdentificationAlgorithm());
        assertNotNull(literal.getServerNames());
        assertTrue(literal.getServerNames().isEmpty());
    }

    private static ServerSocket listen() throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(
                new InetSocketAddress(
                        loopback(),
                        0));
        return server;
    }

    private static String readRequestHead(Socket socket)
            throws IOException {
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminal = {'\r', '\n', '\r', '\n'};
        while (bytes.size() < 32_768) {
            int value = input.read();
            if (value < 0) {
                throw new IOException(
                        "client closed before request headers completed");
            }
            bytes.write(value);
            if (value == terminal[matched]) {
                matched++;
                if (matched == terminal.length) {
                    return bytes.toString(
                            StandardCharsets.ISO_8859_1);
                }
            } else {
                matched = value == terminal[0] ? 1 : 0;
            }
        }
        throw new IOException(
                "request headers exceeded test budget");
    }
}
