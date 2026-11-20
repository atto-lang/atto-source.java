// EXPERIMENTAL single-connection UDS -> M6 mailbox integration slice.
// NOT the canonical Atto IPC wire ABI, production reactor, typed guest compiler,
// durable delivery, arbitrary ActorRegistry routing, or tenant sandbox.
// Host I/O callers, never actor carriers, own the sockets. This slice intentionally
// supports only a bounded i64 message and one destination incarnation.
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jdk.net.ExtendedSocketOptions;
import jdk.net.UnixDomainPrincipal;

public final class AttoM6ProxyUdsV1 {
    private AttoM6ProxyUdsV1() {}
    private static final int REQUEST_LENGTH = 4 + 8 * 9 + 32;
    private static final int RECEIPT_LENGTH = 4 + 8 * 8 + 1 + 8 + 32;
    private static final int MAX_FRAME = 256;
    public record Turn(ProxyReceiptGateV1.Event event, long result) {}
    public record Delivery(ProxyReceiptGateV1.Stage stage,
                           ProxyReceiptGateV1.Outcome outcome, long result) {}
    public static final class Session {
        private final byte[] key;
        public Session(byte[] key) {
            if (key == null || key.length != 32)
                throw new IllegalArgumentException("test session key must be 32 bytes");
            this.key = key.clone();
        }
        private byte[] mac(byte[] data) {
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(key, "HmacSHA256"));
                return m.doFinal(data);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException("HMAC unavailable", e);
            }
        }
        private byte[] seal(byte[] body) {
            byte[] tag = mac(body);
            byte[] sealed = Arrays.copyOf(body, body.length + tag.length);
            System.arraycopy(tag, 0, sealed, body.length, tag.length);
            return sealed;
        }
        private byte[] authenticate(byte[] sealed, int exactSize) throws IOException {
            if (sealed.length != exactSize) throw new IOException("wrong frame size");
            byte[] body = Arrays.copyOf(sealed, sealed.length - 32);
            byte[] received = Arrays.copyOfRange(sealed, body.length, sealed.length);
            if (!MessageDigest.isEqual(mac(body), received))
                throw new IOException("unauthenticated frame");
            return body;
        }
    }
    private static void write_frame(SocketChannel channel, byte[] bytes) throws IOException {
        if (bytes.length > MAX_FRAME) throw new IOException("outbound frame exceeds cap");
        DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }
    private static byte[] read_frame(SocketChannel channel) throws IOException {
        DataInputStream in = new DataInputStream(Channels.newInputStream(channel));
        int length = in.readInt(); // cap before allocation
        if (length <= 0 || length > MAX_FRAME) throw new IOException("frame cap");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
    private static void put_identity(ByteBuffer b, ProxyReceiptGateV1.Identity i) {
        b.putInt(i.protocolMajor()).putLong(i.tenant()).putLong(i.callerIncarnation())
            .putLong(i.targetActor()).putLong(i.targetIncarnation())
            .putLong(i.associationGeneration()).putLong(i.placementEpoch())
            .putLong(i.messageId()).putLong(i.attempt());
    }
    private static ProxyReceiptGateV1.Identity get_identity(ByteBuffer b) {
        return new ProxyReceiptGateV1.Identity(b.getInt(), b.getLong(), b.getLong(),
            b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getLong());
    }
    public static byte[] encode_request(Session session,
        ProxyReceiptGateV1.Identity id, long message) {
        if (!id.valid()) throw new IllegalArgumentException("invalid route");
        ByteBuffer b = ByteBuffer.allocate(REQUEST_LENGTH - 32);
        put_identity(b, id);
        b.putLong(message);
        return session.seal(b.array());
    }
    public static byte[] encode_receipt(Session session,
        ProxyReceiptGateV1.Identity id, Turn turn) {
        if (!id.valid() || turn == null || turn.event() == null)
            throw new IllegalArgumentException("invalid receipt");
        ByteBuffer b = ByteBuffer.allocate(RECEIPT_LENGTH - 32);
        put_identity(b, id);
        b.put((byte) turn.event().ordinal()).putLong(turn.result());
        return session.seal(b.array());
    }
    private record Request(ProxyReceiptGateV1.Identity identity, long message) {}
    private record Receipt(ProxyReceiptGateV1.Identity identity, Turn turn) {}
    private static Request decode_request(Session s, byte[] frame) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(s.authenticate(frame, REQUEST_LENGTH));
        var id = get_identity(b);
        if (!id.valid()) throw new IOException("invalid request route");
        return new Request(id, b.getLong());
    }
    private static Receipt decode_receipt(Session s, byte[] frame) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(s.authenticate(frame, RECEIPT_LENGTH));
        var id = get_identity(b);
        int ordinal = Byte.toUnsignedInt(b.get());
        if (!id.valid() || ordinal >= ProxyReceiptGateV1.Event.values().length)
            throw new IOException("invalid receipt route/event");
        return new Receipt(id,
            new Turn(ProxyReceiptGateV1.Event.values()[ordinal], b.getLong()));
    }
    private static Set<PosixFilePermission> owner_only() {
        return EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }
    private static void check_directory(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(dir)
            || !Files.getPosixFilePermissions(dir).equals(EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE))
            || !Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).getName()
                .equals(System.getProperty("user.name")))
            throw new IOException("UDS parent must be owned, private and real");
    }
    public static ServerSocketChannel bind_private(Path path) throws IOException {
        Objects.requireNonNull(path);
        check_directory(path.toAbsolutePath().getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("socket path already exists");
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(path));
            Files.setPosixFilePermissions(path, owner_only());
            if (!Files.getPosixFilePermissions(path).equals(owner_only()))
                throw new IOException("UDS socket permissions not private");
            return server;
        } catch (IOException | RuntimeException e) {
            server.close();
            throw e;
        }
    }
    private static void peer_same_user(SocketChannel channel) throws IOException {
        try {
            UnixDomainPrincipal principal = channel.getOption(ExtendedSocketOptions.SO_PEERCRED);
            if (principal == null ||
                !principal.user().getName().equals(System.getProperty("user.name")))
                throw new IOException("UDS peer credentials rejected");
        } catch (UnsupportedOperationException e) {
            throw new IOException("UDS peer credentials unavailable: fail closed", e);
        }
    }
    // This server entry point must be called from dedicated trusted host-I/O,
    // not from a scheduler receive callback. The caller provides a per-request
    // queue of *host-issued* started/processed/failed events, never guest-owned.
    // Any connection-level error after a possible enqueue is NOT definitive rejection.
    public static boolean serve_one(ServerSocketChannel server, Session session,
        AttoM6Runtime runtime, AttoM6Runtime.ActorRef target,
        ProxyReceiptGateV1.Identity expected,
        java.util.concurrent.BlockingQueue<Turn> hostTurns,
        Set<String> alreadySeen) throws IOException, InterruptedException {
        try (SocketChannel channel = server.accept()) {
            peer_same_user(channel);
            Request req = decode_request(session, read_frame(channel));
            var id = req.identity();
            String replay = id.callerIncarnation() + ":" + id.messageId() + ":" + id.attempt();
            boolean allowed = id.equals(expected) && id.targetIncarnation() == target.generation()
                && !alreadySeen.contains(replay);
            if (!allowed) {
                write_frame(channel, encode_receipt(session, id,
                    new Turn(ProxyReceiptGateV1.Event.REJECTED, 0)));
                return false;
            }
            // The only positive admission authority: atomic destination mailbox enqueue.
            var admission = runtime.try_send(target, req.message());
            if (admission != AttoM6Runtime.Admission.ACCEPTED) {
                write_frame(channel, encode_receipt(session, id,
                    new Turn(ProxyReceiptGateV1.Event.REJECTED, 0)));
                return false;
            }
            alreadySeen.add(replay);
            write_frame(channel, encode_receipt(session, id,
                new Turn(ProxyReceiptGateV1.Event.ADMITTED, 0)));
            // The guest handler is not run on this I/O thread. A bridge-generated
            // host turn wrapper reports start and terminal outcome from M6 carrier.
            for (int n = 0; n < 2; n++) {
                Turn next = hostTurns.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                if (next == null) throw new IOException("destination turn deadline");
                if (n == 0 && next.event() != ProxyReceiptGateV1.Event.STARTED)
                    throw new IOException("missing scheduler-start evidence");
                if (n == 1 && next.event() != ProxyReceiptGateV1.Event.PROCESSED &&
                    next.event() != ProxyReceiptGateV1.Event.FAILED)
                    throw new IOException("missing terminal turn evidence");
                try {
                    write_frame(channel, encode_receipt(session, id, next));
                } catch (IOException closedAfterAdmit) {
                    // Sender may close after ADMITTED; delivery still happened.
                    // This close is not an authoritative rejection of the admitted mail.
                    if (n == 1) return true;
                    // Continue draining terminal evidence even if the sender closes.
                }
            }
            return true;
        }
    }
    public static Delivery send(Path path, Session session,
        ProxyReceiptGateV1.Identity expected, long message,
        ProxyReceiptGateV1.Stage awaitStage) throws IOException {
        if (awaitStage != ProxyReceiptGateV1.Stage.ADMITTED
            && awaitStage != ProxyReceiptGateV1.Stage.PROCESSED)
            throw new IllegalArgumentException("supported receipt stage");
        check_directory(path.toAbsolutePath().getParent());
        if (Files.isSymbolicLink(path) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            || !Files.getPosixFilePermissions(path).equals(owner_only()))
            throw new IOException("untrusted destination socket");
        ProxyReceiptGateV1 gate = new ProxyReceiptGateV1(expected);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(path));
            peer_same_user(channel);
            write_frame(channel, encode_request(session, expected, message));
            for (int n = 0; n < 3; n++) {
                Receipt receipt = decode_receipt(session, read_frame(channel));
                if (!gate.apply_verified(receipt.identity(), receipt.turn().event()))
                    throw new IOException("invalid authenticated receipt stage or route");
                if (gate.outcome() != ProxyReceiptGateV1.Outcome.PENDING)
                    return new Delivery(gate.stage(), gate.outcome(), 0);
                if (gate.stage().ordinal() >= awaitStage.ordinal())
                    return new Delivery(gate.stage(), gate.outcome(), receipt.turn().result());
            }
            throw new IOException("missing requested receipt");
        } catch (IOException e) {
            // A lost connection cannot prove that destination did not admit/commit.
            gate.apply_verified(expected, ProxyReceiptGateV1.Event.INDETERMINATE);
            return new Delivery(gate.stage(), gate.outcome(), 0);
        }
    }
    // A caller must unlink only the inode it created; never delete a replaced path.
    public static void unlink_owned(Path path, Object pinnedFileKey) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(path)
            && Objects.equals(Files.readAttributes(path,
                java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey(), pinnedFileKey))
            Files.delete(path);
    }
}
