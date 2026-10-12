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
              await worker.ready;
              await thief.ready;
              val String token = request.move_to(worker);
              try { stdio.println(request.method); } catch (err) { stdio.println("sender-revoked"); }
              try { request.move_to(thief); } catch (err) { stdio.println("double-move-rejected"); }
              thief.send(token);
              await thief.done;
              worker.send(token);
              await worker.done;
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

    @Test void stoppingHttpListenerRetainsActorExchangeUntilCancelledCleanupFinishes()
            throws Exception {
        String output = run("""
            define actor Holder as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                defer stdio.println("holder-cleanup");
                self.send("armed");
                val Channel<int> gate = Channel.new<int>(0);
                const ignored = await (nb readch gate);
                stdio.println("INVALID-work-resumed");
                self.end();
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
              stdio.println(server.port);
              val request = await server.accept();
              val holder = spawn Holder();
              await holder.ready;
              holder.send(request.move_to(holder));
              await holder.outputs.next();
              await server.shutdown(0);
              stdio.println("drained");
              return;
            }
            """, port -> {
            try {
                var response = request(port, "GET", "/", "");
                assertEquals(503, response.statusCode());
            } catch (java.io.IOException expectedDisconnect) {
                // HttpServer.stop(0) may close the pending request first.
            }
        });
        assertTrue(output.contains("holder-cleanup"), output);
        assertTrue(output.contains("drained"), output);
        assertFalse(output.contains("INVALID-work-resumed"), output);
        assertTrue(output.indexOf("holder-cleanup") < output.indexOf("drained"),
                "HTTP shutdown may report success only after actor-owned cleanup");
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
              await worker.ready;
              worker.send(request.move_to(worker));
              await worker.done;
              await server.shutdown(0);
              return;
            }
            """, port -> assertEquals("onetwo", request(port, "GET", "/", "").body()));
    }

    @Test
    void streamingResponseCancellationReleasesActorBeforeHttpDrain() throws Exception {
        String output = run("""
            define actor StreamHolder as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                defer stdio.println("stream-cleanup");
                await request.start(200, "text/plain");
                await request.write("first-chunk");
                self.send("chunk-ready");
                val Channel<int> gate = Channel.new<int>(0);
                const ignored = await (nb readch gate);
                await request.write("INVALID-late-chunk");
                self.end();
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
              stdio.println(server.port);
              val request = await server.accept();
              val streamer = spawn StreamHolder();
              await streamer.ready;
              streamer.send(request.move_to(streamer));
              await streamer.outputs.next();
              stdio.println("stream-started");
              await server.shutdown(0);
              stdio.println("stream-drained");
              return;
            }
            """, port -> {
            try {
                var response = request(port, "GET", "/", "");
                assertEquals(200, response.statusCode());
                assertFalse(response.body().contains("INVALID-late-chunk"));
            } catch (java.io.IOException expectedDisconnect) {
                // Transport stop may terminate a partially streamed response.
            }
        });
        assertTrue(output.contains("stream-started"), output);
        assertTrue(output.contains("stream-cleanup"), output);
        assertTrue(output.contains("stream-drained"), output);
        assertFalse(output.contains("INVALID-late-chunk"), output);
        assertTrue(output.indexOf("stream-cleanup") < output.indexOf("stream-drained"),
                "the actor's deferred cleanup must precede HTTP drain completion");
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

    @Test
    void contextCloseDrainsAnUnansweredHttpExchangeBeforeRetirement() throws Exception {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                String printed = toString(StandardCharsets.UTF_8);
                int newline = printed.indexOf('\n');
                if (newline >= 0 && !port.isDone()) {
                    port.complete(Integer.parseInt(printed.substring(0, newline).trim()));
                }
            }
        };
        try (Context context = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(output).build();
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> request = workers.submit(() -> {
                try {
                    var response = request(port.get(5, TimeUnit.SECONDS), "GET", "/", "");
                    // An exchange with no guest response must be aborted rather
                    // than remaining owned after context close.
                    assertEquals(503, response.statusCode());
                } catch (java.io.IOException expectedDisconnect) {
                    // JDK HttpServer.stop(0) can close an uncommitted connection
                    // before a 503 header can be sent.
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            context.eval(Source.newBuilder(OresLanguage.ID, """
                    pub async routine main(): void {
                      val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
                      stdio.println(server.port);
                      val request = await server.accept();
                      stdio.println("accepted");
                      return;
                    }
                    """, "shutdown-unanswered-http.ores").build());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("accepted"));
            assertDoesNotThrow(() -> { context.close(); },
                    "context retirement must physically drain an unanswered exchange");
            request.get(8, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectedAbortAfterPartialStreamReclaimsSocketOnlyAfterCarrierExit()
            throws Exception {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                String printed = toString(StandardCharsets.UTF_8);
                int newline = printed.indexOf('\n');
                if (newline >= 0 && !port.isDone())
                    port.complete(Integer.parseInt(printed.substring(0, newline).trim()));
            }
        };
        NativeHttp.REJECT_NEXT_ABORT_FOR_TEST.set(false);
        try (Context context = IsolatePolicy.developer()
                    .withCapabilities(IsolatePolicy.Capability.NETWORK)
                    .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                    .out(output).build();
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> client = workers.submit(() -> {
                try {
                    var response = request(port.get(5, TimeUnit.SECONDS), "GET", "/", "");
                    assertEquals(200, response.statusCode());
                    assertFalse(response.body().contains("INVALID-late-write"));
                } catch (java.io.IOException expectedDisconnect) {
                    // Closing the partially streamed socket can abort its body.
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            context.eval(Source.newBuilder(OresLanguage.ID, """
                    define actor PausedStream as
                      receive(ActorMail<String> mail): void {
                        val exchange = http.claim(mail.value);
                        defer stdio.println("stream-finalized");
                        await exchange.start(200, "text/plain");
                        await exchange.write("first-chunk");
                        self.send("stream-ready");
                        val Channel<int> gate = Channel.new<int>(0);
                        const ignored = await (nb readch gate);
                        await exchange.write("INVALID-late-write");
                        self.end();
                        return;
                      }
                    end
                    pub async routine main(): void {
                      val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
                      stdio.println(server.port);
                      val exchange = await server.accept();
                      val streamer = spawn PausedStream();
                      await streamer.ready;
                      streamer.send(exchange.move_to(streamer));
                      await streamer.outputs.next();
                      stdio.println("partial-stream-ready");
                      return;
                    }
                    """, "rejected-stream-abort.ores").build());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("partial-stream-ready"));
            // Precisely one actor-completion abort dispatch is rejected.
            // The host close must drain all former I/O carriers before its
            // synchronous socket-close fallback and exchange release.
            NativeHttp.REJECT_NEXT_ABORT_FOR_TEST.set(true);
            assertDoesNotThrow(() -> { context.close(); });
            assertFalse(NativeHttp.REJECT_NEXT_ABORT_FOR_TEST.get(),
                    "the fault must have reached a real abort dispatch");
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("stream-finalized"));
            client.get(8, TimeUnit.SECONDS);
        } finally {
            NativeHttp.REJECT_NEXT_ABORT_FOR_TEST.set(false);
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
        CompletableFuture<Integer> port = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                String text = toString(StandardCharsets.UTF_8);
                int end = text.indexOf('\n');
                if (end >= 0 && !port.isDone()) port.complete(Integer.parseInt(text.substring(0, end).trim()));
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
            } finally { clientWork.cancel(true); }

        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
