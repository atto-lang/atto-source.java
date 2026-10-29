package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
final class OutboundHttpRequestGuestTest {
    @TempDir Path root;

    private String run(
            String program,
            RuntimePermissions permissions)
            throws Exception {
        Path source = root.resolve("http-request.ores");
        Files.writeString(source, program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                IsolatePolicy.developer().withCapabilities(
                        IsolatePolicy.Capability.NETWORK),
                permissions,
                PermissionCheckMode.RUNTIME,
                ExecutionProfile.serverJit(),
                Set.of(),
                System.getenv(),
                output,
                new ByteArrayOutputStream());
        return output.toString().replace("\r", "");
    }

    @Test
    void guestRequestPreservesRepeatedHeadersAndPullsBoundedBody()
            throws Exception {
        AtomicReference<java.util.List<String>> requestHeaders =
                new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                requestHeaders.set(
                        java.util.List.copyOf(
                                exchange.getRequestHeaders()
                                        .getOrDefault("X-Ores-Test", java.util.List.of())));
                exchange.getResponseHeaders().add("Set-Cookie", "one=1");
                exchange.getResponseHeaders().add("Set-Cookie", "two=2");
                byte[] body = "abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String output = run("""
                pub async routine main(): void {
                  val response = await http.request(
                    "GET",
                    "http://127.0.0.1:%d/",
                    [("X-Ores-Test", "one"), ("X-Ores-Test", "two")]
                  );
                  stdio.println(response.status);
                  stdio.println(response.header_values("Set-Cookie").size);
                  val body = response.take_body();
                  try {
                    response.take_body();
                    stdio.println("duplicate-body-accepted");
                  } catch (failure) {
                    stdio.println("duplicate-body-rejected");
                  }
                  val first = await body.read_chunk(3);
                  val second = await body.read_chunk(64);
                  val eof = await body.read_chunk(64);
                  stdio.println(first.size);
                  stdio.println(first[0]);
                  stdio.println(first[1]);
                  stdio.println(first[2]);
                  stdio.println(second.size);
                  stdio.println(second[0]);
                  stdio.println(second[1]);
                  stdio.println(second[2]);
                  stdio.println(eof.size);
                  return;
                }
                """.formatted(port),
                RuntimePermissions.denyAll().withAllowed(
                        RuntimePermissions.Permission.NET,
                        "127.0.0.1:" + port));

            assertEquals(
                    "201\n2\nduplicate-body-rejected\n3\n97\n98\n99\n3\n100\n101\n102\n0\n",
                    output);
            assertEquals(java.util.List.of("one", "two"), requestHeaders.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitBodyCloseRetiresAResponseBlockedWaitingForPeerBytes()
            throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String output = run("""
                pub async routine main(): void {
                  val response = await http.request(
                    "GET",
                    "http://127.0.0.1:%d/",
                    [("X-Ores-Test", "close")]
                  );
                  val body = response.take_body();
                  await body.close();
                  stdio.println(body.closed);
                  return;
                }
                """.formatted(port),
                RuntimePermissions.denyAll().withAllowed(
                        RuntimePermissions.Permission.NET,
                        "127.0.0.1:" + port));
            assertTrue(headersSent.await(2, TimeUnit.SECONDS));
            assertEquals("true\n", output);
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    @Test
    void actorDoneWaitsForStalledGuestRequestPhysicalRetirement()
            throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 4096);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                headersSent.countDown();
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String output = run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val response = await http.request(
                      "GET",
                      mail.value,
                      [("X-Ores-Test", "actor-retirement")]
                    );
                    stdio.println(response.status);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("http://127.0.0.1:%d/");
                  await worker.get_done();
                  stdio.println("done");
                  return;
                }
                """.formatted(port),
                RuntimePermissions.denyAll().withAllowed(
                        RuntimePermissions.Permission.NET,
                        "127.0.0.1:" + port));

