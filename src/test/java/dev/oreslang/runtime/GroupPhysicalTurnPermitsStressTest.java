package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A source-free host/scheduler permit stress, NOT a guest actor
 * max>1 isolation proof. A returned handoff is explicitly published by
 * the external test dispatcher; nothing runs while the permit gate is held.
 */
@Timeout(30)
final class GroupPhysicalTurnPermitsStressTest {
    @Test
    void fourHostCarriersDrainSixtyFourFifoActorsWithoutDoubleCredits() throws Exception {
        final int rounds = 20;
        final int actors = 64;
        final int capacity = 3;
        for (int round = 0; round < rounds; ++round) {
            var gate = new GroupPhysicalTurnPermits(capacity, actors);
            var ready = new ConcurrentLinkedQueue<GroupPhysicalTurnPermits.Grant>();
            for (int id = 1; id <= actors; ++id) {
                var admission = gate.tryRequest(new GroupPhysicalTurnPermits.Actor(id, 1));
                if (id <= capacity) {
                    assertEquals(GroupPhysicalTurnPermits.Admission.GRANTED, admission.status());
                    ready.add(admission.grant());
                } else {
                    assertEquals(GroupPhysicalTurnPermits.Admission.QUEUED, admission.status());
                }
            }
            assertEquals(new GroupPhysicalTurnPermits.Snapshot(capacity, actors - capacity, false),
                    gate.trySnapshot());
            var processed = new AtomicInteger();
            var maxObserved = new AtomicInteger();
            var uniqueTickets = ConcurrentHashMap.<Long>newKeySet();
            var start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(4);
            List<Future<?>> submitted = new ArrayList<>();
            try {
                for (int carrier = 0; carrier < 4; ++carrier) {
                    submitted.add(workers.submit(() -> {
                        start.await();
                        while (processed.get() < actors) {
                            if (Thread.currentThread().isInterrupted()) return null;
                            var token = ready.poll();
                            if (token == null) {
                                Thread.yield();
                                continue;
                            }
                            if (!uniqueTickets.add(token.ticket())) {
                                throw new AssertionError("replayed active actor ticket");
                            }
                            // Simulated physical turn: schedule the next owner
                            // only after release atomically transfers credit.
                            GroupPhysicalTurnPermits.Release released;
                            int retries = 0;
                            do {
                                released = gate.tryRelease(token);
                                if (released.retry()) {
                                    if (++retries > 100000) {
                                        throw new AssertionError("nonparking admission starved");
                                    }
                                    Thread.yield();
                                }
                            } while (released.retry());
                            if (!released.released()) {
                                throw new AssertionError("valid ticket lost ownership");
                            }
                            if (released.successor() != null) ready.add(released.successor());
                            int total = processed.incrementAndGet();
                            if (total > actors) throw new AssertionError("double completion");
                            var snapshot = gate.trySnapshot();
                            if (snapshot != null) {
                                maxObserved.accumulateAndGet(snapshot.active(), Math::max);
                                if (snapshot.active() > capacity
                                        || snapshot.queued() < 0
                                        || snapshot.active() < 0) {
                                    throw new AssertionError("physical permit accounting violated");
                                }
                            }
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (var task : submitted) task.get(8, TimeUnit.SECONDS);
                assertEquals(actors, processed.get());
                assertEquals(actors, uniqueTickets.size());
                assertTrue(maxObserved.get() <= capacity);
                assertEquals(new GroupPhysicalTurnPermits.Snapshot(0, 0, false),
                        gate.trySnapshot());
                assertEquals(GroupPhysicalTurnPermits.Seal.SEALED, gate.trySeal());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
