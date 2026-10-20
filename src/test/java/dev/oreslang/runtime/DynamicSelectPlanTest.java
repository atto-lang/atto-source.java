package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

final class DynamicSelectPlanTest {
    @Test
    void addReplaceRemoveAndSnapshotIndexes() {
        var a = new ChannelRuntime.Channel<String>(2);
        var b = new ChannelRuntime.Channel<String>(2);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        assertThrows(IllegalStateException.class, plan::trySelect);
        plan.put("a", ChannelRuntime.read(a));
        plan.put("b", ChannelRuntime.read(b));
        assertEquals(List.of("a", "b"), plan.keys());

        b.tryWrite("one");
        var first = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow();
        assertEquals("b", first.key());
        assertEquals(1, first.selection().index());
        long version = first.version();

        plan.put("b", ChannelRuntime.read(a));
        assertEquals(List.of("a", "b"), plan.keys(), "replacing an entry must not reorder it");
        assertTrue(plan.remove("a"));
        assertFalse(plan.remove("a"));
        assertEquals(List.of("b"), plan.keys());
        a.tryWrite("two");
        var second = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow();
        assertEquals("b", second.key());
        assertEquals(0, second.selection().index());
        assertTrue(second.version() > version);
        assertEquals("two", second.selection().value());
    }

    @Test
    void inflightSelectionUsesOldSnapshotAfterMutation() {
        var oldChannel = new ChannelRuntime.Channel<String>(0);
        var newChannel = new ChannelRuntime.Channel<String>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("old", ChannelRuntime.read(oldChannel));
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(pending.isDone());

        plan.remove("old");
        plan.put("new", ChannelRuntime.read(newChannel));
        newChannel.tryWrite("new value");
        assertEquals("new", plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.PRIORITY).join().key());

