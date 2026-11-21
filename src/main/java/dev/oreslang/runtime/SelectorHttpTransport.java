package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Selector-owned, nonblocking HTTP/1.1 client transport.
 *
 * <p>V1 is plaintext HTTP only. Connect, request-head write, response-head
 * read and response-body socket reads all stay on nonblocking SocketChannel
 * operations with selector deadlines. HTTPS remains on the pinned TLS proof
 * until an SSLEngine state machine uses these same phases.</p>
 */
final class SelectorHttpTransport implements AutoCloseable {
    enum State {
        RESOLVING,
        CONNECTING,
        WRITING_HEADERS,
        READING_HEADERS,
        STREAMING_BODY,
        RETIRING,
        RETIRED
    }

    private enum Framing {
        NONE,
        CONTENT_LENGTH,
        CHUNKED,
        CLOSE_DELIMITED
    }

    private static final Pattern HEADER_NAME =
            Pattern.compile("[!#$%&'*+.^_\\x60|~0-9A-Za-z-]+");
    private static final Set<String> RESPONSE_HOP = Set.of(
            "connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "te", "trailer", "upgrade",
            "content-length", "proxy-authenticate",
            "proxy-authorization", "host", "http2-settings");
    private static final int MAX_HEADER_FIELDS = 100;
    private static final int MAX_HEADER_BYTES = 32_768;
    private static final int MAX_LINE_BYTES = 8_192;
    private static final int MAX_INFORMATIONAL = 8;
    private static final int MAX_CHUNK_LINE_BYTES = 128;

    private record Head(
            int status,
            List<Map.Entry<String, String>> headers,
            Framing framing,
            long contentLength,
            byte[] initialBody) { }

    private record HeaderBlock(
            byte[] head,
            byte[] leftover) { }

