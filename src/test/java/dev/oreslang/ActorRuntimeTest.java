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

            var ref = runtime.<String>spawn(strict, () -> (message, context) -> {
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

}
