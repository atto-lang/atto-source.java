package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Executable host-level group/actor collection tests; guest lowering is separate. */
@Timeout(20)
final class ActorGroupColumnarEpochTest {

    @Test
    void bulkRequestCollectsIndexedColumnarResultsAcrossIndependentActors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            var join = group.joinCapability();
            List<ActorRuntime.ActorRef<List<Long>>> actors = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                final int index = i;
                ActorRuntime.ActorRef<List<Long>> actor = runtime.spawnShared(() -> (tick, context) ->
                        group.publishDoubleEpoch(tick.get(0), index * tick.get(1), index));
                group.join(actor, join);
                actors.add(actor);
            }

            ActorGroupColumnarEpoch epoch = group.requestDoubleEpoch(
                    actors, id -> List.of(id, 3L), List.of("position", "velocity"));
            ActorGroupColumnarEpoch.Snapshot result = epoch.completion().get(10, TimeUnit.SECONDS);
            assertEquals(32, result.rowCount());
            assertEquals(2, result.columnCount());
            assertEquals(List.of("position", "velocity"), result.fields());
            assertEquals(epoch.epochId(), result.epochId());
            double[] positions = result.column("position");
            double[] velocities = result.column("velocity");
            for (int i = 0; i < result.rowCount(); i++) {
                assertEquals(velocities[i] * 3, positions[i]);
                assertTrue(result.members().contains(actors.get((int) velocities[i]).id()));
            }
            positions[0] = -100;
            assertNotEquals(-100, result.column("position")[0],
                    "no caller may mutate the completed columnar snapshot");

            ActorGroupColumnarEpoch next = group.requestDoubleEpoch(
                    actors, id -> List.of(id, 4L), List.of("position", "velocity"));
            assertTrue(next.epochId() > epoch.epochId());
            ActorGroupColumnarEpoch.Snapshot second = next.completion().get(10, TimeUnit.SECONDS);
            for (int i = 0; i < second.rowCount(); i++) {
                assertEquals(second.value(i, 1) * 4, second.value(i, 0));
            }
        }
    }

    @Test
    void privateActorCanPublishScalarCopyWithoutZeroCopySubscription() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<List<Long>> actor = runtime.spawnPrivateTrusted(
                    context -> (tick, turn) -> group.publishDoubleEpoch(tick.get(0), 7.5));
            group.join(actor, group.joinCapability());
            var epoch = group.requestDoubleEpoch(
                    List.of(actor), id -> List.of(id, 1L), List.of("load"));
            assertEquals(7.5, epoch.completion().get(5, TimeUnit.SECONDS).value(0, 0));
        }
    }

    @Test
    void leaveAbortsCollectionAndPreviouslyAdmittedActorCannotPublish() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<List<Long>> member = runtime.spawnShared(() -> (tick, context) -> group.leaveCurrent());
            group.join(member, group.joinCapability());

            var epoch = group.requestDoubleEpoch(
                    List.of(member), id -> List.of(id, 1L), List.of("value"));
            assertThrows(ExecutionException.class, () -> epoch.completion().get(5, TimeUnit.SECONDS));
            assertEquals(0, group.memberCount());
            assertThrows(SecurityException.class, () -> group.publishDoubleEpoch(epoch.epochId(), 1));
        }
    }

    @Test
    void closeAndActorTerminationAbortPendingCollection() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<List<Long>> actor = runtime.spawnShared(() -> (tick, context) -> { });
            group.join(actor, group.joinCapability());
            var epoch = group.beginDoubleEpoch(List.of("score"));
            assertFalse(epoch.completion().isDone());
            group.close();
            assertThrows(ExecutionException.class, () -> epoch.completion().get(5, TimeUnit.SECONDS));
            assertTrue(actor.isAlive(), "cooperation-only membership never grants stop rights");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<List<Long>> actor = runtime.spawnShared(() -> (tick, context) -> { });
            group.join(actor, group.joinCapability());
            var epoch = group.beginDoubleEpoch(List.of("score"));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> epoch.completion().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsUnknownStaleDuplicateAndInvalidEpochInputs() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            AtomicReference<Throwable> duplicate = new AtomicReference<>();
            CountDownLatch published = new CountDownLatch(1);
            ActorRuntime.ActorRef<List<Long>> actor = runtime.spawnShared(() -> (tick, context) -> {
                try {
                    group.publishDoubleEpoch(tick.get(0), 1);
                    try {
                        group.publishDoubleEpoch(tick.get(0), 2);
                    } catch (IllegalStateException expected) {
                        duplicate.set(expected);
                    }
                } finally {
                    published.countDown();
                }
            });
            group.join(actor, group.joinCapability());

            assertThrows(IllegalArgumentException.class,
                    () -> group.beginDoubleEpoch(List.of("a", "a")));
            assertThrows(IllegalArgumentException.class,
                    () -> group.beginDoubleEpoch(List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> group.requestDoubleEpoch(
                            List.of(actor, actor), id -> List.of(id, 1L), List.of("value")));
            var epoch = group.requestDoubleEpoch(
                    List.of(actor), id -> List.of(id, 1L), List.of("value"));
            assertEquals(1, epoch.completion().get(5, TimeUnit.SECONDS).value(0, 0));
            assertTrue(published.await(5, TimeUnit.SECONDS));
            assertNotNull(duplicate.get());
            assertThrows(SecurityException.class,
                    () -> group.publishDoubleEpoch(epoch.epochId(), 4));
            assertThrows(IllegalArgumentException.class,
                    () -> epoch.completion().join().column("absent"));
        }
    }

    @Test
    void hostCommandFactoryFailureFencesEpochAndDoesNotBlockNextRequest() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<List<Long>> actor = runtime.spawnShared(() -> (tick, context) -> { });
            group.join(actor, group.joinCapability());
            var failed = group.requestDoubleEpoch(
                    List.of(actor), id -> {
                        throw new IllegalArgumentException("failed to build request");
                    }, List.of("value"));
            assertTrue(failed.completion().isCompletedExceptionally());
            var next = group.beginDoubleEpoch(List.of("value"));
            assertFalse(next.completion().isDone());
            group.close();
            assertTrue(next.completion().isCompletedExceptionally());
        }
    }
    @Test
    void rejectsOversizeMembersBeforeTouchingCallerListOrAllocatingBuffers() {
        var oversized = new java.util.AbstractList<ActorRuntime.ActorId>() {
            @Override public int size() { return 262_145; }
            @Override public ActorRuntime.ActorId get(int index) {
                throw new AssertionError("invalid list must not be accessed");
            }
        };
        assertThrows(IllegalArgumentException.class, () ->
                new ActorGroupColumnarEpoch(1, oversized, List.of("score")));
    }

    @Test
    void rejectsMalformedUtf8AndOversizeColumnNamesBeforeAllocation() {
        var id = ActorRuntime.ActorId.create();
        assertThrows(IllegalArgumentException.class, () ->
                new ActorGroupColumnarEpoch(1, List.of(id), List.of("bad" + ((char) 0xD800))));
        assertThrows(IllegalArgumentException.class, () ->
                new ActorGroupColumnarEpoch(1, List.of(id), List.of("x".repeat(257))));
        assertThrows(IllegalArgumentException.class, () ->
                new ActorGroupColumnarEpoch(1, List.of(id), List.of("a" + ((char) 0) + "b")));
        assertThrows(IllegalArgumentException.class, () ->
                new ActorGroupColumnarEpoch(1, List.of(id), List.of("score", "score")));
        assertThrows(IllegalArgumentException.class, () ->
                new ActorGroupColumnarEpoch(1, List.of(id, id), List.of("score")));
    }

    @Test
    void publishedRowsAreIndependentOfSubsequentCallerArrayChanges() {
        var first = ActorRuntime.ActorId.create();
        var second = ActorRuntime.ActorId.create();
        var epoch = new ActorGroupColumnarEpoch(1, List.of(first, second),
                List.of("position", "velocity"));
        double[] input = {7.5, -0.0};
        assertNull(epoch.publish(first, input));
        input[0] = -999;
        input[1] = 22;
        var result = epoch.publish(second, new double[]{8.5, 9.5});
        assertNotNull(result);
        assertEquals(7.5, result.value(0, 0));
        assertEquals(Double.doubleToRawLongBits(-0.0d),
                Double.doubleToRawLongBits(result.value(0, 1)));
        assertEquals(8.5, result.value(1, 0));
        assertEquals(9.5, result.value(1, 1));
    }

}
