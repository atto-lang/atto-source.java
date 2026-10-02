package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {

    @Test
    void actorsOwnBehaviorAndCommunicateThroughFrozenMessages() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> seen = new AtomicReference<>();

            ActorRuntime.ActorRef<Object> actor = runtime.spawn(() -> (message, context) -> {
                seen.set(message);
                received.countDown();
            });

            ArrayList<String> mutable = new ArrayList<>(List.of("hello"));
            runtime.send(actor, mutable);
            mutable.add("mutated-after-send");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("hello"), seen.get());
            assertNotSame(mutable, seen.get());

            assertTrue(runtime.stop(actor));
            assertTrue(runtime.join(actor, Duration.ofSeconds(2)));
        }
    }

    @Test
    void singletonIsOneNamedActorNotSharedMutableHeapState() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<Object> first = runtime.spawnSingleton("cache.primary", () -> (message, context) -> { });
            ActorRuntime.ActorRef<Object> second = runtime.spawnSingleton("cache.primary", () -> (message, context) -> {
                fail("the second factory must not replace a live singleton");
            });

            assertEquals(first.id(), second.id());
            assertTrue(runtime.lookupSingleton("cache.primary").isPresent());
        }
    }

    @Test
    void explicitReadonlySharingAvoidsMutableAliases() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<Object> seen = new AtomicReference<>();
            ActorRuntime.ActorRef<Object> actor = runtime.spawn(() -> (message, context) -> {
                seen.set(message);
                received.countDown();
            });

            ArrayList<String> source = new ArrayList<>(List.of("large", "payload"));
            ActorRuntime.Shared<List<String>> shared = runtime.shareReadonly(source);
            source.set(0, "changed");

            runtime.send(actor, shared);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertSame(shared.value(), seen.get());
            assertEquals(List.of("large", "payload"), seen.get());
            assertThrows(UnsupportedOperationException.class, () -> shared.value().add("nope"));
        }
    }

    @Test
    void cyclicMutableMessagesAreRejectedInsteadOfRecursingForever() {
        ArrayList<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ActorRuntime.freeze(cyclic));

        assertTrue(error.getMessage().contains("cyclic"));
    }

    @Test
    void oversizedMessagesAreRejectedBeforeMailboxAdmission() {
        IsolatePolicy policy = new IsolatePolicy(
                IsolatePolicy.developer().capabilities(),
                16L * 1024L * 1024L,
                32,
                Duration.ofSeconds(5));

        try (ActorRuntime runtime = new ActorRuntime(policy)) {
            ActorRuntime.ActorRef<Object> actor = runtime.spawn(() -> (message, context) -> { });
            String oversized = "x".repeat(5_000_000);

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.send(actor, oversized));

            assertTrue(error.getMessage().contains("too large"));
            assertEquals(0, runtime.snapshot(actor).mailboxMessages());
        }
    }

    @Test
    void unknownMutableHostObjectsCannotCrossActorBoundary() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new Object()));
    }
}
