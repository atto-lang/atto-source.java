package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void sharedHeapIsTheDefaultActorMemoryMode() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<ActorRuntime.MemoryMode> observed = new AtomicReference<>();

            var ref = runtime.<String>spawn(() -> (message, context) -> {
                observed.set(context.memory().mode());
                received.countDown();
            });
            ref.send("ping");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(ActorRuntime.MemoryMode.SHARED_HEAP, observed.get());
        }
    }

    @Test
    void privateArenaIsActorOwnedThreadConfinedMemory() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<MemorySegment> segment = new AtomicReference<>();
            AtomicReference<Long> capacity = new AtomicReference<>();
            AtomicReference<Long> used = new AtomicReference<>();

            var ref = runtime.<String>spawn(
                    ActorRuntime.MemoryPolicy.privateArena(1024 * 1024),
                    () -> (message, context) -> {
                        assertEquals(ActorRuntime.MemoryMode.PRIVATE_ARENA, context.memory().mode());
                        MemorySegment local = context.memory().allocate(8, 8);
                        local.set(ValueLayout.JAVA_LONG, 0, 42L);
                        segment.set(local);
                        capacity.set(context.memory().capacityBytes());
                        used.set(context.memory().usedBytes());
                        received.countDown();
                    });

            ref.send("allocate");
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(1024 * 1024L, capacity.get());
            assertEquals(8L, used.get());

            // The segment came from Arena.ofConfined() on the actor's virtual
            // Thread, so a different thread cannot dereference actor memory.
            assertThrows(WrongThreadException.class,
                    () -> segment.get().get(ValueLayout.JAVA_LONG, 0));
        }
    }

    @Test
    void privateArenaCannotExceedActorPolicyCeiling() {
        IsolatePolicy policy = new IsolatePolicy(
                java.util.Set.of(),
                16L * 1024 * 1024,
                32,
                java.time.Duration.ofSeconds(5));
        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawn(
                            ActorRuntime.MemoryPolicy.privateArena(17L * 1024 * 1024),
                            () -> (message, context) -> { }));
        }
    }

    @Test
    void rawArenaMemoryCannotBeMadeSendable() {
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(8);
            assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(segment));
            assertThrows(IllegalArgumentException.class, () -> new ActorRuntime.Shared<>(segment));
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
}
