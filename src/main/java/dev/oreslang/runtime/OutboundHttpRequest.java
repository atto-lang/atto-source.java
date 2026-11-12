package dev.oreslang.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guest-facing outbound HTTP lifetime bridge.
 *
 * <p>The public Oreslang surface is {@code await http.request(...)}. These Java
 * classes are runtime backing values only; the evaluator exposes a sealed
 * nominal {@code HttpClientResponse}/{@code HttpClientBody} capability surface
 * and never publishes HttpClient, CompletableFuture, InputStream or sockets to
 * guest code.</p>
 *
 * <p>The current guest admission remains literal IPv4 loopback only, but the
 * physical request now runs through the exact-address transport. Hostname DNS,
 * arbitrary egress, redirects, request bodies and CONNECT remain disabled at
 * the guest boundary until their policy and lifecycle gates are complete.</p>
 */
public final class OutboundHttpRequest {
    private static final int PIPE_BYTES = 32 * 1024;
    private static final int MAX_READ_BYTES = 64 * 1024;

    private OutboundHttpRequest() { }

    private record Owner(
            ActorRuntime runtime,
            ActorRuntime.ActorId actorId) {
        static Owner capture(OresContext context) {
            ActorRuntime runtime = ActorRuntime.currentActorRuntime();
            ActorRuntime.ActorId actorId =
                    ActorRuntime.currentActorId().orElse(null);
            if (actorId != null && runtime != context.actors()) {
                throw new SecurityException(
                        "http.request actor owner belongs to a different runtime");
            }
            return new Owner(runtime, actorId);
        }

        void requireOwner() {
            if (actorId == null) {
                if (ActorRuntime.inActorExecution()) {
                    throw new SecurityException(
                            "root-owned HTTP client capability cannot be used from an actor");
                }
                return;
            }
            if (ActorRuntime.currentActorRuntime() != runtime
                    || !ActorRuntime.currentActorId().filter(actorId::equals).isPresent()) {
                throw new SecurityException(
                        "HTTP client capability requires its owning actor turn");
            }
        }

        <T> OresFuture<T> own(OresFuture<T> future) {
            if (actorId == null) return future;
            requireOwner();
            return runtime.ownCurrentActorFuture(future);
        }
    }

    private static final class Revocation {
        private final OresContext context;
        private final AtomicBoolean requested = new AtomicBoolean();
        private final AtomicBoolean gracefulClose = new AtomicBoolean();
        private final AtomicBoolean abortScheduled = new AtomicBoolean();
        private final AtomicReference<PinnedHttpTransport> transport =
                new AtomicReference<>();
        private final AtomicReference<BodyPipe> pipe = new AtomicReference<>();

        private Revocation(OresContext context) {
            this.context = Objects.requireNonNull(context, "context");
        }

        boolean requested() {
            return requested.get();
        }

        void cancel() {
            revoke(false);
        }

        void close() {
            gracefulClose.set(true);
            revoke(true);
        }

        boolean gracefulCloseRequested() {
            return gracefulClose.get();
        }

        private void revoke(boolean closing) {
            if (closing) gracefulClose.set(true);
            requested.set(true);
            BodyPipe body = pipe.get();
            if (body != null) body.cancel();
            scheduleTransportAbort();
        }

        void trackTransport(PinnedHttpTransport value) {
            Objects.requireNonNull(value, "value");
            if (!transport.compareAndSet(null, value)) {
                throw new IllegalStateException(
                        "HTTP transport already tracked");
            }
            if (requested.get()) scheduleTransportAbort();
        }

        void clearTransport(PinnedHttpTransport value) {
            transport.compareAndSet(value, null);
        }

        private void scheduleTransportAbort() {
            PinnedHttpTransport current = transport.get();
            if (current == null
                    || !abortScheduled.compareAndSet(false, true)) {
                return;
            }
            context.nativeIo().submitClose(
                    context,
                    "http.request.abort",
                    () -> {
                        current.close();
                        return null;
                    });
        }

        void bind(BodyPipe body) {
            Objects.requireNonNull(body, "body");
            if (!pipe.compareAndSet(null, body)) {
                throw new IllegalStateException(
                        "HTTP response body pipe already bound");
            }
            if (requested.get()) body.cancel();
        }
    }

