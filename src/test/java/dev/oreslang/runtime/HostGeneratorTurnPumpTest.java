package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forced interleavings of physical turn exit, cancellation and host admission.
 * No sleep-based timing or arbitrary Truffle multi-thread admission override.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class HostGeneratorTurnPumpTest {

    @Test
    void rootSchedulerIdentityAloneDoesNotAuthorizeGuestEntry() {
        assertFalse(OresContext.generatorRootTurnAuthorized(false, false));
        assertFalse(OresContext.generatorRootTurnAuthorized(true, false),
                "bound scheduler without physical Truffle guest entry is not enough");
        assertFalse(OresContext.generatorRootTurnAuthorized(false, true),
                "guest entry without owning root scheduler is not enough");
        assertTrue(OresContext.generatorRootTurnAuthorized(true, true));
    }

    @Test
    void cannotEnterGuestFromForeignThreadEvenWithValidOwnerToken() throws Exception {
        Thread legitimateOwner = Thread.currentThread();
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> Thread.currentThread() == legitimateOwner);
        AtomicInteger starts = new AtomicInteger();
        OresFuture<Integer> pull = pump.request(pump.token(), () -> {
            starts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(7), OresFuture.completed(null));
        });
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Throwable> denied = new AtomicReference<>();
        Thread foreign = Thread.ofPlatform().name("foreign-guest-entry").start(() -> {
            try {
                pump.dispatchOne();
            } catch (Throwable rejection) {
                denied.set(rejection);
            } finally {
                attempted.countDown();
            }
        });
        assertTrue(attempted.await(5, TimeUnit.SECONDS));
        foreign.join();
        assertInstanceOf(SecurityException.class, denied.get());
        assertEquals(0, starts.get(), "foreign thread may not start a guest producer");
        assertFalse(pull.isDone());
        assertTrue(pump.dispatchOne());
        assertEquals(7, pull.get(5, TimeUnit.SECONDS));
        assertEquals(1, starts.get());
        pump.close();
    }

    @Test
    void resultBeforePhysicalExitDoesNotAdmitAnotherTurn() throws Exception {
        AtomicInteger wakes = new AtomicInteger();
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true, wakes::incrementAndGet, 2);
        OresFuture<Integer> firstValue = new OresFuture<>();
        OresFuture<Void> firstPhysicalExit = new OresFuture<>();
        AtomicInteger secondStarts = new AtomicInteger();

        OresFuture<Integer> first = pump.request(pump.token(),
                () -> new HostGeneratorTurnPump.Turn<>(firstValue, firstPhysicalExit));
        OresFuture<Integer> second = pump.request(pump.token(), () -> {
            secondStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(22), OresFuture.completed(null));
        });

        AtomicReference<Boolean> publicationRetainedOwnership = new AtomicReference<>();
        first.whenCompleteRuntime((value, failure) ->
                publicationRetainedOwnership.set(!pump.dispatchOne()));
        assertTrue(pump.dispatchOne());
        firstValue.completeFromRuntime(11);
        assertFalse(first.isDone(),
                "public result cannot settle before the turn has physically left guest");
        assertFalse(pump.dispatchOne(), "a second producer must not overlap guest entry");
        assertEquals(0, secondStarts.get());
        assertThrows(IllegalStateException.class, pump::requireQuiescent);
        firstPhysicalExit.completeFromRuntime(null);
        assertEquals(11, first.get(5, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, publicationRetainedOwnership.get(),
                "the previous Future must publish before the next turn is eligible");
        assertTrue(wakes.get() >= 2, "owner must be notified of resumed capacity");
        assertTrue(pump.dispatchOne());
        assertEquals(22, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, secondStarts.get());
        pump.close();
    }

    @Test
    void cancelledPullDoesNotFakePhysicalGuestExitOrAllowNewTurn() {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(UUID.randomUUID(), () -> true);
        OresFuture<Integer> producerValue = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        OresFuture<Integer> pull = pump.request(pump.token(),
                () -> new HostGeneratorTurnPump.Turn<>(producerValue, physicalExit));
        assertTrue(pump.dispatchOne());
        assertTrue(pull.cancel(false));
        assertTrue(producerValue.isCancelled(),
                "consumer cancellation must request producer cancellation");
        assertFalse(pump.isQuiescent(),
                "Future cancellation is not physical guest cleanup");
        pump.seal();
        assertThrows(IllegalStateException.class, pump::requireQuiescent);
        physicalExit.completeFromRuntime(null);
        assertDoesNotThrow(pump::requireQuiescent);
        assertThrows(RejectedExecutionException.class,
                () -> pump.request(pump.token(),
                        () -> new HostGeneratorTurnPump.Turn<>(
                                OresFuture.completed(1), OresFuture.completed(null))));
    }

    @Test
    void cancelledPhysicalExitReceiptCannotAuthorizeNextTurnOrRetirement() {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(UUID.randomUUID(), () -> true);
        OresFuture<Integer> producer = new OresFuture<>();
        OresFuture<Void> exit = new OresFuture<>();
        AtomicInteger secondStarts = new AtomicInteger();
        OresFuture<Integer> first = pump.request(pump.token(),
                () -> new HostGeneratorTurnPump.Turn<>(producer, exit));
        OresFuture<Integer> second = pump.request(pump.token(), () -> {
            secondStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(22), OresFuture.completed(null));
        });
        assertTrue(pump.dispatchOne());
        assertTrue(exit.cancel(false));
        assertTrue(producer.isCancelled(),
                "failed exit proof must request cancellation of the producer");
        assertTrue(second.isCancelled(), "a failed physical exit seals the pending queue");
        assertFalse(pump.dispatchOne());
        assertFalse(pump.isQuiescent());
        assertThrows(IllegalStateException.class, pump::close,
                "cancelled physical exit is not a DONE receipt");
        assertEquals(0, secondStarts.get());
        assertTrue(first.isDone());
    }

    @Test
    void exceptionalPhysicalExitReceiptCannotAuthorizeRetirement() {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(UUID.randomUUID(), () -> true);
        OresFuture<Integer> producer = OresFuture.completed(13);
        OresFuture<Void> exit = new OresFuture<>();
        OresFuture<Integer> pull = pump.request(pump.token(),
                () -> new HostGeneratorTurnPump.Turn<>(producer, exit));
        assertTrue(pump.dispatchOne());
        exit.failFromRuntime(new IllegalStateException("physical exit unknown"));
        assertTrue(pull.isDone());
        assertFalse(pump.isQuiescent(),
                "failure of DONE evidence must remain permanently quarantined");
        assertThrows(IllegalStateException.class, pump::close);
    }

    @Test
    void contextIncarnationAndBackpressureAreFailClosed() {
        AtomicInteger started = new AtomicInteger();
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true, () -> { }, 1);
        assertThrows(SecurityException.class,
                () -> pump.request(new HostGeneratorTurnPump.OwnerToken(
                        pump.token().contextId(), UUID.randomUUID()),
                        () -> {
                            started.incrementAndGet();
                            return new HostGeneratorTurnPump.Turn<>(
                                    OresFuture.completed(1), OresFuture.completed(null));
                        }));
        OresFuture<Integer> queued = pump.request(pump.token(), () -> {
            started.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(1), OresFuture.completed(null));
        });
        assertThrows(RejectedExecutionException.class,
                () -> pump.request(pump.token(), () -> {
                    started.incrementAndGet();
                    return new HostGeneratorTurnPump.Turn<>(
                            OresFuture.completed(2), OresFuture.completed(null));
                }));
        pump.seal();
        assertTrue(queued.isCancelled(), "sealed requests must be cancelled before dispatch");
        assertEquals(0, started.get());
        assertFalse(pump.dispatchOne());
        pump.close();
    }

    @Test
    void invalidExitBarrierAndFailedWakeDoNotCreateOrphanedProducer()
            throws Exception {
        HostGeneratorTurnPump invalid = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        OresFuture<Integer> pull = invalid.request(invalid.token(), () -> {
            OresFuture<Integer> shared = new OresFuture<>();
            return new HostGeneratorTurnPump.Turn<>(shared, shared);
        });
        assertTrue(invalid.dispatchOne());
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pull.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(invalid.isQuiescent(),
                "invalid starter exit receipt cannot certify physical quiescence");
        assertThrows(IllegalStateException.class, invalid::close,
                "invalid physical-exit receipt must permanently quarantine this incarnation");

        AtomicInteger producerStarts = new AtomicInteger();
        HostGeneratorTurnPump brokenNotifier = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true,
                () -> { throw new RejectedExecutionException("owner has retired"); }, 2);
        assertThrows(RejectedExecutionException.class, () ->
                brokenNotifier.request(brokenNotifier.token(), () -> {
                    producerStarts.incrementAndGet();
                    return new HostGeneratorTurnPump.Turn<>(
                            OresFuture.completed(5), OresFuture.completed(null));
                }));
        assertTrue(brokenNotifier.isQuiescent());
        assertEquals(0, producerStarts.get(),
                "failed owner wake may not leave dispatchable orphan work");
        assertThrows(RejectedExecutionException.class,
                () -> brokenNotifier.request(brokenNotifier.token(),
                        () -> new HostGeneratorTurnPump.Turn<>(
                                OresFuture.completed(6), OresFuture.completed(null))));
        brokenNotifier.close();
    }

    @Test
    void slowStarterCannotHoldCloseMonitorAndCancellationFencesItsExit() throws Exception {
        // Starter is a potentially slow user-supplied bridge callback. The
        // lifecycle owner must be able to seal and report incomplete cleanup
        // before the callback supplies its exit barrier.
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        OresFuture<Integer> guestResult = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        OresFuture<Integer> request = pump.request(pump.token(), () -> {
            starterEntered.countDown();
            try {
                if (!releaseStarter.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("starter did not receive release");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new HostGeneratorTurnPump.Turn<>(guestResult, physicalExit);
        });
        Thread owner = Thread.ofPlatform().start(() -> {
            try { pump.dispatchOne(); }
            catch (Throwable failure) { ownerFailure.set(failure); }
        });
        try {
            assertTrue(starterEntered.await(5, TimeUnit.SECONDS));
            CountDownLatch closeFinished = new CountDownLatch(1);
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    pump.seal();
                    assertThrows(IllegalStateException.class, pump::requireQuiescent);
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeFinished.countDown();
                }
            });
            assertTrue(closeFinished.await(3, TimeUnit.SECONDS),
                    "close blocked behind in-flight generator starter");
            closer.join(3000);
            assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
            assertTrue(request.isCancelled(),
                    "sealing a starting pull must cancel its host request");
            assertThrows(IllegalStateException.class, pump::requireQuiescent);
        } finally {
            releaseStarter.countDown();
            owner.join(5000);
        }
        assertFalse(owner.isAlive());
        assertNull(ownerFailure.get(), String.valueOf(ownerFailure.get()));
        assertTrue(guestResult.isCancelled(),
                "cancellation before starter return must reach its guest result");
        assertFalse(pump.isQuiescent(),
                "cancelled result cannot stand in for physical exit");
        physicalExit.completeFromRuntime(null);
        assertDoesNotThrow(pump::requireQuiescent);
    }

    @Test
    void reentrantStarterCanSealWithoutDeadlockAndWithoutEarlyRetirement() {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        OresFuture<Integer> guestResult = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        OresFuture<Integer> host = pump.request(pump.token(), () -> {
            pump.seal(); // permitted concurrently with starter execution
            assertThrows(IllegalStateException.class, pump::requireQuiescent);
            return new HostGeneratorTurnPump.Turn<>(guestResult, physicalExit);
        });
        assertTrue(pump.dispatchOne());
        assertTrue(host.isCancelled());
        assertTrue(guestResult.isCancelled());
        assertThrows(IllegalStateException.class, pump::requireQuiescent);
        physicalExit.completeFromRuntime(null);
        pump.close();
    }

    @Test
    void starterFailurePoisonsQueueBeforeAnyFollowingDispatch() throws Exception {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        AtomicInteger laterStarts = new AtomicInteger();
        OresFuture<Integer> broken = pump.request(pump.token(), () -> {
            throw new IllegalStateException("producer start failed after reservation");
        });
        OresFuture<Integer> later = pump.request(pump.token(), () -> {
            laterStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(4), OresFuture.completed(null));
        });
        assertTrue(pump.dispatchOne());
        assertInstanceOf(IllegalStateException.class,
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> broken.get(5, TimeUnit.SECONDS)).getCause());
        assertTrue(later.isCancelled(),
                "unknown partial startup cannot admit the next producer");
        assertEquals(0, laterStarts.get());
        assertFalse(pump.dispatchOne());
        assertFalse(pump.isQuiescent(),
                "a starter that threw without an exit receipt is quarantined");
        assertThrows(IllegalStateException.class, pump::close,
                "physical context retirement must remain blocked after unknown startup");
    }

    @Test
    void cancellationAndContextShutdownRaceCannotStartQueuedTurn() throws Exception {
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> Thread.currentThread() == ownerThread.get());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resumeOwner = new CountDownLatch(1);
        OresFuture<Integer> producerValue = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        AtomicInteger secondStarts = new AtomicInteger();
        OresFuture<Integer> first = pump.request(pump.token(), () -> {
            started.countDown();
            return new HostGeneratorTurnPump.Turn<>(producerValue, physicalExit);
        });
        OresFuture<Integer> second = pump.request(pump.token(), () -> {
            secondStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(2), OresFuture.completed(null));
        });

        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        Thread owner = Thread.ofPlatform().name("context-owned-pump-turn").start(() -> {
            ownerThread.set(Thread.currentThread());
            try {
                assertTrue(pump.dispatchOne());
                if (!resumeOwner.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("owner did not receive release barrier");
                }
                assertFalse(pump.dispatchOne(),
                        "retired pump must reject even an authorized owner");
            } catch (Throwable failure) {
                ownerFailure.set(failure);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(first.cancel(false));
        pump.seal();
        assertTrue(second.isCancelled());
        assertThrows(IllegalStateException.class, pump::requireQuiescent);
        resumeOwner.countDown();
        owner.join(5000);
        assertFalse(owner.isAlive(), "owner dispatch is not allowed to deadlock teardown");
        assertNull(ownerFailure.get(), String.valueOf(ownerFailure.get()));
        assertEquals(0, secondStarts.get());
        physicalExit.completeFromRuntime(null);
        assertDoesNotThrow(pump::requireQuiescent);
    }
}
