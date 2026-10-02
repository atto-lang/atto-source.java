package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {
    @Test
    void freezesMessagesBeforeDelivery() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<List<?>> observed = new AtomicReference<>();
            var ref = runtime.<List<Integer>>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
            ref.send(mutable);
            mutable.add(3);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), observed.get());
            assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) observed.get()).add(9));
        }
    }

    @Test
    void rejectsUnknownMutableHostObjects() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new StringBuilder("mutable")));
    }

    @Test
    void actorCarriesItsOwnStricterPolicy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<IsolatePolicy> observed = new AtomicReference<>();
            IsolatePolicy strict = IsolatePolicy.strictFaas();

            var ref = runtime.<String>spawnPrivate(strict, factoryContext -> (message, context) -> {
                observed.set(context.policy());
                received.countDown();
            });
            ref.send("ping");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(strict.capabilities(), observed.get().capabilities());
            assertEquals(strict.maxMailboxMessages(), observed.get().maxMailboxMessages());
        }
    }

    @Test
    void childActorCannotEscalatePastRuntimePolicyCeiling() {
        IsolatePolicy ceiling = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            IsolatePolicy escalated = ceiling.withCapabilities(IsolatePolicy.Capability.PROCESS_INFO);
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawn(escalated, () -> (message, context) -> { }));
        }
    }

    @Test
    void readonlySharingDeepFreezesContainers() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.shareReadonly(Map.of("items", List.of(1, 2, 3)));
            assertNotNull(shared.value());
        }
    }
    @Test
    void sharedActorProcessesOnlyOneMessageAtATime() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(2, 4, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            CountDownLatch received = new CountDownLatch(64);

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                int current = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(2);
                } finally {
                    inFlight.decrementAndGet();
                    received.countDown();
                }
            });

            for (int i = 0; i < 64; i++) ref.send(i);

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(1, maxInFlight.get(), "one actor mailbox must never run concurrently");
        }
    }

    @Test
    void privateAndSharedActorsUseDifferentDispatchers() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch received = new CountDownLatch(2);
            AtomicReference<String> privateThread = new AtomicReference<>();
            AtomicReference<String> sharedThread = new AtomicReference<>();

            var privateRef = runtime.<String>spawnPrivate(() -> (message, context) -> {
                privateThread.set(Thread.currentThread().getName());
                assertEquals(ActorRuntime.ActorKind.PRIVATE, context.kind());
                received.countDown();
            });
            var sharedRef = runtime.<String>spawnShared(() -> (message, context) -> {
                sharedThread.set(Thread.currentThread().getName());
                assertEquals(ActorRuntime.ActorKind.SHARED, context.kind());
                received.countDown();
            });

            privateRef.send("private");
            sharedRef.send("shared");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTrue(privateThread.get().startsWith("ores-private-actor-dispatcher-"));
            assertTrue(sharedThread.get().startsWith("ores-shared-actor-dispatcher-"));
        }
    }

    @Test
    void sharedActorsCanCoordinateThroughExplicitSyncCell() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 4, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(0);
            CountDownLatch received = new CountDownLatch(200);

            var a = runtime.<Integer>spawnShared(() -> (message, context) -> {
                cell.update(value -> value + 1);
                received.countDown();
            });
            var b = runtime.<Integer>spawnShared(() -> (message, context) -> {
                cell.update(value -> value + 1);
                received.countDown();
            });

            for (int i = 0; i < 100; i++) {
                a.send(i);
                b.send(i);
            }

            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(200, cell.snapshot());
        }
    }

    @Test
    void privateActorsRejectSharedMutableCells() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.SyncCell<Integer> cell = runtime.syncCell(0);
            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            assertThrows(IllegalArgumentException.class, () -> ref.send(cell));
        }
    }

    @Test
    void privateActorsIsolationCopyReadonlySharedValues() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> observed = new AtomicReference<>();
            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            var shared = runtime.shareReadonly(List.of("a", "b"));
            ref.send(shared);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("a", "b"), observed.get());
            assertFalse(observed.get() instanceof ActorRuntime.Shared<?>);
        }
    }

    @Test
    void privateActorGetsConfinedMemorySliceOwnedByItsActorId() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorId> owner = new AtomicReference<>();
            AtomicReference<Long> usedDuringTurn = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                var memory = context.privateMemory().orElseThrow();
                owner.set(memory.owner());
                usedDuringTurn.set(memory.usedBytes());
                received.countDown();
            });

            ref.send("private-payload");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(ref.id(), owner.get());
            assertTrue(usedDuringTurn.get() > 0L, "mailbox payload must be charged to the private slice during delivery");
        }
    }

    @Test
    void sharedActorHasNoPrivateMemorySlice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Boolean> hasPrivateMemory = new AtomicReference<>();

            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                hasPrivateMemory.set(context.privateMemory().isPresent());
                received.countDown();
            });

            ref.send("shared-payload");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertFalse(hasPrivateMemory.get());
        }
    }

    @Test
    void privateActorRejectsMessageThatExceedsItsMemorySlice() {
        IsolatePolicy ceiling = IsolatePolicy.developer();
        IsolatePolicy small = new IsolatePolicy(
                ceiling.capabilities(),
                16L * 1024 * 1024,
                ceiling.maxMailboxMessages(),
                ceiling.maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            var ref = runtime.<String>spawnPrivate(small, () -> (message, context) -> { });
            String tooLarge = "x".repeat(9 * 1024 * 1024);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ref.send(tooLarge));
            assertTrue(failure.getMessage().contains("private actor mailbox limit exceeded"));
            assertEquals(0L, runtime.privateMemoryBytes(), "failed admission must roll back aggregate accounting");
        }
    }

    @Test
    void persistentPrivateHeapReservationsShareTheSameSliceBudget() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch reserved = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<ActorRuntime.MemoryReservation> stateReservation = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                var memory = context.privateMemory().orElseThrow();
                stateReservation.set(memory.reserveHeap(1024));
                assertTrue(memory.usedBytes() >= 1024);
                reserved.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
                stateReservation.get().close();
            });

            ref.send("reserve");
            assertTrue(reserved.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.privateMemoryBytes() >= 1024);
            release.countDown();
        }
    }

    @Test
    void privateActorFactoryInitializesInsideOwnedMemorySlice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch constructed = new CountDownLatch(1);
            CountDownLatch handled = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorId> constructionOwner = new AtomicReference<>();
            AtomicReference<ActorRuntime.MemoryReservation> state = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(context -> {
                var memory = context.privateMemory().orElseThrow();
                constructionOwner.set(memory.owner());
                state.set(memory.reserveHeap(4096));
                constructed.countDown();

                return (message, turn) -> {
                    assertEquals(memory.owner(), turn.self().id());
                    assertTrue(memory.usedBytes() >= 4096);
                    state.get().close();
                    handled.countDown();
                };
            });

            ref.send("initialize");

            assertTrue(constructed.await(2, TimeUnit.SECONDS));
            assertEquals(ref.id(), constructionOwner.get());
            assertTrue(handled.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void leakedPrivateMemorySliceCannotBeReservedOutsideOwningActorTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch captured = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> leaked = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                leaked.set(context.privateMemory().orElseThrow());
                captured.countDown();
            });

            ref.send("capture");
            assertTrue(captured.await(2, TimeUnit.SECONDS));

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> leaked.get().reserveHeap(1));
            assertTrue(failure.getMessage().contains("owning actor"));
        }
    }

    @Test
    void privateActorsShareParentAggregateMemoryCeiling() throws Exception {
        IsolatePolicy parent = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024 * 1024,
                32,
                IsolatePolicy.developer().maxWallTime(),
                false);

        try (ActorRuntime runtime = new ActorRuntime(parent, new ActorRuntime.DispatcherConfig(2, 1, 8))) {
            CountDownLatch firstReserved = new CountDownLatch(1);
            CountDownLatch holdFirst = new CountDownLatch(1);

            var first = runtime.<String>spawnPrivate(parent, context -> {
                var reservation = context.privateMemory().orElseThrow().reserveHeap(10L * 1024 * 1024);
                firstReserved.countDown();
                return (message, turn) -> {
                    try {
                        assertTrue(holdFirst.await(2, TimeUnit.SECONDS));
                    } finally {
                        reservation.close();
                    }
                };
            });

            var second = runtime.<String>spawnPrivate(parent, () -> (message, context) -> { });

            first.send("start");
            assertTrue(firstReserved.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.privateMemoryBytes() >= 10L * 1024 * 1024);

            // ~8 MiB logical footprint: below the second actor's 16 MiB slice,
            // but above the remaining aggregate parent budget.
            String payload = "x".repeat(4 * 1024 * 1024);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> second.send(payload));
            assertTrue(failure.getMessage().contains("aggregate runtime limit exceeded"));

            holdFirst.countDown();
        }
    }

    @Test
    void readonlySharingRejectsNestedSyncCells() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(1);
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.shareReadonly(Map.of("cell", cell)));
        }
    }

    @Test
    void syncCellMutationRequiresSharedActorTurn() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(1);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> cell.update(value -> value + 1));
            assertTrue(failure.getMessage().contains("shared actor mailbox turn"));
        }
    }

    @Test
    void privateActorRejectsNestedSharedMutableCells() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var cell = runtime.syncCell(0);
            var ref = runtime.<Object>spawnPrivate(() -> (message, context) -> { });
            assertThrows(IllegalArgumentException.class,
                    () -> ref.send(Map.of("nested", List.of(cell))));
        }
    }

    @Test
    void capturedPrivateMemorySliceCannotBeUsedOutsideOwnerTurn() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorMemorySlice> captured = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                captured.set(context.privateMemory().orElseThrow());
                received.countDown();
            });

            ref.send("capture");
            assertTrue(received.await(2, TimeUnit.SECONDS));

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> captured.get().reserveHeap(64));
            assertTrue(failure.getMessage().contains("owning actor"));
        }
    }


    @Test
    void adversarialActorsRejectSupplierFactoriesThatCanCaptureHostState() {
        IsolatePolicy strict = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(strict)) {
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawnPrivate(strict, () -> (message, context) -> { }));

            assertDoesNotThrow(() ->
                    runtime.<String>spawnPrivate(strict, factoryContext -> (message, context) -> { }));
        }
    }

    @Test
    void actorFailureIsRetainedOnRefAndSubsequentSendReportsTermination() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                entered.countDown();
                throw new IllegalStateException("boom");
            });

            ref.send("fail");
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ref.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);

            assertFalse(ref.isAlive());
            assertTrue(ref.failure().isPresent());
            assertEquals("boom", ref.failure().orElseThrow().getMessage());

            ActorRuntime.ActorTerminatedException terminated = assertThrows(
                    ActorRuntime.ActorTerminatedException.class,
                    () -> ref.send("after-failure"));
            assertSame(ref.failure().orElseThrow(), terminated.getCause());
        }
    }

    @Test
    void explicitStopClosesPrivateSliceAndRejectsFurtherMessages() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch reserved = new CountDownLatch(1);
            AtomicReference<ActorRuntime.MemoryReservation> reservation = new AtomicReference<>();

            var ref = runtime.<String>spawnPrivate(factoryContext -> {
                reservation.set(factoryContext.privateMemory().orElseThrow().reserveHeap(4096));
                reserved.countDown();
                return (message, context) -> { };
            });

            ref.send("initialize");
            assertTrue(reserved.await(2, TimeUnit.SECONDS));
            assertTrue(runtime.privateMemoryBytes() >= 4096);

            ref.stop();

            assertFalse(ref.isAlive());
            assertEquals(0L, runtime.privateMemoryBytes());
            assertThrows(ActorRuntime.ActorTerminatedException.class, () -> ref.send("after-stop"));
            reservation.get().close(); // idempotent after slice teardown
            assertEquals(0L, runtime.privateMemoryBytes());
        }
    }


}
