// Forked-process, real Unix-domain socket -> actual M6 bounded actor probe.
// Host test only. No compiled .atto sender/receiver, async reactor, durable
// provider, protocol standardization, or secure multi-tenancy claimed.
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class AttoM6ProxyUdsV1Probe {
    private AttoM6ProxyUdsV1Probe() {}
    private static void require(boolean ok, String context) {
        if (!ok) throw new AssertionError(context);
    }
    private static byte[] test_key() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 7 + 37);
        return key;
    }
    private static ProxyReceiptGateV1.Identity route(long messageId) {
        return new ProxyReceiptGateV1.Identity(1, 11, 77, 17, 1, 31, 6, messageId, 1);
    }
    private static void server(Path path) throws Exception {
        var turns = new ArrayBlockingQueue<AttoM6ProxyUdsV1.Turn>(8);
        var counter = new AtomicLong();
        var handlerThread = new AtomicReference<String>();
        var session = new AttoM6ProxyUdsV1.Session(test_key());
        boolean compiledGuest = Boolean.getBoolean("atto.proxy.compiled_guest");
        // This is a *generated* Java class compiled from the pinned .atto
        // fixture before startup; no source interpretation occurs in process B.
        // Reflection here is test bootstrap only, not a Graal AOT contract.
        final java.util.function.LongUnaryOperator receiver = compiledGuest
            ? (java.util.function.LongUnaryOperator) Class
                .forName("AttoM6ProxyGuestCounter").getDeclaredConstructor().newInstance()
            : amount -> Math.addExact(counter.get(), amount);
        try (var runtime = new AttoM6Runtime(2, 3);
             var listener = AttoM6ProxyUdsV1.bind_private(path)) {
            Object socketKey = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
            var target = runtime.spawn("counter", 2, amount -> {
                handlerThread.set(Thread.currentThread().getName());
                require(turns.offer(new AttoM6ProxyUdsV1.Turn(
                    ProxyReceiptGateV1.Event.STARTED, 0)), "start event queue overflow");
                long after = receiver.applyAsLong(amount);
                counter.set(after);
                require(turns.offer(new AttoM6ProxyUdsV1.Turn(
                    ProxyReceiptGateV1.Event.PROCESSED, after)), "terminal event queue overflow");
            });
            require(target.generation() == 1, "unexpected actor incarnation");
            var replayWindow = new HashSet<String>();
            try {
                require(AttoM6ProxyUdsV1.serve_one(listener, session, runtime, target,
                    route(101), turns, replayWindow), "first destination admission");
                require(AttoM6ProxyUdsV1.serve_one(listener, session, runtime, target,
                    route(102), turns, replayWindow), "second destination completion");
                require(!AttoM6ProxyUdsV1.serve_one(listener, session, runtime, target,
                    route(102), turns, replayWindow), "replay was admitted");
                require(!AttoM6ProxyUdsV1.serve_one(listener, session, runtime, target,
                    route(103), turns, replayWindow), "cross-target delivery was admitted");
                require(!AttoM6ProxyUdsV1.serve_one(listener, session, runtime, target,
                    route(106), turns, replayWindow), "cross-tenant delivery was admitted");
                boolean rejectedMac = false;
                try {
                    AttoM6ProxyUdsV1.serve_one(listener, session, runtime, target,
                        route(104), turns, replayWindow);
                } catch (IOException unauthenticated) {
                    rejectedMac = true;
                }
                require(rejectedMac, "bad HMAC admitted to mailbox");
                require(AttoM6ProxyUdsV1.serve_one(listener, session, runtime, target,
                    route(105), turns, replayWindow), "actual remote panic delivery");
                require(runtime.await_terminal(target, 10_000), "remote actor PANIC control");
                var s = runtime.snapshot(target);
                AttoM6Runtime.check_conservation(s);
                require(s.accepted() == 3 && s.completed() == 2 && s.failed() == 1 &&
                    s.controlsDelivered() == 1 &&
                    s.lastControl() == AttoM6Runtime.ControlKind.PANIC,
                    "real M6 fault / independent PANIC CONTROL mismatch: " + s);
                require(counter.get() == 15, "real destination actor did not handle 7 then 8");
                require(handlerThread.get() != null &&
                    handlerThread.get().startsWith("atto-m6-carrier-"),
                    "actor handler ran on the I/O thread rather than M6 scheduler");
                require(replayWindow.size() == 3, "replay budget state mismatch");
                System.out.println("ATTO_PROXY_UDS_DESTINATION_M6_RECEIVE=PASS");
                System.out.println("ATTO_PROXY_UDS_REMOTE_ADMITTED_THEN_PROCESSED=PASS");
                System.out.println("ATTO_PROXY_UDS_BAD_MAC_REPLAY_WRONG_TARGET_WRONG_TENANT=REJECTED");
                System.out.println("ATTO_PROXY_UDS_M6_SUPERVISOR_PANIC_CONTROL=PASS");
                System.out.println("ATTO_PROXY_UDS_PROCESSED_AFTER_M6_COMMIT=PASS");
                if (compiledGuest) System.out.println(
                    "ATTO_PROXY_UDS_COMPILED_ATTO_PROCESS_B=PASS");
            } finally {
                AttoM6ProxyUdsV1.unlink_owned(path, socketKey);
            }
        }
    }
    private static void client(Path path) throws Exception {
        var session = new AttoM6ProxyUdsV1.Session(test_key());
        var admitted = AttoM6ProxyUdsV1.send(path, session, route(101), 7,
            ProxyReceiptGateV1.Stage.ADMITTED);
        require(admitted.stage() == ProxyReceiptGateV1.Stage.ADMITTED &&
            admitted.outcome() == ProxyReceiptGateV1.Outcome.PENDING,
            "sender misidentified proxy queue as destination admission");
        var processed = AttoM6ProxyUdsV1.send(path, session, route(102), 8,
            ProxyReceiptGateV1.Stage.PROCESSED);
        require(processed.stage() == ProxyReceiptGateV1.Stage.PROCESSED &&
            processed.outcome() == ProxyReceiptGateV1.Outcome.PENDING &&
            processed.result() == 15, "typed i64 result was not remote handler result");
        var replay = AttoM6ProxyUdsV1.send(path, session, route(102), 8,
            ProxyReceiptGateV1.Stage.PROCESSED);
        require(replay.outcome() == ProxyReceiptGateV1.Outcome.REJECTED,
            "replayed request accepted");
        var wrongId = new ProxyReceiptGateV1.Identity(
            1, 11, 77, 99, 1, 31, 6, 103, 1);
        var crossed = AttoM6ProxyUdsV1.send(path, session, wrongId, 1000,
            ProxyReceiptGateV1.Stage.PROCESSED);
        require(crossed.outcome() == ProxyReceiptGateV1.Outcome.REJECTED,
            "wrong-target authenticated request accepted");
        var foreignTenant = new ProxyReceiptGateV1.Identity(
            1, 12, 77, 17, 1, 31, 6, 106, 1);
        var tenantDenied = AttoM6ProxyUdsV1.send(path, session, foreignTenant, 1000,
            ProxyReceiptGateV1.Stage.PROCESSED);
        require(tenantDenied.outcome() == ProxyReceiptGateV1.Outcome.REJECTED,
            "wrong-tenant authenticated request accepted");
        byte[] wrongKey = new byte[32];
        Arrays.fill(wrongKey, (byte) 0xee);
        var rejectedMac = AttoM6ProxyUdsV1.send(path,
            new AttoM6ProxyUdsV1.Session(wrongKey), route(104), 1000,
            ProxyReceiptGateV1.Stage.PROCESSED);
        require(rejectedMac.outcome() == ProxyReceiptGateV1.Outcome.INDETERMINATE,
            "ambiguous connection close misreported as confirmed rejection");
        var failure = AttoM6ProxyUdsV1.send(path, session, route(105), Long.MAX_VALUE,
            ProxyReceiptGateV1.Stage.PROCESSED);
        require(failure.stage() == ProxyReceiptGateV1.Stage.STARTED &&
            failure.outcome() == ProxyReceiptGateV1.Outcome.FAILED,
            "remote panic was misreported as successfully Processed");
        System.out.println("ATTO_PROXY_UDS_SENDER_FORKED_PROCESS=PASS");
        System.out.println("ATTO_PROXY_UDS_PROCESS_B_TYPED_I64_REPLY=15");
        System.out.println("ATTO_PROXY_UDS_UNCERTAIN_SEND_NOT_REJECTED=PASS");
    }
    private static void launcher() throws Exception {
        Path directory = Files.createTempDirectory("atto-proxy-uds-v1-");
        Files.setPosixFilePermissions(directory, EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
        Path sock = directory.resolve("actor.sock");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("java.class.path");
        Process server = (Boolean.getBoolean("atto.proxy.compiled_guest")
            ? new ProcessBuilder(javaBin, "-Datto.proxy.compiled_guest=true", "-cp", cp,
                AttoM6ProxyUdsV1Probe.class.getName(), "--server", sock.toString())
            : new ProcessBuilder(javaBin, "-cp", cp,
                AttoM6ProxyUdsV1Probe.class.getName(), "--server", sock.toString()))
            .redirectErrorStream(true).start();
        Process client = null;
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            // A bound socket path exists before server chmod(0600) completes.
            // Do not race the sender against the credential/ACL admission step.
            while ((!Files.exists(sock, LinkOption.NOFOLLOW_LINKS) ||
                !Files.getPosixFilePermissions(sock).equals(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
                && server.isAlive() && System.nanoTime() < until)
                Thread.sleep(10);
            require(Files.exists(sock, LinkOption.NOFOLLOW_LINKS) &&
                Files.getPosixFilePermissions(sock).equals(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)),
                "server did not publish private UDS socket");
            client = new ProcessBuilder(javaBin, "-cp", cp,
                AttoM6ProxyUdsV1Probe.class.getName(), "--client", sock.toString())
                .redirectErrorStream(true).start();
            require(client.waitFor(30, TimeUnit.SECONDS), "client process timeout");
            String clientOut = new String(client.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            require(client.exitValue() == 0, "client failed: " + clientOut);
            require(clientOut.contains("ATTO_PROXY_UDS_PROCESS_B_TYPED_I64_REPLY=15"),
                "client did not observe real result: " + clientOut);
            require(server.waitFor(30, TimeUnit.SECONDS), "server process timeout");
            String serverOut = new String(server.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            require(server.exitValue() == 0, "server failed: " + serverOut);
            require(serverOut.contains("ATTO_PROXY_UDS_DESTINATION_M6_RECEIVE=PASS"),
                "server did not execute destination handler");
            if (Boolean.getBoolean("atto.proxy.compiled_guest"))
                require(serverOut.contains("ATTO_PROXY_UDS_COMPILED_ATTO_PROCESS_B=PASS"),
                    "receiver was not the compiled .atto actor: " + serverOut);
            require(!Files.exists(sock, LinkOption.NOFOLLOW_LINKS),
                "owned UDS path not cleaned");
            System.out.print(clientOut);
            System.out.print(serverOut);
            System.out.println("ATTO_PROXY_UDS_TWO_OS_PROCESSES=PASS");
            System.out.println(Boolean.getBoolean("atto.proxy.compiled_guest")
                ? "ATTO_PROXY_UDS_SCOPE=RESTRICTED_COMPILED_ATTO_RECEIVER_HOST_SENDER_ONLY"
                : "ATTO_PROXY_UDS_SCOPE=EXPERIMENTAL_HOST_M6_NO_COMPILED_ATTO_GUEST");
        } finally {
            if (client != null && client.isAlive()) client.destroyForcibly();
            if (server.isAlive()) server.destroyForcibly();
            if (client != null) client.waitFor();
            server.waitFor();
            if (!Files.exists(sock, LinkOption.NOFOLLOW_LINKS))
                Files.delete(directory);
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) launcher();
        else if (args.length == 2 && args[0].equals("--server")) server(Path.of(args[1]));
        else if (args.length == 2 && args[0].equals("--client")) client(Path.of(args[1]));
        else throw new IllegalArgumentException("exact fork-probe CLI only");
    }
}