        assertTrue(oldChannel.tryWrite("old value"), "old waiter remains armed for its snapshot");
        var oldResult = pending.join();
        assertEquals("old", oldResult.key());
        assertEquals("old value", oldResult.selection().value());
        assertTrue(oldResult.version() < plan.version());
    }

    @Test
    void cancellationDetachesOldSnapshotEvenAfterRemoval() {
        var channel = new ChannelRuntime.Channel<String>(0);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("work", ChannelRuntime.read(channel));
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
        plan.remove("work");
        assertTrue(pending.cancel(false));
        assertThrows(CancellationException.class, pending::join);
        assertFalse(channel.tryWrite("orphan"), "cancelled waiter must not consume");
    }

    @Test
    void versionedSnapshotsShareFairnessAcrossReplacement() {
        var a = new ChannelRuntime.Channel<String>(2);
        var b = new ChannelRuntime.Channel<String>(2);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("a", ChannelRuntime.read(a));
        plan.put("b", ChannelRuntime.read(b));
        a.tryWrite("a1");
        b.tryWrite("b1");
        assertEquals("a", plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR).join().key());
        plan.put("b", ChannelRuntime.read(b));
        a.tryWrite("a2");
        assertEquals("b", plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR).join().key());
    }

    @Test
    void cachesDescriptorsUntilMutationWithoutReusingPendingOperations() {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("reader", ChannelRuntime.read(channel));
        var before = plan.selectSetSnapshot();
        assertSame(before, plan.selectSetSnapshot(), "repeated selection should reuse descriptor snapshot");

        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);
        plan.put("reader", ChannelRuntime.read(channel));
        var after = plan.selectSetSnapshot();
        assertNotSame(before, after, "mutation must publish a new immutable descriptor");
        assertSame(after, plan.selectSetSnapshot());
        assertEquals(1, before.cases().size());

        channel.tryWrite(77);
        assertEquals("reader", pending.join().key());
        assertEquals(77, pending.join().selection().value());
    }

    @Test
    void staleWinnerDoesNotRotateNewGenerationFairness() {
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        var oldA = new ChannelRuntime.Channel<Integer>(0);
        var oldB = new ChannelRuntime.Channel<Integer>(0);
        var freshA = new ChannelRuntime.Channel<Integer>(1);
        var freshB = new ChannelRuntime.Channel<Integer>(1);
        plan.put("oldA", ChannelRuntime.read(oldA));
        plan.put("oldB", ChannelRuntime.read(oldB));
        var pending = plan.selectKeyedAsync(ChannelRuntime.SelectPolicy.FAIR);

        plan.remove("oldA");
        plan.remove("oldB");
        plan.put("freshA", ChannelRuntime.read(freshA));
        plan.put("freshB", ChannelRuntime.read(freshB));
        var newGeneration = plan.selectSetSnapshot();

        assertTrue(oldA.tryWrite(11));
        assertEquals("oldA", pending.join().key());
        assertSame(newGeneration, plan.selectSetSnapshot());
        assertTrue(freshA.tryWrite(21));
        assertTrue(freshB.tryWrite(22));
        assertEquals("freshA",
                plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR).orElseThrow().key());
    }

    @Test
    void defaultDispatchDoesNotAdvanceFairnessCursor() {
        var a = new ChannelRuntime.Channel<Integer>(1);
        var b = new ChannelRuntime.Channel<Integer>(1);
        var set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(a),
                ChannelRuntime.defaultCase(),
                ChannelRuntime.read(b));
        assertEquals(ChannelRuntime.SelectOperation.DEFAULT,
                set.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow().operation());
        assertTrue(a.tryWrite(11));
        assertTrue(b.tryWrite(22));
        assertEquals(0, set.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow().index());
    }

    @Test
    void cachedSnapshotStillReusesCaseListAfterRepeatedSelects() {
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        var channel = new ChannelRuntime.Channel<Integer>(3);
        plan.put("reader", ChannelRuntime.read(channel));
        var stable = plan.selectSetSnapshot();
        for (int i = 0; i < 3; i++) {
            assertTrue(channel.tryWrite(i));
            assertEquals(i, plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR)
                    .orElseThrow().selection().value());
            assertSame(stable, plan.selectSetSnapshot());
        }
    }

    @Test
    void longRunningLoopReusesPlansAndKeepsFairPriorityAndRandomSemantics() {
        var first = new ChannelRuntime.Channel<Integer>(4096);
        var second = new ChannelRuntime.Channel<Integer>(4096);
        var plan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second));
        var descriptors = plan.selectSetSnapshot();
        var firstCase = descriptors.cases().get(0);
        var secondCase = descriptors.cases().get(1);
        for (int i = 0; i < 2048; i++) {
            assertTrue(first.tryWrite(i));
            assertTrue(second.tryWrite(i));
            var result = plan.trySelect(ChannelRuntime.SelectPolicy.FAIR).orElseThrow();
            assertEquals(i % 2, result.index(), "reused FAIR plan should rotate");
            assertSame(descriptors, plan.selectSetSnapshot());
            assertSame(firstCase, descriptors.cases().get(0));
            assertSame(secondCase, descriptors.cases().get(1));
        }
        for (int i = 0; i < 128; i++) {
            assertEquals(0, plan.trySelect(ChannelRuntime.SelectPolicy.PRIORITY)
                    .orElseThrow().index());
        }
        var randomPlan = ChannelRuntime.SelectPlan.of(
                ChannelRuntime.read(first), ChannelRuntime.read(second));
        for (int i = 0; i < 256; i++) {
            int index = randomPlan.trySelect(ChannelRuntime.SelectPolicy.RANDOM)
                    .orElseThrow().index();
            assertTrue(index == 0 || index == 1);
        }
    }

    @Test
    void concurrentMutationAndReadinessPreserveSnapshotKeysAndVersions() throws Exception {
        var stable = new ChannelRuntime.Channel<Integer>(128);
        var changing = new ChannelRuntime.Channel<Integer>(128);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("stable", ChannelRuntime.read(stable));
        var workers = java.util.concurrent.Executors.newFixedThreadPool(3);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var mutator = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 600; i++) {
                    plan.put("changing", ChannelRuntime.read(changing));
                    plan.remove("changing");
                }
                return true;
            });
            var producer = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 1500; i++) {
                    stable.tryWrite(i);
                    changing.tryWrite(i);
                }
                return true;
            });
            var selector = workers.submit(() -> {
                start.await();
                for (int i = 0; i < 1800; i++) {
                    var outcome = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR);
                    if (outcome.isPresent()) {
                        var result = outcome.orElseThrow();
                        assertTrue(result.key().equals("stable")
                                || result.key().equals("changing"));
                        assertEquals(ChannelRuntime.SelectOperation.READ,
                                result.selection().operation());
                        assertTrue(result.version() <= plan.version(),
                                "one operation must never observe a future version");
                    }
                }
                return true;
            });
            start.countDown();
            assertTrue(mutator.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(producer.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(selector.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of("stable"), plan.keys());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentAdmissionPreservesExactFairRotationWithContinuouslyReadyArms() throws Exception {
        var a = new ChannelRuntime.Channel<Integer>(5000);
        var b = new ChannelRuntime.Channel<Integer>(5000);
        for (int i = 0; i < 5000; i++) {
            assertTrue(a.tryWrite(i));
            assertTrue(b.tryWrite(i));
        }
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("a", ChannelRuntime.read(a));
        plan.put("b", ChannelRuntime.read(b));

        var workers = java.util.concurrent.Executors.newFixedThreadPool(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<int[]>>();
            for (int thread = 0; thread < 8; thread++) {
                tasks.add(workers.submit(() -> {
                    start.await();
                    int[] counts = new int[2];
                    for (int i = 0; i < 1000; i++) {
                        var selected = plan.trySelectKeyed(ChannelRuntime.SelectPolicy.FAIR)
                                .orElseThrow();
                        if ("a".equals(selected.key())) counts[0]++;
                        else if ("b".equals(selected.key())) counts[1]++;
                        else fail("unexpected selection key " + selected.key());
                    }
                    return counts;
                }));
            }
            start.countDown();
            int[] totals = new int[2];
            for (var task : tasks) {
                var counts = task.get(20, java.util.concurrent.TimeUnit.SECONDS);
                totals[0] += counts[0];
                totals[1] += counts[1];
            }
            assertArrayEquals(new int[] {4000, 4000}, totals,
                    "FAIR must use the cursor of the serialized admission, not stale construction order");
            assertEquals(1000, a.size());
            assertEquals(1000, b.size());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void cancellationVersusRendezvousHasOneTerminalOwner() throws Exception {
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 250; round++) {
                var channel = new ChannelRuntime.Channel<Integer>(0);
                var set = ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel));
                var pending = set.selectAsync(ChannelRuntime.SelectPolicy.FAIR);
                var start = new java.util.concurrent.CountDownLatch(1);
                var writer = workers.submit(() -> {
                    start.await();
                    return channel.writeAsync(99);
                });
                var canceller = workers.submit(() -> {
                    start.await();
                    return pending.cancel(false);
                });
                start.countDown();
                var outgoing = writer.get(10, java.util.concurrent.TimeUnit.SECONDS);
                boolean cancelled = canceller.get(10, java.util.concurrent.TimeUnit.SECONDS);
                channel.close();
                assertTrue(pending.isDone());
                assertTrue(outgoing.isDone());
                if (cancelled) {
                    assertTrue(pending.isCancelled());
                    assertThrows(java.util.concurrent.CancellationException.class, pending::join);
                    assertThrows(java.util.concurrent.CompletionException.class, outgoing::join,
                            "writer must not succeed when its rendezvous reader was cancelled");
                } else {
                    assertEquals(99, pending.join().value());
                    assertNull(outgoing.join());
                }
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void fairnessWinnerCannotDeadlockWithPlanMonitorAndChannelLock() throws Exception {
        var channel = new ChannelRuntime.Channel<Integer>(1);
        assertTrue(channel.tryWrite(71));
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("ready", ChannelRuntime.read(channel));
        // Snapshot before either thread starts: it can then attempt admission
        // without acquiring the public plan's monitor first.
        var snapshot = plan.selectSetSnapshot();
        var field = ChannelRuntime.class.getDeclaredField("COORDINATOR");
        field.setAccessible(true);
        var coordinator = (java.util.concurrent.locks.ReentrantLock) field.get(null);

        var readerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var holderFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var monitorHeld = new java.util.concurrent.CountDownLatch(1);
        var reader = new Thread(() -> {
            try {
                assertEquals(71, snapshot.trySelect(ChannelRuntime.SelectPolicy.FAIR)
                        .orElseThrow().value());
            } catch (Throwable failure) {
                readerFailure.set(failure);
            }
        }, "select-fair-reader");
        var holder = new Thread(() -> {
            try {
                synchronized (plan) {
                    monitorHeld.countDown();
                    // Deliberately take the inverse lock order from an
                    // application that owns its plan while probing a channel.
                    channel.size();
                }
            } catch (Throwable failure) {
                holderFailure.set(failure);
            }
        }, "plan-monitor-holder");
        reader.setDaemon(true);
        holder.setDaemon(true);

        coordinator.lock();
        try {
            reader.start();
            awaitQueued(coordinator, reader);
            holder.start();
            assertTrue(monitorHeld.await(5, java.util.concurrent.TimeUnit.SECONDS));
            awaitQueued(coordinator, holder);
        } finally {
            coordinator.unlock();
        }

        reader.join(5000);
        holder.join(5000);
        assertFalse(reader.isAlive(), "select must not wait for plan monitor under channel coordinator");
        assertFalse(holder.isAlive(), "plan monitor holder must be able to enter channel coordinator");
        assertNull(readerFailure.get());
        assertNull(holderFailure.get());
    }

    private static void awaitQueued(
            java.util.concurrent.locks.ReentrantLock coordinator,
            Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!coordinator.hasQueuedThread(thread) && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(coordinator.hasQueuedThread(thread),
                "expected " + thread.getName() + " to wait on channel coordinator");
    }

    @Test
    void rejectsSecondDefaultAtomicallyAndKeepsPreviousSnapshot() {
        var channel = new ChannelRuntime.Channel<String>(1);
        var plan = new ChannelRuntime.DynamicSelectPlan<String>();
        plan.put("default", ChannelRuntime.defaultCase());
        long before = plan.version();
        assertThrows(IllegalArgumentException.class,
                () -> plan.put("other", ChannelRuntime.defaultCase()));
        assertEquals(before, plan.version());
        assertEquals(List.of("default"), plan.keys());
        assertEquals("default",
                plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow().key());

        plan.put("default", ChannelRuntime.read(channel));
        plan.put("other", ChannelRuntime.defaultCase());
        assertEquals("other",
                plan.trySelectKeyed(ChannelRuntime.SelectPolicy.PRIORITY).orElseThrow().key());
    }
}
