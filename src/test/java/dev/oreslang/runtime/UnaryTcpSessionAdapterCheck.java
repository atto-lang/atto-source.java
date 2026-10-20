package dev.oreslang.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;

/** Real-loopback proof for authenticated bounded unary TCP session admission. */
public final class UnaryTcpSessionAdapterCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private record Binding(long sessionId, long generation) { }

    /**
     * Deterministic slow-reader socket: one fixed request is readable, then
     * both input and output block until close() wakes them.
     */
    private static final class BlockingDuplexSocket extends Socket {
        private final Object monitor = new Object();
        private final byte[] inbound;
        private final int perByteDelayMillis;
        private int inboundIndex;
        private boolean closed;

        BlockingDuplexSocket(byte[] inbound) {
            this(inbound, 0);
        }

        BlockingDuplexSocket(byte[] inbound, int perByteDelayMillis) {
            this.inbound = inbound.clone();
            this.perByteDelayMillis = perByteDelayMillis;
        }

        private final InputStream input = new InputStream() {
            @Override public int read() throws IOException {
                synchronized (monitor) {
                    if (inboundIndex < inbound.length && perByteDelayMillis > 0) {
                        try { monitor.wait(perByteDelayMillis); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("fake socket drip interrupted", interrupted);
                        }
                    }
                    if (closed) return -1;
                    if (inboundIndex < inbound.length)
                        return Byte.toUnsignedInt(inbound[inboundIndex++]);
                    while (!closed) {
                        try { monitor.wait(); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("fake socket input interrupted", interrupted);
                        }
                    }
                    return -1;
                }
            }
        };

        private final OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException {
                synchronized (monitor) {
                    while (!closed) {
                        try { monitor.wait(); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("fake socket output interrupted", interrupted);
                        }
                    }
                    throw new IOException("fake socket closed during blocked write");
                }
            }
        };

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(int timeout) { }
        @Override public boolean isClosed() {
            synchronized (monitor) { return closed; }
        }
        @Override public void close() {
            synchronized (monitor) {
                if (closed) return;
                closed = true;
                monitor.notifyAll();
            }
        }
    }

    private static final class BindingAuthenticator
            implements UnaryTcpSessionAdapter.Authenticator {
        final AtomicInteger calls = new AtomicInteger();
        final boolean fail;

        BindingAuthenticator() { this(false); }
        BindingAuthenticator(boolean fail) { this.fail = fail; }

        @Override
        public UnaryTcpSessionAdapter.PeerIdentity authenticateAndBind(
                Socket socket, long sessionId, long generation) throws Exception {
            calls.incrementAndGet();
            if (fail) throw new SecurityException("test authentication failure");
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeLong(sessionId);
            out.writeLong(generation);
            out.flush();
            return new UnaryTcpSessionAdapter.PeerIdentity("loopback:test");
        }
    }

    private static final class TestHandle implements UnaryTcpSessionAdapter.DispatchHandle {
        final CompletableFuture<UnaryTcpSessionAdapter.Reply> future = new CompletableFuture<>();
        final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void whenComplete(
                BiConsumer<? super UnaryTcpSessionAdapter.Reply, ? super Throwable> completion) {
            future.whenComplete(completion);
        }

        @Override
        public boolean cancel() {
            cancelled.set(true);
            return future.completeExceptionally(new java.util.concurrent.CancellationException());
        }

        void reply(byte... payload) {
            future.complete(UnaryTcpSessionAdapter.Reply.success(payload));
        }
    }

    private static final class ManualDispatcher implements UnaryTcpSessionAdapter.Dispatcher {
        final Map<String, TestHandle> handles = new ConcurrentHashMap<>();
        final AtomicInteger dispatches = new AtomicInteger();

        @Override
        public UnaryTcpSessionAdapter.DispatchHandle dispatch(
                UnaryTcpSessionAdapter.Request request) {
            dispatches.incrementAndGet();
            TestHandle handle = new TestHandle();
            String key = key(request.sessionId(), request.correlationId());
            if (handles.putIfAbsent(key, handle) != null)
                throw new AssertionError("duplicate test dispatch key " + key);
            return handle;
        }

        TestHandle await(long sessionId, long correlationId) throws Exception {
            String key = key(sessionId, correlationId);
            awaitTrue(() -> handles.containsKey(key), 2000, "dispatch " + key);
            return handles.get(key);
        }
    }

    private static String key(long sessionId, long correlationId) {
        return sessionId + ":" + correlationId;
    }

    private static UnaryTcpSessionAdapter.Limits limits(
            int perConnectionRequests,
            long perConnectionBytes,
            int globalWork,
            long globalBytes,
            int requestDeadlineMillis) {
        return new UnaryTcpSessionAdapter.Limits(
                8,
                perConnectionRequests,
                perConnectionBytes,
                globalWork,
                globalBytes,
                1024,
                2000,
                requestDeadlineMillis,
                1000);
    }

    private static Binding binding(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        return new Binding(input.readLong(), input.readLong());
    }

    private static void send(Socket socket, UnaryTransportFrame.Envelope envelope)
            throws IOException {
        UnaryTransportFrame.writeTcp(socket.getOutputStream(), envelope);
        socket.getOutputStream().flush();
    }

    private static UnaryTransportFrame.Envelope request(
            Binding binding, long correlationId, byte[] payload) {
        return new UnaryTransportFrame.Envelope(
                UnaryTransportFrame.Kind.REQUEST,
                binding.sessionId(), binding.generation(), correlationId, payload);
    }

    private static UnaryTransportFrame.Envelope cancel(
            Binding binding, long correlationId) {
        return new UnaryTransportFrame.Envelope(
                UnaryTransportFrame.Kind.CANCEL,
                binding.sessionId(), binding.generation(), correlationId, new byte[0]);
    }

    private static UnaryTransportFrame.Envelope read(Socket socket) throws IOException {
        return UnaryTransportFrame.readTcp(socket.getInputStream(), 1024);
    }

    private static void awaitTrue(
            BooleanSupplier condition, long timeoutMillis, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline)
                throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    private static void compactActorPayloadProjection() {
        byte[] original = new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES];
        original[0] = (byte) 255;
        original[original.length - 1] = (byte) 254;
        var request = new UnaryTcpSessionAdapter.Request(
                new UnaryTcpSessionAdapter.PeerIdentity("peer:compact-bytes"),
                7, 3, 1, original);
        original[0] = 0;

        Object payload = request.actorMessage().get("payload");
        check(payload instanceof OresBytes,
                "actor payload must use compact immutable OresBytes");
        OresBytes bytes = (OresBytes) payload;
        check(bytes.size() == UnaryTransportFrame.MAX_PAYLOAD_BYTES,
                "compact payload preserves wire length");
        check(bytes.unsignedAt(0) == 255
                        && bytes.unsignedAt(bytes.size() - 1) == 254,
                "compact payload is unsigned and backed by the request copy");
        byte[] copy = bytes.toByteArray();
        copy[0] = 1;
        check(bytes.unsignedAt(0) == 255,
                "OresBytes must not expose mutable backing storage");

        byte[] tooLarge = new byte[UnaryTransportFrame.MAX_PAYLOAD_BYTES + 1];
        try {
            new UnaryTcpSessionAdapter.Request(
                    new UnaryTcpSessionAdapter.PeerIdentity("peer:too-large"),
                    8, 4, 2, tooLarge);
            throw new AssertionError("request accepted a payload above the unary protocol limit");
        } catch (IllegalArgumentException expected) { }
        try {
            UnaryTcpSessionAdapter.Reply.success(tooLarge);
            throw new AssertionError("reply accepted a payload above the unary protocol limit");
        } catch (IllegalArgumentException expected) { }
        try {
            new UnaryTcpSessionAdapter.Reply(
                    UnaryTransportFrame.Kind.ERROR, new byte[] {1});
            throw new AssertionError("ERROR accepted an unversioned body");
        } catch (IllegalArgumentException expected) { }
    }

    private static void echoAndReplayFence() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        AtomicInteger dispatches = new AtomicInteger();
        UnaryTcpSessionAdapter.Dispatcher echo = request -> {
            dispatches.incrementAndGet();
            TestHandle handle = new TestHandle();
            handle.reply(request.payload());
            return handle;
        };

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(4, 4096, 8, 8192, 1000), auth, echo);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(1500);
                Binding b = binding(client);
                send(client, request(b, 1, new byte[] {1, 2, 3}));
                var reply = read(client);
                check(reply.kind() == UnaryTransportFrame.Kind.REPLY
                                && reply.correlationId() == 1
                                && Arrays.equals(reply.payload(), new byte[] {1, 2, 3}),
                        "authenticated request must roundtrip through dispatcher");

                send(client, request(b, 1, new byte[] {9}));
                try {
                    read(client);
                    throw new AssertionError("duplicate request correlation did not close session");
                } catch (IOException expected) { }
            }
            served.get(3, TimeUnit.SECONDS);
            check(dispatches.get() == 1, "replay must not redispatch");
            check(auth.calls.get() == 1, "session authenticates once");
            check(adapter.activeConnections() == 0
                            && adapter.activeGlobalWorkItems() == 0
                            && adapter.activeGlobalPayloadBytes() == 0,
                    "echo/replay session must reclaim every global credit");
        }
    }

    private static void wrongBindingRejectedBeforePayloadRead() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        AtomicInteger dispatches = new AtomicInteger();
        UnaryTcpSessionAdapter.Dispatcher dispatcher = request -> {
            dispatches.incrementAndGet();
            return new TestHandle();
        };

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(2, 2048, 4, 4096, 1000), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(750);
                Binding b = binding(client);
                var forged = new UnaryTransportFrame.Envelope(
                        UnaryTransportFrame.Kind.REQUEST,
                        b.sessionId() + 1,
                        b.generation(),
                        1,
                        new byte[] {1, 2, 3, 4, 5});
                byte[] wire = UnaryTransportFrame.encodeTcp(forged);
                client.getOutputStream().write(wire, 0, 4 + UnaryTransportFrame.ENVELOPE_BYTES);
                client.getOutputStream().flush();

                int terminal = client.getInputStream().read();
                check(terminal == -1,
                        "wrong session header must close before waiting for its omitted payload");
            }
            served.get(2, TimeUnit.SECONDS);
            check(dispatches.get() == 0, "foreign session must never dispatch");
            check(adapter.activeConnections() == 0 && adapter.activeGlobalWorkItems() == 0,
                    "foreign session rejection must not leak credits");
        }
    }

    private static void overloadCancelAndRecovery() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        ManualDispatcher dispatcher = new ManualDispatcher();

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(1, 4, 2, 8, 1000), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(1500);
                Binding b = binding(client);

                send(client, request(b, 1, new byte[] {1, 2, 3, 4}));
                TestHandle first = dispatcher.await(b.sessionId(), 1);
                check(adapter.activeGlobalWorkItems() == 1
                                && adapter.activeGlobalPayloadBytes() == 4,
                        "first pending request holds global payload credit");

                send(client, request(b, 2, new byte[] {5}));
                var overloaded = read(client);
                check(overloaded.kind() == UnaryTransportFrame.Kind.ERROR
                                && overloaded.correlationId() == 2
                                && overloaded.payload().length == 0,
                        "per-session saturation returns bounded empty ERROR");
                check(dispatcher.dispatches.get() == 1,
                        "overloaded request must be discarded without dispatch");

                send(client, cancel(b, 1));
                awaitTrue(first.cancelled::get, 1000, "request cancellation propagation");
                awaitTrue(() -> adapter.activeGlobalWorkItems() == 0, 1000, "cancel credit release");

                send(client, request(b, 3, new byte[] {9}));
                TestHandle third = dispatcher.await(b.sessionId(), 3);
                third.reply((byte) 42);
                var recovered = read(client);
                check(recovered.kind() == UnaryTransportFrame.Kind.REPLY
                                && recovered.correlationId() == 3
                                && Arrays.equals(recovered.payload(), new byte[] {42}),
                        "credit release must allow later requests without ID reuse");
            }

            served.get(3, TimeUnit.SECONDS);
            awaitTrue(() -> adapter.activeGlobalWorkItems() == 0
                            && adapter.activeGlobalPayloadBytes() == 0,
                    1000, "post-recovery credit release");
        }
    }

    private static void overloadRejectedIdCannotReplay() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        ManualDispatcher dispatcher = new ManualDispatcher();

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(1, 4, 2, 8, 1000), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });

            try (Socket client = new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(1500);
                Binding b = binding(client);

                send(client, request(b, 1, new byte[] {1, 2, 3, 4}));
                TestHandle first = dispatcher.await(b.sessionId(), 1);
                send(client, request(b, 2, new byte[] {5}));
                var overloaded = read(client);
                check(overloaded.kind() == UnaryTransportFrame.Kind.ERROR
                                && overloaded.correlationId() == 2,
                        "overload rejection must terminally consume correlation ID");

                send(client, cancel(b, 1));
                awaitTrue(first.cancelled::get, 1000, "first cancellation before replay probe");
                awaitTrue(() -> adapter.activeGlobalWorkItems() == 0,
                        1000, "overload replay probe credit release");

                send(client, request(b, 2, new byte[0]));
                try {
                    read(client);
                    throw new AssertionError("overload-rejected correlation ID replayed");
                } catch (IOException expected) { }
                check(dispatcher.dispatches.get() == 1,
                        "terminally rejected correlation ID must never dispatch later");
            }

            served.get(3, TimeUnit.SECONDS);
            check(adapter.activeConnections() == 0
                            && adapter.activeGlobalWorkItems() == 0
                            && adapter.activeGlobalPayloadBytes() == 0,
                    "overload replay rejection must reclaim all credits");
        }
    }

    private static void deadlineCancelsDispatch() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        ManualDispatcher dispatcher = new ManualDispatcher();

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(1, 8, 2, 16, 60), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(1500);
                Binding b = binding(client);
                send(client, request(b, 1, new byte[0]));
                TestHandle handle = dispatcher.await(b.sessionId(), 1);
                var timeout = read(client);
                check(timeout.kind() == UnaryTransportFrame.Kind.ERROR
                                && timeout.correlationId() == 1
                                && timeout.payload().length == 0,
                        "deadline publishes one opaque ERROR");
                awaitTrue(handle.cancelled::get, 1000, "deadline dispatch cancellation");
                awaitTrue(() -> adapter.activeGlobalWorkItems() == 0, 1000, "deadline write credit release");
            }
            served.get(3, TimeUnit.SECONDS);
        }
    }

    private static void globalBudgetAcrossConnections() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        ManualDispatcher dispatcher = new ManualDispatcher();

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(2, 8, 1, 4, 1000), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {

            Future<?> firstServer = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            Socket first = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            first.setSoTimeout(1500);
            Binding one = binding(first);
            send(first, request(one, 1, new byte[] {1, 2, 3, 4}));
            TestHandle oneHandle = dispatcher.await(one.sessionId(), 1);

            Future<?> secondServer = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            try (Socket second = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                second.setSoTimeout(1500);
                Binding two = binding(second);
                send(second, request(two, 1, new byte[] {8}));
                var rejected = read(second);
                check(rejected.kind() == UnaryTransportFrame.Kind.ERROR
                                && rejected.correlationId() == 1,
                        "global work saturation must reject second connection request");
                check(dispatcher.dispatches.get() == 1,
                        "global rejection must not reach dispatcher");

                send(first, cancel(one, 1));
                awaitTrue(oneHandle.cancelled::get, 1000, "first global request cancellation");
                awaitTrue(() -> adapter.activeGlobalWorkItems() == 0, 1000, "global credit release");

                send(second, request(two, 2, new byte[] {9}));
                TestHandle twoHandle = dispatcher.await(two.sessionId(), 2);
                twoHandle.reply((byte) 7);
                var reply = read(second);
                check(reply.kind() == UnaryTransportFrame.Kind.REPLY
                                && reply.correlationId() == 2,
                        "released global credit must admit another connection");
            }

            first.close();
            firstServer.get(3, TimeUnit.SECONDS);
            secondServer.get(3, TimeUnit.SECONDS);
            awaitTrue(() -> adapter.activeConnections() == 0
                            && adapter.activeGlobalWorkItems() == 0
                            && adapter.activeGlobalPayloadBytes() == 0,
                    1000, "cross-connection budget reclamation");
        }
    }

    private static void responseBudgetIsPerConnection() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        ManualDispatcher dispatcher = new ManualDispatcher();

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(2, 2, 4, 16, 1000), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(1500);
                Binding b = binding(client);
                send(client, request(b, 1, new byte[0]));
                TestHandle handle = dispatcher.await(b.sessionId(), 1);
                handle.reply((byte) 1, (byte) 2, (byte) 3);

                var terminal = read(client);
                check(terminal.kind() == UnaryTransportFrame.Kind.ERROR
                                && terminal.correlationId() == 1
                                && terminal.payload().length == 0,
                        "response exceeding per-connection byte budget must fail closed");
                awaitTrue(() -> adapter.activeGlobalWorkItems() == 0
                                && adapter.activeGlobalPayloadBytes() == 0,
                        1000, "oversized response credit release");

                send(client, request(b, 2, new byte[] {8, 9}));
                TestHandle second = dispatcher.await(b.sessionId(), 2);
                second.reply();
                var reply = read(client);
                check(reply.kind() == UnaryTransportFrame.Kind.REPLY
                                && reply.correlationId() == 2,
                        "per-connection response budget must be reclaimed after delivery");
            }

            served.get(3, TimeUnit.SECONDS);
        }
    }

    private static void authenticationFailureAndConnectionCapacity() throws Exception {
        BindingAuthenticator failing = new BindingAuthenticator(true);
        AtomicInteger dispatches = new AtomicInteger();
        UnaryTcpSessionAdapter.Dispatcher dispatcher = request -> {
            dispatches.incrementAndGet();
            return new TestHandle();
        };

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(1, 8, 2, 16, 1000), failing, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(1000);
                check(client.getInputStream().read() == -1,
                        "authentication failure must close before unary framing");
            }
            served.get(2, TimeUnit.SECONDS);
            check(failing.calls.get() == 1 && dispatches.get() == 0,
                    "failed authentication must never dispatch");
            check(adapter.activeConnections() == 0, "failed authentication releases connection credit");
        }

        BindingAuthenticator blockingAuth = new BindingAuthenticator();
        ManualDispatcher manual = new ManualDispatcher();
        UnaryTcpSessionAdapter.Limits oneConnection = new UnaryTcpSessionAdapter.Limits(
                1, 1, 8, 2, 16, 1024, 2000, 1000, 1000);
        try (var adapter = new UnaryTcpSessionAdapter(oneConnection, blockingAuth, manual);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> firstServer = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            Socket first = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            first.setSoTimeout(1000);
            binding(first);
            awaitTrue(() -> adapter.activeConnections() == 1, 1000, "first connection credit");

            Future<?> secondServer = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            try (Socket second = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                second.setSoTimeout(1000);
                check(second.getInputStream().read() == -1,
                        "connection-capacity rejection must close before authentication");
            }
            secondServer.get(2, TimeUnit.SECONDS);
            check(blockingAuth.calls.get() == 1,
                    "capacity-rejected connection must not consume authentication work");

            first.close();
            firstServer.get(3, TimeUnit.SECONDS);
            check(adapter.activeConnections() == 0, "connection capacity fully reclaimed");
        }
    }

    private static void completionCancelRaces() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        ManualDispatcher dispatcher = new ManualDispatcher();

        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(1, 8, 2, 16, 1000), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });

            try (Socket client = new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(1500);
                Binding b = binding(client);
                for (long id = 1; id <= 250; id++) {
                    send(client, request(b, id, new byte[] {1}));
                    TestHandle handle = dispatcher.await(b.sessionId(), id);
                    long rejectedBefore = adapter.rejectedCancellationTasks();
                    CountDownLatch go = new CountDownLatch(1);
                    Future<?> complete = pool.submit(() -> {
                        try { go.await(); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        handle.reply((byte) 3);
                    });
                    long racedId = id;
                    Future<?> cancel = pool.submit(() -> {
                        try { go.await(); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        try { send(client, UnaryTcpSessionAdapterCheck.cancel(b, racedId)); }
                        catch (IOException e) { throw new AssertionError(e); }
                    });
                    go.countDown();
                    complete.get(1, TimeUnit.SECONDS);
                    cancel.get(1, TimeUnit.SECONDS);
                    awaitTrue(() -> adapter.activeGlobalWorkItems() == 0,
                            1000, "race terminal credit " + id);

                    // Never use a short framed-read timeout as a readiness
                    // probe: a timeout can consume a partial TCP frame and
                    // corrupt the next read boundary. Poll availability without
                    // consuming bytes until one terminal outcome is observable.
                    long outcomeDeadline =
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (client.getInputStream().available() == 0
                            && !handle.cancelled.get()
                            && adapter.rejectedCancellationTasks() <= rejectedBefore) {
                        if (System.nanoTime() >= outcomeDeadline) {
                            throw new AssertionError(
                                    "race produced neither reply nor cancellation for " + id);
                        }
                        Thread.sleep(1);
                    }

                    if (client.getInputStream().available() > 0) {
                        var terminal = read(client);
                        check(terminal.kind() == UnaryTransportFrame.Kind.REPLY
                                        && terminal.correlationId() == id,
                                "race may publish only its matching successful reply");
                    } else {
                        check(handle.cancelled.get()
                                        || adapter.rejectedCancellationTasks() > rejectedBefore,
                                "absence of reply requires cancellation to have won");
                    }
                }
            }

            served.get(3, TimeUnit.SECONDS);
            check(adapter.activeGlobalWorkItems() == 0
                            && adapter.activeGlobalPayloadBytes() == 0,
                    "250 terminal races must leave no global work");
        }
    }

    private static void terminalWriteDeadlineClosesSlowReader() throws Exception {
        var request = new UnaryTransportFrame.Envelope(
                UnaryTransportFrame.Kind.REQUEST, 1, 1, 1, new byte[0]);
        var socket = new BlockingDuplexSocket(UnaryTransportFrame.encodeTcp(request));
        UnaryTcpSessionAdapter.Authenticator auth =
                (ignored, sessionId, generation) ->
                        new UnaryTcpSessionAdapter.PeerIdentity("peer:slow-reader");
        UnaryTcpSessionAdapter.Dispatcher dispatcher = inbound -> {
            TestHandle handle = new TestHandle();
            handle.reply((byte) 7);
            return handle;
        };
        var bounded = new UnaryTcpSessionAdapter.Limits(
                1, 1, 8, 1, 8, 1024, 2000, 1000, 50);

        try (var adapter = new UnaryTcpSessionAdapter(bounded, auth, dispatcher);
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> adapter.serve(socket));
            served.get(2, TimeUnit.SECONDS);
            check(socket.isClosed(),
                    "terminal write deadline must close a peer that never reads");
            check(adapter.activeConnections() == 0
                            && adapter.activeGlobalWorkItems() == 0
                            && adapter.activeGlobalPayloadBytes() == 0,
                    "write-deadline close must reclaim connection and response credits");
        }
    }

    private static void sessionCloseCancelsPending() throws Exception {
        BindingAuthenticator auth = new BindingAuthenticator();
        ManualDispatcher dispatcher = new ManualDispatcher();
        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(2, 8, 4, 32, 1000), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            Binding b = binding(client);
            send(client, request(b, 1, new byte[] {1, 2}));
            TestHandle handle = dispatcher.await(b.sessionId(), 1);
            client.close();
            served.get(3, TimeUnit.SECONDS);
            awaitTrue(handle.cancelled::get, 1000, "session-close dispatch cancellation");
            check(adapter.activeConnections() == 0
                            && adapter.activeGlobalWorkItems() == 0
                            && adapter.activeGlobalPayloadBytes() == 0,
                    "session close reclaims request and connection credits");
        }
    }

    @SuppressWarnings("try")
    private static void adapterCloseUnblocksAuthentication() throws Exception {
        CountDownLatch authenticationEntered = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        UnaryTcpSessionAdapter.Authenticator blockedAuth = (socket, id, generation) -> {
            authenticationEntered.countDown();
            // Simulate a peer that never finishes an authenticated handshake.
            socket.getInputStream().read();
            throw new IOException("authentication socket closed");
        };
        UnaryTcpSessionAdapter.Dispatcher dispatcher = request -> {
            dispatches.incrementAndGet();
            return new TestHandle();
        };
        BlockingDuplexSocket socket = new BlockingDuplexSocket(new byte[0]);
        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(1, 8, 2, 16, 1000), blockedAuth, dispatcher);
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> adapter.serve(socket));
            check(authenticationEntered.await(1, TimeUnit.SECONDS),
                    "blocked authentication must start");
            adapter.close();
            served.get(2, TimeUnit.SECONDS);
            check(socket.isClosed(), "shutdown closes pre-authentication socket");
            check(dispatches.get() == 0, "pre-authentication socket never dispatches");
            check(adapter.activeConnections() == 0
                            && adapter.activeGlobalWorkItems() == 0,
                    "shutdown releases pre-authentication connection credit");
        }
    }

    private static void throwingCancellationCannotStrandCredits() throws Exception {
        for (boolean timeoutWins : new boolean[] {false, true}) {
            BindingAuthenticator auth = new BindingAuthenticator();
            AtomicInteger dispatches = new AtomicInteger();
            AtomicInteger cancellations = new AtomicInteger();
            UnaryTcpSessionAdapter.Dispatcher dispatcher = request -> {
                if (dispatches.incrementAndGet() == 1) {
                    return new UnaryTcpSessionAdapter.DispatchHandle() {
                        @Override public void whenComplete(
                                BiConsumer<? super UnaryTcpSessionAdapter.Reply,
                                           ? super Throwable> completion) { }
                        @Override public boolean cancel() {
                            cancellations.incrementAndGet();
                            throw new IllegalStateException("intentional hostile cancel callback");
                        }
                    };
                }
                TestHandle handle = new TestHandle();
                handle.reply((byte) 42);
                return handle;
            };
            try (var adapter = new UnaryTcpSessionAdapter(
                            limits(2, 16, 2, 32, timeoutWins ? 90 : 1500),
                            auth, dispatcher);
                 var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                 var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> served = pool.submit(() -> {
                    try { adapter.serve(server.accept()); }
                    catch (IOException failure) { throw new AssertionError(failure); }
                });
                try (Socket client = new Socket(
                        InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                    client.setSoTimeout(1500);
                    Binding binding = binding(client);
                    send(client, request(binding, 1, new byte[] {1, 2}));
                    awaitTrue(() -> dispatches.get() == 1, 1000,
                            "first hostile cancellation dispatch");
                    if (timeoutWins) {
                        var terminal = read(client);
                        check(terminal.kind() == UnaryTransportFrame.Kind.ERROR
                                        && terminal.correlationId() == 1,
                                "hostile timeout must still publish an opaque terminal");
                    } else {
                        send(client, cancel(binding, 1));
                    }
                    awaitTrue(() -> cancellations.get() == 1
                                    && adapter.activeGlobalWorkItems() == 0
                                    && adapter.activeGlobalPayloadBytes() == 0,
                            1000, "hostile cancel must reclaim credits");
                    send(client, request(binding, 2, new byte[] {3}));
                    var recovered = read(client);
                    check(recovered.kind() == UnaryTransportFrame.Kind.REPLY
                                    && recovered.correlationId() == 2
                                    && Arrays.equals(recovered.payload(), new byte[] {42}),
                            "session must recover after throwing cancel hook");
                }
                served.get(3, TimeUnit.SECONDS);
                check(adapter.activeConnections() == 0
                                && adapter.activeGlobalWorkItems() == 0,
                        "hostile cancellation must not strand connection credits");
            }
        }

        BindingAuthenticator auth = new BindingAuthenticator();
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        UnaryTcpSessionAdapter.Dispatcher dispatcher = request -> {
            dispatches.incrementAndGet();
            return new UnaryTcpSessionAdapter.DispatchHandle() {
                @Override public void whenComplete(
                        BiConsumer<? super UnaryTcpSessionAdapter.Reply,
                                   ? super Throwable> completion) { }
                @Override public boolean cancel() {
                    cancellations.incrementAndGet();
                    throw new AssertionError("intentional hostile close callback");
                }
            };
        };
        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(4, 16, 4, 32, 1500), auth, dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            try (Socket client = new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                Binding binding = binding(client);
                send(client, request(binding, 1, new byte[] {1}));
                send(client, request(binding, 2, new byte[] {2}));
                awaitTrue(() -> dispatches.get() == 2, 1000, "two pending requests");
            }
            served.get(3, TimeUnit.SECONDS);
            awaitTrue(() -> cancellations.get() == 2, 1000,
                    "session close must attempt cancellation of every pending request");
            check(adapter.activeConnections() == 0
                            && adapter.activeGlobalWorkItems() == 0
                            && adapter.activeGlobalPayloadBytes() == 0,
                    "hostile close callbacks must release all session credits");
        }
    }

    private static void absoluteReadDeadlinesRejectStalledPeers() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            boolean stalledAuthentication = mode == 0;
            byte[] inbound = mode == 0 ? new byte[0]
                    : mode == 1 ? new byte[] {0, 0, 0, 26, 1}
                    : UnaryTransportFrame.encodeTcp(new UnaryTransportFrame.Envelope(
                            UnaryTransportFrame.Kind.REQUEST, 1, 1, 1, new byte[0]));
            // Mode 2 continuously trickles bytes, rather than ever blocking for
            // the configured per-read SO_TIMEOUT duration.
            BlockingDuplexSocket socket = new BlockingDuplexSocket(
                    inbound, mode == 2 ? 45 : 0);
            AtomicInteger dispatches = new AtomicInteger();
            UnaryTcpSessionAdapter.Authenticator auth = (connection, id, generation) -> {
                if (stalledAuthentication) connection.getInputStream().read();
                return new UnaryTcpSessionAdapter.PeerIdentity("peer:absolute-deadline");
            };
            UnaryTcpSessionAdapter.Dispatcher dispatcher = request -> {
                dispatches.incrementAndGet();
                return new TestHandle();
            };
            var limits = new UnaryTcpSessionAdapter.Limits(
                    1, 1, 8, 1, 8, 1024, 120, 1000, 1000);
            try (var adapter = new UnaryTcpSessionAdapter(limits, auth, dispatcher);
                 var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> served = pool.submit(() -> adapter.serve(socket));
                served.get(2, TimeUnit.SECONDS);
                check(socket.isClosed(),
                        "absolute deadline must close stalled or trickling socket: " + mode);
                check(dispatches.get() == 0,
                        "timeout must not dispatch an incomplete authenticated request: " + mode);
                check(adapter.activeConnections() == 0
                                && adapter.activeGlobalWorkItems() == 0
                                && adapter.activeGlobalPayloadBytes() == 0,
                        "absolute deadline must release connection budgets: " + mode);
            }
        }
    }

    private static void blockingCancellationCannotStallDeadlines() throws Exception {
        CountDownLatch cancellationEntered = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger timeoutCancellations = new AtomicInteger();
        UnaryTcpSessionAdapter.Dispatcher dispatcher = request -> {
            int ordinal = dispatches.incrementAndGet();
            if (ordinal == 1) {
                return new UnaryTcpSessionAdapter.DispatchHandle() {
                    @Override public void whenComplete(
                            BiConsumer<? super UnaryTcpSessionAdapter.Reply,
                                       ? super Throwable> completion) { }
                    @Override public boolean cancel() {
                        cancellationEntered.countDown();
                        try {
                            releaseCancellation.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return true;
                    }
                };
            }
            if (ordinal == 2) {
                return new UnaryTcpSessionAdapter.DispatchHandle() {
                    @Override public void whenComplete(
                            BiConsumer<? super UnaryTcpSessionAdapter.Reply,
                                       ? super Throwable> completion) { }
                    @Override public boolean cancel() {
                        timeoutCancellations.incrementAndGet();
                        return true;
                    }
                };
            }
            TestHandle immediate = new TestHandle();
            immediate.reply((byte) 11);
            return immediate;
        };
        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(3, 16, 3, 32, 200),
                        new BindingAuthenticator(), dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            try (Socket client = new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(2000);
                Binding binding = binding(client);
                send(client, request(binding, 1, new byte[] {1}));
                awaitTrue(() -> dispatches.get() == 1, 1000,
                        "request whose cancellation blocks");
                send(client, cancel(binding, 1));
                check(cancellationEntered.await(1, TimeUnit.SECONDS),
                        "first cancellation callback entered its blocked state");
                send(client, request(binding, 2, new byte[] {2}));
                UnaryTransportFrame.Envelope deadline = read(client);
                check(deadline.kind() == UnaryTransportFrame.Kind.ERROR
                                && deadline.correlationId() == 2,
                        "second request deadline must fire despite blocked cancellation");
                awaitTrue(() -> timeoutCancellations.get() == 1, 1000,
                        "timer cancellation callback scheduled on independent worker");
                send(client, request(binding, 3, new byte[] {3}));
                UnaryTransportFrame.Envelope recovered = read(client);
                check(recovered.kind() == UnaryTransportFrame.Kind.REPLY
                                && recovered.correlationId() == 3
                                && Arrays.equals(recovered.payload(), new byte[] {11}),
                        "other requests still execute while cancellation callback is blocked");
                awaitTrue(() -> adapter.activeGlobalWorkItems() == 0
                                && adapter.activeGlobalPayloadBytes() == 0,
                        1000, "blocked cancel callback cannot retain global admission credits");
            } finally {
                releaseCancellation.countDown();
            }
            served.get(3, TimeUnit.SECONDS);
            check(adapter.activeConnections() == 0,
                    "blocked cancellation must not strand connection credit");
            check(adapter.rejectedCancellationTasks() == 0,
                    "healthy bounded cancellation worker pool must not drop these tasks");
        }
    }

    private static void boundedCancellationSaturationIsObservable() throws Exception {
        CountDownLatch twoWorkersEntered = new CountDownLatch(2);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger cancellationsStarted = new AtomicInteger();
        UnaryTcpSessionAdapter.Dispatcher dispatcher = request -> {
            dispatches.incrementAndGet();
            return new UnaryTcpSessionAdapter.DispatchHandle() {
                @Override public void whenComplete(
                        BiConsumer<? super UnaryTcpSessionAdapter.Reply,
                                   ? super Throwable> completion) { }
                @Override public boolean cancel() {
                    cancellationsStarted.incrementAndGet();
                    twoWorkersEntered.countDown();
                    try {
                        releaseCallbacks.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }
            };
        };
        try (var adapter = new UnaryTcpSessionAdapter(
                        limits(1, 8, 1, 8, 1500),
                        new BindingAuthenticator(), dispatcher);
             var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> served = pool.submit(() -> {
                try { adapter.serve(server.accept()); }
                catch (IOException failure) { throw new AssertionError(failure); }
            });
            try (Socket client = new Socket(
                    InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                Binding binding = binding(client);
                for (long id = 1; id <= 2; id++) {
                    send(client, request(binding, id, new byte[] {1}));
                    long expected = id;
                    awaitTrue(() -> dispatches.get() >= expected, 1000,
                            "saturation request dispatched " + expected);
                    send(client, cancel(binding, id));
                    awaitTrue(() -> adapter.activeGlobalWorkItems() == 0,
                            1000, "saturation request credit released " + expected);
                }
                check(twoWorkersEntered.await(1, TimeUnit.SECONDS),
                        "two cancellation workers must be blocked");
                for (long id = 3; id <= 4; id++) {
                    send(client, request(binding, id, new byte[] {1}));
                    long expected = id;
                    awaitTrue(() -> dispatches.get() >= expected, 1000,
                            "queued cancellation request dispatched " + expected);
                    send(client, cancel(binding, id));
                    awaitTrue(() -> adapter.activeGlobalWorkItems() == 0,
                            1000, "queued cancellation request credit reclaimed");
                }
                awaitTrue(() -> adapter.rejectedCancellationTasks() == 1,
                        1000, "over-capacity cancellation rejection must be counted");
                check(adapter.activeGlobalPayloadBytes() == 0,
                        "saturation does not retain request payload bytes");
                check(cancellationsStarted.get() == 2,
                        "bounded workers must not start queued cancellations before release");
            } finally {
                releaseCallbacks.countDown();
            }
            served.get(3, TimeUnit.SECONDS);
            awaitTrue(() -> cancellationsStarted.get() == 3, 1000,
                    "the queued callback should be attempted when workers recover");
            check(adapter.activeConnections() == 0,
                    "cancellation saturation must not strand the TCP connection");
        }
    }

    @SuppressWarnings("removal")
    private static void fatalCancellationFailureClassification() {
        UnaryTcpSessionAdapter.rethrowJvmFatal(
                new IllegalStateException("ordinary callback failure"));
        UnaryTcpSessionAdapter.rethrowJvmFatal(
                new AssertionError("ordinary hostile callback error"));

        for (Throwable fatal : new Throwable[] {
                new OutOfMemoryError("synthetic"),
                new LinkageError("synthetic"),
                new ThreadDeath()
        }) {
            try {
                UnaryTcpSessionAdapter.rethrowJvmFatal(fatal);
                throw new AssertionError(
                        "fatal JVM cancellation failure was swallowed: "
                                + fatal.getClass().getSimpleName());
            } catch (Throwable observed) {
                check(observed == fatal,
                        "fatal JVM cancellation classifier must rethrow identity");
            }
        }
    }

    public static void main(String[] unused) throws Exception {
        compactActorPayloadProjection();
        fatalCancellationFailureClassification();
        echoAndReplayFence();
        wrongBindingRejectedBeforePayloadRead();
        overloadCancelAndRecovery();
        overloadRejectedIdCannotReplay();
        deadlineCancelsDispatch();
        globalBudgetAcrossConnections();
        responseBudgetIsPerConnection();
        authenticationFailureAndConnectionCapacity();
        completionCancelRaces();
        terminalWriteDeadlineClosesSlowReader();
        sessionCloseCancelsPending();
        adapterCloseUnblocksAuthentication();
        throwingCancellationCannotStrandCredits();
        blockingCancellationCannotStallDeadlines();
        boundedCancellationSaturationIsObservable();
        absoluteReadDeadlinesRejectStalledPeers();
        System.out.println("PASS: authenticated unary TCP sessions, request/response budgets, deadlines and 250 completion/cancel races");
    }
}
