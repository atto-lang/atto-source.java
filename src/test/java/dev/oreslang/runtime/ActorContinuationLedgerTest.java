package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorContinuationLedgerTest {
    @Test
    void ordinaryReceiptsAreBoundedAndRetiredOnce() {
        ActorContinuationLedger ledger = new ActorContinuationLedger(2);
        AtomicInteger calls = new AtomicInteger();
        var first = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, calls::incrementAndGet);
        var second = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, calls::incrementAndGet);
        assertEquals(1L, first.sequence());
        assertEquals(first.generation(), second.generation());
        assertEquals(2, ledger.snapshot().queued());
        assertThrows(IllegalStateException.class,
                () -> ledger.admit(ActorContinuationLedger.Lane.ORDINARY, calls::incrementAndGet));
        first.run();
        assertEquals(1, calls.get());
        first.close(); // envelope close after the callback is idempotent
        assertThrows(IllegalStateException.class, first::run);
        assertEquals(1, ledger.snapshot().queued());
        second.close();
        assertEquals(0, ledger.snapshot().queued());
        assertThrows(IllegalStateException.class, second::run);
        assertEquals(1, calls.get());
    }

    @Test
    void stopRevokesOrdinaryButNotAlreadyAdmittedSourceCleanup() {
        ActorContinuationLedger ledger = new ActorContinuationLedger(2);
        AtomicInteger calls = new AtomicInteger();
        var ordinary = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, calls::incrementAndGet);
        var cleanup = ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK,
                () -> calls.addAndGet(10));
        ledger.sealOrdinary();
        ledger.sealOrdinary();
        assertEquals(2L, ledger.snapshot().generation());
        assertThrows(IllegalStateException.class,
                () -> ledger.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {}));
        ordinary.run(); // cancelled by actor STOP, never executes guest work
        cleanup.run(); // already-admitted lexical cleanup is allowed
        assertEquals(10, calls.get());
        assertEquals(0, ledger.snapshot().queued());
        assertEquals(0, ledger.snapshot().running());
        ledger.sealAll();
        assertThrows(IllegalStateException.class,
                () -> ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {}));
    }

    @Test
    void sourceCleanupMayBeAdmittedAfterOrdinaryStopWhileStillAuthorized() {
        ActorContinuationLedger ledger = new ActorContinuationLedger(1);
        ledger.sealOrdinary();
        AtomicInteger calls = new AtomicInteger();
        var cleanup = ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK,
                calls::incrementAndGet);
        cleanup.run();
        assertEquals(1, calls.get());
    }

    @Test
    void cancellationCannotFalselyRetireAlreadyExecutingCallback() throws Exception {
        ActorContinuationLedger ledger = new ActorContinuationLedger(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        var receipt = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {
            entered.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("callback was never released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        Thread worker = Thread.ofPlatform().start(() -> {
            try { receipt.run(); } catch (Throwable failure) { error.set(failure); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ledger.sealOrdinary();
            receipt.close(); // STOP cannot forge a retirement receipt
            assertEquals(1, ledger.snapshot().running());
            assertEquals(0, ledger.snapshot().queued());
            assertThrows(IllegalStateException.class, receipt::run);
        } finally {
            finish.countDown();
            worker.join(5_000);
        }
        assertFalse(worker.isAlive());
        assertNull(error.get());
        assertEquals(0, ledger.snapshot().running());
    }

    @Test
    void discardedEnvelopeReleasesCapacityWithoutExecuting() {
        ActorContinuationLedger ledger = new ActorContinuationLedger(1);
        AtomicInteger calls = new AtomicInteger();
        var orphan = ledger.admit(ActorContinuationLedger.Lane.ORDINARY,
                calls::incrementAndGet);
        orphan.close();
        assertEquals(0, ledger.snapshot().queued());
        var live = ledger.admit(ActorContinuationLedger.Lane.ORDINARY,
                calls::incrementAndGet);
        assertTrue(live.sequence() > orphan.sequence());
        live.run();
        assertEquals(1, calls.get());
    }

    @Test
    void failedCallbacksStillRetireTheirLedgerReceipt() {
        ActorContinuationLedger ledger = new ActorContinuationLedger(1);
        var receipt = ledger.admit(ActorContinuationLedger.Lane.SOURCE_TASK,
                () -> { throw new IllegalArgumentException("guest failure"); });
        assertThrows(IllegalArgumentException.class, receipt::run);
        assertEquals(0, ledger.snapshot().queued());
        assertEquals(0, ledger.snapshot().running());
        assertThrows(IllegalStateException.class, receipt::run);
    }
    /** Deterministic closure release check: do not depend on GC timing. */
    private static Object retainedCallback(ActorContinuationLedger.Admission receipt)
            throws ReflectiveOperationException {
        var field = ActorContinuationLedger.Admission.class.getDeclaredField("callback");
        field.setAccessible(true);
        return field.get(receipt);
    }

    @Test
    void consumedAndDiscardedReceiptsReleaseTheirCallbackClosures() throws Exception {
        ActorContinuationLedger ledger = new ActorContinuationLedger(3);
        var executed = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {});
        var closed = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {});
        var stopped = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {});

        assertNotNull(retainedCallback(executed));
        assertNotNull(retainedCallback(closed));
        assertNotNull(retainedCallback(stopped));
        executed.run();
        closed.close();
        ledger.sealOrdinary();
        stopped.run(); // STOP discards this queued ordinary callback.

        for (var receipt : java.util.List.of(executed, closed, stopped)) {
            assertNull(retainedCallback(receipt),
                    "terminal receipt must not retain guest callback closures");
            assertThrows(IllegalStateException.class, receipt::run,
                    "no terminal receipt may replay its detached callback");
        }
        assertEquals(0, ledger.snapshot().queued());
        assertEquals(0, ledger.snapshot().running());
    }

    @Test
    void runningReceiptReleasesItsFieldWithoutForgingPhysicalRetirement()
            throws Exception {
        ActorContinuationLedger ledger = new ActorContinuationLedger(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var receipt = ledger.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("callback was not released");
                }
                completions.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                receipt.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNull(retainedCallback(receipt),
                    "executing stack, not retained receipt, owns callback body");
            assertEquals(1, ledger.snapshot().running(),
                    "clearing receipt's closure cannot complete the running turn");
            ledger.sealOrdinary();
            receipt.close();
            assertEquals(1, ledger.snapshot().running(),
                    "foreign close cannot forge physical callback retirement");
        } finally {
            release.countDown();
            worker.join(5_000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(1, completions.get());
        assertEquals(0, ledger.snapshot().running());
        assertNull(retainedCallback(receipt));
    }

    @Test
    void competingRunCloseAndStopNeverLeakOrReplayContinuation() throws Exception {
        for (int round = 0; round < 128; round++) {
            ActorContinuationLedger ledger = new ActorContinuationLedger(1);
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            var receipt = ledger.admit(ActorContinuationLedger.Lane.ORDINARY,
                    calls::incrementAndGet);
            CountDownLatch begin = new CountDownLatch(1);
            final boolean stopBeforeClose = (round & 1) == 0;
            Thread runner = Thread.ofPlatform().start(() -> {
                try {
                    begin.await();
                    receipt.run();
                } catch (IllegalStateException replay) {
                    if (!replay.getMessage().contains("receipt replay")) {
                        unexpected.compareAndSet(null, replay);
                    }
                } catch (Throwable error) {
                    unexpected.compareAndSet(null, error);
                }
            });
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    begin.await();
                    if (stopBeforeClose) ledger.sealOrdinary();
                    receipt.close();
                } catch (Throwable error) {
                    unexpected.compareAndSet(null, error);
                }
            });
            begin.countDown();
            runner.join(5_000);
            stopper.join(5_000);
            assertFalse(runner.isAlive(), "runner stranded at round " + round);
            assertFalse(stopper.isAlive(), "STOP stranded at round " + round);
            assertNull(unexpected.get(), "unexpected race outcome at round " + round);
            assertTrue(calls.get() <= 1, "duplicate guest invocation at round " + round);
            assertNull(retainedCallback(receipt), "retained callback at round " + round);
            assertEquals(0, ledger.snapshot().queued());
            assertEquals(0, ledger.snapshot().running());
        }
    }

}