    private final OutboundEgressPolicy.Purpose purpose;
    private final OutboundEgressPolicy.Policy policy;
    private final OutboundEgressPolicy.Resolver resolver;
    private final SelectorDnsResolver selectorResolver;
    private final PinnedHttpTransport.ConnectionBudget budget;
    private final long maxResponseBytes;
    private final Set<Transaction> active =
            ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final class Transaction implements AutoCloseable {
        private final PinnedHttpTransport.ConnectionLease lease;
        private final long overallDeadlineNanos;
        private final Thread physicalOwner = Thread.currentThread();
        private final AtomicReference<State> state =
                new AtomicReference<>(State.RESOLVING);
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private volatile Selector selector;
        private volatile SocketChannel channel;
        private volatile SelectionKey key;
        private volatile SelectorDnsResolver.Resolution resolution;

        private Transaction(
                PinnedHttpTransport.ConnectionLease lease,
                long overallDeadlineNanos) {
            this.lease = lease;
            this.overallDeadlineNanos = overallDeadlineNanos;
        }

        State state() {
            return state.get();
        }

        long deadlineNanos() {
            return overallDeadlineNanos;
        }

        void phase(State next) throws IOException {
            State current = state.get();
            if (current == State.RETIRING
                    || current == State.RETIRED
                    || closed.get()) {
                throw new IOException(
                        "selector HTTP transaction cancelled");
            }
            state.set(next);
        }

        OutboundEgressPolicy.ResolvedTarget resolve(
                URI canonical)
                throws IOException {
            phase(State.RESOLVING);
            if (isIpLiteral(canonical.getHost())) {
                return OutboundEgressPolicy.authorizeResolved(
                        canonical,
                        purpose,
                        policy,
                        List.of());
            }
            if (selectorResolver != null) {
                SelectorDnsResolver.Resolution current =
                        selectorResolver.begin(
                                canonical.getHost(),
                                policy.limits().maxDnsAddresses(),
                                overallDeadlineNanos);
                resolution = current;
                try {
                    List<java.net.InetAddress> addresses =
                            current.resolve();
                    checkNotCancelled("DNS resolution");
                    return OutboundEgressPolicy.authorizeResolved(
                            canonical,
                            purpose,
                            policy,
                            addresses);
                } finally {
                    resolution = null;
                }
            }
            return OutboundEgressPolicy.resolveForConnection(
                    canonical,
                    purpose,
                    policy,
                    resolver);
        }

        void connect(OutboundEgressPolicy.ResolvedTarget target)
                throws IOException {
            phase(State.CONNECTING);
            IOException last = null;
            for (int attempt = 0;
                    attempt < target.addresses().size();
                    attempt++) {
                checkNotCancelled("connect");
                closeChannelOnly();

                Selector localSelector = Selector.open();
                SocketChannel localChannel = SocketChannel.open();
                localChannel.configureBlocking(false);
                selector = localSelector;
                channel = localChannel;
                key = localChannel.register(
                        localSelector, SelectionKey.OP_CONNECT);

                try {
                    InetSocketAddress address =
                            target.connectAddress(attempt);
                    if (localChannel.connect(address)
                            || finishConnect(
                                    localChannel,
                                    localSelector,
                                    phaseDeadline(
                                            policy.limits()
                                                    .connectTimeout()))) {
                        return;
                    }
                } catch (IOException failure) {
                    last = failure;
                    closeChannelOnly();
                }
            }
            if (last != null) throw last;
            throw new IOException(
                    "selector HTTP had no authorized connect address");
        }

        private boolean finishConnect(
                SocketChannel localChannel,
                Selector localSelector,
                long deadline)
                throws IOException {
            while (true) {
                checkActive(deadline, "connect");
                if (localChannel.finishConnect()) return true;
                await(
                        localSelector,
                        SelectionKey.OP_CONNECT,
                        deadline,
                        "connect");
            }
        }

        void write(ByteBuffer bytes) throws IOException {
            phase(State.WRITING_HEADERS);
            long deadline =
                    phaseDeadline(policy.limits().headerTimeout());
            while (bytes.hasRemaining()) {
                checkActive(deadline, "request write");
                int written = channel.write(bytes);
                if (written == 0) {
                    await(
                            selector,
                            SelectionKey.OP_WRITE,
                            deadline,
                            "request write");
                }
            }
        }

        Head readHead(String method) throws IOException {
            phase(State.READING_HEADERS);
            long deadline =
                    phaseDeadline(policy.limits().headerTimeout());
            byte[] prefetched = new byte[0];

            for (int informational = 0;
                    informational <= MAX_INFORMATIONAL;
                    informational++) {
                HeaderBlock block =
                        readHeaderBlock(
                                prefetched,
                                deadline);
                Head head =
                        parseHead(
                                block.head(),
                                block.leftover(),
                                method,
                                maxResponseBytes);
                if (head.status() >= 200) return head;
                if (head.status() == 101) {
                    throw new IOException(
                            "HTTP protocol upgrade responses are unsupported");
                }
                prefetched = head.initialBody();
            }
            throw new IOException(
                    "too many informational HTTP responses");
        }

        private HeaderBlock readHeaderBlock(
                byte[] prefetched,
                long deadline)
                throws IOException {
            ByteArrayOutputStream head =
                    new ByteArrayOutputStream(1024);
            ByteArrayOutputStream leftover =
                    new ByteArrayOutputStream();
            int matched = 0;
            boolean complete = false;

            for (byte value : prefetched) {
                if (complete) {
                    leftover.write(value);
                } else {
                    if (head.size() >= MAX_HEADER_BYTES) {
                        throw new IOException(
                                "HTTP response header byte budget exceeded");
                    }
                    head.write(value);
                    matched =
                            headerTerminatorMatch(
                                    matched,
                                    value);
                    if (matched == 4) complete = true;
                }
            }

            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (!complete) {
                checkActive(deadline, "response headers");
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new EOFException(
                            "upstream closed before HTTP response headers");
                }
                if (read == 0) {
                    await(
                            selector,
                            SelectionKey.OP_READ,
                            deadline,
                            "response headers");
                    continue;
                }

                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte value = buffer.get();
                    if (complete) {
                        leftover.write(value);
                    } else {
                        if (head.size() >= MAX_HEADER_BYTES) {
                            throw new IOException(
                                    "HTTP response header byte budget exceeded");
                        }
                        head.write(value);
                        matched =
                                headerTerminatorMatch(
                                        matched,
                                        value);
                        if (matched == 4) complete = true;
                    }
                }
                buffer.clear();
            }

