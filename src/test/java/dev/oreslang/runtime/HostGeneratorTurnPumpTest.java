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
        invalid.close();

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
