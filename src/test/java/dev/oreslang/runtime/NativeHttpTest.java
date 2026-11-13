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
    @Test void httpDispatchPreflightSealsBothRuntimeAndListenerAdmissions() {
        for (boolean runtimeClosed : new boolean[]{false, true}) {
            for (boolean listenerStopped : new boolean[]{false, true}) {
                assertEquals(!runtimeClosed && !listenerStopped,
                        NativeHttp.mayBeginDispatch(runtimeClosed, listenerStopped),
                        "runtimeClosed=" + runtimeClosed + ", listenerStopped=" + listenerStopped);
            }
        }
        assertFalse(NativeHttp.mayBeginDispatch(true, false));
        assertFalse(NativeHttp.mayBeginDispatch(false, true));
    }

    @Test void requestPermitRetirementWaitsForDispatchLeaseAndPhysicalActorExit() {
        // Production State.release() invokes this predicate under its State
        // monitor. All eight combinations are part of the shutdown contract.
        for (boolean transportClosed : new boolean[]{false, true}) {
            for (boolean dispatchInProgress : new boolean[]{false, true}) {
                for (boolean actorPhysicallyDone : new boolean[]{false, true}) {
                    boolean expected = transportClosed && !dispatchInProgress && actorPhysicallyDone;
                    assertEquals(expected, NativeHttp.canRetireRequest(
                                    transportClosed, dispatchInProgress, actorPhysicallyDone),
                            "transportClosed=" + transportClosed
                                    + ", dispatchInProgress=" + dispatchInProgress
                                    + ", actorPhysicallyDone=" + actorPhysicallyDone);
                }
            }
        }
        assertFalse(NativeHttp.canRetireRequest(true, true, true),
                "response close during handoff cannot release request admission");
        assertFalse(NativeHttp.canRetireRequest(true, false, false),
                "handoff alone cannot retire an actor with active physical carrier");
        assertTrue(NativeHttp.canRetireRequest(true, false, true));
    }

    @org.junit.jupiter.api.RepeatedTest(16)
    @org.junit.jupiter.api.Timeout(8)
    void concurrentTimeoutDuringDispatchHandoffNeverRetiresEarly() throws Exception {
        var handoffStarted = new CountDownLatch(1);
        var releaseHandoff = new CountDownLatch(1);
        var inDispatch = new java.util.concurrent.atomic.AtomicBoolean();
        var retired = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> selectedWorker = executor.submit(() -> {
                inDispatch.set(true);
                handoffStarted.countDown();
                try {
                    if (!releaseHandoff.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("handoff signal lost");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    inDispatch.set(false);
                }
            });
            try {
                assertTrue(handoffStarted.await(5, TimeUnit.SECONDS));
                // Timeout closes the transport while a selected worker is
                // not yet associated with the HTTP State; actor completion
                // cannot make its reservation prematurely reusable.
                if (NativeHttp.canRetireRequest(true, inDispatch.get(), true)) {
                    retired.incrementAndGet();
                }
                assertEquals(0, retired.get());
            } finally {
                releaseHandoff.countDown();
            }
            selectedWorker.get(5, TimeUnit.SECONDS);
            assertTrue(NativeHttp.canRetireRequest(true, inDispatch.get(), true));
        }
    }

    @Test void physicalCarrierBarrierRequiresTransportStopCallbackExitAndNoActiveRequest() {
        for (boolean transportStopped : new boolean[]{false,true}) {
            for (boolean empty : new boolean[]{false,true}) {
                for (int publishing : new int[]{0,1,2}) {
                    assertEquals(transportStopped && empty && publishing == 0,
                            NativeHttp.readyForCarrierBarrier(transportStopped, empty, publishing));
                }
            }
        }
    }

    @org.junit.jupiter.api.RepeatedTest(24)
    @org.junit.jupiter.api.Timeout(10)
    void completionCallbackCannotRaceLastExchangeOutOfCarrierBarrier() throws Exception {
        var releasing = new java.util.concurrent.atomic.AtomicInteger();
        var activeEmpty = new java.util.concurrent.atomic.AtomicBoolean();
        var removed = new CountDownLatch(1);
        var publish = new CountDownLatch(1);
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = worker.submit(() -> {
                releasing.incrementAndGet();
                try {
                    activeEmpty.set(true);
                    removed.countDown();
                    if (!publish.await(5, TimeUnit.SECONDS)) throw new AssertionError("release gate");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    releasing.decrementAndGet();
                }
            });
            try {
                assertTrue(removed.await(5, TimeUnit.SECONDS));
                assertFalse(NativeHttp.readyForCarrierBarrier(
                        true, activeEmpty.get(), releasing.get()));
            } finally {
                publish.countDown();
            }
            future.get(5, TimeUnit.SECONDS);
            assertTrue(NativeHttp.readyForCarrierBarrier(
                    true, activeEmpty.get(), releasing.get()));
        }
    }

    @org.junit.jupiter.api.RepeatedTest(8)
    @org.junit.jupiter.api.Timeout(25)
    void pooledListenerDrainWaitsForPostResponseGuestCarrierExit() throws Exception {
        String output = run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    await request.respond(200, "text/plain", "ok");
                    stdio.println("worker-finished");
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 1, 1024, 5000);
                  val handler = http.handler("Worker").pooled(1);
                  stdio.println(server.port);
                  val request = await server.accept();
                  val completion = request.completion;
                  handler.dispatch(rt mut borrow request);
                  await completion;
                  await server.shutdown(0);
                  stdio.println("drain-finished");
                  return;
                }
                """, port -> assertEquals(200, request(port, "GET", "/", "").statusCode()));
        assertTrue(output.contains("worker-finished"), output);
        assertTrue(output.contains("drain-finished"), output);
        assertTrue(output.indexOf("worker-finished") < output.indexOf("drain-finished"),
                "listener reported drained while its accepted receive was still running: " + output);
    }

    @org.junit.jupiter.api.RepeatedTest(8)
    @org.junit.jupiter.api.Timeout(25)
    void unpooledShutdownRequiresReceiveTerminationAndPhysicalExit() throws Exception {
        // A fresh actor cannot retire its HTTP permit solely because DONE
        // notified its subscribers. The host physical exit observation must
        // follow the actor's last response and completed receive turn.
        String output = run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    await request.respond(200, "text/plain", "ok");
                    stdio.println("unpooled-last-receive");
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 1, 1024, 5000);
                  stdio.println(server.port);
                  val handler = http.handler("Worker");
                  val request = await server.accept();
                  val completed = request.completion;
                  handler.dispatch(rt mut borrow request);
                  await completed;
                  await server.shutdown(0);
                  stdio.println("unpooled-drain-complete");
                  stdio.println(server.active);
                  return;
                }
                """, port -> assertEquals(200,
                        request(port, "GET", "/", "").statusCode()));
        assertTrue(output.contains("unpooled-last-receive"), output);
        assertTrue(output.contains("unpooled-drain-complete"), output);
        assertTrue(output.indexOf("unpooled-last-receive")
                        < output.indexOf("unpooled-drain-complete"),
                "listener drained before last unpooled receive: " + output);
        assertTrue(output.endsWith("0\n"), output);
    }

    @org.junit.jupiter.api.RepeatedTest(16)
    @org.junit.jupiter.api.Timeout(12)
    void repeatedConcurrentForcedCloseClaimsExactlyOneOwner() throws Exception {
        var claimed = new java.util.concurrent.atomic.AtomicBoolean();
        var winner = new java.util.concurrent.atomic.AtomicInteger();
        var go = new CountDownLatch(1);
        var ready = new CountDownLatch(16);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i=0;i<16;i++) {
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!go.await(5,TimeUnit.SECONDS)) throw new AssertionError("missing start");
                    if (NativeHttp.claimForcedClose(claimed)) winner.incrementAndGet();
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for(var task:tasks) task.get(5,TimeUnit.SECONDS);
        }
        assertEquals(1,winner.get());
        assertFalse(NativeHttp.claimForcedClose(claimed));
    }

    @Test void pendingAcceptCallbacksCannotExecuteUnderListenerMonitor() throws Exception {
        Object listenerMonitor=new Object();
        OresFuture<Object> accepted = new OresFuture<>(() -> false, () -> { });
        CountDownLatch called = new CountDownLatch(1);
        var nestedLock = new java.util.concurrent.atomic.AtomicBoolean();
        accepted.whenCompleteRuntime((value,error) -> {
            nestedLock.set(Thread.holdsLock(listenerMonitor));
            called.countDown();
        });
        synchronized (listenerMonitor) {
            assertThrows(IllegalStateException.class,
                    () -> NativeHttp.publishOutsideMonitor(listenerMonitor,accepted,"ok"));
            assertFalse(accepted.isDone());
        }
        NativeHttp.publishOutsideMonitor(listenerMonitor,accepted,"ok");
        assertTrue(called.await(5,TimeUnit.SECONDS));
        assertFalse(nestedLock.get());
    }

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

    @org.junit.jupiter.api.RepeatedTest(4)
    @org.junit.jupiter.api.Timeout(45)
    void twoListenersSharingHandlerUseDistinctPooledWorkersAfterFirstShutdown() throws Exception {
        // Real Atto guest + loopback HTTP. Listener A's shutdown seals the
        // shared worker incarnation. Listener B must allocate a distinct
        // generation instead of sending to A's retired worker.
        var firstPort = new CompletableFuture<Integer>();
        var secondPort = new CompletableFuture<Integer>();
        var firstDrain = new CompletableFuture<Void>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                String text = toString(StandardCharsets.UTF_8);
                // Only consume complete lines: stdout may deliver a single
                // decimal port split across multiple write calls.
                int newline = text.lastIndexOf('\n');
                if (newline < 0) return;
                String completeLines = text.substring(0, newline + 1);
                var numeric = completeLines.lines().filter(s -> s.matches("[0-9]+"))
                        .map(Integer::parseInt).toList();
                if (!numeric.isEmpty() && !firstPort.isDone()) firstPort.complete(numeric.get(0));
                if (numeric.size() >= 2 && !secondPort.isDone()) secondPort.complete(numeric.get(1));
                if (completeLines.contains("first-shutdown-completed\n")) firstDrain.complete(null);
            }
        };
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    await request.respond(200, "text/plain", "ok");
                    return;
                  }
                end
                pub async routine main(): void {
                  val first = http.listen("127.0.0.1", 0, 1, 1024, 5000);
                  val second = http.listen("127.0.0.1", 0, 48, 1024, 5000);
                  val handler = http.handler("Worker").pooled(1);
                  stdio.println(first.port);
                  stdio.println(second.port);
                  val firstRequest = await first.accept();
                  val firstCompleted = firstRequest.completion;
                  handler.dispatch(rt mut borrow firstRequest);
                  await firstCompleted;
                  await first.shutdown(0);
                  stdio.println("first-shutdown-completed");
                  for (let int i = 0; i < 32; i = i + 1) {
                    val secondRequest = await second.accept();
                    handler.dispatch(rt mut borrow secondRequest);
                  }
                  await second.shutdown(3000);
                  stdio.println(handler.created);
                  stdio.println(handler.reused);
                  return;
                }
                """;
        try (Context context = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                .out(output).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> client = clients.submit(() -> {
                try {
                    int a = firstPort.get(10, TimeUnit.SECONDS);
                    int b = secondPort.get(10, TimeUnit.SECONDS);
                    assertNotEquals(a, b, "listeners must be separately bound");
                    assertEquals(200, request(a, "GET", "/", "").statusCode());
                    firstDrain.get(15, TimeUnit.SECONDS);
                    try (HttpClient httpClient = HttpClient.newHttpClient()) {
                        var pending = new java.util.ArrayList<CompletableFuture<HttpResponse<String>>>();
                        for (int i = 0; i < 32; ++i) {
                            pending.add(httpClient.sendAsync(
                                    HttpRequest.newBuilder(URI.create(
                                            "http://127.0.0.1:" + b + "/"))
                                            .timeout(Duration.ofSeconds(12)).build(),
                                    HttpResponse.BodyHandlers.ofString()));
                        }
                        for (var response : pending) {
                            assertEquals(200, response.get(15, TimeUnit.SECONDS).statusCode());
                        }
                    }
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, source,
                        "native-http-shared-pool-shutdown.atto").build());
                client.get(30, TimeUnit.SECONDS);
            } finally {
                client.cancel(true);
            }
        }
        assertTrue(firstDrain.isDone(), "first listener never completed carrier drain");
        assertTrue(output.toString(StandardCharsets.UTF_8).endsWith("2\n31\n"),
                "sealed worker must be replaced before the next listener's 32-request burst: "
                        + output.toString(StandardCharsets.UTF_8));
    }

    @Test
    @org.junit.jupiter.api.Timeout(45)
    void twoListenersSharingHandlerDrainIndependentlyWithoutStoppingEachOther()
            throws Exception {
        // The Handler is shared, but each listener owns a distinct pooled
        // ActorRef. Listener A must complete its own *successful* physical
        // shutdown while listener B remains alive and can reuse its actor.
        var firstPort = new CompletableFuture<Integer>();
        var secondPort = new CompletableFuture<Integer>();
        var firstDrain = new CompletableFuture<Void>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int off, int len) {
                super.write(bytes, off, len);
                String text = toString(StandardCharsets.UTF_8);
                int newline = text.lastIndexOf('\n');
                if (newline < 0) return;
                String complete = text.substring(0, newline + 1);
                var ports = complete.lines().filter(line -> line.matches("[0-9]+"))
                        .map(Integer::parseInt).toList();
                if (!ports.isEmpty() && !firstPort.isDone()) firstPort.complete(ports.get(0));
                if (ports.size() >= 2 && !secondPort.isDone()) secondPort.complete(ports.get(1));
                if (complete.contains("first-shutdown-completed\n")) firstDrain.complete(null);
            }
        };
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    await request.respond(200, "text/plain", "ok");
                    return;
                  }
                end
                pub async routine main(): void {
                  val first = http.listen("127.0.0.1", 0, 4, 1024, 5000);
                  val second = http.listen("127.0.0.1", 0, 4, 1024, 5000);
                  val handler = http.handler("Worker").pooled(1);
                  stdio.println(first.port);
                  stdio.println(second.port);
                  val firstRequest = await first.accept();
                  val firstCompleted = firstRequest.completion;
                  handler.dispatch(rt mut borrow firstRequest);
                  await firstCompleted;
                  val secondRequest = await second.accept();
                  val secondCompleted = secondRequest.completion;
                  handler.dispatch(rt mut borrow secondRequest);
                  await secondCompleted;
                  await first.shutdown(0);
                  stdio.println("first-shutdown-completed");
                  val nextRequest = await second.accept();
                  val nextCompleted = nextRequest.completion;
                  handler.dispatch(rt mut borrow nextRequest);
                  await nextCompleted;
                  await second.shutdown(0);
                  stdio.println("other-listener-still-served");
                  stdio.println(handler.created);
                  stdio.println(handler.reused);
                  return;
                }
                """;
        try (Context context = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                .out(output).build();
             ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> client = clients.submit(() -> {
                try {
                    int a = firstPort.get(10, TimeUnit.SECONDS);
                    int b = secondPort.get(10, TimeUnit.SECONDS);
                    assertNotEquals(a, b);
                    assertEquals(200, request(a, "GET", "/", "").statusCode());
                    assertEquals(200, request(b, "GET", "/", "").statusCode());
                    firstDrain.get(15, TimeUnit.SECONDS);
                    assertEquals(200, request(b, "GET", "/", "").statusCode(),
                            "B must remain usable after A successfully drains");
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                context.eval(Source.newBuilder(OresLanguage.ID, source,
                        "native-http-listener-isolation.atto").build());
                client.get(30, TimeUnit.SECONDS);
            } finally {
                client.cancel(true);
            }
        }
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(firstDrain.isDone(), "A never completed successful physical drain: " + text);
        assertTrue(text.contains("other-listener-still-served"), text);
        assertTrue(text.endsWith("2\n1\n"),
                "each listener must own a distinct actor; B must reuse only B's worker: " + text);
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
