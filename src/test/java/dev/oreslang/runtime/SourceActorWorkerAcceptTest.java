package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Oreslang source actor -> nonblocking NIO accept -> actor continuation. */
@EnabledOnOs(OS.LINUX)
final class SourceActorWorkerAcceptTest {
    private static int unusedPort() throws IOException {
        try (ServerSocket bound = new ServerSocket(0, 1,
                InetAddress.getByName("127.0.0.1"))) {
            return bound.getLocalPort();
        }
    }

    private static Socket connectWithRetry(int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
                socket.setSoTimeout(3000);
                return socket;
            } catch (java.net.ConnectException retry) {
                if (System.nanoTime() >= deadline) throw retry;
                Thread.sleep(5);
            }
        }
    }

    @Test @Timeout(30)
    void compiledSharedActorAcceptsAndEchoesWithoutSupervisorHandoff() throws Exception {
        int port = unusedPort();
        String program = """
            define actor Worker as
              receive(ActorMail<int> mail): void {
                val NioTcpListener listener = network.listen_reuseport(
                    "127.0.0.1", mail.value, 64, 8, 16);
                val NioTcpConnection connection = await listener.accept_async();
                val Array<int> bytes = await connection.read_bytes_async(32);
                await connection.write_bytes_async(bytes);
                await connection.close_async();
                await listener.close_async();
                self.end();
                return;
              }
            end

            pub async routine main(): void {
              val worker = spawn Worker();
              await worker.get_ready();
              worker.send(%d);
              await worker.get_done();
              stdio.println("actor-served");
              return;
            }
            """.formatted(port);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context guest = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(output).build();
             var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var client = clients.submit(() -> {
                try (Socket socket = connectWithRetry(port)) {
                    socket.getOutputStream().write(0x5a);
                    assertEquals(0x5a, socket.getInputStream().read());
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            guest.eval(Source.newBuilder(OresLanguage.ID, program, "actor-worker-accept.ores").build());
            client.get(6, TimeUnit.SECONDS);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("actor-served"));
    }

    @Test @Timeout(45)
    void fourCompiledActorsBindTheSamePortAndIndependentlyAccept() throws Exception {
        int port = unusedPort();
        String program = """
            define actor Worker as
              receive(ActorMail<int> mail): void {
                val NioTcpListener listener = network.listen_reuseport(
                    "127.0.0.1", mail.value, 256, 8, 16);
                self.send(1);
                val NioTcpConnection connection = await listener.accept_async();
                val Array<int> payload = await connection.read_bytes_async(4);
                await connection.write_bytes_async(payload);
                await connection.close_async();
                await listener.close_async();
                self.end();
                return;
              }
            end

            pub async routine main(): void {
              val a = spawn Worker();
              val b = spawn Worker();
              val c = spawn Worker();
              val d = spawn Worker();
              await a.get_ready();
              await b.get_ready();
              await c.get_ready();
              await d.get_ready();
              a.send(%d);
              b.send(%d);
              c.send(%d);
              d.send(%d);
              await a.outputs.next();
              await b.outputs.next();
              await c.outputs.next();
              await d.outputs.next();
              stdio.println("listeners-ready");
              await a.get_done();
              await b.get_done();
              await c.get_done();
              await d.get_done();
              stdio.println("four-actors-served");
              return;
            }
            """.formatted(port, port, port, port);
        CompletableFuture<Void> ready = new CompletableFuture<>();
        CompletableFuture<Void> served = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int len) {
                super.write(bytes, offset, len);
                String latest = toString(StandardCharsets.UTF_8);
                if (latest.contains("listeners-ready")) ready.complete(null);
                if (latest.contains("four-actors-served")) served.complete(null);
            }
        };
        try (Context guest = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(output).build();
             var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            var burst = clients.submit(() -> {
                List<Socket> sockets = new ArrayList<>();
                try {
                    ready.get(10, TimeUnit.SECONDS);
                    // Deliberately oversupply connections: SO_REUSEPORT makes
                    // no equal-distribution guarantee between workers.
                    for (int i = 0; i < 128 && !served.isDone(); ++i) {
                        try {
                            Socket peer = new Socket(
                                    InetAddress.getByName("127.0.0.1"), port);
                            sockets.add(peer);
                            peer.getOutputStream().write(0x21);
                        } catch (IOException rejectedDuringDrain) {
                            // Each actor retires its listener after one
                            // connection, so late clients can race group
                            // shrinkage. The assertion is on all four actors
                            // completing, not every surplus TCP attempt.
                            if (served.isDone()) break;
                        }
                    }
                    // Do not tear down the client sockets until all workers
                    // have completed. Otherwise queued accepts can observe
                    // artificial EOF/ECONNRESET instead of genuine traffic.
                    served.get(12, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                } finally {
                    for (Socket socket : sockets) {
                        try { socket.close(); } catch (IOException ignored) { }
                    }
                }
            });
            guest.eval(Source.newBuilder(OresLanguage.ID, program, "four-actor-accept.ores").build());
            burst.get(10, TimeUnit.SECONDS);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("four-actors-served"));
    }
}
