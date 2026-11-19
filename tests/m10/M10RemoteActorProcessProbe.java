// Two independent JVM OS processes with actual M6 actor mailbox on server B.
// The server handler is generated from fixtures/m10/remote_adder.atto by a
// restrictive Java source compiler; no interpreter, Python, TLS or full .atto ABI.
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class M10RemoteActorProcessProbe {
    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
    private static void server(String[] args) throws Exception {
        require(args.length == 3, "server takes UDS endpoint and fresh incarnation only");
        Path socket = Path.of(args[1]);
        long incarnation = Long.parseLong(args[2]);
        BufferedReader stdin = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String keyHex = stdin.readLine(); // trusted test parent, not argv or guest
        require(keyHex != null && keyHex.length() == 64, "missing secret bootstrap");
        byte[] key = HexFormat.of().parseHex(keyHex);
        try (var runtime = new AttoM6Runtime(2, 8)) {
            var target = runtime.spawn_reply_actor("RemoteAdder", 2,
                                                   M10GeneratedActor.new_handler());
            require(target.generation() == 1, "unexpected initial local actor generation");
            try (var gateway = new AttoM10ProxyIpcGateway(runtime, target,
                                                         socket, 7, incarnation, key)) {
                Arrays.fill(key, (byte) 0);
                long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!gateway.is_live() && System.nanoTime() < readyDeadline)
                    Thread.sleep(1);
                require(gateway.is_live(), "gateway reactor never became ready");
                Path ready = Path.of(socket.toString() + ".ready");
                Files.writeString(ready, Long.toString(ProcessHandle.current().pid()));
                try {
                    require("stop".equals(stdin.readLine()),
                            "server did not receive stop command");
                } finally {
                    Files.deleteIfExists(ready);
                }
            }
            require(runtime.request_stop(target) && runtime.await_terminal(target, 5_000),
                    "server actor failed to retire");
        }
        System.out.println("M10_SERVER_RESTRICTED_ATTO_SOURCE_SHA256=" +
                           M10GeneratedActor.SOURCE_SHA256);
        System.out.println("M10_SECOND_PROCESS_ACTOR_MAILBOX_SERVER=PASS");
    }

    private static final class Server implements AutoCloseable {
        final Process process;
        final Path socket;
        Server(Path socket, long incarnation, byte[] key) throws IOException {
            this.socket = socket;
            process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                M10RemoteActorProcessProbe.class.getName(),
                "server", socket.toString(), Long.toString(incarnation))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
            process.getOutputStream().write(
                (HexFormat.of().formatHex(key) + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            Path ready = Path.of(socket.toString() + ".ready");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (!Files.exists(ready)) {
                if (!process.isAlive() || System.nanoTime() >= deadline)
                    throw new AssertionError("second JVM failed to start its gateway reactor");
                try { Thread.sleep(5); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted waiting for server", e);
                }
            }
            require(Files.exists(socket) &&
                    Long.toString(process.pid()).equals(Files.readString(ready)),
                    "untrusted or stale gateway readiness file");
        }
        @Override public void close() throws Exception {
            try {
                if (process.isAlive()) {
                    process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                    process.getOutputStream().flush();
                }
            } catch (IOException ignored) { }
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError("remote JVM did not quiesce in time");
            }
            require(process.exitValue() == 0, "remote JVM exit status: " + process.exitValue());
        }
    }

    private static AttoM10ProxyIpcGateway.Delivery send(Path path, long incarnation,
                                                        long id, long value, byte[] key)
            throws IOException {
        return AttoM10ProxyIpcGateway.send_request_blocking(
            path, "RemoteAdder", 7, incarnation, id, 1, value, key);
    }
    @FunctionalInterface private interface ThrowingCall { void run() throws Exception; }
    private static void fails(ThrowingCall action, String reason) throws Exception {
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("expected remote fail-closed behavior: " + reason);
    }
    private static void rejects(ThrowingCall action, long reason) throws Exception {
        try { action.run(); }
        catch (AttoM10ProxyIpcGateway.RemoteRejected rejected) {
            require(rejected.reason == reason, "wrong reject code: " + rejected.reason);
            return;
        }
        throw new AssertionError("remote gateway accepted a rejected operation");
    }

    private static void parent() throws Exception {
        require(StandardProtocolFamily.UNIX != null, "Unix socket support unavailable");
        String source = Files.readString(Path.of("fixtures/m10/remote_adder.atto"));
        String produced = AttoM10RestrictedActorCompiler.compile_source(source);
        require(produced.contains(M10GeneratedActor.SOURCE_SHA256),
                "server handler does not represent exact fixture source SHA");
        for (String invalid : new String[] {
            "define actor RemoteAdder as { pub fnc on_message(int value): int { return os.read_env(\"secret\"); } }",
            "define proxy actor RemoteAdder as { }",
            "define actor RemoteAdder as { pub fnc on_message(int value): int { return value + 10; } } evil",
        }) {
            try { AttoM10RestrictedActorCompiler.compile_source(invalid);
                  throw new AssertionError("untrusted guest syntax accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        Path directory = Files.createTempDirectory("atto-m10-private-ipc-");
        Files.setPosixFilePermissions(directory, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try {
            Path socket = directory.resolve("actor.sock");
            try (Server server = new Server(socket, 711, key)) {
                byte[] wrongKey = key.clone();
                wrongKey[0] ^= 42;
                fails(() -> send(socket, 711, 101, 32, wrongKey),
                      "wrong MAC key may not connect");
                rejects(() -> AttoM10ProxyIpcGateway.send_request_blocking(
                    socket, "RemoteAdder", 8, 711, 102, 1, 32, key), 3);
                rejects(() -> send(socket, 712, 103, 32, key), 3);
                fails(() -> AttoM10ProxyIpcGateway.send_request_blocking(
                    socket, "WrongActor", 7, 711, 104, 1, 32, key),
                      "cross-target actor name may not authenticate");
                var first = send(socket, 711, 105, 32, key);
                require(first.remote_pid() == server.process.pid(),
                        "ACK is not bound to second OS process PID");
                require(first.remote_pid() != ProcessHandle.current().pid(),
                        "actor executed in the calling process");
                require(first.processed_result() == 42,
                        "source-derived remote receive() returned wrong result");
                var next = send(socket, 711, 106, 10, key);
                require(next.processed_result() == 20,
                        "second message was not processed by real remote actor");
                rejects(() -> send(socket, 711, 105, 32, key), 4);
                fails(() -> send(socket, 711, 107, Long.MAX_VALUE, key),
                      "overflowing source-derived guest handler must fail");
                rejects(() -> send(socket, 711, 108, 1, key), 2);
            }
            require(!Files.exists(socket), "server left owned socket path behind");
            // Same logical ActorRef generation (1) in a fresh JVM must NOT allow
            // a stale remote association: require a fresh incarnation (712).
            try (Server restarted = new Server(socket, 712, key)) {
                rejects(() -> send(socket, 711, 200, 12, key), 3);
                var current = send(socket, 712, 201, 31, key);
                require(current.remote_pid() == restarted.process.pid(),
                        "new incarnation reply not from restarted server");
                require(current.processed_result() == 41,
                        "reconnected remote source handler failed");
            }
        } finally {
            Arrays.fill(key, (byte) 0);
            Files.deleteIfExists(directory.resolve("actor.sock"));
            Files.deleteIfExists(directory.resolve("actor.sock.ready"));
            Files.deleteIfExists(directory);
        }
        System.out.println("M10_TWO_REAL_JVM_PROCESSES_UDS_IPC=PASS");
        System.out.println("M10_RESTRICTED_COMPILED_ATTO_REMOTE_RECEIVE=PASS");
        System.out.println("M10_REMOTE_RECIPIENT_ADMITTED_AND_PROCESSED=PASS");
        System.out.println("M10_REMOTE_TENANT_TARGET_MAC_AND_VERSION_GATE=PARTIAL");
        System.out.println("M10_REMOTE_DUPLICATE_FAULT_AND_RESTART_FENCING=PASS");
        System.out.println("M10_SCOPE=RESTRICTED_ATTO_GUEST_JAVA_HOST_PROTOCOL_NOT_CANONICAL_BACKEND_OR_TLS");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0 && "server".equals(args[0])) server(args);
        else { require(args.length == 0, "unexpected test invocation"); parent(); }
    }
}