    private record PendingRead(int maxBytes, OresFuture<List<Integer>> future) { }

    /**
     * One-producer / one-logical-reader bounded pipe. The native I/O worker may
     * block when the fixed byte budget is full; actor/root carriers never do.
     */
    private static final class BodyPipe {
        private final long deadlineNanos;
        private final ArrayDeque<byte[]> queued = new ArrayDeque<>();

        private BodyPipe(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
        private int firstOffset;
        private int bufferedBytes;
        private boolean eof;
        private Throwable failure;
        private PendingRead pending;

        OresFuture<List<Integer>> read(Owner owner, int maxBytes) {
            owner.requireOwner();
            if (maxBytes < 1 || maxBytes > MAX_READ_BYTES) {
                throw new IllegalArgumentException(
                        "HttpClientBody.read_chunk max_bytes must be 1..65536");
            }

            OresFuture<List<Integer>> ready;
            synchronized (this) {
                if (pending != null) {
                    throw new IllegalStateException(
                            "only one pending HttpClientBody.read_chunk is permitted");
                }
                if (bufferedBytes != 0) {
                    ready = OresFuture.completed(drainLocked(maxBytes));
                    return owner.own(ready);
                }
                if (failure != null) {
                    ready = OresFuture.failed(failure);
                    return owner.own(ready);
                }
                if (eof) {
                    ready = OresFuture.completed(List.of());
                    return owner.own(ready);
                }

                AtomicReference<OresFuture<List<Integer>>> self =
                        new AtomicReference<>();
                ready = new OresFuture<>(() -> cancelPending(self.get()));
                self.set(ready);
                pending = new PendingRead(maxBytes, ready);
            }
            return owner.own(ready);
        }

        private void cancelPending(OresFuture<List<Integer>> future) {
            if (future == null) return;
            synchronized (this) {
                if (pending != null && pending.future() == future) {
                    pending = null;
                    notifyAll();
                }
            }
        }

        void write(byte[] source, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, source.length);
            int cursor = offset;
            int remaining = length;
            while (remaining != 0) {
                PendingRead reader = null;
                List<Integer> direct = null;
                synchronized (this) {
                    requireWritableLocked();

                    if (pending != null && bufferedBytes == 0) {
                        reader = pending;
                        pending = null;
                        int count = Math.min(remaining, reader.maxBytes());
                        direct = bytes(source, cursor, count);
                        cursor += count;
                        remaining -= count;
                    } else {
                        while (failure == null && !eof
                                && bufferedBytes + remaining > PIPE_BYTES) {
                            long waitNanos =
                                    deadlineNanos - System.nanoTime();
                            if (waitNanos <= 0) {
                                throw new IOException(
                                        "HTTP response body backpressure exceeded overall deadline");
                            }
                            try {
                                TimeUnit.NANOSECONDS.timedWait(
                                        this,
                                        waitNanos);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException(
                                        "HTTP body producer interrupted while backpressured",
                                        interrupted);
                            }
                        }
                        requireWritableLocked();
                        int room = PIPE_BYTES - bufferedBytes;
                        int count = Math.min(remaining, room);
                        byte[] copy = new byte[count];
                        System.arraycopy(source, cursor, copy, 0, count);
                        queued.addLast(copy);
                        bufferedBytes += count;
                        cursor += count;
                        remaining -= count;
                        notifyAll();
                    }
                }
                if (reader != null) {
                    reader.future().completeFromRuntime(direct);
                }
            }
        }

        void finish() {
            PendingRead reader = null;
            synchronized (this) {
                if (failure != null || eof) return;
                eof = true;
                if (bufferedBytes == 0 && pending != null) {
                    reader = pending;
                    pending = null;
                }
                notifyAll();
            }
            if (reader != null) reader.future().completeFromRuntime(List.of());
        }

        void fail(Throwable cause) {
            Objects.requireNonNull(cause, "cause");
            PendingRead reader = null;
            synchronized (this) {
                if (failure != null || eof) return;
                failure = cause;
                queued.clear();
                bufferedBytes = 0;
                firstOffset = 0;
                if (pending != null) {
                    reader = pending;
                    pending = null;
                }
                notifyAll();
            }
            if (reader != null) settleFailure(reader.future(), cause);
        }

