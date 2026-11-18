package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.runtime.ActorUpgrade.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Hot upgrade of one actor incarnation (spec chapter 27, section 3): identity
 * kept; generation N drains on its own code; heap kept (same schema) or
 * migrated copy-then-switch; rollback on failure or drain timeout; queued mail
 * filtered; stale continuations refused; N and N+1 never overlap.
 */
@Timeout(60)
final class ActorHotUpgradeTest {

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    /** What a turn saw: which code generation handled which message, on which heap. */
    private record Seen(String version, String message, ActorNativeArena heap, long counter) { }

    /**
     * Version 1 keeps a counter in its heap. "hold" parks an owned Future
     * (generation-N work in flight); "release" completes it.
     */
    private static final class V1 implements ActorRuntime.Behavior<String> {
        final List<Seen> seen;
        final AtomicReference<OresFuture<String>> held;
        ActorNativeArena.Region counter;

        V1(List<Seen> seen, AtomicReference<OresFuture<String>> held) {
            this.seen = seen;
            this.held = held;
        }

        @Override
        public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
            if (counter == null) counter = context.heap().region(8);
            counter.writeLong(0, counter.readLong(0) + 1);
            if (message.equals("hold")) {
                OresFuture<String> pending = new OresFuture<>();
                held.set(pending);
                ActorRuntime runtime = context.runtime();
                runtime.enqueueOnCompletion(pending, runtime.captureCurrentContinuationTarget(),
                        (value, failure) -> seen.add(new Seen("v1-continuation", value, null, -1)));
            }
            seen.add(new Seen("v1", message, context.heap(), counter.readLong(0)));
        }
    }

    /** Version 2 over the same schema: same counter region, different code. */
    private static final class V2 implements ActorRuntime.Behavior<String> {
        final List<Seen> seen;
        final ActorNativeArena.Region counter;

        V2(List<Seen> seen, ActorNativeArena.Region counter) {
            this.seen = seen;
            this.counter = counter;
        }

        @Override
        public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
            counter.writeLong(0, counter.readLong(0) + 100);
            seen.add(new Seen("v2", message, context.heap(), counter.readLong(0)));
        }
    }

    private static ActorUpgrade<String> toV2(List<Seen> seen) {
        return ActorUpgrade.<String>sameSchema((previous, context) -> new V2(seen, ((V1) previous).counter));
    }

    private static List<String> versionsOf(List<Seen> seen) {
        return seen.stream().map(s -> s.version() + ":" + s.message()).toList();
    }

    @Test
    void sameSchemaUpgradeKeepsIdentityHeapAndState() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnSharedTrusted(context -> new V1(seen, new AtomicReference<>()));
            ref.send("a");
            ref.send("b");
            await(() -> seen.size() == 2, "v1 turns");
            ActorUpgrade.Result result = ref.upgrade(toV2(seen)).get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.COMMITTED, result.outcome());
            assertEquals(2, result.codeGeneration());
            assertFalse(result.heapMigrated());
            ref.send("c");
            await(() -> seen.size() == 3, "v2 turn");
            assertEquals(List.of("v1:a", "v1:b", "v2:c"), versionsOf(seen));
            assertSame(seen.get(0).heap(), seen.get(2).heap(), "same schema keeps the heap");
            assertEquals(102, seen.get(2).counter(), "state in the kept heap survives the upgrade");
            assertTrue(ref.isAlive(), "the incarnation and its ref survive");
        }
    }

    @Test
    void generationNDrainsOnItsOwnCodeBeforeTheSwitch() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        AtomicReference<OresFuture<String>> held = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnSharedTrusted(context -> new V1(seen, held));
            ref.send("hold");
            await(() -> held.get() != null && seen.size() == 1, "v1 parked an owned Future");
            OresFuture<ActorUpgrade.Result> upgrade = ref.upgrade(toV2(seen));
            ref.send("during-freeze");
            Thread.sleep(100);
            assertFalse(upgrade.isDone(), "the switch waits for generation N to drain");
            assertEquals(List.of("v1:hold"), versionsOf(seen), "no new invocation is admitted while frozen");

            held.get().completeFromRuntime("done");
            ActorUpgrade.Result result = upgrade.get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.COMMITTED, result.outcome());
            await(() -> seen.size() == 3, "drained continuation and frozen mail");
            assertEquals(List.of("v1:hold", "v1-continuation:done", "v2:during-freeze"), versionsOf(seen),
                    "the generation-N continuation ran on N; mail queued during the freeze reaches N+1");
        }
    }

    @Test
    void aContinuationCapturedUnderGenerationNIsRefusedAfterTheSwitch() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        AtomicReference<ActorRuntime.ContinuationTarget> stale = new AtomicReference<>();
        AtomicBoolean staleRan = new AtomicBoolean();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnSharedTrusted(context -> (message, turn) -> {
                stale.set(turn.runtime().captureCurrentContinuationTarget());
                seen.add(new Seen("v1", message, null, 0));
            });
            ref.send("capture");
            await(() -> stale.get() != null, "target captured");
            assertEquals(Outcome.COMMITTED, ref.upgrade(ActorUpgrade.<String>sameSchema(
                    (previous, context) -> (message, turn) -> seen.add(new Seen("v2", message, null, 0))))
                    .get(5, TimeUnit.SECONDS).outcome());
            runtime.enqueueOnCompletion(OresFuture.completed("late"), stale.get(),
                    (value, failure) -> staleRan.set(true));
            ref.send("after");
            await(() -> seen.size() == 2, "v2 turn");
            Thread.sleep(100);
            assertFalse(staleRan.get(), "generation-N code never runs against generation N+1 state");
        }
    }

    /** Version 1 of a two-field schema: [a, b]. Version 2 is [sum, a, b]. */
    private static final class PairV1 implements ActorRuntime.Behavior<List<Long>> {
        ActorNativeArena.Region pair;
        final AtomicReference<ActorNativeArena.Region> leaked;

        PairV1(AtomicReference<ActorNativeArena.Region> leaked) {
            this.leaked = leaked;
        }

        @Override
        public void onMessage(List<Long> message, ActorRuntime.ActorContext<List<Long>> context) {
            if (pair == null) {
                pair = context.heap().region(16);
                leaked.set(pair);
            }
            pair.writeLong(0, message.get(0));
            pair.writeLong(8, message.get(1));
        }
    }

    @Test
    void aSchemaChangeMigratesIntoAFreshHeapAndRetiresTheOldOne() throws Exception {
        AtomicReference<ActorNativeArena.Region> oldRegion = new AtomicReference<>();
        CompletableFuture<long[]> v2Saw = new CompletableFuture<>();
        AtomicReference<ActorNativeArena> v2Heap = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<List<Long>>spawnSharedTrusted(context -> new PairV1(oldRegion));
            ref.send(List.of(3L, 4L));
            await(() -> oldRegion.get() != null, "v1 wrote its pair");
            AtomicReference<ActorNativeArena> heapDuringMigration = new AtomicReference<>();
            ActorUpgrade.Result result = ref.upgrade(ActorUpgrade.<List<Long>>migrating(
                    (previous, oldHeap, newHeap, context) -> {
                        heapDuringMigration.set(oldHeap);
                        ActorNativeArena.Region from = ((PairV1) previous).pair;
                        ActorNativeArena.Region to = newHeap.region(24);
                        long a = from.readLong(0);
                        long b = from.readLong(8);
                        to.writeLong(0, a + b);
                        to.writeLong(8, a);
                        to.writeLong(16, b);
                        return (message, turn) -> {
                            v2Heap.set(turn.heap());
                            v2Saw.complete(new long[] {to.readLong(0), to.readLong(8), to.readLong(16)});
                        };
                    })).get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.COMMITTED, result.outcome());
            assertTrue(result.heapMigrated());
            ref.send(List.of(0L, 0L));
            long[] state = v2Saw.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(7L, 3L, 4L), List.of(state[0], state[1], state[2]),
                    "generation N+1 reads the migrated layout from the new heap");
            assertNotSame(heapDuringMigration.get(), v2Heap.get(), "a fresh heap is installed");
            assertTrue(heapDuringMigration.get().closed(), "the old heap is retired after the switch");
            assertThrows(IllegalStateException.class, () -> oldRegion.get().readLong(0),
                    "a region of the retired heap is fenced");
        }
    }

    @Test
    void aFailingMigrationRollsBackToTheUntouchedGenerationAndHeap() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        AtomicReference<ActorNativeArena> abandoned = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnSharedTrusted(context -> new V1(seen, new AtomicReference<>()));
            ref.send("a");
            await(() -> seen.size() == 1, "v1 turn");
            long bytesBefore = runtime.runtimeDiagnostics().sharedMemoryBytes();
            ActorUpgrade.Result result = ref.upgrade(ActorUpgrade.<String>migrating(
                    (previous, oldHeap, newHeap, context) -> {
                        abandoned.set(newHeap);
                        newHeap.region(4096).writeLong(0, 99);
                        throw new IllegalStateException("migration bug");
                    })).get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.ROLLED_BACK_MIGRATION_FAILED, result.outcome());
            assertEquals(IllegalStateException.class.getName(), result.failureType());
            assertEquals(1, result.codeGeneration(), "generation N stays installed");
            assertTrue(abandoned.get().closed(), "the abandoned new heap is retired");
            assertEquals(bytesBefore, runtime.runtimeDiagnostics().sharedMemoryBytes(),
                    "the abandoned heap's budget is refunded");
            ref.send("b");
            await(() -> seen.size() == 2, "v1 still serves");
            assertEquals(List.of("v1:a", "v1:b"), versionsOf(seen));
            assertSame(seen.get(0).heap(), seen.get(1).heap(), "the old heap is untouched");
            assertEquals(2, seen.get(1).counter(), "the old state is unchanged");
        }
    }

    @Test
    void aDrainThatMissesItsDeadlineRollsBackAndResumesAdmission() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        AtomicReference<OresFuture<String>> held = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnSharedTrusted(context -> new V1(seen, held));
            ref.send("hold");
            await(() -> held.get() != null, "parked");
            OresFuture<ActorUpgrade.Result> upgrade = ref.upgrade(
                    toV2(seen).drainDeadline(Duration.ofMillis(100)));
            ref.send("queued");
            ActorUpgrade.Result result = upgrade.get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.ROLLED_BACK_DRAIN_TIMEOUT, result.outcome());
            assertEquals(1, result.codeGeneration());
            await(() -> seen.size() == 2, "admission resumed on generation N");
            assertEquals(List.of("v1:hold", "v1:queued"), versionsOf(seen));
            held.get().completeFromRuntime("late");
            await(() -> seen.size() == 3, "the parked continuation still completes on N");
        }
    }

    @Test
    void queuedMailOfARemovedMessageTypeIsRejectedToItsSink() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        List<String> rejected = new CopyOnWriteArrayList<>();
        AtomicReference<OresFuture<String>> held = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnSharedTrusted(context -> new V1(seen, held));
            ref.send("hold");
            await(() -> held.get() != null, "parked");
            OresFuture<ActorUpgrade.Result> upgrade = ref.upgrade(toV2(seen)
                    .admitsQueued(message -> !message.startsWith("legacy:"))
                    .onRejected(mail -> rejected.add(mail.value())));
            for (String message : List.of("legacy:1", "keep:1", "legacy:2", "keep:2")) ref.send(message);
            held.get().completeFromRuntime("done");
            ActorUpgrade.Result result = upgrade.get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.COMMITTED, result.outcome());
            assertEquals(2, result.rejectedMail(), "the outcome counts rejected mail");
            ref.send("fresh");
            await(() -> seen.size() == 5, "v2 turns");
            assertEquals(List.of("legacy:1", "legacy:2"), rejected);
            assertEquals(List.of("v1:hold", "v1-continuation:done", "v2:keep:1", "v2:keep:2", "v2:fresh"),
                    versionsOf(seen), "admitted queued mail keeps its order; fresh mail is not filtered");
        }
    }

    @Test
    void anUpgradeOfATerminatingIncarnationAborts() throws Exception {
        AtomicReference<OresFuture<String>> held = new AtomicReference<>();
        List<Seen> seen = new CopyOnWriteArrayList<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnSharedTrusted(context -> new V1(seen, held));
            ref.send("hold");
            await(() -> held.get() != null, "parked");
            OresFuture<ActorUpgrade.Result> upgrade = ref.upgrade(toV2(seen));
            ref.cancel();
            assertEquals(Outcome.ABORTED_ACTOR_TERMINATED, upgrade.get(5, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void upgradeIsHostOnlyAndOneAtATime() throws Exception {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        AtomicReference<OresFuture<String>> held = new AtomicReference<>();
        CompletableFuture<Throwable> fromActor = new CompletableFuture<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnSharedTrusted(context -> new V1(seen, held));
            var other = runtime.<String>spawnSharedTrusted(context -> (message, turn) -> {
                try {
                    turn.runtime().upgrade(ref, toV2(seen));
                    fromActor.complete(null);
                } catch (Throwable failure) {
                    fromActor.complete(failure);
                }
            });
            other.send("try");
            assertTrue(fromActor.get(5, TimeUnit.SECONDS) instanceof SecurityException,
                    "actor code cannot hot-upgrade another actor");
            ref.send("hold");
            await(() -> held.get() != null, "parked");
            OresFuture<ActorUpgrade.Result> first = ref.upgrade(toV2(seen));
            assertThrows(IllegalStateException.class, () -> ref.upgrade(toV2(seen)),
                    "one upgrade at a time");
            held.get().completeFromRuntime("done");
            assertEquals(Outcome.COMMITTED, first.get(5, TimeUnit.SECONDS).outcome());
        }
    }

    /** Versioned behavior for the overlap property: every turn records its version. */
    private static final class Versioned implements ActorRuntime.Behavior<Integer> {
        final int version;
        final List<int[]> log;

        Versioned(int version, List<int[]> log) {
            this.version = version;
            this.log = log;
        }

        @Override
        public void onMessage(Integer message, ActorRuntime.ActorContext<Integer> context) {
            log.add(new int[] {version, message});
        }
    }

    @Test
    void generationsNeverOverlapUnderConcurrentSendsAndRepeatedUpgrades() throws Exception {
        List<int[]> log = new CopyOnWriteArrayList<>();
        int senders = 3;
        int perSender = 400;
        int upgrades = 12;
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnSharedTrusted(context -> new Versioned(1, log));
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            for (int s = 0; s < senders; s++) {
                int sender = s;
                Thread thread = new Thread(() -> {
                    try {
                        for (int i = 0; i < perSender; i++) {
                            ref.sendAsync(sender * 100_000 + i).get(10, TimeUnit.SECONDS);
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (int u = 2; u <= upgrades + 1; u++) {
                int version = u;
                ActorUpgrade.Result result = ref.upgrade(ActorUpgrade.<Integer>sameSchema(
                        (previous, context) -> new Versioned(version, log))).get(10, TimeUnit.SECONDS);
                assertEquals(Outcome.COMMITTED, result.outcome());
                assertEquals(version, result.codeGeneration());
                Thread.sleep(2);
            }
            for (Thread thread : threads) thread.join(30_000);
            assertEquals(List.of(), failures);
            ref.drain().get(10, TimeUnit.SECONDS);
            assertEquals(senders * perSender, log.size(), "every message delivered exactly once");
            int last = 0;
            int[] lastBySender = new int[senders];
            java.util.Arrays.fill(lastBySender, -1);
            for (int[] entry : log) {
                assertTrue(entry[0] >= last, "generation " + entry[0] + " ran after generation " + last);
                last = entry[0];
                int sender = entry[1] / 100_000;
                int seq = entry[1] % 100_000;
                assertEquals(lastBySender[sender] + 1, seq, "per-sender order survives upgrades");
                lastBySender[sender] = seq;
            }
        }
    }
    /** A private actor's next generation obeys the same state confinement as its first. */
    private static ActorRuntime.Behavior<String> privateWorker(ActorRuntime.ActorContext<String> context) {
        return (message, turn) -> { };
    }

    private static final class LeakyBehavior implements ActorRuntime.Behavior<String> {
        final List<String> hostList = new ArrayList<>();

        @Override
        public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
            hostList.add(message);
        }
    }

    @Test
    void aPrivateActorCannotBeUpgradedIntoRetainedHostState() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(ActorHotUpgradeTest::privateWorker);
            ref.send("start");
            ActorUpgrade.Result leaky = ref.upgrade(ActorUpgrade.<String>sameSchema(
                    (previous, context) -> new LeakyBehavior())).get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.ROLLED_BACK_MIGRATION_FAILED, leaky.outcome(),
                    "a mutable JVM object cannot become private actor state through an upgrade");
            ActorUpgrade.Result clean = ref.upgrade(ActorUpgrade.<String>sameSchema(
                    (previous, context) -> privateWorker(context))).get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.COMMITTED, clean.outcome());
            assertTrue(ref.isAlive());
        }
    }
}
