package dev.oreslang.runtime;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Physically cancellable UDP DNS resolver for exact outbound connection pins.
 *
 * <p>This resolver never calls {@link InetAddress#getAllByName(String)}.
 * Resolution uses a nonblocking {@link DatagramChannel} and a request-local
 * selector. Closing a {@link Resolution} closes the datagram channel and wakes
 * its selector, so cancellation has a concrete native handle to revoke.</p>
 *
 * <p>DNS answers are intentionally not cached. Every new physical HTTP
 * connection obtains a fresh resolution and a fresh policy generation. UDP
 * truncation fails closed in v1 rather than silently falling back to a
 * blocking resolver.</p>
 */
final class SelectorDnsResolver implements AutoCloseable {
    private static final SecureRandom QUERY_RANDOM =
            new SecureRandom();
    private static final AtomicLong NEXT_RESOLUTION_ID =
            new AtomicLong();
    private static final int DNS_HEADER_BYTES = 12;
    private static final int TYPE_A = 1;
    private static final int TYPE_CNAME = 5;
    private static final int TYPE_AAAA = 28;
    private static final int CLASS_IN = 1;
    private static final int MAX_PACKET_BYTES = 4096;
    private static final int MAX_CNAME_DEPTH = 8;

    record Config(
            List<InetSocketAddress> nameservers,
            Duration timeout) {
        Config {
            nameservers = List.copyOf(
                    Objects.requireNonNull(nameservers, "nameservers"));
            if (nameservers.isEmpty() || nameservers.size() > 8) {
                throw new IllegalArgumentException(
                        "DNS resolver requires 1..8 nameservers");
            }
            for (InetSocketAddress nameserver : nameservers) {
                Objects.requireNonNull(nameserver, "nameserver");
                if (nameserver.isUnresolved()) {
                    throw new IllegalArgumentException(
                            "DNS nameserver must be an already-resolved address");
                }
                int port = nameserver.getPort();
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException(
                            "DNS nameserver port must be 1..65535");
                }
            }
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException(
                        "DNS timeout must be positive");
            }
        }
    }

    final class Resolution implements AutoCloseable {
        private final long id =
                NEXT_RESOLUTION_ID.incrementAndGet();
        private final String host;
        private final int maxAddresses;
        private final long parentDeadlineNanos;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<DatagramChannel> activeChannel =
                new AtomicReference<>();
        private final AtomicReference<Selector> activeSelector =
                new AtomicReference<>();

        private Resolution(
                String host,
                int maxAddresses,
                long parentDeadlineNanos) {
            this.host = canonicalHost(host);
            this.parentDeadlineNanos = parentDeadlineNanos;
            if (maxAddresses < 1 || maxAddresses > 64) {
                throw new IllegalArgumentException(
                        "DNS maxAddresses must be 1..64");
            }
            this.maxAddresses = maxAddresses;
        }

        long id() {
            return id;
        }

        boolean cancelled() {
            return cancelled.get();
        }

        List<InetAddress> resolve() throws IOException {
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "DNS resolution already started or consumed");
            }
            Throwable last = null;
            long deadline = Math.min(
                    deadlineAfter(config.timeout()),
                    parentDeadlineNanos);
            try {
                for (InetSocketAddress nameserver : config.nameservers()) {
                    checkActive(deadline);
                    try {
                        List<InetAddress> addresses =
                                resolveAgainst(nameserver, deadline);
                        if (addresses.isEmpty()) {
                            last = new UnknownHostException(
                                    "DNS returned no A/AAAA records for " + host);
                            continue;
                        }
                        return boundedUnique(addresses, maxAddresses);
                    } catch (CancellationException cancelledFailure) {
                        throw cancelledFailure;
                    } catch (SocketTimeoutException timeout) {
                        last = timeout;
                    } catch (IOException malformedOrTransportFailure) {
                        last = malformedOrTransportFailure;
                    }
                }

                if (last instanceof SocketTimeoutException timeout) {
                    throw timeout;
                }
                if (last instanceof IOException io) {
                    throw io;
                }
                throw new UnknownHostException(
                        "DNS returned no usable address for " + host);
            } finally {
                finished.set(true);
                active.remove(this);
                closeActiveHandles();
            }
        }

        private List<InetAddress> resolveAgainst(
                InetSocketAddress nameserver,
                long deadline)
                throws IOException {
            int aId = nextQueryId();
            int aaaaId;
            do {
                aaaaId = nextQueryId();
            } while (aaaaId == aId);
            Map<Integer, Integer> pending = new HashMap<>();
            pending.put(aId, TYPE_A);
            pending.put(aaaaId, TYPE_AAAA);

            Map<Integer, ParsedResponse> responses = new HashMap<>();

            try (Selector selector = Selector.open();
                    DatagramChannel channel = DatagramChannel.open()) {
                channel.configureBlocking(false);
                channel.connect(nameserver);
                activeSelector.set(selector);
                activeChannel.set(channel);
                channel.register(selector, SelectionKey.OP_WRITE);

                ArrayList<ByteBuffer> writes = new ArrayList<>();
                writes.add(ByteBuffer.wrap(query(host, aId, TYPE_A)));
                writes.add(ByteBuffer.wrap(query(host, aaaaId, TYPE_AAAA)));

                ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_BYTES);
                while (!pending.isEmpty()) {
                    checkActive(deadline);
                    SelectionKey key = channel.keyFor(selector);
                    int interests = SelectionKey.OP_READ;
                    if (!writes.isEmpty()) interests |= SelectionKey.OP_WRITE;
                    key.interestOps(interests);

                    select(selector, deadline);
                    var selected = selector.selectedKeys().iterator();
                    while (selected.hasNext()) {
                        SelectionKey ready = selected.next();
                        selected.remove();
                        if (!ready.isValid()) continue;

                        if (ready.isWritable()) {
                            while (!writes.isEmpty()) {
                                ByteBuffer next = writes.getFirst();
                                int before = next.remaining();
                                int written = channel.write(next);
                                if (written == 0 && before != 0) break;
                                if (!next.hasRemaining()) writes.removeFirst();
                            }
                        }

                        if (ready.isReadable()) {
                            while (true) {
                                packet.clear();
                                int read = channel.read(packet);
                                if (read <= 0) break;
                                packet.flip();
                                byte[] bytes = new byte[packet.remaining()];
                                packet.get(bytes);
                                if (bytes.length < DNS_HEADER_BYTES) continue;
                                int responseId = u16(bytes, 0);
                                Integer expectedType = pending.get(responseId);
                                if (expectedType == null) continue;

                                ParsedResponse parsed =
                                        parseResponse(
                                                bytes,
                                                responseId,
                                                host,
                                                expectedType);
                                responses.put(expectedType, parsed);
                                pending.remove(responseId);
                            }
                        }
                    }
                }
            } catch (java.nio.channels.ClosedChannelException closedChannel) {
                if (cancelled.get() || SelectorDnsResolver.this.closed.get()) {
                    throw new CancellationException(
                            "DNS resolution cancelled");
                }
                throw closedChannel;
            } catch (ClosedSelectorException closedSelector) {
                if (cancelled.get() || SelectorDnsResolver.this.closed.get()) {
                    throw new CancellationException(
                            "DNS resolution cancelled");
                }
                throw closedSelector;
            } finally {
                activeChannel.set(null);
                activeSelector.set(null);
            }

            ArrayList<InetAddress> addresses = new ArrayList<>();
            ParsedResponse a = responses.get(TYPE_A);
            ParsedResponse aaaa = responses.get(TYPE_AAAA);
            if ((a != null && a.nxdomain())
                    || (aaaa != null && aaaa.nxdomain())) {
                return List.of();
            }
            if (a != null) addresses.addAll(a.addresses());
            if (aaaa != null) addresses.addAll(aaaa.addresses());
            return addresses;
        }

        private void checkActive(long deadline)
                throws SocketTimeoutException {
            if (cancelled.get() || SelectorDnsResolver.this.closed.get()) {
                throw new CancellationException(
                        "DNS resolution cancelled");
            }
            if (System.nanoTime() >= deadline) {
                throw new SocketTimeoutException(
                        "DNS resolution deadline expired for " + host);
            }
        }

        @Override
        public void close() {
            if (!cancelled.compareAndSet(false, true)) return;
            closeActiveHandles();
            if (!started.get()) {
                finished.set(true);
                active.remove(this);
            }
        }

        private void closeActiveHandles() {
            DatagramChannel channel = activeChannel.getAndSet(null);
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Physical resolution still retires in resolve() finally.
                }
            }
            Selector selector = activeSelector.getAndSet(null);
            if (selector != null) {
                selector.wakeup();
                try {
                    selector.close();
                } catch (IOException ignored) {
                    // resolve() owns the authoritative terminal receipt.
                }
            }
        }
    }

    private record NameRead(String name, int nextOffset) { }

    private record ParsedResponse(
            List<InetAddress> addresses,
            boolean nxdomain) { }

    private final Config config;
    private final Set<Resolution> active =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    SelectorDnsResolver(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    Resolution begin(String asciiHost, int maxAddresses) {
        return begin(
                asciiHost,
                maxAddresses,
                Long.MAX_VALUE);
    }

    Resolution begin(
            String asciiHost,
            int maxAddresses,
            long parentDeadlineNanos) {
        if (closed.get()) {
            throw new IllegalStateException(
                    "DNS resolver is closed");
        }
        if (parentDeadlineNanos <= System.nanoTime()) {
            throw new IllegalArgumentException(
                    "DNS parent deadline already expired");
        }
        Resolution resolution =
                new Resolution(
                        asciiHost,
                        maxAddresses,
                        parentDeadlineNanos);
        active.add(resolution);
        if (closed.get()) {
            resolution.close();
            active.remove(resolution);
            throw new IllegalStateException(
                    "DNS resolver closed during admission");
        }
        return resolution;
    }

    int activeCount() {
        return active.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Resolution resolution : List.copyOf(active)) {
            resolution.close();
        }
    }

    private static ParsedResponse parseResponse(
            byte[] packet,
            int expectedId,
            String queryHost,
            int queryType)
            throws IOException {
        if (packet.length < DNS_HEADER_BYTES
                || u16(packet, 0) != expectedId) {
            throw new IOException("DNS response transaction mismatch");
        }

        int flags = u16(packet, 2);
        if ((flags & 0x8000) == 0) {
            throw new IOException("DNS packet is not a response");
        }
        if ((flags & 0x0200) != 0) {
            throw new IOException(
                    "truncated UDP DNS response is unsupported");
        }
        int rcode = flags & 0x000f;
        if (rcode == 3) {
            return new ParsedResponse(List.of(), true);
        }
        if (rcode != 0) {
            throw new IOException(
                    "DNS response failure rcode=" + rcode);
        }

        int qd = u16(packet, 4);
        int an = u16(packet, 6);
        if (qd != 1) {
            throw new IOException(
                    "DNS response must contain exactly one question");
        }
        if (an > 128) {
            throw new IOException(
                    "DNS answer count exceeds parser budget");
        }
        int offset = DNS_HEADER_BYTES;

        for (int i = 0; i < qd; i++) {
            NameRead question = readName(packet, offset);
            offset = question.nextOffset();
            require(packet, offset, 4);
            int type = u16(packet, offset);
            int clazz = u16(packet, offset + 2);
            offset += 4;
            if (i == 0
                    && (!question.name().equals(queryHost)
                            || type != queryType
                            || clazz != CLASS_IN)) {
                throw new IOException(
                        "DNS response question does not match request");
            }
        }

        Map<String, String> cnames = new HashMap<>();
        Map<String, List<InetAddress>> addresses =
                new LinkedHashMap<>();

        for (int i = 0; i < an; i++) {
            NameRead ownerRead = readName(packet, offset);
            String owner = ownerRead.name();
            offset = ownerRead.nextOffset();
            require(packet, offset, 10);
            int type = u16(packet, offset);
            int clazz = u16(packet, offset + 2);
            int rdLength = u16(packet, offset + 8);
            offset += 10;
            require(packet, offset, rdLength);

            if (clazz == CLASS_IN) {
                if (type == queryType
                        && type == TYPE_A
                        && rdLength == 4) {
                    addresses
                            .computeIfAbsent(
                                    owner,
                                    ignored -> new ArrayList<>())
                            .add(address(
                                    packet,
                                    offset,
                                    4));
                } else if (type == queryType
                        && type == TYPE_AAAA
                        && rdLength == 16) {
                    addresses
                            .computeIfAbsent(
                                    owner,
                                    ignored -> new ArrayList<>())
                            .add(address(
                                    packet,
                                    offset,
                                    16));
                } else if (type == TYPE_CNAME) {
                    NameRead cname =
                            readName(packet, offset);
                    if (cname.nextOffset() > offset + rdLength) {
                        throw new IOException(
                                "DNS CNAME exceeds RDATA boundary");
                    }
                    cnames.put(owner, cname.name());
                }
            }
            offset += rdLength;
        }

        String owner = queryHost;
        Set<String> seen = new HashSet<>();
        for (int depth = 0; depth <= MAX_CNAME_DEPTH; depth++) {
            if (!seen.add(owner)) {
                throw new IOException(
                        "DNS CNAME loop detected");
            }
            List<InetAddress> direct = addresses.get(owner);
            if (direct != null && !direct.isEmpty()) {
                return new ParsedResponse(
                        List.copyOf(direct),
                        false);
            }
            String next = cnames.get(owner);
            if (next == null) break;
            owner = next;
        }
        return new ParsedResponse(List.of(), false);
    }

    private static InetAddress address(
            byte[] packet,
            int offset,
            int length)
            throws IOException {
        byte[] raw = new byte[length];
        System.arraycopy(packet, offset, raw, 0, length);
        try {
            return InetAddress.getByAddress(raw);
        } catch (UnknownHostException impossible) {
            throw new IOException(
                    "invalid DNS address record",
                    impossible);
        }
    }

    private static byte[] query(
            String host,
            int id,
            int type) {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        putU16(buffer, id);
        putU16(buffer, 0x0100);
        putU16(buffer, 1);
        putU16(buffer, 0);
        putU16(buffer, 0);
        putU16(buffer, 0);
        for (String label : host.split("\\.")) {
            byte[] bytes =
                    label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length < 1 || bytes.length > 63) {
                throw new IllegalArgumentException(
                        "invalid DNS label length");
            }
            buffer.put((byte) bytes.length);
            buffer.put(bytes);
        }
        buffer.put((byte) 0);
        putU16(buffer, type);
        putU16(buffer, CLASS_IN);
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    private static NameRead readName(
            byte[] packet,
            int offset)
            throws IOException {
        StringBuilder name = new StringBuilder();
        int cursor = offset;
        int next = -1;
        int jumps = 0;
        Set<Integer> visited = new HashSet<>();

        while (true) {
            require(packet, cursor, 1);
            int length = packet[cursor] & 0xff;
            if ((length & 0xc0) == 0xc0) {
                require(packet, cursor, 2);
                int pointer =
                        ((length & 0x3f) << 8)
                                | (packet[cursor + 1] & 0xff);
                if (pointer >= packet.length
                        || !visited.add(pointer)
                        || ++jumps > 32) {
                    throw new IOException(
                            "invalid DNS compression pointer");
                }
                if (next < 0) next = cursor + 2;
                cursor = pointer;
                continue;
            }
            if ((length & 0xc0) != 0) {
                throw new IOException(
                        "unsupported DNS label encoding");
            }
            cursor++;
            if (length == 0) {
                if (next < 0) next = cursor;
                break;
            }
            if (length > 63) {
                throw new IOException(
                        "invalid DNS label length");
            }
            require(packet, cursor, length);
            if (!name.isEmpty()) name.append('.');
            for (int i = 0; i < length; i++) {
                int ch = packet[cursor + i] & 0xff;
                if (!(ch >= 'A' && ch <= 'Z')
                        && !(ch >= 'a' && ch <= 'z')
                        && !(ch >= '0' && ch <= '9')
                        && ch != '-') {
                    throw new IOException(
                            "invalid character in DNS name");
                }
                name.append(
                        Character.toLowerCase((char) ch));
            }
            cursor += length;
            if (name.length() > 253) {
                throw new IOException(
                        "DNS name exceeds 253 bytes");
            }
        }

        if (name.isEmpty()) {
            throw new IOException(
                    "root DNS name is not valid for HTTP origin resolution");
        }
        return new NameRead(name.toString(), next);
    }

    private static List<InetAddress> boundedUnique(
            List<InetAddress> addresses,
            int maximum) {
        LinkedHashMap<String, InetAddress> unique =
                new LinkedHashMap<>();
        for (InetAddress address : addresses) {
            Objects.requireNonNull(address, "DNS address");
            String key = java.util.Arrays.toString(
                    address.getAddress());
            unique.putIfAbsent(key, address);
            if (unique.size() > maximum) {
                throw new IllegalArgumentException(
                        "DNS result exceeds address budget "
                                + maximum);
            }
        }
        return List.copyOf(unique.values());
    }

    private static String canonicalHost(String host) {
        Objects.requireNonNull(host, "host");
        String canonical = host.toLowerCase(Locale.ROOT);
        if (canonical.endsWith(".")) {
            canonical = canonical.substring(
                    0,
                    canonical.length() - 1);
        }
        if (canonical.isEmpty()
                || canonical.length() > 253) {
            throw new IllegalArgumentException(
                    "invalid DNS host");
        }
        for (String label : canonical.split("\\.", -1)) {
            if (label.isEmpty()
                    || label.length() > 63
                    || label.startsWith("-")
                    || label.endsWith("-")
                    || !label.matches("[0-9a-z-]+")) {
                throw new IllegalArgumentException(
                        "DNS resolver requires canonical ASCII host names");
            }
        }
        return canonical;
    }

    private static int nextQueryId() {
        return QUERY_RANDOM.nextInt(1 << 16);
    }

    private static void select(
            Selector selector,
            long deadline)
            throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException(
                    "DNS resolution deadline expired");
        }
        long millis = Math.max(
                1L,
                Math.min(
                        1000L,
                        (remaining + 999_999L) / 1_000_000L));
        int ready = selector.select(millis);
        if (ready == 0 && System.nanoTime() >= deadline) {
            throw new SocketTimeoutException(
                    "DNS resolution deadline expired");
        }
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        return Long.MAX_VALUE - now < nanos
                ? Long.MAX_VALUE
                : now + nanos;
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static void putU16(
            ByteBuffer buffer,
            int value) {
        buffer.put((byte) ((value >>> 8) & 0xff));
        buffer.put((byte) (value & 0xff));
    }

    private static void require(
            byte[] packet,
            int offset,
            int length)
            throws IOException {
        if (offset < 0
                || length < 0
                || offset > packet.length - length) {
            throw new IOException(
                    "truncated DNS response");
        }
    }
}
