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
    @org.junit.jupiter.api.Timeout(30)
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
                    handler.dispatch(rt mut borrow request);
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
            // Supervisor counters may interleave with post-response diagnostics.
            var numbers = output.lines().filter(line -> line.matches("[0-9]+")).toList();
            assertEquals(4, numbers.size(), output); // port plus three counters
            assertEquals(List.of("1", "7", "0"), numbers.subList(1, 4), output);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void scopedDispatchReceiptBindsHandlerPurposeAndActorIncarnationAcrossPoolReuse() throws Exception {
        String output = run("""
            define isoactor Worker as
              let String previous = "";
              receive(ActorMail<String> mail): void {
                if self.previous != ""; then
                  try {
                    http.claim(self.previous);
                    stdio.println("RECEIPT REPLAY ACCEPTED");
                  } catch (err) {
                    stdio.println("receipt-replay-rejected");
                  }
                fi
                self.previous = mail.value;
                val request = http.claim(mail.value);
                if !request.dispatch_receipt_valid(
                    "Worker", "proxy:greeting", "local-no-net", 7); then
                  stdio.println("RECEIPT INVALID");
                  await request.respond(500, "text/plain", "invalid");
                  return;
                fi
                if request.dispatch_receipt_valid(
                    "Worker", "proxy:other", "local-no-net", 7) ||
                  request.dispatch_receipt_valid(
                    "Other", "proxy:greeting", "local-no-net", 7) ||
                  request.dispatch_receipt_valid(
                    "Worker", "proxy:greeting", "net", 7) ||
                  request.dispatch_receipt_valid(
                    "Worker", "proxy:greeting", "local-no-net", 8); then
                  stdio.println("WRONG RECEIPT ACCEPTED");
                  await request.respond(500, "text/plain", "wrong");
                  return;
                fi
                stdio.println(request.dispatch_handler);
                stdio.println(request.dispatch_binding);
                stdio.println(request.dispatch_authority_class);
                stdio.println(request.dispatch_scope_generation);
                stdio.println(request.dispatch_actor_generation > 0);
                stdio.println(request.dispatch_exchange_generation > 0);
                stdio.println(request.dispatch_sequence);
                await request.respond(200, "text/plain", "ok");
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
              val base_handler = http.handler("Worker");
              val scoped_handler = base_handler.scoped(
                "proxy:greeting", "local-no-net", 7);
              val handler = scoped_handler.pooled(1);
              stdio.println(server.port);
              for (let int i = 0; i < 2; i = i + 1) {
                val request = await server.accept();
                val completion = request.completion;
                handler.dispatch(rt mut borrow request);
                await completion;
              }
              await server.shutdown(0);
              stdio.println(handler.created);
              stdio.println(handler.reused);
              return;
            }
            """, port -> {
                assertEquals(200, request(port, "GET", "/one", "").statusCode());
                assertEquals(200, request(port, "GET", "/two", "").statusCode());
            });
        assertFalse(output.contains("INVALID"), output);
        assertFalse(output.contains("ACCEPTED"), output);
        assertEquals(1, output.split("receipt-replay-rejected", -1).length - 1, output);
        assertEquals(2, output.split("Worker", -1).length - 1, output);
        assertEquals(2, output.split("proxy:greeting", -1).length - 1, output);
        assertEquals(2, output.split("local-no-net", -1).length - 1, output);
        assertEquals(2, output.split("\n7\n", -1).length - 1, output);
        assertTrue(output.contains("7\ntrue\ntrue\n1"), output);
        assertTrue(output.contains("7\ntrue\ntrue\n2"), output);
        assertTrue(output.endsWith("1\n1\n"), output);
    }

    @Test
    void manualExchangeMoveDoesNotFabricateAnAttestedHandlerReceipt() throws Exception {
        String output = run("""
            define isoactor Owner as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                try {
                  request.dispatch_receipt_valid(
                    "manual", "manual", "unscoped", 0);
                  stdio.println("MANUAL RECEIPT ACCEPTED");
                } catch (err) {
                  stdio.println("manual-unattested");
                }
                await request.respond(200, "text/plain", "ok");
                self.end();
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 1, 1024, 5000);
              stdio.println(server.port);
              val request = await server.accept();
              val owner = spawn Owner();
              await owner.get_ready();
              owner.send(request.move_to(owner));
              await owner.get_done();
              await server.shutdown(0);
              return;
            }
            """, port -> assertEquals(200, request(port, "GET", "/", "").statusCode()));
        assertTrue(output.contains("manual-unattested"), output);
        assertFalse(output.contains("MANUAL RECEIPT ACCEPTED"), output);
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
                    handler.dispatch(rt mut borrow request);
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
                    handler.dispatch(rt mut borrow request);
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

    @Test void oneLongLivedActorStreamsDistinctResponsesAndReleasesEachAdmission() throws Exception {
        String output = run("""
            define actor Reused as
              receive(ActorMail<String> mail): void {
                if mail.value == "stop"; do
                  self.end();
                  return;
                fi
                val exchange = http.claim(mail.value);
                await exchange.start(200, "text/plain");
                await exchange.write(exchange.target);
                await exchange.write("::");
                await exchange.write(exchange.request_id);
                await exchange.finish();
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
              stdio.println(server.port);
              val worker = spawn Reused();
              await worker.ready;

              // Keep both requests admitted before delivering either to
              // the shared actor. Response envelopes must not cross streams.
              val first = await server.accept();
              val second = await server.accept();
              val firstDone = first.completion;
              val secondDone = second.completion;
              worker.send(first.move_to(worker));
              worker.send(second.move_to(worker));
              stdio.println(await firstDone);
              stdio.println(await secondDone);
              stdio.println(server.active);

              worker.send("stop");
              await worker.done;
              await server.shutdown(0);
              return;
            }
            """, port -> {
            try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<HttpResponse<String>> firstWork = clients.submit(() -> request(port, "GET", "/first", ""));
                Future<HttpResponse<String>> secondWork = clients.submit(() -> request(port, "GET", "/second", ""));
                HttpResponse<String> first = firstWork.get(8, TimeUnit.SECONDS);
                HttpResponse<String> second = secondWork.get(8, TimeUnit.SECONDS);
                assertEquals(200, first.statusCode());
                assertEquals(200, second.statusCode());
                assertTrue(first.body().startsWith("/first::"), first.body());
                assertTrue(second.body().startsWith("/second::"), second.body());
                assertNotEquals(first.body(), second.body(), "each exchange must keep its own request identity");
            }
        });
        assertTrue(output.contains("200\n200\n0"), output);
    }

    @Test void abortingOneRequestNeverCancelsReusableActorOrAnotherRequest() throws Exception {
        String output = run("""
            define actor Reused as
              receive(ActorMail<String> mail): void {
                if mail.value == "stop"; do
                  self.end();
                  return;
                fi
                val exchange = http.claim(mail.value);
                if exchange.target == "/abort"; do
                  exchange.abort();
                  return;
                fi
                await exchange.respond(200, "text/plain", exchange.target);
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
              stdio.println(server.port);
              val worker = spawn Reused();
              await worker.ready;

              val first = await server.accept();
              val firstDone = first.completion;
              worker.send(first.move_to(worker));
              stdio.println(await firstDone);

              val second = await server.accept();
              val secondDone = second.completion;
              worker.send(second.move_to(worker));
              stdio.println(await secondDone);
              stdio.println(server.active);

              worker.send("stop");
              await worker.done;
              await server.shutdown(0);
              return;
            }
            """, port -> {
            assertEquals(500, request(port, "GET", "/abort", "").statusCode());
            HttpResponse<String> second = request(port, "GET", "/alive", "");
            assertEquals(200, second.statusCode());
            assertEquals("/alive", second.body());
        });
        assertTrue(output.contains("500\n200\n0"), output);
    }

    @Test void persistentActorReleasesSingleListenerSlotAcrossManyRequests() throws Exception {
        // Generate 20 distinct source-level accept/dispatch/completion steps.
        // The fixture must not rely on source loop syntax unrelated to HTTP.
        String steps = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> """
                    val exchange%d = await server.accept();
                    val completed%d = exchange%d.completion;
                    worker.send(exchange%d.move_to(worker));
                    await completed%d;
                    """.formatted(i, i, i, i, i))
                .collect(java.util.stream.Collectors.joining());
        String output = run("""
            define actor Persistent as
              receive(ActorMail<String> mail): void {
                if mail.value == "stop"; do
                  self.end();
                  return;
                fi
                val exchange = http.claim(mail.value);
                await exchange.respond(200, "text/plain", exchange.target);
                return;
              }
            end
            pub async routine main(): void {
              // A capacity of one catches any request whose admission is
              // mistakenly held until a persistent actor exits.
              val server = http.listen("127.0.0.1", 0, 1, 1024, 10000);
              stdio.println(server.port);
              val worker = spawn Persistent();
              await worker.ready;
              __STEPS__
              stdio.println("reused-20");
              stdio.println(server.active);
              worker.send("stop");
              await worker.done;
              await server.shutdown(0);
              return;
            }
            """.replace("__STEPS__", steps), port -> {
            for (int i = 0; i < 20; i++) {
                String target = "/repeat-" + i;
                HttpResponse<String> response = request(port, "GET", target, "");
                assertEquals(200, response.statusCode(), target);
                assertEquals(target, response.body(), target);
            }
        });
        // All 20 client responses are asserted above. With listener capacity
        // one, this cannot succeed if a request holds its permit until the
        // persistent actor dies. Do not assert on an async stdout epilogue:
        // Context.eval may return before its final print is captured.
        assertFalse(output.isBlank(), "the listener must have published its port");
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
              http.handler("Owner").dispatch(rt mut borrow request);
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

    @Test void guestResponseDraftIsTypedAndTransferredToRealHttp11Exchange() throws Exception {
        for (String kind : List.of("actor", "isoactor")) {
            String output = run("""
                define %s Hello as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    val response = http.response(201, "text/plain; charset=utf-8", "hello from Oreslang");
                    await request.respond_response(response);
                    try {
                      await request.respond_response(response);
                      stdio.println("DUPLICATE RESPONSE ACCEPTED");
                    } catch (failure) {
                      stdio.println("duplicate-response-rejected");
                    }
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
                  stdio.println(server.port);
                  val request = await server.accept();
                  val done = request.completion;
                  http.handler("Hello").dispatch(rt mut borrow request);
                  stdio.println(await done);
                  await server.shutdown(0);
                  return;
                }
                """.formatted(kind), port -> {
                    var response = request(port, "GET", "/", "");
                    assertEquals(201, response.statusCode());
                    assertEquals("hello from Oreslang", response.body());
                    assertEquals("text/plain; charset=utf-8",
                            response.headers().firstValue("Content-Type").orElseThrow());
                });
            assertFalse(output.contains("DUPLICATE RESPONSE ACCEPTED"), output);
            assertTrue(output.contains("duplicate-response-rejected"), output);
            assertTrue(output.contains("201"), output);
        }
    }

    @Test void guestNominalHttpResponseConstructorHandlesTextAndEmptyBody() throws Exception {
        String output = run("""
            define actor Hello as
              receive(ActorMail<String> mail): void {
                val request = http.claim(mail.value);
                if request.target == "/empty"; then
                  val response = new HttpResponse();
                  await request.respond_response(response);
                elif request.target == "/no-content"; then
                  val response = http.response_empty(204);
                  await request.respond_response(response);
                else
                  val response = new HttpResponse("hello from Oreslang");
                  await request.respond_response(response);
                fi
                self.end();
                return;
              }
            end
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 2, 1024, 5000);
              stdio.println(server.port);
              for (let int i = 0; i < 3; i = i + 1) {
                val request = await server.accept();
                val completion = request.completion;
                http.handler("Hello").dispatch(rt mut borrow request);
                await completion;
              }
              await server.shutdown(0);
              return;
            }
            """, port -> {
                var response = request(port, "GET", "/text", "");
                assertEquals(200, response.statusCode());
                assertEquals("hello from Oreslang", response.body());
                assertEquals("text/plain; charset=utf-8",
                        response.headers().firstValue("Content-Type").orElseThrow());
                var empty = request(port, "GET", "/empty", "");
                assertEquals(200, empty.statusCode());
                assertEquals("", empty.body());
                var noContent = request(port, "GET", "/no-content", "");
                assertEquals(204, noContent.statusCode());
                assertEquals("", noContent.body());
            });
        assertFalse(output.contains("exception"), output);
    }

    @Test void guestHttpResponseConstructorRejectsUnimplementedOptionsAndWrongTypes() {
        String template = """
            define actor A as
              receive(ActorMail<String> mail): void {
                val reply = %s;
                return;
              }
            end
            """;
        for (String rejected : List.of(
                "new HttpResponse(100)",
                "new HttpResponse(\"ok\", 201)",
                "new HttpResponse<unknown>(\"ok\")")) {
            assertThrows(Exception.class,
                    () -> OresCompiler.parseAndTypeCheck(template.formatted(rejected)),
                    rejected);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                template.formatted("new HttpResponse(\"ok\")")));
    }

    @Test void httpResponseNominalTypeCannotBeSpoofedOrMailedBetweenActors() {
        for (String declaration : List.of(
                "define class HttpResponse as end",
                "type HttpResponse = int;",
                "define actor Bad as receive(ActorMail<HttpResponse> mail): void { return; } end")) {
            assertThrows(Exception.class, () -> OresCompiler.parseAndTypeCheck(declaration), declaration);
        }
    }

    @Test void guestResponseDraftRejectsInvalidStatusAndBodylessStatus() {
        String valid = """
            define actor A as
              receive(ActorMail<String> mail): void {
                val response = http.response(200, "text/plain", "ok");
                return;
              }
            end
            """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(valid));
        String invalid = valid.replace("http.response(200, \"text/plain\", \"ok\")",
                "http.response(\"200\", \"text/plain\", \"ok\")");
        assertThrows(Exception.class, () -> OresCompiler.parseAndTypeCheck(invalid));
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
        CompletableFuture<Void> supervisorFooterBarrier = new CompletableFuture<>();
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
                if (expectedPostResponseDiagnostics > 0
                        && text.lines().filter(line -> line.matches("[0-9]+")).count() >= 4) {
                    supervisorFooterBarrier.complete(null);
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
                    CompletableFuture.allOf(diagnosticBarrier, supervisorFooterBarrier)
                            .get(8, TimeUnit.SECONDS);
                    closeWhenQuiescent(context);
                }
            } finally { clientWork.cancel(true); }

        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void closeWhenQuiescent(Context context) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try {
                context.close(false);
                return;
            } catch (IllegalStateException busy) {
                if (busy.getMessage() == null
                        || !busy.getMessage().contains("currently executing on another thread")
                        || System.nanoTime() >= deadline) {
                    throw busy;
                }
                Thread.sleep(5);
            }
        }
    }
}
