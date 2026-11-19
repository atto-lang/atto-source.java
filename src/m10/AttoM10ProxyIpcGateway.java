// M10 experimental Unix-domain-socket actor gateway: real OS IPC -> M6 mailbox.
// Host-side restricted scalar protocol only; NOT canonical Atto proxy wire ABI,
// general .atto actor execution, production peer authentication, TLS or TenantVM.
import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
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
    private final long tenant;
    private final byte[] key;
    private final Selector selector;
    private final ServerSocketChannel listener;
    private final Thread reactor;
    private final Map<SocketChannel, Session> sessions = new HashMap<>();
    private final Set<RequestKey> seen = new HashSet<>();
    private final ArrayBlockingQueue<Runnable> commands = new ArrayBlockingQueue<>(128);
    private volatile boolean stopping;
    private volatile boolean live;
    private volatile boolean overload;

    public AttoM10ProxyIpcGateway(AttoM6Runtime runtime, AttoM6Runtime.ActorRef target,
                                   Path socketPath, long tenant, byte[] key) throws IOException {
        if (runtime == null || target == null || socketPath == null || tenant <= 0)
            throw new IllegalArgumentException("invalid authorized gateway");
        validate_secret(key);
        if (!socketPath.isAbsolute() || Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("socket path not absolute or already occupied");
        Path parent = socketPath.getParent();
        if (parent == null || Files.isSymbolicLink(parent) || !Files.isDirectory(parent) ||
            Files.getPosixFilePermissions(parent).contains(PosixFilePermission.OTHERS_WRITE) ||
            Files.getPosixFilePermissions(parent).contains(PosixFilePermission.GROUP_WRITE))
            throw new IllegalArgumentException("socket parent must be private and non-symlink");
        this.runtime = runtime;
        this.target = target;
        this.socketPath = socketPath;
        this.tenant = tenant;
        this.key = key.clone();
        this.selector = Selector.open();
        try {
            this.listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            this.listener.configureBlocking(false);
            this.listener.bind(UnixDomainSocketAddress.of(socketPath));
            Files.setPosixFilePermissions(socketPath, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
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
            if (f.tenant() != tenant || f.generation() != target.generation() ||
                f.message_id() <= 0 || f.attempt() <= 0) {
                phase = 3;
                enqueue(new Frame(REJECTED, f.tenant(), f.generation(),
                                  f.message_id(), f.attempt(), 3));
                return;
            }
            if (phase == 0 && f.kind() == HELLO) {
                hello = f;
                phase = 1;
                enqueue(new Frame(HELLO_OK, tenant, target.generation(),
                                  f.message_id(), f.attempt(), ProcessHandle.current().pid()));
            } else if (phase == 1 && f.kind() == SEND && same_request(hello, f)) {
                RequestKey request = new RequestKey(f.message_id(), f.attempt());
                if (seen.contains(request) || seen.size() >= MAX_REQUEST_IDENTITIES) {
                    phase = 3;
                    enqueue(new Frame(REJECTED, tenant, target.generation(),
                                      f.message_id(), f.attempt(), 4));
                    return;
                }
                CompletableFuture<AttoM6Runtime.TurnResult> completion =
                    new CompletableFuture<>();
                AttoM6Runtime.Admission outcome =
                    runtime.try_send_tracked(target, f.value(), completion);
                if (outcome != AttoM6Runtime.Admission.ACCEPTED) {
                    phase = 3;
                    enqueue(new Frame(REJECTED, tenant, target.generation(),
                        f.message_id(), f.attempt(),
                        outcome == AttoM6Runtime.Admission.FULL ? 1 : 2));
                    return;
                }
                // Only the actual destination mailbox can make this true.
                seen.add(request);
                phase = 2;
                enqueue(new Frame(ADMITTED, tenant, target.generation(),
                                  f.message_id(), f.attempt(), 0));
                completion.whenCompleteAsync((turn, err) -> {
                    boolean scheduled = commands.offer(() -> {
                        if (closed) return;
                        try {
                            byte kind = err != null ? FAILED :
                                turn.outcome() == AttoM6Runtime.TurnOutcome.PROCESSED ?
                                    PROCESSED :
                                turn.outcome() == AttoM6Runtime.TurnOutcome.FAILED ?
                                    FAILED : ABANDONED;
                            long result = kind == PROCESSED ? turn.reply() : 0;
                            phase = 3;
                            enqueue(new Frame(kind, tenant, target.generation(),
                                              f.message_id(), f.attempt(), result));
                        } catch (IOException io) { close(); }
                    });
                    if (!scheduled) overload = true; // fail closed at next reactor wake
                    selector.wakeup();
                });
            } else {
                phase = 3;
                enqueue(new Frame(REJECTED, tenant, target.generation(),
                                  f.message_id(), f.attempt(), 5));
            }
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

    private void accept() throws IOException {
        for (int i = 0; i < 16; i++) {
            SocketChannel next = listener.accept();
            if (next == null) return;
            if (sessions.size() >= MAX_SESSIONS) { next.close(); continue; }
            try {
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
                Runnable task;
                while ((task = commands.poll()) != null) task.run();
                if (overload) {
                    // Never promise success if the completion queue cannot retain it.
                    for (Session s : java.util.List.copyOf(sessions.values())) s.close();
                    overload = false;
                }
                selector.select(100);
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
                for (Session s : java.util.List.copyOf(sessions.values()))
                    if (System.nanoTime() - s.deadline >= 0) s.close();
            }
        } catch (IOException fatal) {
            // Test-only reactor fail-closed. Never synthesize destination success.
            throw new IllegalStateException("IPC gateway reactor failure", fatal);
        } finally {
            for (Session s : java.util.List.copyOf(sessions.values())) s.close();
            try { listener.close(); } catch (IOException ignored) { }
            try { selector.close(); } catch (IOException ignored) { }
            Arrays.fill(key, (byte) 0);
            try { Files.deleteIfExists(socketPath); } catch (IOException ignored) { }
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