            assertTrue(headersSent.await(2, TimeUnit.SECONDS));
            assertEquals("200\ndone\n", output);
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    @Test
    void actorCancellationWithPendingBodyReadRetiresTheWholeRequest()
            throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 4096);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                releaseServer.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String output = run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val response = await http.request("GET", mail.value, []);
                    val body = response.take_body();
                    val first = await body.read_chunk(1);
                    stdio.println(first[0]);
                    val pending = body.read_chunk(64);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("http://127.0.0.1:%d/");
                  await worker.get_done();
                  stdio.println("retired");
                  return;
                }
                """.formatted(port),
                RuntimePermissions.denyAll().withAllowed(
                        RuntimePermissions.Permission.NET,
                        "127.0.0.1:" + port));

            assertEquals("120\nretired\n", output);
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    @Test
    void responseCloseWinsAgainstLaterBodyExtraction()
            throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                byte[] body = "unused".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String output = run("""
                pub async routine main(): void {
                  val response = await http.request(
                    "GET", "http://127.0.0.1:%d/", []);
                  await response.close();
                  try {
                    response.take_body();
                    stdio.println("closed-body-accepted");
                  } catch (failure) {
                    stdio.println("closed-body-rejected");
                  }
                  return;
                }
                """.formatted(port),
                RuntimePermissions.denyAll().withAllowed(
                        RuntimePermissions.Permission.NET,
                        "127.0.0.1:" + port));
            assertEquals("closed-body-rejected\n", output);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void eofThenRepeatedCloseIsIdempotent()
            throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String output = run("""
                pub async routine main(): void {
                  val response = await http.request(
                    "GET", "http://127.0.0.1:%d/", []);
                  val body = response.take_body();
                  val payload = await body.read_chunk(64);
                  val eof = await body.read_chunk(64);
                  stdio.println(payload.size);
                  stdio.println(eof.size);
                  await body.close();
                  await body.close();
                  await response.close();
                  stdio.println(body.closed);
                  return;
                }
                """.formatted(port),
                RuntimePermissions.denyAll().withAllowed(
                        RuntimePermissions.Permission.NET,
                        "127.0.0.1:" + port));
            assertEquals("2\n0\ntrue\n", output);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stalledRequestCannotPoisonSiblingRequest()
            throws Exception {
        CountDownLatch releaseSlow = new CountDownLatch(1);
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 8);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/slow", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 4096);
                exchange.getResponseBody().write('s');
                exchange.getResponseBody().flush();
                releaseSlow.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/fast", exchange -> {
            try {
                byte[] body = "fast".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            String output = run("""
                pub async routine main(): void {
                  val slow = await http.request(
                    "GET", "http://127.0.0.1:%d/slow", []);
                  val fast = await http.request(
                    "GET", "http://127.0.0.1:%d/fast", []);
                  stdio.println(fast.status);
                  val fast_body = fast.take_body();
                  val bytes = await fast_body.read_chunk(64);
                  stdio.println(bytes.size);
                  await slow.close();
                  stdio.println("slow-retired");
                  return;
                }
                """.formatted(port, port),
                RuntimePermissions.denyAll().withAllowed(
                        RuntimePermissions.Permission.NET,
                        "127.0.0.1:" + port));
            assertEquals("201\n4\nslow-retired\n", output);
        } finally {
            releaseSlow.countDown();
            server.stop(0);
        }
    }

    @Test
    void framingHeadersAndMalformedValuesFailClosedBeforeNetworkAdmission()
            throws Exception {
        for (String header : java.util.List.of(
                "Host", "Connection", "Content-Length", "Transfer-Encoding",
                "TE", "Trailer", "Upgrade", "Expect")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ProxyOutboundLoopback.validateRequestHeader(
                            header, "value"));
            assertTrue(
                    failure.getMessage().contains("transport-owned"),
                    failure.getMessage());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> ProxyOutboundLoopback.validateRequestHeader(
                        "X-Test", "ok\r\nbad"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProxyOutboundLoopback.validateRequestHeader(
                        "bad header", "value"));
    }

    @Test
    void responseCapabilitiesCannotCrossActorMailboxBoundary()
            throws Exception {
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> run("""
                    define actor Worker as
                      receive(ActorMail<HttpClientResponse> mail): void {
                        self.end();
                        return;
                      }
                    end

                    pub routine main(): void {
                      return;
                    }
                    """,
                    RuntimePermissions.denyAll()));
        assertTrue(
                String.valueOf(failure).contains("HttpClientResponse")
                        && String.valueOf(failure).contains("actor"),
                String.valueOf(failure));
    }

    @Test
    void guestCannotShadowSealedClientTransportTypes()
            throws Exception {
        for (String type : java.util.List.of(
                "HttpClientResponse", "HttpClientBody")) {
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> run("""
                        define class %s as
                        end

                        pub routine main(): void {
                          return;
                        }
                        """.formatted(type),
                        RuntimePermissions.denyAll()));
            assertTrue(
                    String.valueOf(failure).contains("sealed HTTP transport type"),
                    String.valueOf(failure));
        }
    }
}
