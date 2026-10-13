package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Host Future blocking bridges may never park an actor or scheduler carrier. */
final class OresFutureBlockingBridgeTest {
    @Test
    void schedulerCarrierRejectsEveryPendingBlockingBridgeWithoutConsumingTheProducer() {
        try (var scheduler = new OresScheduler(1)) {
            var checked = scheduler.startSync(() -> {
                OresFuture<Integer> pending = new OresFuture<>();
                assertThrows(IllegalStateException.class, pending::join);
                assertThrows(IllegalStateException.class, pending::get);
                assertThrows(IllegalStateException.class,
                        () -> pending.get(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,
                        () -> pending.get(0, TimeUnit.SECONDS),
                        "zero-timeout observation cannot park the carrier");
                assertEquals(0, pending.pendingRuntimeWaiterCount(),
                        "denied blocking calls must not allocate parked subscribers");
                assertFalse(pending.isDone(), "rejecting a host bridge cannot cancel the producer");

                var completed = OresFuture.completed(42);
                assertEquals(42, completed.join());
                assertEquals(42, completed.get());
                assertEquals(42, completed.get(1, TimeUnit.SECONDS));
                return true;
            });
            assertTrue(checked.join());
            assertEquals(17, scheduler.startSync(() -> 17).join(),
                    "rejected bridge cannot stall later scheduler work");
        }
    }

    @Test
    void physicalActorCarrierRemainsNonblockingAfterMailboxContextExits() throws Exception {
        var shared = OresMutex.shared(18);
        var hostGuard = shared.lock();
        var critical = new CriticalGate();
        var active = critical.newTicket();
        assertTrue(active.enterAsync().isDone());
        CountDownLatch inspected = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        AtomicBoolean observedOnce = new AtomicBoolean();
        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            // A turn is finished and its actor execution context is cleared,
            // but the same physical worker is still an admitted actor carrier.
            if (!observedOnce.compareAndSet(false, true)) return;
            try {
                assertTrue(ActorRuntime.isActorCarrierThread());
                assertFalse(ActorRuntime.inActorExecution());
                OresFuture<Void> pending = new OresFuture<>();
                assertThrows(IllegalStateException.class, pending::join);
                assertThrows(IllegalStateException.class, pending::get);
                assertThrows(IllegalStateException.class,
                        () -> pending.get(1, TimeUnit.SECONDS));
                assertEquals(0, pending.pendingRuntimeWaiterCount());

                var contender = critical.newTicket();
                assertThrows(IllegalStateException.class, contender::enterSync);
                assertEquals(0, critical.queuedCount());
                contender.close();

                assertThrows(OresMutex.WrongMutexDomainException.class, shared::lock);
                assertThrows(OresMutex.WrongMutexDomainException.class,
                        () -> shared.lockFor(java.time.Duration.ofMillis(25)));
                assertThrows(OresMutex.WrongMutexDomainException.class,
                        () -> shared.recover(value -> value));
                var mutexFuture = shared.lockAsync();
                try {
                    assertThrows(OresMutex.WrongMutexDomainException.class,
                            mutexFuture::join);
                } finally {
                    mutexFuture.cancel(false);
                }
                assertFalse(pending.isDone());
                assertTrue(active.isHeld());
            } catch (Throwable failure) {
                unexpected.set(failure);
            } finally {
                inspected.countDown();
            }
        };
        try {
            try (var runtime = new ActorRuntime(
                    IsolatePolicy.developer(), ActorRuntime.DispatcherConfig.defaults(), executor)) {
                var worker = runtime.<String>spawnShared(() -> (message, context) -> { });
                worker.send("verify");
                assertTrue(inspected.await(5, TimeUnit.SECONDS),
                        "actor carrier post-turn callback was not observed");
                assertNull(unexpected.get());
            }
        } finally {
            active.close();
            hostGuard.close();
        }
        assertEquals(0, critical.queuedCount());
        assertFalse(critical.held());
    }

