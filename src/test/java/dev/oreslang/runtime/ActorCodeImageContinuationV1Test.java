package dev.oreslang.runtime;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact production receipt + image ledger composition. Host-only: physical
 * loader witnessing and source actor mail execution are independent gates.
 */
final class ActorCodeImageContinuationV1Test {
    private static final String SHA = "a".repeat(64);

    private static final class Fixture {
        final HotCodeGenerationRetentionV1 images =
                new HotCodeGenerationRetentionV1(1, 128);
        final HotCodeGenerationRetentionV1.Ticket code = images.reserve(1, SHA, 64);
        final ActorContinuationLedger ledger = new ActorContinuationLedger(1);
        final ActorCodeImageContinuationV1 bridge =
                new ActorCodeImageContinuationV1(ledger, images, code);
        final HttpGenerationDrainV1 http = new HttpGenerationDrainV1(1, 1, 1);

        Fixture() {
            images.activated(code);
            http.beginDrain();
        }

        long pinned() {
            return images.status().retained().get(0).liveCodeLeases();
        }

        void drainAndReclaim() {
            images.draining(code);
            assertTrue(http.readyToRetire());
            images.acknowledgePhysicalCleanup(code, http, true);
            assertEquals(0, images.status().heldBytes());
        }
    }

    private static Object retainedCodeResource(ActorContinuationLedger.Admission receipt)
            throws Exception {
        Field field = ActorContinuationLedger.Admission.class.getDeclaredField("codeLease");
        field.setAccessible(true);
        return field.get(receipt);
    }

    @Test
    void queuedContinuationPinsPhysicalImageThroughCutoverAndStopDiscard() throws Exception {
        Fixture f = new Fixture();
        AtomicInteger invoked = new AtomicInteger();
        var receipt = f.bridge.admit(ActorContinuationLedger.Lane.ORDINARY,
                invoked::incrementAndGet);
        assertEquals(1L, f.pinned());
        assertNotNull(retainedCodeResource(receipt));
        f.images.draining(f.code);
        assertThrows(IllegalStateException.class,
                () -> f.images.acknowledgePhysicalCleanup(f.code, f.http, true));
        assertThrows(IllegalStateException.class,
                () -> f.images.reserve(2, "b".repeat(64), 32),
                "bounded image quota must not evict running or queued code");
        f.ledger.sealOrdinary();
        receipt.run(); // STOP: ordinary callback discarded, never entered
        receipt.close(); // duplicate terminal release never underflows
        assertEquals(0, invoked.get());
        assertEquals(0L, f.pinned());
        assertNull(retainedCodeResource(receipt));
        f.images.acknowledgePhysicalCleanup(f.code, f.http, true);
        assertEquals(0, f.images.status().heldBytes());
    }

    @Test
    void runningFrameOutlivesStopAndForeignCloseUntilActualCallbackReturn() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        var receipt = f.bridge.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {
            entered.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("callback stalled");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        Thread runner = Thread.ofPlatform().start(() -> {
            try {
                receipt.run();
            } catch (Throwable failure) {
                unexpected.set(failure);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.images.draining(f.code);
            f.ledger.sealOrdinary();
            receipt.close(); // running code may not be physically retired here
            assertEquals(1L, f.pinned());
            assertEquals(1, f.ledger.snapshot().running());
            assertThrows(IllegalStateException.class,
                    () -> f.images.acknowledgePhysicalCleanup(f.code, f.http, true));
        } finally {
            finish.countDown();
            runner.join(5000);
        }
        assertFalse(runner.isAlive());
        assertNull(unexpected.get());
        assertEquals(0L, f.pinned());
        assertNull(retainedCodeResource(receipt));
        f.images.acknowledgePhysicalCleanup(f.code, f.http, true);
    }

    @Test
    void sourceTaskCleanupPinsImageEvenAfterOrdinaryAdmissionSealed() {
        Fixture f = new Fixture();
        f.ledger.sealOrdinary();
        AtomicInteger called = new AtomicInteger();
        var receipt = f.bridge.admit(ActorContinuationLedger.Lane.SOURCE_TASK,
                called::incrementAndGet);
        f.images.draining(f.code);
        assertEquals(1L, f.pinned());
        assertThrows(IllegalStateException.class,
                () -> f.images.acknowledgePhysicalCleanup(f.code, f.http, true));
        receipt.run();
        assertEquals(1, called.get());
        assertEquals(0L, f.pinned());
        f.ledger.sealAll();
        f.images.acknowledgePhysicalCleanup(f.code, f.http, true);
    }

    @Test
    void rejectedAndExceptionalCallbacksNeverLeakPhysicalCodePins() {
        Fixture f = new Fixture();
        var live = f.bridge.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {});
        assertEquals(1L, f.pinned());
        assertThrows(IllegalStateException.class,
                () -> f.bridge.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {}),
                "rejected capacity must return newly acquired image pin");
        assertEquals(1L, f.pinned());
        live.close();
        assertEquals(0L, f.pinned());
        var failure = f.bridge.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {
            throw new IllegalArgumentException("guest failed");
        });
        assertThrows(IllegalArgumentException.class, failure::run);
        assertEquals(0L, f.pinned());
        f.ledger.sealAll();
        assertThrows(IllegalStateException.class,
                () -> f.bridge.admit(ActorContinuationLedger.Lane.SOURCE_TASK, () -> {}));
        assertEquals(0L, f.pinned());
        f.drainAndReclaim();
    }

    @Test
    void foreignImagesCannotAttachToAnotherImageBudget() {
        Fixture f = new Fixture();
        var foreignBudget = new HotCodeGenerationRetentionV1(1, 128);
        var foreign = new ActorCodeImageContinuationV1(f.ledger, foreignBudget, f.code);
        assertThrows(IllegalStateException.class,
                () -> foreign.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {}));
        assertEquals(0L, f.pinned());
        f.drainAndReclaim();
        assertThrows(IllegalStateException.class,
                () -> f.bridge.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {}),
                "retired physical image must not admit any new callbacks");
    }

    @Test
    void racingCloseRunAndStopHasSingleCodeImageTerminalRelease() throws Exception {
        for (int round = 0; round < 64; round++) {
            Fixture f = new Fixture();
            AtomicInteger invoked = new AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            var receipt = f.bridge.admit(ActorContinuationLedger.Lane.ORDINARY,
                    invoked::incrementAndGet);
            CountDownLatch start = new CountDownLatch(1);
            Thread runner = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    receipt.run();
                } catch (IllegalStateException replay) {
                    if (!replay.getMessage().contains("receipt replay"))
                        unexpected.compareAndSet(null, replay);
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    f.ledger.sealOrdinary();
                    receipt.close();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            start.countDown();
            runner.join(5000);
            stopper.join(5000);
            assertFalse(runner.isAlive());
            assertFalse(stopper.isAlive());
            assertNull(unexpected.get());
            assertTrue(invoked.get() <= 1);
            assertEquals(0, f.ledger.snapshot().queued());
            assertEquals(0, f.ledger.snapshot().running());
            assertEquals(0L, f.pinned(), "one and only one terminal image release");
            assertNull(retainedCodeResource(receipt));
            f.drainAndReclaim();
        }
    }
}
