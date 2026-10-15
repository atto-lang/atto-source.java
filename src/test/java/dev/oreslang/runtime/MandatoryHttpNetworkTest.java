package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class MandatoryHttpNetworkTest {
    @TempDir Path root;

    private Socket connect(int port, Future<?> program) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (;;) {
            try { Socket peer = new Socket("127.0.0.1", port); peer.setSoTimeout(5000); return peer; }
            catch (ConnectException notReady) {
                if (program.isDone()) program.get(1, TimeUnit.SECONDS);
                if (System.nanoTime() >= deadline) throw notReady;
                Thread.sleep(5); // Host test coordination; never guest transport work.
            }
        }
    }

    @Test void realOreslangHttpExecutesWithMandatoryAsyncIoAndNoGuestStdio() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        Path entry = root.resolve("http.ores");
        Files.writeString(entry, """
                import module net_core from "std/net";
                import module http_wire from "std/http";
                pub async routine main(): int {
                  val Future<NioTcpListener> opening = net_core.listen_loopback(%d, 64, 16, 256);
                  val NioTcpListener listener = await opening;
                  for (let int i = 0; i < 4; i = i + 1) {
                    val Future<NioTcpConnection> incoming = net_core.accept(rt borrow listener);
                    val NioTcpConnection peer = await incoming;
                    let mut Array<int> pending = new Array<int>();
                    loop {
                      val http_wire.Head head = http_wire.parse_head(rt borrow pending);
                      if head.status == 0; then
                        val Future<Array<int>> reading = net_core.read(rt borrow peer, 2048);
                        val Array<int> received = await reading;
                        if received.size == 0; then break; fi
                        for (const c of received) { pending.add(c); }
                      else
                        val Array<int> response = http_wire.response(head.status, head.keep_alive, head.head_only);
                        val Future<int> sending = net_core.write(rt borrow peer, response);
                        val int written = await sending;
                        if head.status != 200 || !head.keep_alive; then break; fi
                        let mut Array<int> rest = new Array<int>();
                        for (let int k = head.consumed; k < pending.size; k = k + 1) { rest.add(pending.get(k)); }
                        pending = rest;
                      fi
                    }
                    val Future<void> closed = net_core.close_connection(peer);
                    await closed;
                  }
                  val Future<void> stopped = net_core.close_listener(listener);
                  await stopped;
                  return 0;
                }
                """.formatted(port));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> program = pool.submit(() -> LinkedProgramRunner.runWithExitStatus(entry,
                    IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK),
                    ExecutionProfile.serverJit(), output, new ByteArrayOutputStream()).exitStatus());
            try (Socket peer = connect(port, program)) {
                peer.getOutputStream().write("GET /shared HTTP/1.1\r\nHost: loc".getBytes(StandardCharsets.US_ASCII));
                peer.getOutputStream().flush();
                Thread.sleep(25);
                peer.getOutputStream().write("alhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                String response = new String(peer.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
                assertTrue(response.endsWith("ok\n"), response);
            }
            try (Socket peer = connect(port, program)) {
                peer.getOutputStream().write(("GET /isolated HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        + "GET /shared HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                String response = new String(peer.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertEquals(2, response.split("HTTP/1.1 200 OK", -1).length - 1, response);
            }
            for (String invalid : java.util.List.of(
                    "GET /shared HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
                    "GET /shared HTTP/1.1\r\nConnection: close\r\n\r\n")) {
                try (Socket peer = connect(port, program)) {
                    peer.getOutputStream().write(invalid.getBytes(StandardCharsets.US_ASCII));
                    String response = new String(peer.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                    assertTrue(response.startsWith("HTTP/1.1 400 Bad Request\r\n"), response);
                }
            }
            assertEquals(0, program.get(10, TimeUnit.SECONDS));
        }
        assertEquals(0, output.size(), "guest HTTP must not use a synchronous stdout handshake");
    }
}
