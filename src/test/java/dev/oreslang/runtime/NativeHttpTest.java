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
    @org.junit.jupiter.api.RepeatedTest(32)
    @org.junit.jupiter.api.Timeout(10)
    void abortDuringBlockedDispatchCannotReleaseAdmissionBeforeHandoff()
            throws Exception {
        // Drive the production State.release() admission predicate across a
        // deterministic two-thread ordering: one thread owns dispatch, while
        // another observes the transport abort. This tests the predicate's
        // cross-thread contract; GraalVM guest execution is covered separately.
        Object ownershipMonitor = new Object();
        var dispatching = new java.util.concurrent.atomic.AtomicBoolean();
        var transportClosed = new java.util.concurrent.atomic.AtomicBoolean();
        var selected = new CountDownLatch(1);
        var finishHandoff = new CountDownLatch(1);
        var releaseCount = new java.util.concurrent.atomic.AtomicInteger();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> handoff = executor.submit(() -> {
                synchronized (ownershipMonitor) {
                    dispatching.set(true);
                    selected.countDown();
                }
                try {
                    if (!finishHandoff.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("dispatch handoff not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    synchronized (ownershipMonitor) {
                        dispatching.set(false);
                    }
                }
            });
            try {
                assertTrue(selected.await(5, TimeUnit.SECONDS));
                synchronized (ownershipMonitor) {
                    transportClosed.set(true); // request deadline/abort wins
                    if (NativeHttp.canRetireRequest(
                            transportClosed.get(), dispatching.get(), false)) {
                        releaseCount.incrementAndGet();
                    }
                }
                assertEquals(0, releaseCount.get(),
                        "transport abort must not retire an in-flight actor dispatch");
            } finally {
                finishHandoff.countDown();
            }
            handoff.get(5, TimeUnit.SECONDS);
            synchronized (ownershipMonitor) {
                if (NativeHttp.canRetireRequest(
                        transportClosed.get(), dispatching.get(), false)) {
                    releaseCount.incrementAndGet();
                }
            }
            assertEquals(1, releaseCount.get(),
                    "completed dispatch must reclaim admission exactly once");
        }
    }

    @org.junit.jupiter.api.RepeatedTest(25)
    @org.junit.jupiter.api.Timeout(10)
    void concurrentForcedListenerCloseHasExactlyOneWinner() throws Exception {
        // This is the same claim used by Server.close(). Every losing caller
        // must return without invoking transport.stop or state cleanup.
        var claimed = new java.util.concurrent.atomic.AtomicBoolean();
        var winners = new java.util.concurrent.atomic.AtomicInteger();
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(64);
        var threads = new java.util.ArrayList<Thread>();
        for (int i = 0; i < 64; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS)) return;
                    if (NativeHttp.claimForcedClose(claimed)) winners.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
            assertFalse(thread.isAlive(), "concurrent close did not return");
        }
        assertEquals(1, winners.get(), "transport stop must have a single owner");
        assertTrue(claimed.get());
        assertFalse(NativeHttp.claimForcedClose(claimed),
                "a repeated forced close cannot claim another shutdown");
    }

    @org.junit.jupiter.api.RepeatedTest(25)
    void acceptFutureSubscribersNeverRunUnderListenerMonitor() throws Exception {
        // This exercises the exact production publication gate and Future
        // notification implementation (not a Boolean lifecycle predicate).
        // An accept callback can synchronously wait for another thread to
        // take the listener monitor, so completing under it would deadlock.
        Object listenerMonitor = new Object();
        OresFuture<Object> accepted = new OresFuture<>(() -> false, () -> { });
        Object exchange = new Object();
        CountDownLatch delivered = new CountDownLatch(1);
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        var received = new java.util.concurrent.atomic.AtomicReference<Object>();
        var receivedFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var heldMonitor = new java.util.concurrent.atomic.AtomicBoolean();
        var blockedPeer = new java.util.concurrent.atomic.AtomicBoolean();
        accepted.whenCompleteRuntime((value, failure) -> {
            callbacks.incrementAndGet();
            received.set(value);
            receivedFailure.set(failure);
            if (Thread.holdsLock(listenerMonitor)) heldMonitor.set(true);
            CountDownLatch peerAcquired = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                synchronized (listenerMonitor) { peerAcquired.countDown(); }
            });
            try {
                if (!peerAcquired.await(2, TimeUnit.SECONDS)) blockedPeer.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                blockedPeer.set(true);
            } finally {
                delivered.countDown();
            }
        });
        synchronized (listenerMonitor) {
            assertThrows(IllegalStateException.class, () ->
                    NativeHttp.publishOutsideMonitor(listenerMonitor, accepted, exchange));
            assertFalse(accepted.isDone(),
                    "a rejected under-lock publication must not settle the accept");
        }
        NativeHttp.publishOutsideMonitor(listenerMonitor, accepted, exchange);
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
        assertTrue(accepted.isDone());
        assertSame(exchange, accepted.get(5, TimeUnit.SECONDS));
        assertSame(exchange, received.get(), "accept callback received the wrong exchange");
        assertNull(receivedFailure.get());
        assertEquals(1, callbacks.get(), "accept callback must run exactly once");
        assertFalse(heldMonitor.get(), "accept callback executed inside listener monitor");
        assertFalse(blockedPeer.get(),
                "accept callback blocked another thread's listener operation");
    }

    @org.junit.jupiter.api.RepeatedTest(25)
    void pendingAcceptFailsAfterListenerMonitorIsReleased() throws Exception {
        Object listenerMonitor = new Object();
        OresFuture<Object> pending = new OresFuture<>(() -> false, () -> { });
        var failure = new IllegalStateException("HTTP listener sealed");
        var observedFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var observedHeldLock = new java.util.concurrent.atomic.AtomicBoolean();
        var peerBlocked = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch callbackExited = new CountDownLatch(1);
        pending.whenCompleteRuntime((ignored, rejected) -> {
            observedFailure.set(rejected);
            if (Thread.holdsLock(listenerMonitor)) observedHeldLock.set(true);
            CountDownLatch peerEntered = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                synchronized (listenerMonitor) { peerEntered.countDown(); }
            });
            try {
                if (!peerEntered.await(2, TimeUnit.SECONDS)) peerBlocked.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                peerBlocked.set(true);
            } finally {
                callbackExited.countDown();
            }
        });
        synchronized (listenerMonitor) {
            assertThrows(IllegalStateException.class, () ->
                    NativeHttp.failOutsideMonitor(listenerMonitor, pending, failure));
            assertFalse(pending.isDone(), "under-lock rejection must not fail an accept");
        }
        NativeHttp.failOutsideMonitor(listenerMonitor, pending, failure);
        assertTrue(callbackExited.await(5, TimeUnit.SECONDS));
        var thrown = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pending.get(5, TimeUnit.SECONDS));
        assertSame(failure, thrown.getCause());
        assertSame(failure, observedFailure.get());
        assertFalse(observedHeldLock.get(),
                "shutdown accept failure callback ran while listener was locked");
        assertFalse(peerBlocked.get(),
                "shutdown accept failure callback blocked competing listener admission");
    }

    @Test void noActorDispatchOrTransferAfterRuntimeOrListenerClose() {
        assertTrue(NativeHttp.mayBeginDispatch(false, false));
        for (boolean runtimeClosed : new boolean[] {false, true}) {
            for (boolean listenerStopping : new boolean[] {false, true}) {
                assertEquals(!runtimeClosed && !listenerStopping,
                        NativeHttp.mayBeginDispatch(runtimeClosed, listenerStopping),
                        "runtimeClosed=" + runtimeClosed
                                + ", listenerStopping=" + listenerStopping);
            }
        }
        assertFalse(NativeHttp.mayBeginDispatch(true, false));
        assertFalse(NativeHttp.mayBeginDispatch(false, true));
    }

    @Test void dispatchLeasePreventsPrematureRequestRetirement() {
        // Exercise the same predicate used by State.release(), including
        // an abort that closes the socket while actor initialization or a
        // mailbox send still owns the dispatch admission lease.
        for (boolean transportClosed : new boolean[] { false, true }) {
            for (boolean dispatching : new boolean[] { false, true }) {
                for (boolean actorPending : new boolean[] { false, true }) {
                    assertEquals(transportClosed && !dispatching && !actorPending,
                            NativeHttp.canRetireRequest(
                                    transportClosed, dispatching, actorPending),
                            "closed=" + transportClosed + ", dispatching=" + dispatching
                                    + ", actorPending=" + actorPending);
                }
            }
        }
        assertFalse(NativeHttp.canRetireRequest(true, true, false),
                "socket close during pooled worker selection cannot retire admission");
        assertFalse(NativeHttp.canRetireRequest(true, false, true),
                "unpooled actor initialization must finalize before retirement");
        assertTrue(NativeHttp.canRetireRequest(true, false, false),
                "a closed transport with no dispatch or actor lease can retire");
    }

    @org.junit.jupiter.api.RepeatedTest(25)
    @org.junit.jupiter.api.Timeout(10)
    void shutdownCannotPublishSuccessUntilAllAbortCleanupHasRun() throws Exception {
        var cleanupEntered = new CountDownLatch(1);
        var allowCleanup = new CountDownLatch(1);
        var stoppedPublished = new CountDownLatch(1);
        var successfulDrain = new java.util.concurrent.atomic.AtomicBoolean();

        try (ExecutorService carrier = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> stop = carrier.submit(() -> NativeHttp.finishShutdownCleanup(
                    () -> {
                        cleanupEntered.countDown();
                        try {
                            if (!allowCleanup.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("abort cleanup gate expired");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }, () -> {
                        stoppedPublished.countDown();
                        successfulDrain.set(true);
                    }));
            try {
                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
                assertEquals(1L, stoppedPublished.getCount(),
                        "transport stop became visible before abort cleanup finished");
                assertFalse(successfulDrain.get(),
                        "a concurrent request release could falsely succeed here");
            } finally {
                allowCleanup.countDown();
            }
            stop.get(5, TimeUnit.SECONDS);
            assertEquals(0L, stoppedPublished.getCount());
            assertTrue(successfulDrain.get());
        }
    }

    @Test void shutdownPublishesTerminalStateEvenWhenAbortCleanupThrows() {
        var publication = new java.util.concurrent.atomic.AtomicInteger();
        var aborted = new IllegalStateException("abort failed");
        assertSame(aborted, assertThrows(IllegalStateException.class,
                () -> NativeHttp.finishShutdownCleanup(
                        () -> { throw aborted; },
                        publication::incrementAndGet)));
        assertEquals(1, publication.get(),
                "failed cleanup must still allow terminal failure publication");
    }

    @org.junit.jupiter.api.RepeatedTest(25)
    @org.junit.jupiter.api.Timeout(10)
    void lastRequestRemovalCannotFinishShutdownDuringCompletionPublication()
            throws Exception {
        // State.release removes a request from active before completing its
        // OresFuture. Race that exact ordering against the production gate:
        // active.isEmpty() is NOT permission to publish a drained listener.
        var releasesInProgress = new java.util.concurrent.atomic.AtomicInteger();
        var activeEmpty = new java.util.concurrent.atomic.AtomicBoolean();
        var removedLastRequest = new CountDownLatch(1);
        var allowCompletionToReturn = new CountDownLatch(1);

        assertFalse(NativeHttp.readyForCarrierBarrier(false, true, 0));
        assertFalse(NativeHttp.readyForCarrierBarrier(true, false, 0));
        assertFalse(NativeHttp.readyForCarrierBarrier(true, true, 2));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> release = executor.submit(() -> {
                releasesInProgress.incrementAndGet();
                try {
                    activeEmpty.set(true);
                    removedLastRequest.countDown();
                    if (!allowCompletionToReturn.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("completion callback gate timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    releasesInProgress.decrementAndGet();
                }
            });
            try {
                assertTrue(removedLastRequest.await(5, TimeUnit.SECONDS));
                assertTrue(activeEmpty.get(), "last request must already be removed");
                assertFalse(NativeHttp.readyForCarrierBarrier(
                                true, activeEmpty.get(), releasesInProgress.get()),
                        "shutdown succeeded before request completion was published");
            } finally {
                allowCompletionToReturn.countDown();
            }
            release.get(5, TimeUnit.SECONDS);
            assertEquals(0, releasesInProgress.get());
            assertTrue(NativeHttp.readyForCarrierBarrier(
                    true, activeEmpty.get(), releasesInProgress.get()),
                    "the final completed request must unblock shutdown");
        }
    }

    @Test void abortNeverCancelsAWorkerSharedByOtherListeners() {
        // A worker generation can be simultaneously referenced by two HTTP
        // listeners. Neither a single deadline nor a forced listener shutdown
        // may cancel that actor; only a fresh per-request actor is owned.
        for (boolean cancelOwner : new boolean[] {false, true}) {
            for (boolean pooled : new boolean[] {false, true}) {
                assertEquals(cancelOwner && !pooled,
                        NativeHttp.shouldCancelActorForAbort(cancelOwner, pooled),
                        "cancelOwner=" + cancelOwner + ", pooled=" + pooled);
            }
        }
        assertFalse(NativeHttp.shouldCancelActorForAbort(true, true),
                "aborting listener A must not cancel the worker for listener B");
        assertTrue(NativeHttp.shouldCancelActorForAbort(true, false),
                "aborting a fresh actor must still request cancellation");
    }

    @org.junit.jupiter.api.RepeatedTest(100)
    void cancellationFailureStillClosesTransportAndReleasesAdmission() {
        var calls = new java.util.ArrayList<String>();
        var cancelFailure = new IllegalStateException("actor cancellation failed");
        var closeFailure = new IllegalStateException("socket closure failed");

        assertSame(cancelFailure, assertThrows(IllegalStateException.class, () ->
                NativeHttp.runCancellationThenCleanup(
                        () -> { calls.add("cancel"); throw cancelFailure; },
                        () -> calls.add("release"))));
        assertEquals(List.of("cancel", "release"), calls);
        calls.clear();

        assertSame(cancelFailure, assertThrows(IllegalStateException.class, () ->
                NativeHttp.runCancellationThenCleanup(
                        () -> { calls.add("cancel"); throw cancelFailure; },
                        () -> { calls.add("release"); throw closeFailure; })));
        assertEquals(List.of("cancel", "release"), calls);
        assertArrayEquals(new Throwable[] {closeFailure}, cancelFailure.getSuppressed());
        calls.clear();

        assertSame(closeFailure, assertThrows(IllegalStateException.class, () ->
                NativeHttp.runCancellationThenCleanup(
                        () -> calls.add("cancel"),
                        () -> { calls.add("release"); throw closeFailure; })));
        assertEquals(List.of("cancel", "release"), calls);
        calls.clear();

        NativeHttp.runCancellationThenCleanup(
                () -> calls.add("cancel"), () -> calls.add("release"));
        assertEquals(List.of("cancel", "release"), calls);
    }

    @org.junit.jupiter.api.RepeatedTest(100)
    void timeoutDetachesPendingPhysicalQuiescenceCallback() {
        OresFuture<Void> waitingOnStalledActor = new OresFuture<>();
        OresFuture<Object> listenerDrain = new OresFuture<>();
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        var reg = waitingOnStalledActor.whenCompleteRuntimeCancellable(
                (ignored, failure) -> callbacks.incrementAndGet());
        assertEquals(1, waitingOnStalledActor.pendingRuntimeWaiterCount());

        listenerDrain.whenCompleteRuntime((ignored, failure) -> reg.detach());
        assertTrue(listenerDrain.failFromRuntime(
                new TimeoutException("actor carrier did not quiesce")));
        assertEquals(0, waitingOnStalledActor.pendingRuntimeWaiterCount(),
                "timed-out server must not remain retained by a live actor's Future");
        assertTrue(waitingOnStalledActor.completeFromRuntime(null));
        assertEquals(0, callbacks.get(),
                "late actor quiescence must not replay a timed-out listener callback");
    }

    @org.junit.jupiter.api.RepeatedTest(100)
    void quiescenceSubscriptionRegistrationRacingTimeoutStillDetaches()
            throws Exception {
        OresFuture<Void> actorQuiescence = new OresFuture<>();
        OresFuture<Object> drain = new OresFuture<>();
        var subscribers = new ConcurrentLinkedQueue<
                OresFuture.RuntimeWaiterRegistration<Void>>();
        drain.whenCompleteRuntime((ignored, failure) -> {
            for (var subscription : subscribers) subscription.detach();
            subscribers.clear();
        });
        CountDownLatch registrationReady = new CountDownLatch(1);
        CountDownLatch publishRegistration = new CountDownLatch(1);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> registration = workers.submit(() -> {
                var sub = actorQuiescence.whenCompleteRuntimeCancellable(
                        (ignored, failure) -> { });
                registrationReady.countDown();
                try {
                    if (!publishRegistration.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test registration gate timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(interrupted);
                }
                subscribers.add(sub);
                if (drain.isDone()) sub.detach();
            });
            try {
                assertTrue(registrationReady.await(5, TimeUnit.SECONDS));
                assertTrue(drain.failFromRuntime(new TimeoutException("listener stopped")));
            } finally {
                publishRegistration.countDown();
            }
            registration.get(5, TimeUnit.SECONDS);
        }
        assertEquals(0, actorQuiescence.pendingRuntimeWaiterCount(),
                "subscribers registered after timeout callback must detach");
    }

    @Test void rejectedAbortAlwaysRunsReleaseEvenWhenTransportCloseThrows() {
        var order = new java.util.ArrayList<String>();
        var closeFailure = new IllegalStateException("socket close failed");
        assertSame(closeFailure, assertThrows(IllegalStateException.class, () ->
                NativeHttp.retireRejectedAbort(
                        () -> { order.add("close"); throw closeFailure; },
                        () -> order.add("release"))));
        assertEquals(List.of("close", "release"), order);
        order.clear();
        NativeHttp.retireRejectedAbort(
                () -> order.add("close"),
                () -> order.add("release"));
        assertEquals(List.of("close", "release"), order);
    }

    @Test void shutdownVisitsEveryAcceptedStateAfterEarlierAbortFailures() {
        var visited = new java.util.ArrayList<Integer>();
        var first = new IllegalArgumentException("first failed abort");
        var second = new IllegalStateException("third failed abort");
        var result = NativeHttp.abortEveryAccepted(List.of(0, 1, 2, 3, 4), state -> {
            visited.add(state);
            if (state == 0) throw first;
            if (state == 2) throw second;
        });
        assertSame(first, result, "first failure must win");
        assertEquals(List.of(0, 1, 2, 3, 4), visited,
                "a failed abort must not skip later accepted exchanges");
        assertArrayEquals(new Throwable[] { second }, result.getSuppressed());
        assertNull(NativeHttp.abortEveryAccepted(List.of(1, 2), ignored -> { }));
    }

    @org.junit.jupiter.api.RepeatedTest(12)
    @org.junit.jupiter.api.Timeout(15)
    void shuttingDownIdleListenerFailsPendingAcceptAndCompletesDrain() throws Exception {
        // End-to-end guest regression for Server.shutdown's two-phase
        // admission sealing and off-monitor Future notifications. The client
        // intentionally sends nothing: a waiter is registered before the
        // transport stops and must fail, while its drain must still succeed.
        String output = run("""
            pub async routine main(): void {
              val server = http.listen("127.0.0.1", 0, 1, 1024, 2000);
              stdio.println(server.port);
              val waiting = server.accept();
              await server.shutdown(0);
              try {
                await waiting;
                stdio.println("ACCEPT-WAS-NOT-REVOKED");
              } catch (err) {
                stdio.println("accept-revoked");
              }
              stdio.println(server.active);
              return;
            }
            """, port -> { /* Deliberately no accepted request. */ });
        assertFalse(output.contains("ACCEPT-WAS-NOT-REVOKED"), output);
        assertTrue(output.stripTrailing().endsWith("accept-revoked\n0"), output);
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
                });
            assertFalse(output.contains("ACCEPTED"), output);
            assertEquals(7, output.split("stale-rejected", -1).length - 1, output);
            assertEquals(8, output.split("closed-rejected", -1).length - 1, output);
            // Supervisor counters may interleave with post-response diagnostics.
            // Assert all three counters without masking actor-carrier teardown.
            var numbers = output.lines().filter(line -> line.matches("[0-9]+")).toList();
            assertEquals(4, numbers.size(), output); // port plus three counters
            assertEquals(List.of("1", "7", "0"), numbers.subList(1, 4), output);
        }
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void shutdownRevokesAlreadyAcceptedRequestBeforeLatePooledDispatch() throws Exception {
        for (String kind : List.of("actor", "isoactor")) {
            String output = run("""
                define %s Worker as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    await request.respond(200, "text/plain", "unexpected");
                    return;
                  }
                end
                pub async routine main(): void {
                  val server = http.listen("127.0.0.1", 0, 1, 1024, 5000);
                  val handler = http.handler("Worker").pooled(1);
                  stdio.println(server.port);
                  val request = await server.accept();
                  await server.shutdown(0);
                  try {
                    handler.dispatch(rt borrow mut request);
                    stdio.println("LATE-DISPATCH-ACCEPTED");
                  } catch (err) {
                    stdio.println("late-dispatch-rejected");
                  }
                  stdio.println(handler.created);
                  stdio.println(server.active);
                  return;
                }
                """.formatted(kind), port -> {
                    // A transport stopped during an active request may send
                    // 503 or close the socket. Neither permits guest delivery.
                    try {
                        HttpResponse<String> response = request(port, "GET", "/", "");
                        assertNotEquals(200, response.statusCode());
                    } catch (java.io.IOException expectedStop) {
                        // The connection may close before a 503 is sent.
                    }
                });
            assertFalse(output.contains("LATE-DISPATCH-ACCEPTED"), output);
            assertTrue(output.stripTrailing().endsWith("late-dispatch-rejected\n0\n0"),
                    output);
        }
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void closingOneListenerDoesNotStopPoolSharedWithAnotherListener() throws Exception {
        for (String kind : List.of("actor", "isoactor")) {
            String output = runTwoPorts("""
                define %s Worker as
                  let int uses = 0;
                  receive(ActorMail<String> mail): void {
                    self.uses = self.uses + 1;
                    val request = http.claim(mail.value);
                    request.set_header("X-Reused", self.uses > 1 ? "yes" : "no");
                    await request.respond(200, "text/plain", "ok");
                    return;
                  }
                end
                pub async routine main(): void {
                  val first = http.listen("127.0.0.1", 0, 2, 1024, 5000);
                  val second = http.listen("127.0.0.1", 0, 2, 1024, 5000);
                  val handler = http.handler("Worker").pooled(1);
                  stdio.println(first.port);
                  stdio.println(second.port);
                  val firstRequest = await first.accept();
                  val firstCompletion = firstRequest.completion;
                  handler.dispatch(rt borrow mut firstRequest);
                  await firstCompletion;
                  await first.shutdown(0);
                  stdio.println("first-shutdown");
                  val secondRequest = await second.accept();
                  val secondCompletion = secondRequest.completion;
                  handler.dispatch(rt borrow mut secondRequest);
                  await secondCompletion;
                  await second.shutdown(0);
                  stdio.println(handler.created);
                  stdio.println(handler.reused);
                  stdio.println(first.active);
                  stdio.println(second.active);
                  return;
                }
                """.formatted(kind), (first, second) -> {
                    var before = request(first, "GET", "/", "");
                    assertEquals(200, before.statusCode());
                    assertEquals("no", before.headers().firstValue("X-Reused").orElseThrow());
                    var after = request(second, "GET", "/", "");
                    assertEquals(200, after.statusCode());
                    assertEquals("yes", after.headers().firstValue("X-Reused").orElseThrow());
                });
            assertTrue(output.contains("first-shutdown"), output);
            assertTrue(output.stripTrailing().endsWith("1\n1\n0\n0"), output);
        }
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    @org.junit.jupiter.api.Timeout(30)
    void abortingRequestOnFirstListenerKeepsSharedWorkerForSecondListener()
            throws Exception {
        for (String kind : List.of("actor", "isoactor")) {
            String output = runTwoPorts("""
                define %s Worker as
                  let int uses = 0;
                  receive(ActorMail<String> mail): void {
                    self.uses = self.uses + 1;
                    val request = http.claim(mail.value);
                    if request.target == "/abort"; then
                      request.abort();
                    else
                      request.set_header("X-Reused", self.uses > 1 ? "yes" : "no");
                      await request.respond(200, "text/plain", "ok");
                    fi
                    return;
                  }
                end
                pub async routine main(): void {
                  val first = http.listen("127.0.0.1", 0, 2, 1024, 5000);
                  val second = http.listen("127.0.0.1", 0, 2, 1024, 5000);
                  val handler = http.handler("Worker").pooled(1);
                  stdio.println(first.port);
                  stdio.println(second.port);
                  val firstRequest = await first.accept();
                  val firstCompletion = firstRequest.completion;
                  handler.dispatch(rt borrow mut firstRequest);
                  await firstCompletion;
                  await first.shutdown(0);
                  val secondRequest = await second.accept();
                  val secondCompletion = secondRequest.completion;
                  handler.dispatch(rt borrow mut secondRequest);
                  await secondCompletion;
                  await second.shutdown(0);
                  stdio.println(handler.created);
                  stdio.println(handler.reused);
                  stdio.println(first.active);
                  stdio.println(second.active);
                  return;
                }
                """.formatted(kind), (first, second) -> {
                    assertEquals(500, request(first, "GET", "/abort", "").statusCode());
                    var following = request(second, "GET", "/ok", "");
                    assertEquals(200, following.statusCode());
                    assertEquals("yes", following.headers()
                            .firstValue("X-Reused").orElseThrow());
                });
            assertTrue(output.stripTrailing().endsWith("1\n1\n0\n0"), output);
        }
    }

    @org.junit.jupiter.api.RepeatedTest(8)
    @org.junit.jupiter.api.Timeout(30)
    void freshActorDeadlineDuringDispatchCannotOrphanCarrier() throws Exception {
        for (String kind : List.of("actor", "isoactor")) {
            String output = run("""
                define %s Worker as
                  receive(ActorMail<String> mail): void {
                    val request = http.claim(mail.value);
                    await request.respond(200, "text/plain", "ok");
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  // A short deadline deliberately races non-pooled actor
                  // spawn/ready, exchange transfer and native carrier entry.
                  val server = http.listen("127.0.0.1", 0, 1, 1024, 100);
                  stdio.println(server.port);
                  val request = await server.accept();
                  val completion = request.completion;
                  http.handler("Worker").dispatch(rt borrow mut request);
                  await completion;
                  await server.shutdown(0);
                  stdio.println(server.active);
                  return;
                }
                """.formatted(kind), port -> {
                    try {
                        var response = request(port, "GET", "/", "");
                        // Either a completed handler or a deadline failure
                        // is legitimate. Context.close must not race either.
                        assertTrue(response.statusCode() == 200
                                || response.statusCode() == 504
                                || response.statusCode() == 500,
                                "unexpected HTTP status: " + response.statusCode());
                    } catch (java.io.IOException deadlineClosedSocket) {
                        // A timed-out HTTP/1.1 socket can close before headers.
                    }
                });
            assertTrue(output.stripTrailing().endsWith("\n0"), output);
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

    @FunctionalInterface interface TwoClient {
        void run(int firstPort, int secondPort) throws Exception;
    }

    private static String runTwoPorts(String program, TwoClient client) throws Exception {
        CompletableFuture<int[]> ports = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] b, int off, int len) {
                super.write(b, off, len);
                if (ports.isDone()) return;
                String text = toString(StandardCharsets.UTF_8);
                int firstEnd = text.indexOf('\n');
                if (firstEnd < 0) return;
                int secondEnd = text.indexOf('\n', firstEnd + 1);
                if (secondEnd < 0) return;
                try {
                    int first = Integer.parseInt(text.substring(0, firstEnd).trim());
                    int second = Integer.parseInt(
                            text.substring(firstEnd + 1, secondEnd).trim());
                    ports.complete(new int[] {first, second});
                } catch (NumberFormatException badPort) {
                    ports.completeExceptionally(badPort);
                }
            }
        };
        try (Context context = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.NETWORK)
                .restrictedContextBuilder(ExecutionProfile.serverJit(), Set.of())
                .out(output).build();
             ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> clientWork = worker.submit(() -> {
                try {
                    int[] actual = ports.get(10, TimeUnit.SECONDS);
                    client.run(actual[0], actual[1]);
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                context.eval(Source.newBuilder(
                        OresLanguage.ID, program, "native-http-two-listeners.ores").build());
                clientWork.get(10, TimeUnit.SECONDS);
            } finally {
                clientWork.cancel(true);
            }
        }
        return output.toString(StandardCharsets.UTF_8);
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
                // Server.shutdown(), not a test-only latch, must guarantee
                // post-response actor work is finished before context.close().
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
                // The caller immediately closes the polyglot Context. If
                // shutdown returned while a pooled actor still ran, this
                // must fail rather than being masked by a host-side barrier.
            } finally { clientWork.cancel(true); }

        }
        return output.toString(StandardCharsets.UTF_8);
    }
    @Test
    void hostAssertionFailureCannotStrandAcceptedHttpExchangeCleanup() {
        var visited = new java.util.ArrayList<Integer>();
        AssertionError unchecked = new AssertionError("first abort Error");
        IllegalArgumentException later = new IllegalArgumentException("later abort failure");
        RuntimeException result = NativeHttp.abortEveryAccepted(
                List.of(0, 1, 2, 3), state -> {
                    visited.add(state);
                    if (state == 0) throw unchecked;
                    if (state == 2) throw later;
                });
        assertEquals(List.of(0, 1, 2, 3), visited,
                "unchecked host failure must never skip an accepted request");
        assertInstanceOf(IllegalStateException.class, result);
        assertSame(unchecked, result.getCause(),
                "original host Error remains the primary failure cause");
        assertArrayEquals(new Throwable[]{later}, result.getSuppressed());
    }

    @Test
    void uncheckedCancellationErrorStillReleasesHttpRequestAdmission() {
        var order = new java.util.ArrayList<String>();
        var cancellationError = new AssertionError("injected actor STOP failure");
        var closeError = new IllegalStateException("injected socket close failure");
        AssertionError observed = assertThrows(AssertionError.class, () ->
                NativeHttp.runCancellationThenCleanup(
                        () -> { order.add("cancel"); throw cancellationError; },
                        () -> { order.add("release"); throw closeError; }));
        assertSame(cancellationError, observed);
        assertEquals(List.of("cancel", "release"), order,
                "socket/admission cleanup must run after unchecked cancellation Error");
        assertArrayEquals(new Throwable[]{closeError}, observed.getSuppressed());
    }

}
