package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class SelectorDnsResolverTest {
    @Test
    void resolvesAAndAaaaWithoutSystemResolverAndFeedsPolicy()
            throws Exception {
        try (DatagramChannel dns = dnsServer()) {
            AtomicReference<Throwable> serverFailure =
                    new AtomicReference<>();
            Thread server = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        Query request = receiveQuery(dns);
                        byte[] address = request.type() == 1
                                ? new byte[] {127, 0, 0, 1}
                                : InetAddress.getByName("::1").getAddress();
                        send(
                                dns,
                                request,
                                answer(
                                        request,
                                        request.questionName(),
                                        request.type(),
                                        address,
                                        false));
                    }
                } catch (Throwable failure) {
                    serverFailure.set(failure);
                }
            });

            var config = new SelectorDnsResolver.Config(
                    List.of((InetSocketAddress) dns.getLocalAddress()),
                    Duration.ofSeconds(2));
            try (SelectorDnsResolver resolver =
                    new SelectorDnsResolver(config)) {
                var resolution =
                        resolver.begin("origin.example", 8);
                List<InetAddress> addresses =
                        resolution.resolve();

                assertEquals(2, addresses.size());
                assertEquals(0, resolver.activeCount());

                var target =
                        OutboundEgressPolicy.authorizeResolved(
                                URI.create(
                                        "http://origin.example:8080/path"),
                                OutboundEgressPolicy.Purpose.REVERSE_PROXY,
                                OutboundEgressPolicy.Policy.loopbackOnly(),
                                addresses);
                assertEquals("origin.example", target.host());
                assertEquals(2, target.addresses().size());
                assertTrue(target.resolutionGeneration() > 0);
            }

            server.join();
            if (serverFailure.get() != null) {
                throw new AssertionError(serverFailure.get());
            }
        }
    }

    @Test
    void cancellationClosesPhysicalDnsHandleAndWaitsForResolverExit()
            throws Exception {
        try (DatagramChannel dns = dnsServer()) {
            CountDownLatch received = new CountDownLatch(1);
            Thread server = Thread.ofVirtual().start(() -> {
                try {
                    receiveQuery(dns);
                    received.countDown();
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Test server can be closed after client cancellation.
                }
            });

            var config = new SelectorDnsResolver.Config(
                    List.of((InetSocketAddress) dns.getLocalAddress()),
                    Duration.ofSeconds(5));
            try (SelectorDnsResolver resolver =
                    new SelectorDnsResolver(config)) {
                var resolution =
                        resolver.begin("origin.example", 8);
                AtomicReference<Throwable> failure =
                        new AtomicReference<>();
                Thread worker = Thread.ofVirtual().start(() -> {
                    try {
                        resolution.resolve();
                    } catch (Throwable caught) {
                        failure.set(caught);
                    }
                });

                assertTrue(received.await(2, TimeUnit.SECONDS));
                assertEquals(1, resolver.activeCount());
                resolution.close();
                worker.join();

                assertTrue(
                        failure.get() instanceof CancellationException,
                        String.valueOf(failure.get()));
                assertEquals(
                        0,
                        resolver.activeCount(),
                        "physical resolver receipt retires only after resolve() exits");
            }
            server.interrupt();
            server.join();
        }
    }

    @Test
    void timeoutIsBoundedAndRetiresResolution()
            throws Exception {
        try (DatagramChannel dns = dnsServer()) {
            CountDownLatch received = new CountDownLatch(1);
            Thread server = Thread.ofVirtual().start(() -> {
                try {
                    receiveQuery(dns);
                    received.countDown();
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                }
            });

            var config = new SelectorDnsResolver.Config(
                    List.of((InetSocketAddress) dns.getLocalAddress()),
                    Duration.ofMillis(75));
            try (SelectorDnsResolver resolver =
                    new SelectorDnsResolver(config)) {
                var resolution =
                        resolver.begin("origin.example", 8);
                assertThrows(
                        SocketTimeoutException.class,
                        resolution::resolve);
                assertEquals(0, resolver.activeCount());
            }
            server.interrupt();
            server.join();
        }
    }

    @Test
    void unrelatedAnswerOwnerCannotPoisonResolution()
            throws Exception {
        try (DatagramChannel dns = dnsServer()) {
            Thread server = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        Query request = receiveQuery(dns);
                        byte[] address = request.type() == 1
                                ? new byte[] {127, 0, 0, 1}
                                : InetAddress.getByName("::1").getAddress();
                        send(
                                dns,
                                request,
                                answer(
                                        request,
                                        "attacker.example",
                                        request.type(),
                                        address,
                                        false));
                    }
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });

            var config = new SelectorDnsResolver.Config(
                    List.of((InetSocketAddress) dns.getLocalAddress()),
                    Duration.ofSeconds(2));
            try (SelectorDnsResolver resolver =
                    new SelectorDnsResolver(config)) {
                var resolution =
                        resolver.begin("origin.example", 8);
                assertThrows(
                        UnknownHostException.class,
                        resolution::resolve);
            }
            server.join();
        }
    }

    @Test
    void truncatedUdpResponseFailsClosedWithoutBlockingFallback()
            throws Exception {
        try (DatagramChannel dns = dnsServer()) {
            Thread server = Thread.ofVirtual().start(() -> {
                try {
                    Query request = receiveQuery(dns);
                    send(
                            dns,
                            request,
                            answer(
                                    request,
                                    request.questionName(),
                                    request.type(),
                                    new byte[] {127, 0, 0, 1},
                                    true));
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });

            var config = new SelectorDnsResolver.Config(
                    List.of((InetSocketAddress) dns.getLocalAddress()),
                    Duration.ofMillis(300));
            try (SelectorDnsResolver resolver =
                    new SelectorDnsResolver(config)) {
                var resolution =
                        resolver.begin("origin.example", 8);
                IOException failure = assertThrows(
                        IOException.class,
                        resolution::resolve);
                assertTrue(
                        failure.getMessage().contains("truncated"),
                        failure.toString());
            }
            server.join();
        }
    }

    @Test
    void nxdomainOnEitherFamilyFailsClosedEvenIfSiblingCarriesAnAddress()
            throws Exception {
        try (DatagramChannel dns = dnsServer()) {
            Thread server = Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 2; i++) {
                        Query request = receiveQuery(dns);
                        byte[] response =
                                request.type() == 1
                                        ? nxdomain(request)
                                        : answer(
                                                request,
                                                request.questionName(),
                                                request.type(),
                                                InetAddress.getByName("::1")
                                                        .getAddress(),
                                                false);
                        send(dns, request, response);
                    }
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });

            var config = new SelectorDnsResolver.Config(
                    List.of((InetSocketAddress) dns.getLocalAddress()),
                    Duration.ofSeconds(2));
            try (SelectorDnsResolver resolver =
                    new SelectorDnsResolver(config)) {
                var resolution =
                        resolver.begin("origin.example", 8);
                assertThrows(
                        UnknownHostException.class,
                        resolution::resolve);
            }
            server.join();
        }
    }

    @Test
    void externallyResolvedLiteralCannotBeRebound()
            throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> OutboundEgressPolicy.authorizeResolved(
                        URI.create("http://127.0.0.1:8080/"),
                        OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                        OutboundEgressPolicy.Policy.loopbackOnly(),
                        List.of(InetAddress.getByName("127.0.0.2"))));
    }

    private record Query(
            int id,
            int type,
            String questionName,
            byte[] packet,
            InetSocketAddress peer) { }

    private static DatagramChannel dnsServer()
            throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        channel.bind(new InetSocketAddress("127.0.0.1", 0));
        channel.configureBlocking(true);
        return channel;
    }

    private static Query receiveQuery(DatagramChannel server)
            throws IOException {
        ByteBuffer packet = ByteBuffer.allocate(512);
        InetSocketAddress peer =
                (InetSocketAddress) server.receive(packet);
        packet.flip();
        byte[] bytes = new byte[packet.remaining()];
        packet.get(bytes);
        if (bytes.length < 17) {
            throw new IOException("short DNS query");
        }
        int id = u16(bytes, 0);
        int cursor = 12;
        StringBuilder name = new StringBuilder();
        while (true) {
            int length = bytes[cursor++] & 0xff;
            if (length == 0) break;
            if (!name.isEmpty()) name.append('.');
            name.append(
                    new String(
                            bytes,
                            cursor,
                            length,
                            java.nio.charset.StandardCharsets.US_ASCII));
            cursor += length;
        }
        int type = u16(bytes, cursor);
        return new Query(
                id,
                type,
                name.toString().toLowerCase(),
                bytes,
                peer);
    }

    private static void send(
            DatagramChannel server,
            Query request,
            byte[] response)
            throws IOException {
        server.send(
                ByteBuffer.wrap(response),
                request.peer());
    }

    private static byte[] answer(
            Query request,
            String owner,
            int type,
            byte[] address,
            boolean truncated)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        putU16(out, request.id());
        putU16(out, truncated ? 0x8380 : 0x8180);
        putU16(out, 1);
        putU16(out, 1);
        putU16(out, 0);
        putU16(out, 0);

        int questionLength = questionLength(request.packet());
        out.write(
                request.packet(),
                12,
                questionLength);

        writeName(out, owner);
        putU16(out, type);
        putU16(out, 1);
        out.write(new byte[] {0, 0, 0, 60});
        putU16(out, address.length);
        out.write(address);
        return out.toByteArray();
    }

    private static byte[] nxdomain(Query request)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        putU16(out, request.id());
        putU16(out, 0x8183);
        putU16(out, 1);
        putU16(out, 0);
        putU16(out, 0);
        putU16(out, 0);
        int questionLength = questionLength(request.packet());
        out.write(
                request.packet(),
                12,
                questionLength);
        return out.toByteArray();
    }

    private static int questionLength(byte[] packet)
            throws IOException {
        int cursor = 12;
        while (true) {
            if (cursor >= packet.length) {
                throw new IOException("truncated query name");
            }
            int length = packet[cursor++] & 0xff;
            if (length == 0) break;
            cursor += length;
            if (cursor > packet.length) {
                throw new IOException("truncated query label");
            }
        }
        if (cursor + 4 > packet.length) {
            throw new IOException("truncated DNS question");
        }
        return cursor + 4 - 12;
    }

    private static void writeName(
            ByteArrayOutputStream out,
            String name) {
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.writeBytes(bytes);
        }
        out.write(0);
    }

    private static int u16(byte[] packet, int offset) {
        return ((packet[offset] & 0xff) << 8)
                | (packet[offset + 1] & 0xff);
    }

    private static void putU16(
            ByteArrayOutputStream out,
            int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}
