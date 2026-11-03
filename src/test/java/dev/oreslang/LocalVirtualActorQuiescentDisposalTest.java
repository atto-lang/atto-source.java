package dev.oreslang;

import dev.oreslang.runtime.LocalVirtualActorI64;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        runtime.close();
        assertEquals(2, disposed.get(), "completed shutdown must not dispose twice");
    }

}