            return new HeaderBlock(
                    head.toByteArray(),
                    leftover.toByteArray());
        }

        int read(ByteBuffer target, Duration idleTimeout)
                throws IOException {
            long deadline = phaseDeadline(idleTimeout);
            while (true) {
                checkActive(deadline, "response body");
                int read = channel.read(target);
                if (read != 0) return read;
                await(
                        selector,
                        SelectionKey.OP_READ,
                        deadline,
                        "response body");
            }
        }

        private long phaseDeadline(Duration duration) {
            return Math.min(
                    overallDeadlineNanos,
                    deadlineAfter(duration));
        }

        private void checkNotCancelled(String phase)
                throws IOException {
            if (closed.get()
                    || cancelRequested.get()
                    || state.get() == State.RETIRING
                    || state.get() == State.RETIRED) {
                throw new CancellationException(
                        "selector HTTP " + phase + " cancelled");
            }
        }

        private void checkActive(long deadline, String phase)
                throws IOException {
            checkNotCancelled(phase);
            if (System.nanoTime() >= deadline) {
                throw new SocketTimeoutException(
                        "selector HTTP "
                                + phase
                                + " deadline expired");
            }
        }

        private void await(
                Selector localSelector,
                int interest,
                long deadline,
                String phase)
                throws IOException {
            checkActive(deadline, phase);
            SelectionKey localKey = key;
            if (localKey == null || !localKey.isValid()) {
                throw new ClosedChannelException();
            }
            localKey.interestOps(interest);
            long remaining = deadline - System.nanoTime();
            long millis = Math.max(
                    1L,
                    Math.min(
                            1000L,
                            (remaining + 999_999L)
                                    / 1_000_000L));
            final int ready;
            try {
                ready = localSelector.select(millis);
            } catch (ClosedSelectorException closedSelector) {
                throw new CancellationException(
                        "selector HTTP " + phase + " cancelled");
            }
            if (ready == 0) {
                checkActive(deadline, phase);
                return;
            }
            var selected =
                    localSelector.selectedKeys().iterator();
            while (selected.hasNext()) {
                selected.next();
                selected.remove();
            }
        }

        private void closeChannelOnly() {
            SelectorDnsResolver.Resolution currentResolution = resolution;
            if (currentResolution != null) currentResolution.close();

            SelectionKey localKey = key;
            key = null;
            if (localKey != null) localKey.cancel();

            SocketChannel localChannel = channel;
            channel = null;
            if (localChannel != null) {
                try {
                    localChannel.close();
                } catch (IOException ignored) {
                }
            }

            Selector localSelector = selector;
            selector = null;
            if (localSelector != null) {
                localSelector.wakeup();
                try {
                    localSelector.close();
                } catch (IOException ignored) {
                }
            }
        }

        void requestCancel() {
            if (state.get() == State.RETIRED) return;
            cancelRequested.set(true);
            state.set(State.RETIRING);
            closeChannelOnly();
        }

        void retire() {
            if (!retired.compareAndSet(false, true)) return;
            requestCancel();
            lease.release();
            active.remove(this);
            state.set(State.RETIRED);
        }

        @Override
        public void close() {
            if (Thread.currentThread() == physicalOwner) {
                retire();
            } else {
                // CONTROL/foreign threads may revoke native handles but cannot
                // publish physical retirement or reuse connection capacity.
                requestCancel();
            }
        }
    }

    static final class Response implements AutoCloseable {
        private final SelectorHttpTransport owner;
        private final Transaction transaction;
        private final int status;
        private final List<Map.Entry<String, String>> headers;
        private final Framing framing;
        private final long contentLength;
        private final Duration bodyIdleTimeout;
        private final long maxResponseBytes;
        private ByteBuffer buffered;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Response(
                SelectorHttpTransport owner,
                Transaction transaction,
                Head head) {
            this.owner = owner;
            this.transaction = transaction;
            this.status = head.status();
            this.headers = head.headers();
            this.framing = head.framing();
            this.contentLength = head.contentLength();
            this.bodyIdleTimeout =
                    owner.policy.limits().bodyIdleTimeout();
            this.maxResponseBytes = owner.maxResponseBytes;
            this.buffered = ByteBuffer.wrap(head.initialBody());
        }

        int status() {
            return status;
        }

        List<Map.Entry<String, String>> headers() {
            return headers;
        }

        List<String> headerValues(String name) {
            Objects.requireNonNull(name, "name");
            ArrayList<String> values = new ArrayList<>();
            for (Map.Entry<String, String> field : headers) {
                if (field.getKey().equalsIgnoreCase(name)) {
                    values.add(field.getValue());
                }
            }
            return List.copyOf(values);
        }

        State state() {
            return transaction.state();
        }

        long deadlineNanos() {
            return transaction.deadlineNanos();
        }

        long streamBody(
                OutputStream target,
                BooleanSupplier cancelled)
                throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(cancelled, "cancelled");
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "selector HTTP response body already consumed");
            }

            long total = 0;
            try {
                transaction.phase(State.STREAMING_BODY);
                switch (framing) {
                    case NONE -> {
                        return 0;
                    }
                    case CONTENT_LENGTH -> {
                        long remaining = contentLength;
                        byte[] bytes = new byte[8192];
                        while (remaining != 0) {
                            checkCancelled(cancelled);
                            int count =
                                    (int) Math.min(
                                            bytes.length,
                                            remaining);
                            int read =
                                    readSome(bytes, 0, count);
                            if (read < 0) {
                                throw new EOFException(
                                        "upstream closed before Content-Length bytes arrived");
                            }
                            total = addBodyBytes(total, read);
                            target.write(bytes, 0, read);
                            remaining -= read;
                        }
                        return total;
                    }
                    case CLOSE_DELIMITED -> {
                        byte[] bytes = new byte[8192];
                        while (true) {
                            checkCancelled(cancelled);
                            int read =
                                    readSome(
                                            bytes,
                                            0,
                                            bytes.length);
                            if (read < 0) return total;
                            total = addBodyBytes(total, read);
                            target.write(bytes, 0, read);
                        }
                    }
                    case CHUNKED -> {
                        while (true) {
                            checkCancelled(cancelled);
                            long chunk = readChunkSize();
                            if (chunk == 0) {
                                readTrailers();
                                return total;
                            }
                            long remaining = chunk;
                            byte[] bytes = new byte[8192];
                            while (remaining != 0) {
                                int count =
                                        (int) Math.min(
                                                bytes.length,
                                                remaining);
                                int read =
                                        readSome(
                                                bytes,
                                                0,
                                                count);
                                if (read < 0) {
                                    throw new EOFException(
                                            "upstream closed inside chunked body");
                                }
                                total = addBodyBytes(total, read);
                                target.write(bytes, 0, read);
                                remaining -= read;
                            }
                            expectCrlf();
                        }
                    }
                }
                throw new AssertionError("unknown framing");
            } finally {
                close();
            }
        }

        private void checkCancelled(
                BooleanSupplier cancelled)
                throws IOException {
            if (closed.get()
                    || owner.closed.get()
                    || cancelled.getAsBoolean()) {
                throw new IOException(
                        "selector HTTP response cancelled");
            }
            if (System.nanoTime()
                    >= transaction.deadlineNanos()) {
                throw new SocketTimeoutException(
                        "selector HTTP overall deadline expired");
            }
        }

        private int readSome(
                byte[] target,
                int offset,
                int length)
                throws IOException {
            if (buffered.hasRemaining()) {
                int count =
                        Math.min(
                                length,
                                buffered.remaining());
                buffered.get(target, offset, count);
                return count;
            }
            return transaction.read(
                    ByteBuffer.wrap(
                            target,
                            offset,
                            length),
                    bodyIdleTimeout);
        }

        private int readOne() throws IOException {
            byte[] one = new byte[1];
            int read = readSome(one, 0, 1);
            return read < 0 ? -1 : one[0] & 0xff;
        }

        private String readLine(int maximum, String what)
                throws IOException {
            ByteArrayOutputStream out =
                    new ByteArrayOutputStream(
                            Math.min(maximum, 128));
            boolean cr = false;
            while (true) {
                int value = readOne();
                if (value < 0) {
                    throw new EOFException(
                            "upstream closed while reading "
                                    + what);
                }
                if (cr) {
                    if (value != '\n') {
                        throw new IOException(
                                what + " used bare CR");
                    }
                    return out.toString(
                            StandardCharsets.ISO_8859_1);
                }
                if (value == '\r') {
                    cr = true;
                    continue;
                }
                if (value == '\n') {
                    throw new IOException(
                            what + " used bare LF");
                }
                if (out.size() >= maximum) {
                    throw new IOException(
                            what + " exceeds line budget");
                }
                out.write(value);
            }
        }

        private long readChunkSize() throws IOException {
            String line =
                    readLine(
                            MAX_CHUNK_LINE_BYTES,
                            "HTTP chunk size");
            if (line.isEmpty()
                    || line.indexOf(';') >= 0) {
                throw new IOException(
                        "unsupported or malformed HTTP chunk size");
            }
            long size = 0;
            for (int i = 0; i < line.length(); i++) {
                int digit =
                        Character.digit(
                                line.charAt(i),
                                16);
                if (digit < 0
                        || size
                                > (Long.MAX_VALUE - digit)
                                        / 16) {
                    throw new IOException(
                            "malformed or overflowing HTTP chunk size");
                }
                size = size * 16 + digit;
            }
            if (size > maxResponseBytes) {
                throw new IOException(
                        "HTTP chunk exceeds response byte budget");
            }
            return size;
        }

        private void expectCrlf() throws IOException {
            if (readOne() != '\r'
                    || readOne() != '\n') {
                throw new IOException(
                        "missing CRLF after HTTP chunk payload");
            }
        }

        private void readTrailers() throws IOException {
            int fields = 0;
            int bytes = 0;
            while (true) {
                String line =
                        readLine(
                                MAX_LINE_BYTES,
                                "HTTP trailer");
                bytes = checkedHeaderBytes(bytes, line);
                if (line.isEmpty()) return;
                if (++fields > MAX_HEADER_FIELDS) {
                    throw new IOException(
                            "HTTP trailer field count exceeds "
                                    + MAX_HEADER_FIELDS);
                }
                Map.Entry<String, String> field =
                        parseHeaderLine(line);
                String lower =
                        field.getKey()
                                .toLowerCase(Locale.ROOT);
                if (RESPONSE_HOP.contains(lower)) {
                    throw new IOException(
                            "forbidden framing/control HTTP trailer: "
                                    + field.getKey());
                }
            }
        }

        private long addBodyBytes(long current, int amount)
                throws IOException {
            if (amount < 0
                    || amount > maxResponseBytes - current) {
                throw new IOException(
                        "selector HTTP response body byte budget exceeded");
            }
            return current + amount;
        }

        @Override
        public void close() {
            closed.set(true);
            // A CONTROL thread may revoke first; the physical owner must still
            // call through later so Transaction can publish RETIRED and release
            // the shared permit exactly once.
            transaction.close();
        }
    }

    SelectorHttpTransport(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            OutboundEgressPolicy.Resolver resolver,
            PinnedHttpTransport.ConnectionBudget budget,
            long maxResponseBytes) {
        this(
                purpose,
                policy,
                Objects.requireNonNull(resolver, "resolver"),
                null,
                budget,
                maxResponseBytes);
    }

    SelectorHttpTransport(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            SelectorDnsResolver selectorResolver,
            PinnedHttpTransport.ConnectionBudget budget,
            long maxResponseBytes) {
        this(
                purpose,
                policy,
                null,
                Objects.requireNonNull(
                        selectorResolver,
                        "selectorResolver"),
                budget,
                maxResponseBytes);
    }

    private SelectorHttpTransport(
            OutboundEgressPolicy.Purpose purpose,
            OutboundEgressPolicy.Policy policy,
            OutboundEgressPolicy.Resolver resolver,
            SelectorDnsResolver selectorResolver,
            PinnedHttpTransport.ConnectionBudget budget,
            long maxResponseBytes) {
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resolver = resolver;
        this.selectorResolver = selectorResolver;
        this.budget = Objects.requireNonNull(budget, "budget");
        this.budget.requireCompatible(policy.limits());
        if (maxResponseBytes < 0
                || maxResponseBytes > (1L << 30)) {
            throw new IllegalArgumentException(
                    "maxResponseBytes must be 0..1GiB");
        }
        this.maxResponseBytes = maxResponseBytes;
    }

    Response request(
            URI target,
            String method,
            List<Map.Entry<String, String>> requestHeaders)
            throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(
                requestHeaders, "requestHeaders");
        if (!method.equals("GET")
                && !method.equals("HEAD")) {
            throw new IllegalArgumentException(
                    "selector HTTP v1 supports only GET and HEAD");
        }
        validateRequestHeaders(requestHeaders);
        if (closed.get()) {
            throw new RejectedExecutionException(
                    "selector HTTP transport is closed");
        }

        URI canonical =
                OutboundEgressPolicy.canonicalUri(
                        target.toASCIIString());
        if (!canonical.getScheme().equals("http")) {
            throw new IllegalArgumentException(
                    "selector HTTP v1 requires http; HTTPS requires SSLEngine integration");
        }
        String origin =
                canonical.getScheme()
                        + "://"
                        + canonical.getRawAuthority();
        PinnedHttpTransport.ConnectionLease lease =
                budget.acquire(origin);
        Transaction transaction =
                new Transaction(
                        lease,
                        deadlineAfter(
                                policy.limits()
                                        .overallTimeout()));
        active.add(transaction);

        boolean transferred = false;
        try {
            OutboundEgressPolicy.ResolvedTarget resolved =
                    transaction.resolve(canonical);
            transaction.connect(resolved);
            transaction.write(
                    requestBytes(
                            resolved,
                            method,
                            requestHeaders));
            Head head = transaction.readHead(method);
            Response response =
                    new Response(this, transaction, head);
            transferred = true;
            return response;
        } finally {
            if (!transferred) transaction.retire();
        }
    }

    int activeCount() {
        return active.size();
    }

    List<State> activeStates() {
        return active.stream()
                .map(Transaction::state)
                .toList();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Transaction transaction : List.copyOf(active)) {
            transaction.close();
        }
    }

    private static ByteBuffer requestBytes(
            OutboundEgressPolicy.ResolvedTarget target,
            String method,
            List<Map.Entry<String, String>> headers) {
        String path = target.uri().getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        String query = target.uri().getRawQuery();
        String requestTarget =
                query == null ? path : path + "?" + query;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLatin1(
                out,
                method
                        + " "
                        + requestTarget
                        + " HTTP/1.1\r\n");
        writeLatin1(
                out,
                "Host: " + target.authority() + "\r\n");
        writeLatin1(out, "Connection: close\r\n");
        for (Map.Entry<String, String> field : headers) {
            writeLatin1(
                    out,
                    field.getKey()
                            + ": "
                            + field.getValue()
                            + "\r\n");
        }
        writeLatin1(out, "\r\n");
        return ByteBuffer.wrap(out.toByteArray());
    }

    private static void writeLatin1(
            ByteArrayOutputStream out,
            String value) {
        out.writeBytes(
                value.getBytes(
                        StandardCharsets.ISO_8859_1));
    }

    private static Head parseHead(
            byte[] raw,
            byte[] initialBody,
            String method,
            long maxResponseBytes)
            throws IOException {
        String text =
                new String(raw, StandardCharsets.ISO_8859_1);
        if (!text.endsWith("\r\n\r\n")) {
            throw new IOException(
                    "HTTP response headers missing terminator");
        }
        String headText =
                text.substring(0, text.length() - 4);
        String[] lines = headText.split("\\r\\n", -1);
        if (lines.length == 0) {
            throw new IOException("missing HTTP status line");
        }

        String statusLine = lines[0];
        if (!(statusLine.startsWith("HTTP/1.1 ")
                || statusLine.startsWith("HTTP/1.0 "))) {
            throw new IOException(
                    "unsupported or malformed HTTP status line");
        }
        int firstSpace = statusLine.indexOf(' ');
        int secondSpace =
                statusLine.indexOf(' ', firstSpace + 1);
        String codeText =
                secondSpace < 0
                        ? statusLine.substring(firstSpace + 1)
                        : statusLine.substring(
                                firstSpace + 1, secondSpace);
        if (codeText.length() != 3
                || !codeText.chars()
                        .allMatch(Character::isDigit)) {
            throw new IOException("malformed HTTP status code");
        }
        int status = Integer.parseInt(codeText);
        if (status < 100 || status > 599) {
            throw new IOException(
                    "HTTP status outside 100..599");
        }
        ArrayList<Map.Entry<String, String>> fields =
                new ArrayList<>();
        int headerBytes = statusLine.length() + 2;
        for (int i = 1; i < lines.length; i++) {
            headerBytes =
                    checkedHeaderBytes(
                            headerBytes, lines[i]);
            if (fields.size() >= MAX_HEADER_FIELDS) {
                throw new IOException(
                        "HTTP response header count exceeds "
                                + MAX_HEADER_FIELDS);
            }
            fields.add(parseHeaderLine(lines[i]));
        }

        List<Map.Entry<String, String>> immutable =
                List.copyOf(fields);
        Framing framing;
        long length = -1;

        if (method.equals("HEAD")
                || status < 200
                || status == 204
                || status == 205
                || status == 304) {
            framing = Framing.NONE;
        } else {
            List<String> transfer =
                    values(immutable, "transfer-encoding");
            List<String> lengths =
                    values(immutable, "content-length");
            if (!transfer.isEmpty()
                    && !lengths.isEmpty()) {
                throw new IOException(
                        "HTTP response contains both Transfer-Encoding and Content-Length");
            }
            if (!transfer.isEmpty()) {
                if (transfer.size() != 1
                        || !transfer.getFirst()
                                .trim()
                                .equalsIgnoreCase("chunked")) {
                    throw new IOException(
                            "unsupported HTTP Transfer-Encoding");
                }
                framing = Framing.CHUNKED;
            } else if (!lengths.isEmpty()) {
                if (lengths.size() != 1) {
                    throw new IOException(
                            "multiple HTTP Content-Length fields are rejected");
                }
                String rawLength = lengths.getFirst().trim();
                if (rawLength.isEmpty()
                        || !rawLength.chars()
                                .allMatch(Character::isDigit)) {
                    throw new IOException(
                            "malformed HTTP Content-Length");
                }
                try {
                    length = Long.parseLong(rawLength);
                } catch (NumberFormatException overflow) {
                    throw new IOException(
                            "overflowing HTTP Content-Length",
                            overflow);
                }
                if (length > maxResponseBytes) {
                    throw new IOException(
                            "HTTP Content-Length exceeds response byte budget");
                }
                framing = Framing.CONTENT_LENGTH;
            } else {
                framing = Framing.CLOSE_DELIMITED;
            }
        }

        return new Head(
                status,
                filteredResponseHeaders(immutable),
                framing,
                length,
                initialBody);
    }

    private static int headerTerminatorMatch(
            int matched,
            byte value) {
        return switch (matched) {
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
    }

    private static List<Map.Entry<String, String>>
            filteredResponseHeaders(
                    List<Map.Entry<String, String>> headers)
                    throws IOException {
        Set<String> excluded = new HashSet<>(RESPONSE_HOP);
        for (String connection : values(headers, "connection")) {
            for (String item : connection.split(",", -1)) {
                String token = item.trim();
                if (token.isEmpty()
                        || !HEADER_NAME.matcher(token).matches()) {
                    throw new IOException(
                            "invalid HTTP Connection header token");
                }
                excluded.add(token.toLowerCase(Locale.ROOT));
            }
        }

        ArrayList<Map.Entry<String, String>> retained =
                new ArrayList<>();
        for (Map.Entry<String, String> field : headers) {
            if (!excluded.contains(
                    field.getKey().toLowerCase(Locale.ROOT))) {
                retained.add(field);
            }
        }
        return List.copyOf(retained);
    }

    private static List<String> values(
            List<Map.Entry<String, String>> headers,
            String name) {
        ArrayList<String> values = new ArrayList<>();
        for (Map.Entry<String, String> field : headers) {
            if (field.getKey().equalsIgnoreCase(name)) {
                values.add(field.getValue());
            }
        }
        return values;
    }

    private static Map.Entry<String, String>
            parseHeaderLine(String line)
                    throws IOException {
        if (line.isEmpty()
                || line.charAt(0) == ' '
                || line.charAt(0) == '\t') {
            throw new IOException(
                    "HTTP obs-fold/whitespace-prefixed header is forbidden");
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            throw new IOException(
                    "malformed HTTP header field");
        }
        String name = line.substring(0, colon);
        if (!HEADER_NAME.matcher(name).matches()) {
            throw new IOException(
                    "invalid HTTP header name");
        }
        String value = line.substring(colon + 1).strip();
        for (int i = 0; i < value.length(); i++) {
            int ch = value.charAt(i);
            if (ch < 32 || ch == 127) {
                throw new IOException(
                        "invalid control character in HTTP header value");
            }
        }
        return Map.entry(name, value);
    }

    private static int checkedHeaderBytes(
            int current,
            String line)
            throws IOException {
        long next =
                (long) current + line.length() + 2L;
        if (next > MAX_HEADER_BYTES) {
            throw new IOException(
                    "HTTP header byte budget exceeded");
        }
        return (int) next;
    }

    private static void validateRequestHeaders(
            List<Map.Entry<String, String>> headers) {
        if (headers.size() > MAX_HEADER_FIELDS) {
            throw new IllegalArgumentException(
                    "outbound request header count exceeds "
                            + MAX_HEADER_FIELDS);
        }
        long bytes = 0;
        for (Map.Entry<String, String> field : headers) {
            Objects.requireNonNull(field, "request header");
            ProxyOutboundLoopback.validateRequestHeader(
                    field.getKey(), field.getValue());
            long fieldBytes =
                    (long) field.getKey().length()
                            + field.getValue().length()
                            + 4L;
            if (fieldBytes > MAX_HEADER_BYTES - bytes) {
                throw new IllegalArgumentException(
                        "outbound request header byte budget exceeded");
            }
            bytes += fieldBytes;
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) return true;
        boolean dot = false;
        for (int i = 0; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch == '.') dot = true;
            else if (ch < '0' || ch > '9') return false;
        }
        return dot;
    }

    private static long deadlineAfter(Duration duration) {
        long now = System.nanoTime();
        long delta;
        try {
            delta = duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        return Long.MAX_VALUE - now < delta
                ? Long.MAX_VALUE
                : now + delta;
    }
}