    @Test
    void schedulerTerminalCallbackCannotParkCarrierOnAnotherFuture() throws Exception {
        OresFuture<Void> gate = new OresFuture<>();
        OresFuture<Integer> neverCompletes = new OresFuture<>();
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var scheduler = new OresScheduler(1)) {
            var task = scheduler.start(resume -> {
                if (resume.initial()) return OresScheduler.await(gate);
                return OresScheduler.done(91);
            });
            task.whenCompleteRuntime((value, error) -> {
                try {
                    assertNull(error);
                    assertEquals(91, value);
                    assertTrue(OresScheduler.isSchedulerCarrierThread(),
                            "the completion callback runs on the scheduler carrier");
                    assertThrows(IllegalStateException.class, neverCompletes::join);
                    assertThrows(IllegalStateException.class, neverCompletes::get);
                    assertThrows(IllegalStateException.class,
                            () -> neverCompletes.get(5, TimeUnit.SECONDS));
                    assertEquals(0, neverCompletes.pendingRuntimeWaiterCount());
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    observed.countDown();
                }
            });
            assertTrue(gate.completeFromRuntime(null));
            assertTrue(observed.await(5, TimeUnit.SECONDS),
                    "terminal callback must make progress without parking a carrier");
            assertNull(failure.get());
            assertEquals(91, task.join());
            assertEquals(27, scheduler.startSync(() -> 27).join(),
                    "terminal callback cannot leave the scheduler lane stalled");
        }
        assertFalse(neverCompletes.isDone());
    }

    @Test
    void completionRacingHostTimeoutNeverLeaksRegisteredWaiters() throws Exception {
        for (int attempt = 0; attempt < 192; attempt++) {
            OresFuture<Integer> contested = new OresFuture<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread host = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    contested.get(1, TimeUnit.MILLISECONDS);
                } catch (TimeoutException expected) {
                    // The producer may finish before or after this timeout.
                } catch (Throwable failure) {
                    unexpected.set(failure);
                }
            });
            Thread completer = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    contested.completeFromRuntime(29);
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            });
            start.countDown();
            host.join(5_000);
            completer.join(5_000);
            assertFalse(host.isAlive());
            assertFalse(completer.isAlive());
            assertNull(unexpected.get());
            assertEquals(0, contested.pendingRuntimeWaiterCount(),
                    "completion/timeout race must retire its host waiter");
            assertEquals(29, contested.join());
        }
    }

    @Test
    void repeatedHostTimeoutsDoNotRetainCallbacksOrCancelProducer() {
        OresFuture<Integer> pending = new OresFuture<>();
        for (int i = 0; i < 1024; i++) {
            assertThrows(TimeoutException.class,
                    () -> pending.get(1, TimeUnit.NANOSECONDS));
            assertEquals(0, pending.pendingRuntimeWaiterCount(),
                    "timed host reads must release their waiter even if producer never settles");
        }
        assertFalse(pending.isDone());
        assertTrue(pending.completeFromRuntime(23));
        assertEquals(23, pending.join());
        assertEquals(0, pending.pendingRuntimeWaiterCount());
    }

    @Test
    void interruptedHostWaitDetachesWithoutCancellingProducer() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread host = new Thread(() -> {
            started.countDown();
            try {
                pending.get();
                unexpected.set(new AssertionError("a pending Future must not complete"));
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } catch (Throwable failure) {
                unexpected.set(failure);
            }
        }, "host-future-get-interruption");
        host.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pending.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(1, pending.pendingRuntimeWaiterCount());
        } finally {
            host.interrupt();
            host.join(5_000);
        }
        assertFalse(host.isAlive(), "host must terminate after interrupt");
        assertNull(unexpected.get());
        assertTrue(interrupted.get());
        assertEquals(0, pending.pendingRuntimeWaiterCount(),
                "interrupted get must not retain a callback on a suspended producer");
        assertFalse(pending.isDone());
        assertTrue(pending.completeFromRuntime(7));
        assertEquals(7, pending.join());
    }
}
