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
    @Test void pooledAdmissionIsAtomicBoundedAndWorkerSpecific() throws Exception {
        var tracked = new ConcurrentHashMap<String, Set<Integer>>();
        tracked.put("worker-a", ConcurrentHashMap.newKeySet());
        var outcomes = new ConcurrentLinkedQueue<NativeHttp.PoolAdmission>();
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(1);
            var executions = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 256; i++) {
                final int token = i;
                executions.add(calls.submit(() -> {
                    ready.await();
                    outcomes.add(NativeHttp.registerPoolAdmission(tracked, "worker-a", token, 64));
                    return null;
                }));
            }
            ready.countDown();
            for (var task : executions) task.get(10, TimeUnit.SECONDS);
        }
        assertEquals(64, tracked.get("worker-a").size());
        assertEquals(64, outcomes.stream().filter(x -> x == NativeHttp.PoolAdmission.REGISTERED).count());
        assertEquals(192, outcomes.stream().filter(x -> x == NativeHttp.PoolAdmission.FULL).count());
        tracked.remove("worker-a");
        assertEquals(NativeHttp.PoolAdmission.WORKER_GONE,
                NativeHttp.registerPoolAdmission(tracked, "worker-a", 999, 64));
        tracked.put("worker-b", ConcurrentHashMap.newKeySet());
        assertEquals(NativeHttp.PoolAdmission.REGISTERED,
                NativeHttp.registerPoolAdmission(tracked, "worker-b", 999, 64));
        assertEquals(1, tracked.get("worker-b").size(),
                "replacement workers must not inherit an old worker's retained exchanges");
    }

    @Test void cleanupAcceptedOrRejectedByExecutorRunsExactlyOnceAndNeverInlineOnRejection() throws Exception {
        java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
        NativeHttp.executeCleanup(Runnable::run, successes::incrementAndGet);
        assertEquals(1, successes.get(), "accepted cleanup should execute exactly once");

        CountDownLatch finished = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> carrier =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = Thread.currentThread();
        NativeHttp.executeCleanup(task -> { throw new RejectedExecutionException("closing"); }, () -> {
            carrier.set(Thread.currentThread());
            successes.incrementAndGet();
            finished.countDown();
        });
        assertTrue(finished.await(5, TimeUnit.SECONDS), "rejected cleanup must run without an executor");
        assertEquals(2, successes.get(), "rejected cleanup should execute exactly once");
        assertNotSame(caller, carrier.get(), "fallback socket cleanup must not park the caller");
        assertTrue(carrier.get().isVirtual(), "fallback should use an independent virtual thread");
    }

    @Test void repeatedGracefulShutdownReturnsASettledDrainAfterLastExchange() throws Exception {
        String output = run("""
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 1, 1024, 3000);
              stdio.println(server.port);
              val request = await server.accept();
              val completed = request.completion;
              await request.respond(200, "text/plain", "drained");
              stdio.println(await completed);
              val first = server.shutdown(0);
              val second = server.shutdown(0);
              await first;
              await second;
              stdio.println("DRAINED");
              stdio.println(server.active);
              stdio.println(server.accepted);
              stdio.println(server.completed);
              return;
            }
            """, port -> {
                HttpResponse<String> response = request(port, "GET", "/", "");
                assertEquals(200, response.statusCode());
                assertEquals("drained", response.body());
            });
        assertTrue(output.contains("200\nDRAINED\n0\n1\n1"),
                "repeated shutdown should settle without retaining admissions: " + output);
    }

    @Test void pooledAdmissionIsBoundedUnderConcurrentReservationReleaseAndWorkerDeath() throws Exception {
        var registry = new java.util.concurrent.ConcurrentHashMap<String, Set<Integer>>();
        registry.put("worker", java.util.concurrent.ConcurrentHashMap.newKeySet());
        final int capacity = 64;
        final int contenders = 256;
        var start = new CountDownLatch(1);
        var finished = new CountDownLatch(contenders);
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < contenders; i++) {
                final int request = i;
                executor.submit(() -> {
                    try {
                        start.await();
                        var result = NativeHttp.registerPoolAdmission(
                            registry, "worker", request, capacity);
                        if (result == NativeHttp.PoolAdmission.REGISTERED) accepted.incrementAndGet();
                        else if (result == NativeHttp.PoolAdmission.FULL) rejected.incrementAndGet();
                        else throw new AssertionError("live worker unexpectedly missing");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
        }
        assertEquals(capacity, accepted.get());
        assertEquals(contenders - capacity, rejected.get());
        assertEquals(capacity, registry.get("worker").size());

        // Releasing one completed exchange must make room for exactly one
        // subsequent reservation without exceeding the capacity.
        int victim = registry.get("worker").iterator().next();
        registry.get("worker").remove(victim);
        assertEquals(NativeHttp.PoolAdmission.REGISTERED,
            NativeHttp.registerPoolAdmission(registry, "worker", 1000, capacity));
        assertTrue(registry.get("worker").size() <= capacity);

        registry.remove("worker");
        assertEquals(NativeHttp.PoolAdmission.WORKER_GONE,
            NativeHttp.registerPoolAdmission(registry, "worker", 1001, capacity));
        assertFalse(registry.containsKey("worker"),
            "termination must not recreate an outstanding-exchange registry");
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
