package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression proving the same actor receives successive HTTP exchanges. */
class NativeHttpPooledActorTest {

    @Test void unexpectedlyEndedPooledWorkerAbortsInFlightExchangeAndRespawns() throws Exception {
        String program = """
            define actor Worker as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                if request.target == "/die"; then
                  self.end();
                  return;
                fi
                await request.respond(200, "text/plain", "recovered");
                return;
              }
            end
            pub async routine main(): void {
              val pool = http.pool_handler("Worker", 1);
              val server = http.listen("127.0.0.1", 0, 4, 1024, 10000);
              stdio.println(server.port);
              for (let int i = 0; i < 2; i = i + 1) {
                val request = await server.accept();
                val completed = request.completion;
                pool.dispatch(rt borrow mut request);
                stdio.println(await completed);
              }
              stdio.println("POOL_TERMINAL_CLEANUP");
              stdio.println(pool.workers_created);
              stdio.println(pool.workers_inflight);
              await server.shutdown(0);
              return;
            }
            """;
        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int length) {
                super.write(bytes, off, length);
                String value = toString(StandardCharsets.UTF_8);
                int end = value.indexOf('\n');
                if (end >= 0 && !port.isDone())
                    port.complete(Integer.parseInt(value.substring(0, end).trim()));
            }
        };
        try (Context context = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of()).out(output).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> work = clients.submit(() -> {
                try (HttpClient http = HttpClient.newHttpClient()) {
                    int listeningPort = port.get(10, TimeUnit.SECONDS);
                    var die = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + listeningPort + "/die"))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                    var healthy = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + listeningPort + "/healthy"))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                    assertEquals(500, http.send(die, HttpResponse.BodyHandlers.ofString()).statusCode());
                    var after = http.send(healthy, HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, after.statusCode());
                    assertEquals("recovered", after.body());
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            context.eval(Source.newBuilder(OresLanguage.ID, program, "pooled-worker-died.ores").build());
            work.get(30, TimeUnit.SECONDS);
        }
        String result = output.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("POOL_TERMINAL_CLEANUP\n2\n0"),
            "failed worker left an exchange retained or was incorrectly reused: " + result);
    }

    @Test void oneTimedOutExchangeMustNotDestroyAnOtherwiseHealthyPooledActor() throws Exception {
        String program = """
            define actor Worker as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                if request.target == "/stall"; then
                  try { await request.body_text(); } catch (err) {
                    // A timed-out body read must not resurrect a closed
                    // exchange with a late successful response.
                    try { await request.respond(200, "text/plain", "late"); } catch (late) { }
                  }
                else
                  await request.respond(200, "text/plain", "still-alive");
                fi
                return;
              }
            end
            pub async routine main(): void {
              val pool = http.pool_handler("Worker", 1);
              val server = http.listen("127.0.0.1", 0, 4, 4096, 500);
              stdio.println(server.port);
              for (let int i = 0; i < 2; i = i + 1) {
                val request = await server.accept();
                val completed = request.completion;
                pool.dispatch(rt borrow mut request);
                if i == 1; then await completed; fi
              }
              stdio.println("AFTER_REQUEST_TIMEOUT");
              stdio.println(pool.workers_created);
              stdio.println(pool.workers_reused);
              stdio.println(pool.workers_inflight);
              await server.shutdown(0);
              return;
            }
            """;
        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int length) {
                super.write(bytes, off, length);
                String text = toString(StandardCharsets.UTF_8);
                int end = text.indexOf('\n');
                if (end >= 0 && !port.isDone())
                    port.complete(Integer.parseInt(text.substring(0, end).trim()));
            }
        };
        try (Context context = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of()).out(output).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> work = clients.submit(() -> {
                try (java.net.Socket stalled = new java.net.Socket("127.0.0.1", port.get(10, TimeUnit.SECONDS));
                     HttpClient http = HttpClient.newHttpClient()) {
                    // Request handler starts reading an incomplete body. Keep
                    // the socket open past the 500ms request deadline.
                    stalled.getOutputStream().write((
                        "POST /stall HTTP/1.1\r\nHost: localhost\r\nContent-Length: 256\r\n" +
                        "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    stalled.getOutputStream().flush();
                    stalled.setSoTimeout(4000);
                    Thread.sleep(950);
                    String expiredReply = new String(stalled.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                    assertFalse(expiredReply.contains("HTTP/1.1 200"),
                        "closed exchange unexpectedly produced a late success response: " + expiredReply);
                    var request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port.get(10, TimeUnit.SECONDS) + "/ok"))
                            .timeout(Duration.ofSeconds(8)).GET().build();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode());
                    assertEquals("still-alive", response.body());
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            context.eval(Source.newBuilder(OresLanguage.ID, program, "pooled-timeout-test.ores").build());
            work.get(30, TimeUnit.SECONDS);
        }
        String result = output.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("AFTER_REQUEST_TIMEOUT\n1\n1\n0"),
            "request-local deadline killed a worker or retained a finished exchange: " + result);
    }

    @Test void gracefulShutdownLetsPooledActorFinishAnAlreadyAdmittedResponse() throws Exception {
        String program = """
            define actor Worker as
              receive(ActorMail<String> mail): void {
                val exchange = http.claim(mail.value);
                val body = await exchange.body_text();
                await exchange.respond(200, "text/plain", body);
                return;
              }
            end
            pub async routine main(): void {
              val pool = http.pool_handler("Worker", 1);
              val server = http.listen("127.0.0.1", 0, 4, 4096, 5000);
              stdio.println(server.port);
              val request = await server.accept();
              pool.dispatch(rt borrow mut request);
              await server.shutdown(1500);
              stdio.println("GRACEFUL_SHUTDOWN");
              stdio.println(server.active);
              return;
            }
            """;
        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int length) {
                super.write(bytes, off, length);
                String text = toString(StandardCharsets.UTF_8);
                int end = text.indexOf('\n');
                if (end >= 0 && !port.isDone())
                    port.complete(Integer.parseInt(text.substring(0, end).trim()));
            }
        };
        try (Context context = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of()).out(output).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> work = clients.submit(() -> {
                try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port.get(10, TimeUnit.SECONDS))) {
                    socket.setSoTimeout(7000);
                    socket.getOutputStream().write((
                        "POST /grace HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n" +
                        "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    // The supervisor starts graceful shutdown while this
                    // request is blocked reading its not-yet-arrived body.
                    Thread.sleep(300);
                    socket.getOutputStream().write("ping".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertTrue(response.startsWith("HTTP/1.1 200"), response);
                    assertTrue(response.contains("ping"), response);
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            context.eval(Source.newBuilder(OresLanguage.ID, program, "pooled-graceful-shutdown.ores").build());
            work.get(30, TimeUnit.SECONDS);
        }
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("GRACEFUL_SHUTDOWN\n0"),
            "shutdown must drain the exchange before stopping pooled actors: " + output);
    }

    @Test void poolSizeMustNotTruncateLongToValidInt() {
        String program = """
            define actor Worker as
              receive(ActorMail<String> mail): void { return; }
            end
            pub routine main(): void {
              http.pool_handler("Worker", 4294967297);
              return;
            }
            """;
        try (Context context = IsolatePolicy.developer().restrictedContextBuilder(
                ExecutionProfile.serverJit(), Set.of()).build()) {
            org.graalvm.polyglot.PolyglotException denied = assertThrows(
                org.graalvm.polyglot.PolyglotException.class,
                () -> context.eval(Source.newBuilder(OresLanguage.ID, program, "pool-size-overflow.ores").build()));
            assertTrue(denied.getMessage().contains("pool") || denied.getMessage().contains("10000"),
                "invalid pool size must be rejected, not silently truncated: " + denied.getMessage());
        }
    }
    @Test void sharedAndIsolatedWorkersHandleMultipleExchangesWithoutEnding() throws Exception {
        String program = """
            define actor Shared as
              receive(ActorMail<String> mail): void {
                val exchange = http.claim(mail.value);
                await exchange.respond(200, "text/plain", "ok");
                return;
              }
            end
            define isoactor Isolated as
              receive(ActorMail<String> mail): void {
                val exchange = http.claim(mail.value);
                await exchange.respond(200, "text/plain", "ok");
                return;
              }
            end
            pub async routine main(): void {
              val shared = http.pool_handler("Shared", 1);
              val isolated = http.pool_handler("Isolated", 1);
              val server = http.listen("127.0.0.1", 0, 32, 1024, 10000);
              stdio.println(server.port);
              for (let int i = 0; i < 200; i = i + 1) {
                val exchange = await server.accept();
                val completed = exchange.completion;
                if exchange.target == "/shared"; then
                  shared.dispatch(rt borrow mut exchange);
                else
                  isolated.dispatch(rt borrow mut exchange);
                fi
                await completed;
              }
              stdio.println("POOL_CREATIONS");
              stdio.println(shared.workers_created);
              stdio.println(isolated.workers_created);
              stdio.println("POOL_REUSES");
              stdio.println(shared.workers_reused);
              stdio.println(isolated.workers_reused);
              stdio.println(server.active);
              stdio.println("POOL_INFLIGHT");
              stdio.println(shared.workers_inflight);
              stdio.println(isolated.workers_inflight);
              stdio.println("SERVER_POOL_COUNTERS");
              stdio.println(server.pooled_created);
              stdio.println(server.pooled_reused);
              await server.shutdown(0);
              return;
            }
            """;
        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int length) {
                super.write(bytes, off, length);
                String text = toString(StandardCharsets.UTF_8);
                int end = text.indexOf('\n');
                if (end >= 0 && !port.isDone())
                    port.complete(Integer.parseInt(text.substring(0, end).trim()));
            }
        };
        try (Context context = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of()).out(output).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> work = clients.submit(() -> {
                try (HttpClient http = HttpClient.newHttpClient()) {
                    for (int i = 0; i < 200; i++) {
                        String path = i % 2 == 0 ? "/shared" : "/isolated";
                        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" +
                                port.get(10, TimeUnit.SECONDS) + path))
                                .timeout(Duration.ofSeconds(8)).GET().build();
                        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, response.statusCode());
                        assertEquals("ok", response.body());
                    }
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            context.eval(Source.newBuilder(OresLanguage.ID, program, "pooled-http-test.ores").build());
            work.get(30, TimeUnit.SECONDS);
        }
        String result = output.toString(StandardCharsets.UTF_8);
        assertTrue(result.contains("POOL_CREATIONS\n1\n1\nPOOL_REUSES\n99\n99\n0"),
            "pool did not reuse one actor per mode: " + result);
        assertTrue(result.contains("POOL_INFLIGHT\n0\n0"),
            "completed requests retained on pooled actor termination futures: " + result);
        assertTrue(result.contains("SERVER_POOL_COUNTERS\n2\n198"),
            "server-owned counters did not retain completed reuse accounting: " + result);
    }
}
