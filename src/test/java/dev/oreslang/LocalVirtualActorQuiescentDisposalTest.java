package dev.oreslang;

import dev.oreslang.runtime.LocalVirtualActorI64;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VM-only lifecycle tests: these do not establish native arenas, production
 * ActorRuntime CONTROL scheduling, guest borrow safety or Graal Native Image.
 */
final class LocalVirtualActorQuiescentDisposalTest {
    @Test void completedTurnsDisposeOncePerIncarnationAndNeverBeforeSettlement() {
        var created = new AtomicInteger();
        var disposed = new AtomicInteger();
        try (var runtime = new LocalVirtualActorI64("app", "Cart", 2, 2,
                ignored -> {
                    created.incrementAndGet();
                    return new LocalVirtualActorI64.Turn() {
                        @Override public void on_message(long value) { }
                        @Override public void close() { disposed.incrementAndGet(); }
                    };
                }, notice -> fail("unexpected supervisor notice"))) {
            var ref = runtime.resolve("one");
            var first = runtime.try_send(ref, 7);
            assertTrue(first.admitted());
            assertThrows(IllegalStateException.class, () -> runtime.deactivate(ref));
            assertEquals(0, disposed.get());
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, first.completion().join());
            runtime.deactivate(ref);
            assertEquals(1, disposed.get());
            runtime.deactivate(ref);
            assertEquals(1, disposed.get());

            var second = runtime.try_send(ref, 3);
            assertTrue(second.admitted());
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, second.completion().join());
            assertEquals(2, created.get());
            assertEquals(1, disposed.get());
        }
        assertEquals(2, disposed.get(), "shutdown retires the second idle incarnation");
    }

    @Test void acknowledgedPanicDisposesBeforeReactivation() {
        var disposal = new AtomicInteger();
        var notices = new ArrayList<LocalVirtualActorI64.Notice>();
        try (var runtime = new LocalVirtualActorI64("app", "Failing", 2, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) {
                        throw new IllegalStateException("guest panic");
                    }
                    @Override public void close() { disposal.incrementAndGet(); }
                }, notices::add)) {
            var ref = runtime.resolve("one");
            var accepted = runtime.try_send(ref, 1);
            assertTrue(accepted.admitted());
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.FAILED_CONTROL_NOTIFIED,
                    accepted.completion().join());
            assertEquals(1, notices.size());
            assertEquals(1, disposal.get());

            var retry = runtime.try_send(ref, 2);
            assertTrue(retry.admitted());
            assertTrue(runtime.run_one(ref));
            assertEquals(2, disposal.get());
        }
        assertEquals(2, disposal.get());
    }

    @Test void failedNoticeRetainsCreditsAndProhibitsDisposal() {
        var disposal = new AtomicInteger();
        var runtime = new LocalVirtualActorI64("app", "Failing", 2, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) {
                        throw new IllegalStateException("guest panic");
                    }
                    @Override public void close() { disposal.incrementAndGet(); }
                }, notice -> { throw new IllegalStateException("CONTROL unavailable"); });
        var ref = runtime.resolve("one");
        var ticket = runtime.try_send(ref, 1);
        assertTrue(ticket.admitted());
        assertThrows(IllegalStateException.class, () -> runtime.run_one(ref));
        assertFalse(ticket.completion().isDone());
        assertEquals(0, disposal.get());
        assertThrows(IllegalStateException.class, () -> runtime.deactivate(ref));
        assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(0, disposal.get());
    }

    @Test void failedDisposalQuarantinesSlotRatherThanReactivatingOrClosingSilently() {
        var attempts = new AtomicInteger();
        var runtime = new LocalVirtualActorI64("app", "FaultyCleanup", 1, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) { }
                    @Override public void close() {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("resource could not be released");
                    }
                }, notice -> fail("unexpected supervisor notice"));
        var ref = runtime.resolve("one");
        var accepted = runtime.try_send(ref, 1);
        assertTrue(runtime.run_one(ref));
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, accepted.completion().join());
        assertThrows(IllegalStateException.class, () -> runtime.deactivate(ref));
        assertEquals(1, attempts.get());
        assertEquals(LocalVirtualActorI64.AdmissionKind.UNAVAILABLE,
                runtime.try_send(ref, 2).kind());
        assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(1, attempts.get(), "disposal must not be silently retried");
    }

    @Test void concurrentReactivationWaitsForPhysicalDisposal() throws Exception {
        var disposalEntered = new CountDownLatch(1);
        var allowDisposal = new CountDownLatch(1);
        var created = new AtomicInteger();
        var retired = new AtomicInteger();
        var runtime = new LocalVirtualActorI64("app", "Cart", 1, 1,
                ignored -> {
                    int incarnation = created.incrementAndGet();
                    return new LocalVirtualActorI64.Turn() {
                        @Override public void on_message(long value) { }
                        @Override public void close() {
                            if (incarnation == 1) {
                                disposalEntered.countDown();
                                try {
                                    if (!allowDisposal.await(5, TimeUnit.SECONDS))
                                        throw new IllegalStateException("disposal timed out");
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("disposal interrupted", ex);
                                }
                            }
                            retired.incrementAndGet();
                        }
                    };
                }, notice -> fail("unexpected supervisor notice"));
        var ref = runtime.resolve("one");
        var first = runtime.try_send(ref, 1);
        assertTrue(runtime.run_one(ref));
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, first.completion().join());
        Thread teardown = new Thread(() -> runtime.deactivate(ref), "virtual-actor-disposal-test");
        teardown.start();
        try {
            assertTrue(disposalEntered.await(5, TimeUnit.SECONDS));
            assertEquals(LocalVirtualActorI64.AdmissionKind.UNAVAILABLE,
                    runtime.try_send(ref, 2).kind());
            assertEquals(1, created.get());
            assertThrows(IllegalStateException.class, runtime::close);
        } finally {
            allowDisposal.countDown();
            teardown.join(5000);
        }
        assertFalse(teardown.isAlive(), "deactivation must complete after disposer returns");
        assertEquals(1, retired.get());
        var next = runtime.try_send(ref, 2);
        assertTrue(next.admitted());
        assertEquals(2, created.get());
        assertTrue(runtime.run_one(ref));
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, next.completion().join());
        runtime.close();
        assertEquals(2, retired.get());
    }
    @Test void shutdownAttemptsEveryIdleDisposerDespiteOneFailure() {
        var disposed = new AtomicInteger();
        var runtime = new LocalVirtualActorI64("app", "Cart", 3, 1,
                identity -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) { }
                    @Override public void close() {
                        disposed.incrementAndGet();
                        if (identity.key().equals("bad"))
                            throw new IllegalStateException("simulated disposal failure");
                    }
                }, notice -> fail("unexpected supervisor notice"));
        var good = runtime.resolve("good");
        var bad = runtime.resolve("bad");
        var a = runtime.try_send(good, 1);
        var b = runtime.try_send(bad, 2);
        assertTrue(a.admitted() && b.admitted());
        assertTrue(runtime.run_one(good));
        assertTrue(runtime.run_one(bad));
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, a.completion().join());
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, b.completion().join());
        assertThrows(IllegalStateException.class, runtime::close);
        assertEquals(2, disposed.get(), "one broken disposer cannot starve other owners");
        assertThrows(IllegalStateException.class, () -> runtime.resolve("new"));
        assertThrows(IllegalStateException.class, runtime::close,
                "failed shutdown must remain visibly failed on every later close");
        assertEquals(2, disposed.get(), "failed shutdown must not dispose twice");
    }

    @Test void disposerNeverRunsUnderDirectoryOrSlotMonitor() throws Exception {
        var runtimeRef = new AtomicReference<LocalVirtualActorI64>();
        var actorRef = new AtomicReference<LocalVirtualActorI64.Ref>();
        var probe = Executors.newSingleThreadExecutor();
        try {
            var runtime = new LocalVirtualActorI64("app", "Cart", 1, 1,
                    ignored -> new LocalVirtualActorI64.Turn() {
                        @Override public void on_message(long value) { }
                        @Override public void close() {
                            // A second thread must enter both directory and slot
                            // admission during disposal, observing the retirement
                            // fence. If either monitor is held here, this times out.
                            var observed = probe.submit(() ->
                                    runtimeRef.get().try_send(actorRef.get(), 9).kind());
                            try {
                                if (observed.get(3, TimeUnit.SECONDS)
                                        != LocalVirtualActorI64.AdmissionKind.UNAVAILABLE)
                                    throw new IllegalStateException("retirement admission was not fenced");
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("probe interrupted", ex);
                            } catch (ExecutionException | TimeoutException ex) {
                                throw new IllegalStateException("disposer ran with monitor held", ex);
                            }
                        }
                    }, notice -> fail("unexpected supervisor notice"));
            runtimeRef.set(runtime);
            var ref = runtime.resolve("one");
            actorRef.set(ref);
            var ticket = runtime.try_send(ref, 1);
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, ticket.completion().join());
            runtime.deactivate(ref);
            runtime.close();
        } finally {
            probe.shutdownNow();
        }
    }


    @Test void inFlightHandlerPreventsShutdownAndPhysicalDisposal() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var disposed = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var runtime = new LocalVirtualActorI64("app", "Worker", 1, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) throws Exception {
                        entered.countDown();
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("guest turn timed out");
                    }
                    @Override public void close() { disposed.incrementAndGet(); }
                }, notice -> fail("unexpected supervisor CONTROL"));
        var ref = runtime.resolve("one");
        var ticket = runtime.try_send(ref, 1);
        assertTrue(ticket.admitted());
        Thread worker = new Thread(() -> {
            try {
                assertTrue(runtime.run_one(ref));
            } catch (Throwable ex) {
                failure.set(ex);
            }
        }, "virtual-actor-inflight-turn");
        worker.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(ticket.completion().isDone());
            assertThrows(IllegalStateException.class, () -> runtime.deactivate(ref));
            assertThrows(IllegalStateException.class, runtime::close);
            assertEquals(0, disposed.get(), "an active handler still owns its state");
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, ticket.completion().join());
        runtime.close();
        assertEquals(1, disposed.get());
    }

    @Test void pendingSupervisorNoticePreventsPrematureDisposal() throws Exception {
        var controlEntered = new CountDownLatch(1);
        var allowControl = new CountDownLatch(1);
        var disposed = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var runtime = new LocalVirtualActorI64("app", "Worker", 1, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) {
                        throw new IllegalStateException("guest panic");
                    }
                    @Override public void close() { disposed.incrementAndGet(); }
                }, notice -> {
                    controlEntered.countDown();
                    try {
                        if (!allowControl.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("supervisor CONTROL timed out");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("supervisor CONTROL interrupted", ex);
                    }
                });
        var ref = runtime.resolve("one");
        var ticket = runtime.try_send(ref, 1);
        assertTrue(ticket.admitted());
        Thread worker = new Thread(() -> {
            try {
                assertTrue(runtime.run_one(ref));
            } catch (Throwable ex) {
                failure.set(ex);
            }
        }, "virtual-actor-control-publish");
        worker.start();
        try {
            assertTrue(controlEntered.await(5, TimeUnit.SECONDS));
            assertFalse(ticket.completion().isDone());
            assertThrows(IllegalStateException.class, () -> runtime.deactivate(ref));
            assertThrows(IllegalStateException.class, runtime::close);
            assertEquals(0, disposed.get(), "state must outlive CONTROL notification");
        } finally {
            allowControl.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(LocalVirtualActorI64.Outcome.FAILED_CONTROL_NOTIFIED,
                ticket.completion().join());
        assertEquals(1, disposed.get(), "retire after CONTROL disposition settles");
        runtime.close();
        assertEquals(1, disposed.get());
    }

    @Test void rejectedBackpressureLeavesNoUnreceiptedMailboxEntry() {
        var processed = new ArrayList<Long>();
        var retired = new AtomicInteger();
        var runtime = new LocalVirtualActorI64("app", "Queue", 1, 1,
                ignored -> new LocalVirtualActorI64.Turn() {
                    @Override public void on_message(long value) { processed.add(value); }
                    @Override public void close() { retired.incrementAndGet(); }
                }, notice -> fail("unexpected supervisor notice"));
        var ref = runtime.resolve("one");
        var first = runtime.try_send(ref, 11);
        assertTrue(first.admitted());
        assertEquals(LocalVirtualActorI64.AdmissionKind.BACKPRESSURE,
                runtime.try_send(ref, 99).kind());
        assertThrows(IllegalStateException.class, () -> runtime.deactivate(ref));
        assertTrue(runtime.run_one(ref));
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, first.completion().join());
        assertFalse(runtime.run_one(ref), "rejected message cannot enter mailbox");
        var second = runtime.try_send(ref, 22);
        assertTrue(second.admitted());
        assertTrue(runtime.run_one(ref));
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, second.completion().join());
        assertEquals(java.util.List.of(11L, 22L), processed);
        runtime.deactivate(ref);
        assertEquals(1, retired.get());
        runtime.close();
        assertEquals(1, retired.get());
    }

    @Test void completingAnAcceptedTurnPublishesSettledCreditBeforeDependentCallback() {
        var processed = new ArrayList<Long>();
        try (var runtime = new LocalVirtualActorI64("app", "Queue", 1, 1,
                ignored -> processed::add,
                notice -> fail("unexpected supervisor notice"))) {
            var ref = runtime.resolve("one");
            var first = runtime.try_send(ref, 10);
            assertTrue(first.admitted());
            var chained = new AtomicReference<LocalVirtualActorI64.SendResult>();
            first.completion().thenRun(() -> chained.set(runtime.try_send(ref, 20)));
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, first.completion().join());
            assertNotNull(chained.get(), "completion callback must be invoked");
            assertTrue(chained.get().admitted(),
                    "a settled credit must be reusable before completion callbacks");
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, chained.get().completion().join());
            assertEquals(java.util.List.of(10L, 20L), processed);
        }
    }


    @Test void saturatedMailboxRejectsWithoutPerRequestFutureOrEnvelope() {
        var handled = new AtomicInteger();
        var runtime = new LocalVirtualActorI64("app", "Bounded", 1, 1,
                ignored -> value -> handled.incrementAndGet(),
                notice -> fail("unexpected supervisor notice"));
        var ref = runtime.resolve("only");
        var accepted = runtime.try_send(ref, 7);
        assertTrue(accepted.admitted());
        var firstDenied = runtime.try_send(ref, 8);
        assertEquals(LocalVirtualActorI64.AdmissionKind.BACKPRESSURE, firstDenied.kind());
        assertNull(firstDenied.completion());
        for (int i = 0; i < 10_000; i++) {
            var denied = runtime.try_send(ref, i);
            assertSame(firstDenied, denied,
                    "saturated edge must reuse one immutable rejection response");
            assertNull(denied.completion(), "no rejected message receives a future");
        }
        assertTrue(runtime.run_one(ref));
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, accepted.completion().join());
        assertEquals(1, handled.get(), "no denied message may become a hidden turn");
        assertFalse(runtime.run_one(ref));
        var next = runtime.try_send(ref, 11);
        assertTrue(next.admitted(), "settlement restores exactly one credit");
        assertTrue(runtime.run_one(ref));
        assertEquals(2, handled.get());
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, next.completion().join());
        runtime.close();
        var closed = runtime.try_send(ref, 12);
        assertEquals(LocalVirtualActorI64.AdmissionKind.CLOSED, closed.kind());
        assertSame(closed, runtime.try_send(ref, 13), "closed responses must not allocate");
    }


    @Test void senderCannotForgeCompletedOrCancelledActorReceipt() {
        var handled = new AtomicInteger();
        var callback = new AtomicInteger();
        try (var runtime = new LocalVirtualActorI64("app", "Protected", 1, 1,
                ignored -> value -> handled.incrementAndGet(),
                notice -> fail("unexpected supervisor notice"))) {
            var ref = runtime.resolve("one");
            var accepted = runtime.try_send(ref, 9);
            assertTrue(accepted.admitted());
            var completion = accepted.completion();
            assertNotNull(completion);
            assertEquals(0, LocalVirtualActorI64.Completion.class.getConstructors().length,
                    "external code cannot mint an observed completion handle");
            assertEquals(0, LocalVirtualActorI64.SendResult.class.getConstructors().length,
                    "external code cannot mint an ADMITTED result");
            assertFalse(java.util.concurrent.CompletableFuture.class.isAssignableFrom(
                    completion.getClass()),
                    "returning mutable CompletableFuture permits sender-forged completion");
            for (String mutator : java.util.List.of("complete", "completeExceptionally",
                    "cancel", "obtrudeValue", "obtrudeException", "completeAsync")) {
                assertFalse(java.util.Arrays.stream(completion.getClass().getMethods())
                        .anyMatch(method -> method.getName().equals(mutator)),
                        "sender must not have completion mutator: " + mutator);
            }
            // A caller may mutate its dependent future, never the original
            // completion owned by the mailbox receipt.
            var dependent = completion.thenRun(callback::incrementAndGet);
            dependent.toCompletableFuture().complete(null);
            assertFalse(completion.isDone(), "derived-stage forgery cannot complete parent");
            var writableCopy = completion.stage().toCompletableFuture();
            assertTrue(writableCopy.complete(LocalVirtualActorI64.Outcome.PROCESSED));
            assertFalse(completion.isDone(),
                    "copy of read-only stage must not complete the actor's original receipt");
            assertThrows(IllegalStateException.class, runtime::close,
                    "accepted, unprocessed turn still holds a lifetime credit");
            assertEquals(0, handled.get());
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, completion.join());
            assertEquals(1, handled.get());
            assertTrue(callback.get() <= 1, "dependent callback executes at most once");
        }
    }


    @Test void initializingFactoryFencesShutdownAndConcurrentAdmission() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var destroyed = new AtomicInteger();
        var senderFailure = new AtomicReference<Throwable>();
        var sent = new AtomicReference<LocalVirtualActorI64.SendResult>();
        var runtime = new LocalVirtualActorI64("app", "Factory", 1, 1,
                ignored -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("initializer timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("initializer interrupted", interrupted);
                    }
                    return new LocalVirtualActorI64.Turn() {
                        @Override public void on_message(long value) { }
                        @Override public void close() { destroyed.incrementAndGet(); }
                    };
                }, notice -> fail("unexpected supervisor notice"));
        var ref = runtime.resolve("only");
        Thread initializer = new Thread(() -> {
            try {
                sent.set(runtime.try_send(ref, 11));
            } catch (Throwable failure) {
                senderFailure.set(failure);
            }
        }, "actor-physical-initializer");
        initializer.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(LocalVirtualActorI64.AdmissionKind.ACTIVATING,
                    runtime.try_send(ref, 12).kind(),
                    "a second sender may not pass incomplete guest initialization");
            assertThrows(IllegalStateException.class, runtime::close,
                    "shutdown must wait for actual guest initializer completion");
            assertEquals(0, destroyed.get());
        } finally {
            release.countDown();
            initializer.join(5_000);
        }
        assertFalse(initializer.isAlive());
        assertNull(senderFailure.get());
        assertNotNull(sent.get());
        assertTrue(sent.get().admitted());
        assertTrue(runtime.run_one(ref));
        assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, sent.get().completion().join());
        runtime.close();
        assertEquals(1, destroyed.get());
    }

    @Test void failedFactoryCanRetryFreshIncarnationWithoutLostCredit() {
        var attempts = new AtomicInteger();
        var destroyed = new AtomicInteger();
        try (var runtime = new LocalVirtualActorI64("app", "Factory", 1, 1,
                ignored -> {
                    if (attempts.incrementAndGet() == 1)
                        throw new IllegalStateException("synthetic initializer rejection");
                    return new LocalVirtualActorI64.Turn() {
                        @Override public void on_message(long value) { }
                        @Override public void close() { destroyed.incrementAndGet(); }
                    };
                }, notice -> fail("unexpected supervisor notice"))) {
            var ref = runtime.resolve("one");
            assertThrows(IllegalStateException.class, () -> runtime.try_send(ref, 1));
            assertEquals(0, destroyed.get(), "nothing was published for failed initialization");
            var recovered = runtime.try_send(ref, 2);
            assertTrue(recovered.admitted(), "fresh initialization must recover");
            assertTrue(runtime.run_one(ref));
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, recovered.completion().join());
            assertEquals(2, attempts.get());
        }
        assertEquals(1, destroyed.get(), "only published incarnation is disposed");
    }

    @Test void writableCancellationCopyDoesNotCancelOriginalAcceptedTurn() {
        var handled = new AtomicInteger();
        try (var runtime = new LocalVirtualActorI64("app", "Protected", 1, 1,
                ignored -> value -> handled.incrementAndGet(),
                notice -> fail("unexpected supervisor notice"))) {
            var ref = runtime.resolve("only");
            var result = runtime.try_send(ref, 1);
            assertTrue(result.admitted());
            var writableCopy = result.completion().stage().toCompletableFuture();
            assertTrue(writableCopy.cancel(true));
            assertFalse(result.completion().isDone(),
                    "cancel of a copied stage must not revoke a queued actor turn");
            assertThrows(IllegalStateException.class, runtime::close);
            assertTrue(runtime.run_one(ref));
            assertEquals(1, handled.get());
            assertEquals(LocalVirtualActorI64.Outcome.PROCESSED, result.completion().join());
        }
    }

}
