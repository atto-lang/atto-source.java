package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Immediate actor send probes must use the same bounded USER channel admission
 * as the awaitable send, with no deferred message on a failed probe.
 */
@Timeout(20)
final class ActorTrySendAtomicAdmissionTest {
    private static IsolatePolicy oneMessagePolicy() {
        IsolatePolicy base = IsolatePolicy.developer();
        return new IsolatePolicy(
                base.capabilities(), base.maxHeapBytes(), 1,
                base.maxWallTime(), base.adversarial());
    }

    @Test
    void racingImmediateProducersCanFillOnlyOneFreeUserCredit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch launch = new CountDownLatch(1);
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());

        try (ActorRuntime runtime = new ActorRuntime();
             var producers = Executors.newFixedThreadPool(8)) {
            var target = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (mail, context) -> {
                if (mail == -1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test actor turn did not release");
                    }
                }
                received.add(mail);
            });
            target.send(-1); // Hold handler; USER queue is empty and has one credit.
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            List<Future<Boolean>> probes = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final int payload = i;
                probes.add(producers.submit(() -> {
                    if (!launch.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("producer start timed out");
                    }
                    return target.trySend(payload);
                }));
            }
            launch.countDown();
            AtomicInteger successful = new AtomicInteger();
            for (Future<Boolean> probe : probes) {
                if (probe.get(5, TimeUnit.SECONDS)) successful.incrementAndGet();
            }

            assertEquals(1, successful.get(), "one USER credit must admit exactly one producer");
            release.countDown();
            target.drain().get(5, TimeUnit.SECONDS);
            assertEquals(2, received.size());
            assertEquals(-1, received.getFirst());
            assertNotEquals(-1, received.get(1));
            target.stop();
            target.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            launch.countDown();
        }
    }

    @Test
    void cancellationProgressesThroughFullUserMailboxAndFailsQueuedProducer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());

        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (mail, context) -> {
                if (mail == 1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test actor turn did not release");
                    }
                }
                received.add(mail);
            });
            target.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            target.send(2); // User mailbox saturated.

            assertFalse(target.trySend(3), "immediate send may not bypass USER capacity");
            OresFuture<Void> pending = target.sendAsync(4);
            assertFalse(pending.isDone());

            assertTrue(target.cancel(), "runtime CONTROL cancellation must not need USER credit");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause(), "pending admission must settle with a typed failure");

            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> target.trySend(5));
            assertFalse(rejected instanceof ActorRuntime.ActorMailboxFullException,
                    "closed/sealed actor may not masquerade as a full mailbox");

            release.countDown();
            assertTrue(target.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(received.contains(3), "failed try_send cannot arrive later");
            assertFalse(received.contains(4), "cancelled admission cannot arrive later");
        } finally {
            release.countDown();
        }
    }

    @Test
    void immediateSendCannotCrossAnActorRuntimeAuthorityBoundary() {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            var actor = owner.<Integer>spawnShared(() -> (mail, context) -> {});
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> foreign.trySend(actor, 42));
            actor.stop();
        }
    }
    @Test
    void invalidPayloadWithFreeUserCreditFailsTypedAndRefundsAdmission() throws Exception {
        List<Object> received = Collections.synchronizedList(new ArrayList<>());
        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Object>spawnShared(oneMessagePolicy(),
                    () -> (mail, context) -> received.add(mail));
            target.ready().get(5, TimeUnit.SECONDS);

            // A non-Sendable host object is not just a failed immediate probe
            // when capacity exists. Validation must reject it, without
            // poisoning the capacity or creating a deferred delivery.
            RuntimeException invalid = assertThrows(RuntimeException.class,
                    () -> target.trySend(new Object()));
            assertFalse(invalid instanceof ActorRuntime.ActorMailboxFullException,
                    "invalid transport must not look like mailbox saturation");

            assertTrue(target.trySend(17),
                    "failed transport validation must refund its claimed USER slot");
            target.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(17), received,
                    "rejected object must never arrive after a later accepted send");
            target.stop();
            target.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancellingOldestPendingProducerSkipsItWithoutStealingSuccessorCredit()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());
        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (mail, context) -> {
                if (mail == 1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn never released");
                    }
                }
                received.add(mail);
            });
            target.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            target.send(2);
            OresFuture<Void> first = target.sendAsync(3);
            OresFuture<Void> second = target.sendAsync(4);
            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertTrue(first.cancel(false), "oldest waiter cancellation must revoke its credit claim");
            assertFalse(second.isDone(), "next waiter must not receive credit before a real dequeue");
            assertFalse(target.trySend(5), "immediate sender must not steal a waiting producer's credit");

            release.countDown();
            second.get(5, TimeUnit.SECONDS);
            target.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2, 4), received,
                    "FIFO successor must enter once; cancelled predecessor cannot arrive");
            assertTrue(target.trySend(6), "cancelled waiter must not leak USER capacity");
            target.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2, 4, 6), received);
            target.stop();
            target.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void actorPanicWithFullUserMailboxFailsWaiterAndPreservesControlProgress()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> received = Collections.synchronizedList(new ArrayList<>());
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (mail, context) -> {
                if (mail == 1) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("panic gate never released");
                    }
                    throw new IllegalStateException("intentional-mailbox-panic");
                }
                received.add(mail);
            });
            actor.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            actor.send(2); // Saturate the sole USER credit.
            assertFalse(actor.trySend(3), "USER saturation must not become a CONTROL admission");
            OresFuture<Void> pending = actor.sendAsync(4);
            assertFalse(pending.isDone());

            release.countDown(); // Fail the active handler while USER is full.
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "panic must fail-stop despite a saturated USER mailbox");
            assertTrue(actor.failure().isPresent(),
                    "panic must retain its failure rather than appear as clean STOP");
            assertTrue(String.valueOf(actor.failure().orElseThrow())
                            .contains("intentional-mailbox-panic"),
                    () -> "unexpected panic cause " + actor.failure());
            assertThrows(ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS),
                    "pending producer must fail on panic rather than wait for a USER credit");
            assertFalse(received.contains(2), "queued USER mail must not execute after panic");
            assertFalse(received.contains(3), "rejected immediate probe must not arrive");
            assertFalse(received.contains(4), "failed async producer must never arrive");
        } finally {
            release.countDown();
        }
    }

}