        void cancel() {
            fail(new CancellationException("HTTP response body revoked"));
        }

        private void requireWritableLocked() throws IOException {
            if (failure != null) {
                if (failure instanceof CancellationException) {
                    throw new IOException("HTTP response body cancelled", failure);
                }
                throw new IOException("HTTP response body failed", failure);
            }
            if (eof) throw new IOException("HTTP response body already finished");
        }

        private List<Integer> drainLocked(int maxBytes) {
            int target = Math.min(maxBytes, bufferedBytes);
            ArrayList<Integer> result = new ArrayList<>(target);
            int remaining = target;
            while (remaining != 0) {
                byte[] head = queued.getFirst();
                int count = Math.min(remaining, head.length - firstOffset);
                for (int i = 0; i < count; i++) {
                    result.add(head[firstOffset + i] & 0xff);
                }
                firstOffset += count;
                bufferedBytes -= count;
                remaining -= count;
                if (firstOffset == head.length) {
                    queued.removeFirst();
                    firstOffset = 0;
                }
            }
            notifyAll();
            return List.copyOf(result);
        }

        private static List<Integer> bytes(byte[] source, int offset, int length) {
            ArrayList<Integer> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                result.add(source[offset + i] & 0xff);
            }
            return List.copyOf(result);
        }
    }

    private static final class PipeOutputStream extends OutputStream {
        private final BodyPipe pipe;

        private PipeOutputStream(BodyPipe pipe) {
            this.pipe = pipe;
        }

        @Override public void write(int value) throws IOException {
            byte[] one = { (byte) value };
            pipe.write(one, 0, 1);
        }

        @Override public void write(byte[] value, int offset, int length)
                throws IOException {
            pipe.write(value, offset, length);
        }
    }

    /** Sealed guest runtime value for one response body. */
    public static final class Body {
        private final Owner owner;
        private final BodyPipe pipe;
        private final Revocation revocation;
        private final OresFuture<Void> lifetime;

        private Body(
                Owner owner,
                BodyPipe pipe,
                Revocation revocation,
                OresFuture<Void> lifetime) {
            this.owner = owner;
            this.pipe = pipe;
            this.revocation = revocation;
            this.lifetime = lifetime;
        }

        public OresFuture<List<Integer>> readChunk(int maxBytes) {
            return pipe.read(owner, maxBytes);
        }

        public OresFuture<Void> closeAsync() {
            owner.requireOwner();
            revocation.close();
            return lifetime;
        }

        public boolean closed() {
            owner.requireOwner();
            return lifetime.isDone();
        }
    }

    /** Sealed guest runtime value for status, repeated headers and one body. */
    public static final class Response {
        private final Owner owner;
        private final int status;
        private final List<Map.Entry<String, String>> headers;
        private static final int BODY_AVAILABLE = 0;
        private static final int BODY_TAKEN = 1;
        private static final int RESPONSE_CLOSED = 2;

        private final Body body;
        private final AtomicInteger bodyState =
                new AtomicInteger(BODY_AVAILABLE);

        private Response(
                Owner owner,
                int status,
                List<Map.Entry<String, String>> headers,
                Body body) {
            this.owner = owner;
            this.status = status;
            this.headers = headers.stream()
                    .map(field -> Map.entry(field.getKey(), field.getValue()))
                    .toList();
            this.body = body;
        }

        public int status() {
            owner.requireOwner();
            return status;
        }

        public int headerCount() {
            owner.requireOwner();
            return headers.size();
        }

        public String headerNameAt(int index) {
            owner.requireOwner();
            return header(index).getKey();
        }

        public String headerValueAt(int index) {
            owner.requireOwner();
            return header(index).getValue();
        }

        public List<String> headerValues(String name) {
            owner.requireOwner();
            Objects.requireNonNull(name, "header name");
            ArrayList<String> values = new ArrayList<>();
            for (Map.Entry<String, String> field : headers) {
                if (field.getKey().equalsIgnoreCase(name)) values.add(field.getValue());
            }
            return List.copyOf(values);
        }

        public Body takeBody() {
            owner.requireOwner();
            if (!bodyState.compareAndSet(BODY_AVAILABLE, BODY_TAKEN)) {
                int state = bodyState.get();
                throw new IllegalStateException(
                        state == RESPONSE_CLOSED
                                ? "HttpClientResponse is closed; body capability cannot be taken"
                                : "HttpClientResponse body capability already taken");
            }
            return body;
        }

        public OresFuture<Void> closeAsync() {
            owner.requireOwner();
            bodyState.set(RESPONSE_CLOSED);
            return body.closeAsync();
        }

        private Map.Entry<String, String> header(int index) {
            if (index < 0 || index >= headers.size()) {
                throw new IndexOutOfBoundsException(
                        "HTTP response header index " + index
                                + " outside 0.." + Math.max(-1, headers.size() - 1));
            }
            return headers.get(index);
        }
    }

    /**
     * Start one bodyless loopback request. Request headers are represented as
     * List<(String,String)> at the guest boundary and copied before admission.
     */
    public static OresFuture<Response> request(
            OresContext context,
            String method,
            String url,
            List<?> rawHeaders) {
        return request(context, method, url, rawHeaders, null);
    }

    /**
     * Fourth argument is an immutable String (UTF-8) or list of 0..255
     * integers (Bytes-compatible portable bootstrap). Copy the entire
     * body before asynchronous admission; no live guest Array or mutable
     * alias crosses the source Task to physical I/O worker.
     */
    public static OresFuture<Response> request(
            OresContext context,
            String method,
            String url,
            List<?> rawHeaders,
            Object rawBody) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(url, "url");
        final byte[] requestBody = requestBodyBytes(rawBody);
        if (!method.equals("POST") && requestBody.length != 0) {
            throw new IllegalArgumentException(
                    "only POST may carry a request body");
        }
        if (!method.equals("GET") && !method.equals("HEAD")
                && !method.equals("POST")) {
            throw new IllegalArgumentException("http.request supports GET, HEAD or POST");
        }
        List<Map.Entry<String, String>> providedHeaders = requestHeaders(rawHeaders);
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            return OresFuture.failed(new IllegalArgumentException(
                    "http.request requires a canonical absolute URL", invalid));
        }

        // Preserve the GET/HEAD proof harness public contract. A POST has
        // exactly the same canonical loopback authority/path admission; its
        // method and bounded body are checked independently above.
        ProxyOutboundLoopback.validateTarget(
                target, method.equals("POST") ? "GET" : method);
        String endpoint = "127.0.0.1:" + target.getPort();
        context.requirePermission(
                RuntimePermissions.Permission.NET, endpoint, "http.request");

        // Read the immutable context on the *guest admission* carrier, not
        // the later physical-I/O worker. No userland tracing call is required.
        // The same immutable header list is then carried through async I/O.
        final List<Map.Entry<String, String>> headers;
        final SemanticTraceBuffer.Scope clientSpan;
        if (context.autoTrace().enabled()) {
            // A top-level client call without a logical parent is a genuine
            // root span, not a synthetic child of an invisible span.
            TraceParent activeParent = TaskContext.traceParent();
            TraceParent outbound = activeParent == null
                    ? TraceParent.newRoot(true) : activeParent.newChild();
            headers = withTraceparentValue(providedHeaders, outbound);
            clientSpan = context.semanticTrace().begin(outbound,
                    activeParent == null ? "" : activeParent.spanId(),
                    SemanticTraceBuffer.HTTP_CLIENT, SemanticTraceBuffer.Kind.CLIENT);
        } else {
            headers = providedHeaders;
            clientSpan = null;
        }
        // Allocated only with sampling and trusted context; otherwise the
        // CLIENT path incurs no new physical-operation bookkeeping.
        final java.util.concurrent.atomic.AtomicInteger observedStatus =
                clientSpan == null ? null : new java.util.concurrent.atomic.AtomicInteger();

        Owner owner = Owner.capture(context);
        Revocation revocation = new Revocation(context);
        OresFuture<Response> exposed = new OresFuture<>(revocation::cancel);
        OresFuture<Void> lifetime = new OresFuture<>(revocation::cancel);
        owner.own(exposed);
        owner.own(lifetime);
        if (exposed.isCancelled() || lifetime.isCancelled()) {
            revocation.cancel();
            return exposed;
        }

        OresFuture<Void> physical = context.nativeIo().submit(
                context,
                RuntimePermissions.Permission.NET,
                "http.request.physical",
                () -> {
                    runPhysical(
                            context,
                            target,
                            method,
                            headers,
                            requestBody,
                            owner,
                            revocation,
                            exposed,
                            lifetime,
                            observedStatus);
                    return null;
                });

        physical.whenCompleteRuntime((ignored, failure) -> {
            // An async CLIENT span follows actual physical network operation
            // retirement, not the Future returned at source admission.
            SemanticTraceBuffer.finish(clientSpan,
                    observedStatus == null ? 0 : observedStatus.get(),
                    failure != null);
            if (failure == null) {
                if (!lifetime.isDone()) lifetime.completeFromRuntime(null);
                return;
            }
            Throwable cause = OresFuture.unwrap(failure);
            revocation.cancel();
            if (!exposed.isDone()) settleFailure(exposed, cause);
            if (!lifetime.isDone()) {
                if (cause instanceof CancellationException
                        && revocation.gracefulCloseRequested()) {
                    lifetime.completeFromRuntime(null);
                } else {
                    settleFailure(lifetime, cause);
                }
            }
        });
        return exposed;
    }

    private static void runPhysical(
            OresContext context,
            URI target,
            String method,
            List<Map.Entry<String, String>> headers,
            byte[] requestBody,
            Owner owner,
            Revocation revocation,
            OresFuture<Response> exposed,
            OresFuture<Void> lifetime,
            java.util.concurrent.atomic.AtomicInteger observedStatus) throws IOException {
        if (revocation.requested()) {
            throw new CancellationException(
                    "http.request revoked before native acquisition");
        }

        PinnedHttpTransport transport =
                PinnedHttpTransport.systemDefault(
                        OutboundEgressPolicy.Purpose.GENERAL_HTTP,
                        context.outboundHttpPolicy(),
                        64L * 1024 * 1024,
                        context.outboundHttpBudget(),
                        context.outboundDnsResolver());
        revocation.trackTransport(transport);
        BodyPipe pipe = null;
        try (transport) {
            PinnedHttpTransport.Response upstream =
                    transport.request(target, method, headers, requestBody);
            if (observedStatus != null) observedStatus.set(upstream.status());
            try (upstream) {
                if (revocation.requested()) {
                    throw new CancellationException(
                            "http.request revoked after headers");
                }

                pipe = new BodyPipe(upstream.deadlineNanos());
                revocation.bind(pipe);
                Body body = new Body(owner, pipe, revocation, lifetime);
                Response response = new Response(
                        owner,
                        upstream.status(),
                        upstream.headers(),
                        body);

                if (!exposed.completeFromRuntime(response)) {
                    revocation.cancel();
                    throw new CancellationException(
                            "http.request response owner revoked before publication");
                }

                upstream.streamBody(
                        new PipeOutputStream(pipe),
                        revocation::requested);
                if (revocation.requested()) {
                    throw new CancellationException(
                            "http.request revoked after response body");
                }
                pipe.finish();
            }
        } catch (Throwable failure) {
            if (pipe != null) pipe.fail(failure);
            if (revocation.gracefulCloseRequested()
                    && revocation.requested()
                    && (failure instanceof IOException
                            || failure instanceof CancellationException)) {
                throw new CancellationException(
                        "http.request response body closed by owner");
            }
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IOException(
                    "http.request body transport failed",
                    failure);
        } finally {
            revocation.clearTransport(transport);
        }
    }

    /**
     * Trusted HTTP CLIENT boundary: inject a new W3C child parent-id from
     * immutable logical task context. No sender-controlled traceparent can
     * override the runtime-owned identity while tracing is enabled.
     *
     * This first slice does NOT create an OTLP CLIENT span, or copy tracestate.
     * It is a standalone wire-propagation primitive with explicit budgets.
     */
    static List<Map.Entry<String, String>> withTraceparent(
            List<Map.Entry<String, String>> provided,
            TraceParent parent) {
        Objects.requireNonNull(provided, "provided");
        if (parent == null) return provided;
        return withTraceparentValue(provided, parent.newChild());
    }

    /** Inject an already-minted root or child span ID exactly once. */
    private static List<Map.Entry<String, String>> withTraceparentValue(
            List<Map.Entry<String, String>> provided,
            TraceParent outgoing) {
        Objects.requireNonNull(provided, "provided");
        Objects.requireNonNull(outgoing, "outgoing");

        long bytes = 0L;
        for (Map.Entry<String, String> header : provided) {
            String key = header.getKey();
            if (key.equalsIgnoreCase("traceparent") || key.equalsIgnoreCase("tracestate")) {
                throw new IllegalArgumentException(
                        "traceparent/tracestate are runtime-owned while auto tracing is enabled");
            }
            bytes += (long) key.length() + header.getValue().length() + 4L;
        }
        // "traceparent" (11) + ": " (2) + 55-byte W3C v00 value + CRLF (2)
        if (provided.size() >= 100 || bytes > 32_768L - 70L) {
            throw new IllegalArgumentException(
                    "http.request trace propagation exceeds header budgets");
        }
        ArrayList<Map.Entry<String, String>> result =
                new ArrayList<>(provided.size() + 1);
        result.addAll(provided);
        result.add(Map.entry("traceparent", outgoing.toHeader()));
        return List.copyOf(result);
    }

    private static byte[] requestBodyBytes(Object value) {
        if (value == null) return new byte[0];
        final int max = PinnedHttpTransport.MAX_REQUEST_BODY_BYTES;
        if (value instanceof String text) {
            if (text.length() > max) {
                throw new IllegalArgumentException(
                        "HTTP UTF-8 request body exceeds 1 MiB limit");
            }
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > max) {
                throw new IllegalArgumentException(
                        "HTTP UTF-8 request body exceeds 1 MiB limit");
            }
            return utf8;
        }
        if (value instanceof List<?> bytes) {
            if (bytes.size() > max) {
                throw new IllegalArgumentException(
                        "HTTP request body exceeds 1 MiB limit");
            }
            byte[] data = new byte[bytes.size()];
            for (int i = 0; i < bytes.size(); i++) {
                Object item = bytes.get(i);
                if (!(item instanceof Byte || item instanceof Short
                        || item instanceof Integer || item instanceof Long)) {
                    throw new IllegalArgumentException(
                            "HTTP Bytes require integral octets, index " + i);
                }
                long octet = ((Number) item).longValue();
                if (octet < 0 || octet > 255) {
                    throw new IllegalArgumentException(
                            "HTTP Bytes octet outside 0..255, index " + i);
                }
                data[i] = (byte) octet;
            }
            return data;
        }
        throw new IllegalArgumentException(
                "HTTP request body must be UTF-8 String or List<int> Bytes");
    }

    private static List<Map.Entry<String, String>> requestHeaders(List<?> raw) {
        Objects.requireNonNull(raw, "request headers");
        if (raw.size() > 100) {
            throw new IllegalArgumentException("http.request header count exceeds 100");
        }
        ArrayList<Map.Entry<String, String>> result = new ArrayList<>(raw.size());
        long bytes = 0;
        for (Object item : raw) {
            if (!(item instanceof List<?> pair)
                    || pair.size() != 2
                    || !(pair.get(0) instanceof String name)
                    || !(pair.get(1) instanceof String value)) {
                throw new IllegalArgumentException(
                        "http.request headers must be List<(String,String)>");
            }
            ProxyOutboundLoopback.validateRequestHeader(name, value);
            long fieldBytes = (long) name.length() + value.length() + 4L;
            if (fieldBytes > 32_768L - bytes) {
                throw new IllegalArgumentException(
                        "http.request header byte budget exceeds 32768");
            }
            bytes += fieldBytes;
            result.add(Map.entry(name, value));
        }
        return List.copyOf(result);
    }

    private static <T> void settleFailure(OresFuture<T> future, Throwable failure) {
        Throwable cause = OresFuture.unwrap(failure);
        if (cause instanceof CancellationException) future.cancel(false);
        else future.failFromRuntime(cause);
    }
}
