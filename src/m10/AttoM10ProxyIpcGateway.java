// M10 experimental Unix-domain-socket actor gateway: real OS IPC -> M6 mailbox.
// Host-side restricted scalar protocol only; NOT canonical Atto proxy wire ABI,
// general .atto actor execution, production peer authentication, TLS or TenantVM.
import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import jdk.net.UnixDomainPrincipal;
import jdk.net.ExtendedSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AttoM10ProxyIpcGateway implements AutoCloseable {
    private static final int MAGIC = 0x4154544f; // ATTO, restricted host test protocol.
    private static final short VERSION = 1;
    public static final byte HELLO = 1, HELLO_OK = 2, SEND = 3,
        ADMITTED = 4, PROCESSED = 5, REJECTED = 6, FAILED = 7, ABANDONED = 8;
    public static final int FRAME_BYTES = 84;
    private static final int MAX_SESSIONS = 64;
    private static final int MAX_REQUEST_IDENTITIES = 1024;
    private static final long SESSION_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(5);

    public record Frame(byte kind, long tenant, long generation, long message_id,
                        long attempt, long value) {}
    public record Delivery(long remote_pid, long processed_result) {}
    private record RequestKey(long message_id, long attempt) {}
    public static final class RemoteRejected extends IOException {
        private static final long serialVersionUID = 1L;
        public final long reason;
        public RemoteRejected(long reason) {
            super("remote destination rejected request, reason=" + reason);
            this.reason = reason;
        }
    }

    // The path is an explicitly authorized host configuration, not a guest-supplied URI.
    private final AttoM6Runtime runtime;
    private final AttoM6Runtime.ActorRef target;
    private final Path socketPath;
    private final Object socketFileKey;
    private final long tenant;
    // Fresh server-issued actor association incarnation, not an OS PID or
    // per-runtime generation counter (which may restart at one).
    private final long incarnation;
    private final byte[] key;
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final Thread reactor;
    private final Map<SocketChannel, Session> sessions = new HashMap<>();
    private final Set<RequestKey> seen = new HashSet<>();
    private volatile boolean stopping;
    private volatile boolean live;

    public AttoM10ProxyIpcGateway(AttoM6Runtime runtime, AttoM6Runtime.ActorRef target,
                                   Path socketPath, long tenant, long incarnation,
                                   byte[] key) throws IOException {
        if (runtime == null || target == null || socketPath == null ||
            tenant <= 0 || incarnation <= 0)
            throw new IllegalArgumentException("invalid authorized gateway");
        validate_secret(key);
        if (!socketPath.isAbsolute() || Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("socket path not absolute or already occupied");
        Path parent = socketPath.getParent();
        if (parent == null || Files.isSymbolicLink(parent) || !Files.isDirectory(parent) ||
            !Files.getPosixFilePermissions(parent).equals(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE)))
            throw new IllegalArgumentException("socket parent must be private and non-symlink");
        this.runtime = runtime;
        this.target = target;
        this.socketPath = socketPath;
        this.tenant = tenant;
        this.incarnation = incarnation;
        this.key = key.clone();
        this.selector = Selector.open();
        try {
            this.listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            this.listener.configureBlocking(false);
            this.listener.bind(UnixDomainSocketAddress.of(socketPath));
            Files.setPosixFilePermissions(socketPath, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            this.socketFileKey = Files.readAttributes(socketPath, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
            if (this.socketFileKey == null)
                throw new IOException("platform cannot pin listener socket inode");
            this.listener.register(selector, SelectionKey.OP_ACCEPT);
        } catch (IOException | RuntimeException failure) {
            try { selector.close(); } catch (IOException ignored) { }
            throw failure;
        }
        reactor = new Thread(this::run_reactor, "atto-m10-ipc-host-reactor");
        reactor.start();
    }

    private static void validate_secret(byte[] secret) {
        if (secret == null || secret.length != 32)
            throw new IllegalArgumentException("32-byte provisioned test session key required");
        int nonzero = 0;
        for (byte b : secret) nonzero |= b;
        if (nonzero == 0) throw new IllegalArgumentException("zero session key rejected");
    }

    private static byte[] mac(byte[] full, byte[] key, String actorName)
            throws IOException {
        try {
            Mac signer = Mac.getInstance("HmacSHA256");
            signer.init(new SecretKeySpec(key, "HmacSHA256"));
            signer.update(actorName.getBytes(StandardCharsets.UTF_8));
            signer.update(full, 4, FRAME_BYTES - 36);
            return signer.doFinal();
        } catch (GeneralSecurityException failure) {
            throw new IOException("MAC provider unavailable", failure);
        }
    }
    private static ByteBuffer encode(Frame frame, byte[] key, String actorName)
            throws IOException {
        ByteBuffer b = ByteBuffer.allocate(FRAME_BYTES);
        b.putInt(FRAME_BYTES - 4).putInt(MAGIC).putShort(VERSION);
        b.put(frame.kind()).put((byte) 0); // reserved flags, must remain zero
        b.putLong(frame.tenant()).putLong(frame.generation());
        b.putLong(frame.message_id()).putLong(frame.attempt()).putLong(frame.value());
        byte[] bytes = b.array();
        b.put(mac(bytes, key, actorName));
        b.flip();
        return b;
    }
    private static Frame decode(ByteBuffer b, byte[] key, String actorName)
            throws IOException {
        if (b.remaining() != FRAME_BYTES) throw new IOException("truncated frame");
        byte[] bytes = new byte[FRAME_BYTES];
        b.get(bytes);
        ByteBuffer body = ByteBuffer.wrap(bytes);
        if (body.getInt() != FRAME_BYTES - 4 || body.getInt() != MAGIC ||
            body.getShort() != VERSION)
            throw new IOException("unknown protocol or oversized frame");
        byte kind = body.get();
        if (body.get() != 0) throw new IOException("unexpected reserved flag");
        byte[] provided = Arrays.copyOfRange(bytes, FRAME_BYTES - 32, FRAME_BYTES);
        if (!MessageDigest.isEqual(provided, mac(bytes, key, actorName)))
            throw new IOException("untrusted frame authentication");
        return new Frame(kind, body.getLong(), body.getLong(), body.getLong(),
                         body.getLong(), body.getLong());
    }
    private static boolean same_request(Frame one, Frame two) {
        return one.tenant() == two.tenant() &&
               one.generation() == two.generation() &&
               one.message_id() == two.message_id() &&
               one.attempt() == two.attempt();
    }

    private final class Session {
        final SocketChannel socket;
        final SelectionKey selection;
        final ByteBuffer input = ByteBuffer.allocate(FRAME_BYTES);
        final ArrayDeque<ByteBuffer> output = new ArrayDeque<>();
        final long deadline = System.nanoTime() + SESSION_LIFETIME_NANOS;
        Frame hello;
        Frame pendingRequest;
        AttoM6Runtime.Ticket pendingTicket;
        int phase; // 0 HELLO, 1 SEND, 2 waiting for actor turn, 3 closing
        boolean closed;
        Session(SocketChannel socket) throws IOException {
            this.socket = socket;
            socket.configureBlocking(false);
            selection = socket.register(selector, SelectionKey.OP_READ, this);
        }
        void enqueue(Frame response) throws IOException {
            if (closed || output.size() >= 2) throw new IOException("bounded output overflow");
            output.addLast(encode(response, key, target.name()));
            rearm();
        }
        void rearm() {
            if (!closed && selection.isValid())
                selection.interestOps((phase <= 1 ? SelectionKey.OP_READ : 0) |
                                       (!output.isEmpty() ? SelectionKey.OP_WRITE : 0));
        }
        void close() {
            if (closed) return;
            closed = true;
            selection.cancel();
            sessions.remove(socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
        void read() throws IOException {
            int budget = 4;
            while (budget-- > 0 && phase <= 1) {
                int n = socket.read(input);
                if (n < 0) throw new EOFException("peer disconnected");
                if (n == 0) break;
                if (!input.hasRemaining()) {
                    input.flip();
                    Frame incoming = decode(input, key, target.name());
                    input.clear();
                    accept_frame(incoming);
                }
            }
        }
        void accept_frame(Frame f) throws IOException {
            if (f.tenant() != tenant || f.generation() != incarnation ||
                f.message_id() <= 0 || f.attempt() <= 0) {
                phase = 3;
                enqueue(new Frame(REJECTED, f.tenant(), f.generation(),
                                  f.message_id(), f.attempt(), 3));
                return;
            }
            if (phase == 0 && f.kind() == HELLO) {
                hello = f;
                phase = 1;
                enqueue(new Frame(HELLO_OK, tenant, incarnation,
                                  f.message_id(), f.attempt(), ProcessHandle.current().pid()));
            } else if (phase == 1 && f.kind() == SEND && same_request(hello, f)) {
                RequestKey request = new RequestKey(f.message_id(), f.attempt());
                if (seen.contains(request) || seen.size() >= MAX_REQUEST_IDENTITIES) {
                    phase = 3;
                    enqueue(new Frame(REJECTED, tenant, incarnation,
                                      f.message_id(), f.attempt(), 4));
                    return;
                }
                // M10's 84-byte experimental frame does NOT contain a complete
                // canonical Atto capability/route. The gateway has already
                // authenticated the HMAC, tenant and server-issued incarnation.
                // Fill the missing fields from HOST-OWNED configuration only;
                // these are correlation bindings, NEVER authorization tokens.
                var route = new AttoM6Runtime.ReceiptRoute(
                    1, tenant, incarnation,
                    Integer.toUnsignedLong(target.name().hashCode()) + 1L,
                    target.generation(), incarnation, incarnation,
                    f.message_id(), f.attempt());
                var admitted = runtime.try_send_ticket(target, f.value(), route);
                if (admitted.admission() != AttoM6Runtime.Admission.ACCEPTED) {
                    phase = 3;
                    enqueue(new Frame(REJECTED, tenant, incarnation,
                        f.message_id(), f.attempt(),
                        admitted.admission() == AttoM6Runtime.Admission.FULL ? 1 : 2));
                    return;
                }
                // Only the destination's atomic mailbox admission mints a
                // private per-MAIL ticket. Neither a callback nor actor-wide
                // completed/failed counters can authorize a terminal response.
                if (!runtime.ticket_route(admitted.ticket()).equals(route))
                    throw new IOException("destination-scoped ticket route mismatch");
                seen.add(request);
                pendingRequest = f;
                pendingTicket = admitted.ticket();
                phase = 2;
                enqueue(new Frame(ADMITTED, tenant, incarnation,
                                  f.message_id(), f.attempt(), 0));
            } else {
                phase = 3;
                enqueue(new Frame(REJECTED, tenant, incarnation,
                                  f.message_id(), f.attempt(), 5));
            }
        }
        // This runs on the single NIO selector thread, with NO waiting
        // for a carrier or executor callback. A bounded 64-session scan
        // observes the exact runtime-issued mailbox ticket. The client
        // deadline stays authoritative if the actor is slow or disconnected.
        void poll_ticket() throws IOException {
            if (closed || phase != 2 || pendingTicket == null || !output.isEmpty())
                return;
            var outcome = runtime.ticket_snapshot(pendingTicket);
            byte kind;
            long result = 0;
            switch (outcome.state()) {
            case ADMITTED, STARTED -> { return; }
            case PROCESSED -> {
                if (!outcome.hasResult())
                    throw new IOException("destination handler returned no typed result");
                kind = PROCESSED;
                result = outcome.result();
            }
            case FAILED -> kind = FAILED;
            case ABANDONED -> kind = ABANDONED;
            default -> throw new IOException("unknown destination mailbox terminal state");
            }
            // Refuse to emit a terminal ACK whose correlation is no longer
            // equal to the original host-authenticated SEND frame.
            if (pendingRequest == null ||
                runtime.ticket_route(pendingTicket).messageId() != pendingRequest.message_id() ||
                runtime.ticket_route(pendingTicket).attempt() != pendingRequest.attempt())
                throw new IOException("terminal receipt correlation mismatch");
            phase = 3;
            enqueue(new Frame(kind, tenant, incarnation,
                pendingRequest.message_id(), pendingRequest.attempt(), result));
            pendingTicket = null;
            pendingRequest = null;
        }
        void write() throws IOException {
            while (!output.isEmpty()) {
                ByteBuffer frame = output.peekFirst();
                socket.write(frame);
                if (frame.hasRemaining()) break;
                output.removeFirst();
            }
            if (phase == 3 && output.isEmpty()) close();
            else rearm();
        }
    }

    // Host-only same-UID UDS fence, independent of the test HMAC. Compare
    // the kernel-supplied peer to the actual private socket directory owner,
    // never the mutable Java system property "user.name". A production
    // TenantVM gateway still needs explicit per-tenant capability checks.
    private static void peer_same_user(SocketChannel channel, Path socket)
            throws IOException {
        Path parent = socket.getParent();
        if (parent == null || Files.isSymbolicLink(parent) ||
            !Files.getPosixFilePermissions(parent).equals(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE)))
            throw new IOException("untrusted UDS socket parent");
        var hostOwner = Files.getOwner(parent, LinkOption.NOFOLLOW_LINKS);
        try {
            UnixDomainPrincipal principal = channel.getOption(ExtendedSocketOptions.SO_PEERCRED);
            if (principal == null || !principal.user().equals(hostOwner))
                throw new IOException("untrusted UDS peer principal");
        } catch (UnsupportedOperationException unavailable) {
            throw new IOException("UDS peer credentials unavailable: fail closed", unavailable);
        }
    }

    private void accept() throws IOException {
        for (int i = 0; i < 16; i++) {
            SocketChannel next = listener.accept();
            if (next == null) return;
            if (sessions.size() >= MAX_SESSIONS) { next.close(); continue; }
            try {
                peer_same_user(next, socketPath);
                Session s = new Session(next);
                sessions.put(next, s);
            } catch (IOException | RuntimeException err) {
                try { next.close(); } catch (IOException ignored) { }
            }
        }
    }
    private void run_reactor() {
        live = true;
        try {
            while (!stopping) {
                // A short SELECT timeout keeps ticket settlement asynchronous
                // and bounded even if no socket becomes readable/writable.
                selector.select(20);
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                int budget = 64;
                while (keys.hasNext() && budget-- > 0) {
                    SelectionKey item = keys.next(); keys.remove();
                    try {
                        if (!item.isValid()) continue;
                        if (item.isAcceptable()) accept();
                        else {
                            Session s = (Session) item.attachment();
                            if (item.isReadable()) s.read();
                            if (!s.closed && item.isValid() && item.isWritable()) s.write();
                        }
                    } catch (IOException | RuntimeException fail) {
                        if (item.attachment() instanceof Session s) s.close();
                    }
                }
                for (Session s : java.util.List.copyOf(sessions.values())) {
                    try {
                        if (System.nanoTime() - s.deadline >= 0) s.close();
                        else s.poll_ticket();
                    } catch (IOException | RuntimeException failed) {
                        // Lost connection AFTER admission is indeterminate for
                        // sender; never change the issued M6 ticket to REJECTED.
                        s.close();
                    }
                }
            }
        } catch (IOException fatal) {
            // Test-only reactor fail-closed. Never synthesize destination success.
            throw new IllegalStateException("IPC gateway reactor failure", fatal);
        } finally {
            for (Session s : java.util.List.copyOf(sessions.values())) s.close();
            try { listener.close(); } catch (IOException ignored) { }
            try { selector.close(); } catch (IOException ignored) { }
            Arrays.fill(key, (byte) 0);
            try {
                // Never unlink another user's path substituted during shutdown.
                if (Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(socketPath) &&
                    Objects.equals(socketFileKey,
                        Files.readAttributes(socketPath, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).fileKey()))
                    Files.delete(socketPath);
            } catch (IOException ignored) { }
            live = false;
        }
    }

    public boolean is_live() { return live && !stopping; }
    @Override public void close() throws InterruptedException {
        stopping = true;
        selector.wakeup();
        if (Thread.currentThread() == reactor)
            throw new IllegalStateException("cannot join reactor on reactor thread");
        reactor.join(10_000);
        if (reactor.isAlive()) throw new IllegalStateException("IPC reactor failed to stop");
    }

    // Test client ONLY: intentionally blocking and forbidden on an actor carrier.
    // A production proxy will have its own nonblocking reactor-owned client path.
    public static Delivery send_request_blocking(Path socket, String actor, long tenant,
            long generation, long messageId, long attempt, long value, byte[] secret)
            throws IOException {
        if (Thread.currentThread().getName().startsWith("atto-m6-carrier-"))
            throw new IllegalStateException("blocking proxy client on actor carrier");
        validate_secret(secret);
        long deadline = System.nanoTime() + SESSION_LIFETIME_NANOS;
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.configureBlocking(false);
            try (Selector wait = Selector.open()) {
                channel.register(wait, SelectionKey.OP_CONNECT);
                channel.connect(UnixDomainSocketAddress.of(socket));
                while (!channel.finishConnect()) wait_ready(wait, SelectionKey.OP_CONNECT, deadline);
                peer_same_user(channel, socket);
                Frame hello = new Frame(HELLO, tenant, generation, messageId, attempt, 0);
                write_exact(channel, wait, encode(hello, secret, actor), deadline);
                Frame welcome = read_exact(channel, wait, secret, actor, deadline);
                if (welcome.kind() == REJECTED) throw new RemoteRejected(welcome.value());
                require_reply(welcome, hello, HELLO_OK);
                Frame send = new Frame(SEND, tenant, generation, messageId, attempt, value);
                write_exact(channel, wait, encode(send, secret, actor), deadline);
                Frame admitted = read_exact(channel, wait, secret, actor, deadline);
                if (admitted.kind() == REJECTED) throw new RemoteRejected(admitted.value());
                require_reply(admitted, send, ADMITTED);
                Frame finished = read_exact(channel, wait, secret, actor, deadline);
                require_reply(finished, send, finished.kind());
                if (finished.kind() != PROCESSED)
                    throw new IOException("destination turn failed/abandoned: " + finished.kind());
                return new Delivery(welcome.value(), finished.value());
            }
        }
    }
    private static void require_reply(Frame actual, Frame expected, byte kind)
            throws IOException {
        if (actual.kind() != kind || !same_request(actual, expected))
            throw new IOException("wrong stage or forged/crossed correlation");
    }
    private static void wait_ready(Selector wait, int interest, long deadline)
            throws IOException {
        for (SelectionKey k : wait.keys()) if (k.isValid()) k.interestOps(interest);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || wait.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))) == 0)
            throw new IOException("IPC deadline: outcome may be indeterminate");
        wait.selectedKeys().clear();
    }
    private static void write_exact(SocketChannel channel, Selector selector,
                                    ByteBuffer bytes, long deadline) throws IOException {
        while (bytes.hasRemaining()) {
            if (channel.write(bytes) == 0) wait_ready(selector, SelectionKey.OP_WRITE, deadline);
        }
    }
    private static Frame read_exact(SocketChannel channel, Selector selector,
                                    byte[] secret, String actor, long deadline)
            throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(FRAME_BYTES);
        while (bytes.hasRemaining()) {
            int n = channel.read(bytes);
            if (n < 0) throw new EOFException("remote closed: result indeterminate");
            if (n == 0) wait_ready(selector, SelectionKey.OP_READ, deadline);
        }
        bytes.flip();
        return decode(bytes, secret, actor);
    }
}
