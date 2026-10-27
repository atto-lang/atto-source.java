package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class SelectorHttpTransportTest {
    @Test
    void hostnameFlowsThroughSelectorDnsPolicyAndPinnedSocket()
            throws Exception {
        try (DatagramChannel dns = dnsServer();
                ServerSocket http = listen()) {
            AtomicReference<Throwable> dnsFailure =
                    new AtomicReference<>();
            Thread dnsPeer = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        DnsQuery query = receiveDns(dns);
                        byte[] answer =
                                query.type() == 1
                                        ? new byte[] {127, 0, 0, 1}
                                        : null;
                        sendDns(
                                dns,
                                query,
                                dnsAnswer(query, answer));
                    }
                } catch (Throwable failure) {
                    dnsFailure.set(failure);
                }
            });

            AtomicReference<String> requestHead =
                    new AtomicReference<>();
            AtomicReference<Throwable> httpFailure =
                    new AtomicReference<>();
            Thread httpPeer = Thread.ofVirtual().start(() -> {
                try (Socket socket = http.accept()) {
                    requestHead.set(
                            readRequestHead(
                                    socket.getInputStream()));
                    socket.getOutputStream().write((
                            "HTTP/1.1 201 Created\r\n"
                            + "Content-Length: 6\r\n"
                            + "Set-Cookie: one=1\r\n"
                            + "Set-Cookie: two=2\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
                            + "abcdef").getBytes(
                                    StandardCharsets.ISO_8859_1));
                } catch (Throwable failure) {
                    httpFailure.set(failure);
                }
            });

            var dnsConfig =
                    new SelectorDnsResolver.Config(
                            List.of((InetSocketAddress)
                                    dns.getLocalAddress()),
                            Duration.ofSeconds(2));
            var policy = loopbackPolicy(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
            var budget =
                    new PinnedHttpTransport.ConnectionBudget(
                            policy.limits());

            try (SelectorDnsResolver resolver =
                            new SelectorDnsResolver(dnsConfig);
                    SelectorHttpTransport transport =
                            new SelectorHttpTransport(
                                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                                    policy,
                                    resolver,
                                    budget,
                                    1024)) {
                SelectorHttpTransport.Response response =
                        transport.request(
                                URI.create(
                                        "http://origin.example:"
                                                + http.getLocalPort()
                                                + "/a%2Fb?q=x%2Fy"),
                                "GET",
                                List.of(
                                        Map.entry(
                                                "X-Test",
                                                "one")));
                assertEquals(201, response.status());
                assertEquals(
                        SelectorHttpTransport.State.READING_HEADERS,
                        response.state());
                assertEquals(
                        List.of("one=1", "two=2"),
                        response.headerValues("Set-Cookie"));

                ByteArrayOutputStream body =
                        new ByteArrayOutputStream();
                assertEquals(
                        6,
                        response.streamBody(
                                body,
                                () -> false));
                assertEquals(
                        "abcdef",
                        body.toString(
                                StandardCharsets.UTF_8));
                assertEquals(
                        SelectorHttpTransport.State.RETIRED,
                        response.state());
                assertEquals(0, transport.activeCount());
                assertEquals(0, resolver.activeCount());
            }

            dnsPeer.join();
            httpPeer.join();
            if (dnsFailure.get() != null) {
                throw new AssertionError(dnsFailure.get());
            }
            if (httpFailure.get() != null) {
                throw new AssertionError(httpFailure.get());
            }
            assertTrue(
                    requestHead.get().startsWith(
                            "GET /a%2Fb?q=x%2Fy HTTP/1.1\r\n"),
                    requestHead.get());
            assertTrue(
                    requestHead.get().contains(
                            "Host: origin.example:"
                                    + http.getLocalPort()
                                    + "\r\n"),
                    requestHead.get());
        }
    }

    @Test
    void transportCloseCancelsDnsBeforeAuthorizationOrConnect()
            throws Exception {
        try (DatagramChannel dns = dnsServer()) {
            CountDownLatch querySeen =
                    new CountDownLatch(1);
            Thread dnsPeer = Thread.ofVirtual().start(() -> {
                try {
                    receiveDns(dns);
                    querySeen.countDown();
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                }
            });

            var dnsConfig =
                    new SelectorDnsResolver.Config(
                            List.of((InetSocketAddress)
                                    dns.getLocalAddress()),
                            Duration.ofSeconds(5));
            var policy = loopbackPolicy(
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5));
            var budget =
                    new PinnedHttpTransport.ConnectionBudget(
                            policy.limits());

            try (SelectorDnsResolver resolver =
                    new SelectorDnsResolver(dnsConfig)) {
                SelectorHttpTransport transport =
                        new SelectorHttpTransport(
                                OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                                policy,
                                resolver,
                                budget,
                                1024);
                AtomicReference<Throwable> failure =
                        new AtomicReference<>();
                Thread worker = Thread.ofVirtual().start(() -> {
                    try {
                        transport.request(
                                URI.create(
                                        "http://origin.example:6553/"),
                                "GET",
                                List.of());
                    } catch (Throwable caught) {
                        failure.set(caught);
                    }
                });

                assertTrue(
                        querySeen.await(
                                2,
                                TimeUnit.SECONDS));
                assertEquals(1, resolver.activeCount());
                assertEquals(
                        List.of(
                                SelectorHttpTransport.State.RESOLVING),
                        transport.activeStates());

                transport.close();
                worker.join();

                assertNotNull(failure.get());
                assertTrue(
                        failure.get()
                                        instanceof CancellationException
                                || failure.get()
                                        instanceof IOException,
                        failure.get().toString());
                assertEquals(0, resolver.activeCount());
                assertEquals(0, transport.activeCount());
            }
            dnsPeer.interrupt();
            dnsPeer.join();
        }
    }

    @Test
    void responseHeaderDeadlineRetiresSelectorAndPermit()
            throws Exception {
        try (ServerSocket http = listen()) {
            CountDownLatch accepted =
                    new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = http.accept()) {
                    accepted.countDown();
                    readRequestHead(socket.getInputStream());
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                }
            });

            var policy = loopbackPolicy(
                    Duration.ofMillis(75),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2));
            var budget =
                    new PinnedHttpTransport.ConnectionBudget(
                            policy.limits());

            try (SelectorHttpTransport transport =
                    new SelectorHttpTransport(
                            OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                            policy,
                            noDns(),
                            budget,
                            1024)) {
                IOException failure =
                        assertThrows(
                                IOException.class,
                                () -> transport.request(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + http.getLocalPort()
                                                        + "/"),
                                        "GET",
                                        List.of()));
                assertTrue(
                        failure instanceof SocketTimeoutException
                                || failure.getMessage()
                                        .contains("deadline"),
                        failure.toString());
                assertEquals(0, transport.activeCount());
                assertEquals(0, budget.activeOrigins());
            }

            assertTrue(
                    accepted.await(
                            2,
                            TimeUnit.SECONDS));
            peer.join();
        }
    }

    @Test
    void informationalResponsesAdvanceToFinalHead()
            throws Exception {
        try (ServerSocket http = listen()) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = http.accept()) {
                    readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write((
                            "HTTP/1.1 103 Early Hints\r\n"
                            + "Link: </style.css>; rel=preload\r\n"
                            + "\r\n"
                            + "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 2\r\n"
                            + "X-Final: yes\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
                            + "ok").getBytes(
                                    StandardCharsets.ISO_8859_1));
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });

            var policy = loopbackPolicy(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(3));
            var budget =
                    new PinnedHttpTransport.ConnectionBudget(
                            policy.limits());
            try (SelectorHttpTransport transport =
                    new SelectorHttpTransport(
                            OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                            policy,
                            noDns(),
                            budget,
                            1024)) {
                var response =
                        transport.request(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + http.getLocalPort()
                                                + "/"),
                                "GET",
                                List.of());
                assertEquals(200, response.status());
                assertEquals(
                        List.of("yes"),
                        response.headerValues("X-Final"));
                assertTrue(
                        response.headerValues("Link").isEmpty());

                ByteArrayOutputStream body =
                        new ByteArrayOutputStream();
                response.streamBody(body, () -> false);
                assertEquals(
                        "ok",
                        body.toString(
                                StandardCharsets.UTF_8));
            }
            peer.join();
        }
    }

    @Test
    void chunkedBodyAndHopByHopFilteringSurviveSelectorPath()
            throws Exception {
        try (ServerSocket http = listen()) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = http.accept()) {
                    readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n"
                            + "Transfer-Encoding: chunked\r\n"
                            + "X-Remove: secret\r\n"
                            + "Connection: close, X-Remove\r\n"
                            + "Set-Cookie: a=1\r\n"
                            + "Set-Cookie: b=2\r\n"
                            + "\r\n"
                            + "4\r\nabcd\r\n"
                            + "2\r\nef\r\n"
                            + "0\r\n\r\n").getBytes(
                                    StandardCharsets.ISO_8859_1));
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });

            var policy = loopbackPolicy(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(3));
            var budget =
                    new PinnedHttpTransport.ConnectionBudget(
                            policy.limits());

            try (SelectorHttpTransport transport =
                    new SelectorHttpTransport(
                            OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                            policy,
                            noDns(),
                            budget,
                            6)) {
                var response =
                        transport.request(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + http.getLocalPort()
                                                + "/"),
                                "GET",
                                List.of());
                assertTrue(
                        response.headerValues(
                                        "Transfer-Encoding")
                                .isEmpty());
                assertTrue(
                        response.headerValues("Connection")
                                .isEmpty());
                assertTrue(
                        response.headerValues("X-Remove")
                                .isEmpty());
                assertEquals(
                        List.of("a=1", "b=2"),
                        response.headerValues("Set-Cookie"));

                ByteArrayOutputStream body =
                        new ByteArrayOutputStream();
                assertEquals(
                        6,
                        response.streamBody(
                                body,
                                () -> false));
                assertEquals(
                        "abcdef",
                        body.toString(
                                StandardCharsets.UTF_8));
            }
            peer.join();
        }
    }

    @Test
    void foreignThreadRevocationStillRetiresOnPhysicalOwner()
            throws Exception {
        try (ServerSocket http = listen()) {
            CountDownLatch bodyStarted =
                    new CountDownLatch(1);
            CountDownLatch releasePeer =
                    new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket socket = http.accept()) {
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
                    releasePeer.await(
                            5,
                            TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                }
            });

            var policy = loopbackPolicy(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5));
            var budget =
                    new PinnedHttpTransport.ConnectionBudget(
                            policy.limits());
            SelectorHttpTransport transport =
                    new SelectorHttpTransport(
                            OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                            policy,
                            noDns(),
                            budget,
                            8192);

            AtomicReference<Throwable> failure =
                    new AtomicReference<>();
            AtomicReference<SelectorHttpTransport.Response> seen =
                    new AtomicReference<>();
            Thread physical = Thread.ofVirtual().start(() -> {
                try {
                    var response =
                            transport.request(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + http.getLocalPort()
                                                    + "/"),
                                    "GET",
                                    List.of());
                    seen.set(response);
                    response.streamBody(
                            new ByteArrayOutputStream(),
                            () -> false);
                } catch (Throwable caught) {
                    failure.set(caught);
                }
            });

            assertTrue(
                    bodyStarted.await(
                            2,
                            TimeUnit.SECONDS));
            long deadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(2);
            while (seen.get() == null
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertNotNull(seen.get());
            assertEquals(1, budget.activeOrigins());

            transport.close();
            physical.join();

            assertNotNull(failure.get());
            assertEquals(
                    SelectorHttpTransport.State.RETIRED,
                    seen.get().state());
            assertEquals(0, transport.activeCount());
            assertEquals(0, budget.activeOrigins());

            releasePeer.countDown();
            peer.join();
        }
    }

    @Test
    void fullGuestBodyPipeExpiresAtAbsoluteDeadline()
            throws Exception {
        OutboundHttpRequest.BodyPipe pipe =
                new OutboundHttpRequest.BodyPipe();
        byte[] full = new byte[32 * 1024];
        pipe.write(
                full,
                0,
                full.length,
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(1));

        long started = System.nanoTime();
        SocketTimeoutException failure =
                assertThrows(
                        SocketTimeoutException.class,
                        () -> pipe.write(
                                new byte[] {1},
                                0,
                                1,
                                System.nanoTime()
                                        + TimeUnit.MILLISECONDS
                                                .toNanos(75)));
        long elapsed = System.nanoTime() - started;

        assertTrue(
                failure.getMessage()
                        .contains("backpressure deadline"));
        assertTrue(
                elapsed
                        < TimeUnit.SECONDS.toNanos(1),
                "backpressure expiry must be bounded");
    }

    @Test
    void sharedCapacityRejectsBeforeSecondHostnameDnsQuery()
            throws Exception {
        try (DatagramChannel dns = dnsServer();
                ServerSocket http = listen()) {
            AtomicInteger dnsQueries = new AtomicInteger();
            Thread dnsPeer = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        DnsQuery query = receiveDns(dns);
                        dnsQueries.incrementAndGet();
                        sendDns(
                                dns,
                                query,
                                dnsAnswer(
                                        query,
                                        query.type() == 1
                                                ? new byte[] {
                                                    127, 0, 0, 1
                                                }
                                                : null));
                    }
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });

            CountDownLatch firstAccepted =
                    new CountDownLatch(1);
            CountDownLatch releaseFirst =
                    new CountDownLatch(1);
            Thread httpPeer = Thread.ofVirtual().start(() -> {
                try (Socket socket = http.accept()) {
                    readRequestHead(socket.getInputStream());
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: 1\r\n"
                            + "Connection: close\r\n"
                            + "\r\n"
                            + "x").getBytes(
                                    StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                    firstAccepted.countDown();
                    releaseFirst.await(
                            5,
                            TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                }
            });

            var limits =
                    new OutboundEgressPolicy.Limits(
                            8,
                            0,
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(3),
                            1,
                            1);
            var policy =
                    new OutboundEgressPolicy.Policy(
                            Set.of(
                                    OutboundEgressPolicy.AddressClass.LOOPBACK),
                            false,
                            limits);
            var budget =
                    new PinnedHttpTransport.ConnectionBudget(
                            limits);
            var dnsConfig =
                    new SelectorDnsResolver.Config(
                            List.of((InetSocketAddress)
                                    dns.getLocalAddress()),
                            Duration.ofSeconds(1));

            try (SelectorDnsResolver resolver =
                            new SelectorDnsResolver(dnsConfig);
                    SelectorHttpTransport first =
                            new SelectorHttpTransport(
                                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                                    policy,
                                    resolver,
                                    budget,
                                    1024);
                    SelectorHttpTransport second =
                            new SelectorHttpTransport(
                                    OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                                    policy,
                                    resolver,
                                    budget,
                                    1024)) {
                URI target =
                        URI.create(
                                "http://origin.example:"
                                        + http.getLocalPort()
                                        + "/");
                var response =
                        first.request(
                                target,
                                "GET",
                                List.of());
                assertTrue(
                        firstAccepted.await(
                                2,
                                TimeUnit.SECONDS));
                assertEquals(2, dnsQueries.get());

                assertThrows(
                        java.util.concurrent.RejectedExecutionException.class,
                        () -> second.request(
                                target,
                                "GET",
                                List.of()));
                assertEquals(
                        2,
                        dnsQueries.get(),
                        "capacity rejection must happen before DNS");

                response.close();
            } finally {
                releaseFirst.countDown();
            }

            dnsPeer.join();
            httpPeer.join();
        }
    }

    private static OutboundEgressPolicy.Policy loopbackPolicy(
            Duration headerTimeout,
            Duration bodyIdleTimeout,
            Duration overallTimeout) {
        return new OutboundEgressPolicy.Policy(
                Set.of(
                        OutboundEgressPolicy.AddressClass.LOOPBACK),
                false,
                new OutboundEgressPolicy.Limits(
                        8,
                        0,
                        Duration.ofSeconds(1),
                        headerTimeout,
                        bodyIdleTimeout,
                        overallTimeout,
                        4,
                        8));
    }

    private static OutboundEgressPolicy.Resolver noDns() {
        return host -> {
            throw new AssertionError(
                    "literal selector HTTP target unexpectedly used DNS: "
                            + host);
        };
    }

    private static ServerSocket listen()
            throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(
                new InetSocketAddress(
                        "127.0.0.1", 0));
        return server;
    }

    private static String readRequestHead(InputStream input)
            throws IOException {
        ByteArrayOutputStream bytes =
                new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 32_768) {
            int value = input.read();
            if (value < 0) {
                throw new IOException(
                        "request closed before headers");
            }
            bytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n'
                        ? 2
                        : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n'
                        ? 4
                        : value == '\r' ? 1 : 0;
                default -> 4;
            };
            if (matched == 4) {
                return bytes.toString(
                        StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException(
                "request header budget exceeded");
    }

    private record DnsQuery(
            int id,
            int type,
            byte[] packet,
            InetSocketAddress peer) { }

    private static DatagramChannel dnsServer()
            throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        channel.bind(
                new InetSocketAddress(
                        "127.0.0.1", 0));
        channel.configureBlocking(true);
        return channel;
    }

    private static DnsQuery receiveDns(
            DatagramChannel server)
            throws IOException {
        ByteBuffer packet = ByteBuffer.allocate(512);
        InetSocketAddress peer =
                (InetSocketAddress) server.receive(packet);
        packet.flip();
        byte[] bytes = new byte[packet.remaining()];
        packet.get(bytes);
        int cursor = 12;
        while ((bytes[cursor++] & 0xff) != 0) {
            cursor += bytes[cursor - 1] & 0xff;
        }
        int type = u16(bytes, cursor);
        return new DnsQuery(
                u16(bytes, 0),
                type,
                bytes,
                peer);
    }

    private static byte[] dnsAnswer(
            DnsQuery query,
            byte[] address)
            throws IOException {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream();
        putU16(out, query.id());
        putU16(out, 0x8180);
        putU16(out, 1);
        putU16(out, address == null ? 0 : 1);
        putU16(out, 0);
        putU16(out, 0);

        int questionLength =
                questionLength(query.packet());
        out.write(
                query.packet(),
                12,
                questionLength);

        if (address != null) {
            out.write(0xc0);
            out.write(0x0c);
            putU16(out, query.type());
            putU16(out, 1);
            out.write(new byte[] {0, 0, 0, 60});
            putU16(out, address.length);
            out.write(address);
        }
        return out.toByteArray();
    }

    private static void sendDns(
            DatagramChannel server,
            DnsQuery query,
            byte[] response)
            throws IOException {
        server.send(
                ByteBuffer.wrap(response),
                query.peer());
    }

    private static int questionLength(byte[] packet)
            throws IOException {
        int cursor = 12;
        while (true) {
            if (cursor >= packet.length) {
                throw new IOException(
                        "truncated DNS query");
            }
            int length = packet[cursor++] & 0xff;
            if (length == 0) break;
            cursor += length;
            if (cursor > packet.length) {
                throw new IOException(
                        "truncated DNS label");
            }
        }
        if (cursor + 4 > packet.length) {
            throw new IOException(
                    "truncated DNS question");
        }
        return cursor + 4 - 12;
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static void putU16(
            ByteArrayOutputStream out,
            int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
