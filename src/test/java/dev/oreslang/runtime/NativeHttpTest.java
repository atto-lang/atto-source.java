package dev.oreslang.runtime;

import dev.oreslang.OresLanguage;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeHttpTest {
    @org.junit.jupiter.api.RepeatedTest(5)
    void pooledActorsReuseMailboxWithoutRetainingExchangeAdmission() throws Exception {
        for (String kind : List.of("actor", "isoactor")) {
            String output = run("""
                define %s Worker as
                  let int uses = 0;
                  let String previous = "";
                  receive(ActorMail<String> mail): void {
                    if self.previous != ""; then
                      try { http.claim(self.previous); stdio.println("STALE TOKEN ACCEPTED"); }
                      catch (err) { stdio.println("stale-rejected"); }
                    fi
                    self.previous = mail.value;
                    self.uses = self.uses + 1;
                    val request = http.claim(mail.value);
                    request.set_header("X-Reused", self.uses > 1 ? "yes" : "no");
                    await request.respond(200, "text/plain", "ok");
                    try { request.respond(200, "text/plain", "wrong"); stdio.println("CLOSED ACCEPTED"); }
                    catch (err) { stdio.println("closed-rejected"); }
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 1, 1024, 5000);
                  val handler = http.handler("Worker").pooled(1);
                  stdio.println(server.port);
                  for (let int i = 0; i < 8; i = i + 1) {
                    val request = await server.accept();
                    val completion = request.completion;
                    handler.dispatch(rt borrow mut request);
                    await completion;
                  }
                  await server.shutdown(0);
                  stdio.println(handler.created);
                  stdio.println(handler.reused);
                  stdio.println(server.active);
                  return;
                }
                """.formatted(kind), port -> {
                    for (int i = 1; i <= 8; i++) {
                        HttpResponse<String> response = request(port, "GET", "/", "");
                        assertEquals(200, response.statusCode());
                        assertEquals(i > 1 ? "yes" : "no", response.headers().firstValue("X-Reused").orElseThrow());
                    }
                }, 8);
            assertFalse(output.contains("ACCEPTED"), output);
            assertEquals(7, output.split("stale-rejected", -1).length - 1, output);
            assertEquals(8, output.split("closed-rejected", -1).length - 1, output);
            // Capture teardown may omit the trailing newline even when all\n            // counters were emitted. Check the final values, not flush timing.\n            assertTrue(output.stripTrailing().endsWith("1\n7\n0"), output);
        }
    }

    @Test void pooledWorkerDeathReleasesRequestAndReplacementServesFollowingRequests() throws Exception {
        for (String kind : List.of("actor", "isoactor")) {
            String output = run("""
                define %s Worker as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    if request.target == "/fail"; then self.end(); return; fi
                    await request.respond(200, "text/plain", "ok");
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 1, 1024, 5000);
                  val handler = http.handler("Worker").pooled(1);
                  stdio.println(server.port);
                  for (let int i = 0; i < 3; i = i + 1) {
                    val request = await server.accept();
                    val completion = request.completion;
                    handler.dispatch(rt borrow mut request);
                    await completion;
                  }
                  await server.shutdown(0);
                  stdio.println(handler.created);
                  stdio.println(handler.reused);
                  stdio.println(server.active);
                  return;
                }
                """.formatted(kind), port -> {
                    assertEquals(500, request(port, "GET", "/fail", "").statusCode());
                    assertEquals(200, request(port, "GET", "/ok", "").statusCode());
                    assertEquals(200, request(port, "GET", "/ok", "").statusCode());
                });
            assertTrue(output.endsWith("2\n1\n0\n"), output);
        }
    }

    @Test void pooledReceiveRemainsExclusiveAcrossAwaitUnderConcurrentRequests() throws Exception {
        for (String kind : List.of("actor", "isoactor")) {
            String output = run("""
                define %s Worker as
                  let bool receiving = false;
                  val String identity = process.unique_id();
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    if self.receiving; then
                      await request.respond(409, "text/plain", "overlap"); return;
                    fi
                    self.receiving = true;
                    request.set_header("X-Worker", self.identity);
                    await request.respond(200, "text/plain", "ok");
                    self.receiving = false;
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 32, 1024, 5000);
                  val handler = http.handler("Worker").pooled(2);
                  stdio.println(server.port);
                  for (let int i = 0; i < 32; i = i + 1) {
                    val request = await server.accept();
                    handler.dispatch(rt borrow mut request);
                  }
                  await server.shutdown(3000);
                  stdio.println(handler.created);
                  stdio.println(handler.reused);
                  stdio.println(server.active);
                  return;
                }
                """.formatted(kind), port -> {
                    try (HttpClient client = HttpClient.newHttpClient()) {
                        var pending = new java.util.ArrayList<CompletableFuture<HttpResponse<String>>>();
                        for (int i = 0; i < 32; i++) pending.add(client.sendAsync(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                                .timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString()));
                        var identities = new java.util.HashSet<String>();
                        for (var future : pending) {
                            var response = future.get(8, TimeUnit.SECONDS);
                            assertEquals(200, response.statusCode());
                            identities.add(response.headers().firstValue("X-Worker").orElseThrow());
                        }
                        assertEquals(2, identities.size());
                    }
                });
            assertTrue(output.endsWith("2\n30\n0\n"), output);
        }
    }

    @Test void pathDecodingPreservesSegmentBoundariesAndRejectsAmbiguousBytes() {
        assertEquals(List.of(), NativeHttp.segments("/"));
        assertEquals(List.of("😀"), NativeHttp.segments("/😀"));
        assertEquals(List.of("a", "", "b", ""), NativeHttp.segments("/a//b/"));
        assertEquals(List.of("a/b", "+", "%2F", "é"), NativeHttp.segments("/a%2Fb/+/%252F/%C3%A9"));
        for (String bad : List.of("/%", "/%GG", "/%C0%AF", "/%00", "/%5c", "/%7f"))
            assertThrows(IllegalArgumentException.class, () -> NativeHttp.segments(bad), bad);
    }

    @Test void isolatedMoveRevokesSenderRejectsWrongActorAndAllowsOneClaim() throws Exception {
        String program = """
            define isoactor Owner as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                try { http.claim(mail.value); } catch (err) { stdio.println("duplicate-rejected"); }
                await request.respond(200, "text/plain", "owned");
                try { request.respond(200, "text/plain", "twice"); } catch (err) { stdio.println("closed-rejected"); }
                self.end();
                return;
              }
            end
            define isoactor Thief as
              receive(ActorMail<String> mail): void {
                try { http.claim(mail.value); } catch (err) { stdio.println("wrong-actor-rejected"); }
                self.end();
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 4, 1024, 5000);
              stdio.println(server.port);
              val request = await server.accept();
              val worker = spawn Owner();
              val thief = spawn Thief();
              await worker.get_ready();
              await thief.get_ready();
              val String token = request.move_to(worker);
              try { stdio.println(request.method); } catch (err) { stdio.println("sender-revoked"); }
              try { request.move_to(thief); } catch (err) { stdio.println("double-move-rejected"); }
              thief.send(token);
              await thief.get_done();
              worker.send(token);
              await worker.get_done();
              await server.shutdown(0);
              return;
            }
            """;
        String output = run(program, port -> {
            var response = request(port, "GET", "/", "");
            assertEquals(200, response.statusCode()); assertEquals("owned", response.body());
        });
        for (String expected : List.of("sender-revoked", "double-move-rejected", "wrong-actor-rejected", "duplicate-rejected", "closed-rejected"))
            assertTrue(output.contains(expected), output);
    }

    @Test void completionSurvivesMoveAndIncludesActorFinalization() throws Exception {
        String output = run("""
            define isoactor Owner as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                stdio.println(request.admitted_ns <= process.monotonic_ns());
                await request.respond(201, "text/plain", "observed");
                stdio.println("actor-finished");
                self.end();
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 1, 1024, 5000);
              stdio.println(server.port);
              val request = await server.accept();
              val completion = request.completion;
              http.handler("Owner").dispatch(rt borrow mut request);
              try { request.completion; } catch (err) { stdio.println("moved-observer-rejected"); }
              val int status = await completion;
              stdio.println(status);
              stdio.println(server.active);
              await server.shutdown(0);
              return;
            }
            """, port -> assertEquals(201, request(port, "GET", "/", "").statusCode()));
        assertTrue(output.contains("true"), output);
        assertTrue(output.contains("moved-observer-rejected"), output);
        assertTrue(output.indexOf("actor-finished") < output.lastIndexOf("201"), output);
        assertTrue(output.contains("201\n0"), output);
    }

    @Test void completionReportsDeadlineWithoutAnActor() throws Exception {
        String output = run("""
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 1, 1024, 100);
              stdio.println(server.port);
              val request = await server.accept();
              val completion = request.completion;
              stdio.println(await completion);
              await server.shutdown(0);
              return;
            }
            """, port -> assertEquals(504, request(port, "GET", "/", "").statusCode()));
        assertTrue(output.contains("504"), output);
    }

    @Test void sharedHandlerStreamsWithoutBlockingTheActorCarrier() throws Exception {
        run("""
            define actor Stream as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                await request.start(200, "text/plain");
                await request.write("one");
                await request.write("two");
                await request.finish();
                self.end();
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
              stdio.println(server.port);
              val request = await server.accept();
              val worker = spawn Stream();
              await worker.get_ready();
              worker.send(request.move_to(worker));
              await worker.get_done();
              await server.shutdown(0);
              return;
            }
            """, port -> assertEquals("onetwo", request(port, "GET", "/", "").body()));
    }

    @Test void ordinaryBorrowsStillCannotSpanActorAwait() {
        for (String binding : new String[] {
                "val borrowed = rt borrow values;",
                "val borrowed = (rt borrow values);",
                "val (borrowed) = tuple (rt borrow values);"}) {
            String source = """
                define actor Bad as
                  receive(ActorMail<String> mail): void {
                    val Array<int> values = new Array<int>();
                    %s
                    val request = http.claim(mail.value);
                    await request.respond(200, "text/plain", "bad");
                    stdio.println(borrowed.size);
                    return;
                  }
                end
                """.formatted(binding);
            Exception error = assertThrows(Exception.class, () -> OresCompiler.parseAndTypeCheck(source));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
    }

    @FunctionalInterface interface Client { void run(int port) throws Exception; }
    private static HttpResponse<String> request(int port, String method, String path, String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8)).method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private static String run(String program, Client client) throws Exception {
        return run(program, client, 0);
    }

    private static String run(String program, Client client, int expectedPostResponseDiagnostics)
            throws Exception {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        CompletableFuture<Void> diagnosticBarrier = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                String text = toString(StandardCharsets.UTF_8);
                int end = text.indexOf('\n');
                if (end >= 0 && !port.isDone()) port.complete(Integer.parseInt(text.substring(0, end).trim()));
                // HTTP response completion can precede the actor's final
                // post-response duplicate-rejection check. Keep the context
                // alive until the diagnostic has actually been emitted, not
                // merely until the supervisor's response Future completes.
                if (expectedPostResponseDiagnostics > 0
                        && text.split("closed-rejected", -1).length - 1
                                >= expectedPostResponseDiagnostics) {
                    diagnosticBarrier.complete(null);
                }
            }
        };
        try (Context context = IsolatePolicy.developer().withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of()).out(output).build();
             ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> clientWork = worker.submit(() -> {
                try { client.run(port.get(10, TimeUnit.SECONDS)); }
                catch (Exception failure) { throw new CompletionException(failure); }
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, program, "native-http-test.ores").build());
                clientWork.get(10, TimeUnit.SECONDS);
                // A bounded actor-output completion barrier closes the race
                // between response transport completion and receive cleanup.
                // If a duplicate rejection never occurs, the test fails
                // rather than silently reducing its expected assertion count.
                if (expectedPostResponseDiagnostics > 0) {
                    diagnosticBarrier.get(8, TimeUnit.SECONDS);
                }
            } finally { clientWork.cancel(true); }

        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
