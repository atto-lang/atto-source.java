package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Host runtime/real ActorRef tests, not compiled Atto guest or distributed proof. */
@Timeout(30)
final class ActorRegistryTest {
    private static ActorRegistry<String, Integer> registry(ActorRuntime runtime, int capacity) {
        return new ActorRegistry<>(runtime, "vhost/main", "QueueActor", capacity,
                Function.identity());
    }

    private static ActorRuntime.ActorRef<Integer> worker(ActorRuntime runtime) {
        return runtime.<Integer>spawnShared(() -> (message, context) -> { });
    }

    @Test
    void thousandKeyedLookupsWith32WorkersReuseOneActorReference() throws Exception {
        try (var runtime = new ActorRuntime();
             var pool = Executors.newFixedThreadPool(32)) {
            var registry = registry(runtime, 4);
            var start = new CountDownLatch(1);
            var creations = new AtomicInteger();
            var futures = new ArrayList<java.util.concurrent.Future<ActorRuntime.ActorRef<Integer>>>();
            for (int i = 0; i < 1_000; i++) {
                futures.add(pool.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    return registry.get_or_create("orders", key -> {
                        creations.incrementAndGet();
                        return worker(runtime);
                    });
                }));
            }
            start.countDown();
            var refs = new HashSet<ActorRuntime.ActorRef<Integer>>();
            for (var future : futures) refs.add(future.get(10, TimeUnit.SECONDS));
            assertEquals(1, creations.get());
            assertEquals(1, refs.size());
            var only = refs.iterator().next();
            assertSame(only, registry.lookup("orders").orElseThrow());
            assertTrue(only.isAlive());
            assertEquals(1, registry.registered_count());
        }
    }

    @Test
    void differentKeysSendThroughDistinctPhysicalMailboxes() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var registry = registry(runtime, 4);
            var a = new AtomicInteger();
            var b = new AtomicInteger();
            var received = new CountDownLatch(2);
            var first = registry.get_or_create("orders", key -> runtime.<Integer>spawnShared(
                    () -> (message, context) -> { a.addAndGet(message); received.countDown(); }));
            var second = registry.get_or_create("payments", key -> runtime.<Integer>spawnShared(
                    () -> (message, context) -> { b.addAndGet(message); received.countDown(); }));
            assertNotSame(first, second);
            first.send(7);
            second.send(11);
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(7, a.get());
            assertEquals(11, b.get());
        }
    }

    @Test
    void cannotRemoveAStillLiveActor() {
        try (var runtime = new ActorRuntime()) {
            var registry = registry(runtime, 4);
            var first = registry.get_or_create("orders", key -> worker(runtime));
            assertThrows(IllegalStateException.class, () -> registry.unregister("orders", first));
            assertSame(first, registry.get_or_create("orders", key -> fail("duplicate")));
        }
    }

    @Test
    void retiredActorIsReplacedAndStaleUnregisterDoesNotEraseReplacement() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var registry = registry(runtime, 4);
            var first = registry.get_or_create("orders", key -> worker(runtime));
            first.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(first.isAlive());
            var second = registry.get_or_create("orders", key -> worker(runtime));
            assertNotSame(first, second);
            assertNotEquals(first.address(), second.address());
            assertFalse(registry.unregister("orders", first));
            assertSame(second, registry.lookup("orders").orElseThrow());
        }
    }

    @Test
    void prematureRestartRejectedUntilOldTurnPhysicallyCompletes() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var registry = registry(runtime, 4);
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var first = registry.get_or_create("orders", key -> runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        started.countDown();
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("release timeout");
                    }));
            first.send(1);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            try {
                first.stop();
                assertFalse(first.done().isDone(), "stop flag alone is not physical retirement");
                assertThrows(IllegalStateException.class,
                        () -> registry.get_or_create("orders", key -> worker(runtime)));
            } finally {
                release.countDown();
            }
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertNotSame(first, registry.get_or_create("orders", key -> worker(runtime)));
        }
    }

    @Test
    void slowFactoryDoesNotBlockUnrelatedKeysOrPermitPrematureClose() throws Exception {
        try (var runtime = new ActorRuntime();
             var pool = Executors.newFixedThreadPool(2)) {
            var registry = registry(runtime, 4);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var slow = pool.submit(() -> registry.get_or_create("slow", key -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("factory release timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return worker(runtime);
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                assertThrows(IllegalStateException.class, registry::close);
                var fast = pool.submit(() -> registry.get_or_create(
                        "fast", key -> worker(runtime)));
                assertTrue(fast.get(2, TimeUnit.SECONDS).isAlive(),
                        "unrelated activation must not wait for slow factory");
            } finally {
                release.countDown();
            }
            assertTrue(slow.get(5, TimeUnit.SECONDS).isAlive());
            assertEquals(2, registry.registered_count());
        }
    }

    @Test
    void failedFactoryWakesWaitersAndAllowsRetry() throws Exception {
        try (var runtime = new ActorRuntime();
             var pool = Executors.newFixedThreadPool(2)) {
            var registry = registry(runtime, 4);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var first = pool.submit(() -> assertThrows(IllegalArgumentException.class,
                    () -> registry.get_or_create("orders", key -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("release timeout");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        throw new IllegalArgumentException("factory failed");
                    })));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = pool.submit(() -> registry.get_or_create("orders", key -> worker(runtime)));
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertSame(second.get(5, TimeUnit.SECONDS), registry.lookup("orders").orElseThrow());
        }
    }

    @Test
    void recursiveCreationFailsClosedWithoutDeadlock() {
        try (var runtime = new ActorRuntime()) {
            var registry = registry(runtime, 4);
            var actor = registry.get_or_create("orders", key -> {
                assertThrows(IllegalStateException.class,
                        () -> registry.get_or_create("orders", k -> worker(runtime)));
                return worker(runtime);
            });
            assertSame(actor, registry.lookup("orders").orElseThrow());
        }
    }

    @Test
    void refusesForeignStoppedAndAliasedActorReferences() {
        try (var runtime = new ActorRuntime();
             var foreign = new ActorRuntime()) {
            var registry = registry(runtime, 4);
            assertThrows(IllegalArgumentException.class,
                    () -> registry.get_or_create("foreign", key -> worker(foreign)));
            assertTrue(registry.lookup("foreign").isEmpty());
            var actor = registry.get_or_create("orders", key -> worker(runtime));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.get_or_create("payments", key -> actor));
            assertEquals(1, registry.registered_count());
        }
    }

    @Test
    void enforcesCapacityCanonicalKeysAndNonActivatingLogicalRefs() {
        try (var runtime = new ActorRuntime()) {
            var registry = registry(runtime, 1);
            var logical = registry.ref_for("orders");
            assertEquals("orders", logical.identity().key());
            assertTrue(logical.resolve_existing().isEmpty());
            assertEquals(0, registry.registered_count());
            registry.get_or_create("orders", key -> worker(runtime));
            assertThrows(IllegalStateException.class,
                    () -> registry.get_or_create("payments", key -> worker(runtime)));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.ref_for("e\u0301"));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.lookup("x".repeat(500)));
        }
    }

    @Test
    void supervisorCompletionCleansRegistrationWithoutGrantingGuestAuthority()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var registry = registry(runtime, 4);
            var blocked = new CountDownLatch(1);
            var ref = registry.get_or_create("orders", key -> runtime.<Integer>spawnShared(
                    () -> (message, context) -> {
                        try {
                            registry.lookup("orders");
                            throw new AssertionError("host lookup authorized inside actor turn");
                        } catch (SecurityException expected) {
                            blocked.countDown();
                            context.self().stop();
                        }
                    }));
            ref.send(1);
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(registry.lookup("orders").isEmpty());
        }
    }

    @Test
    void closeRefusesToAbandonAnActiveActor() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var registry = registry(runtime, 4);
            var actor = registry.get_or_create("orders", key -> worker(runtime));
            assertThrows(IllegalStateException.class, registry::close);
            assertTrue(actor.isAlive());
            assertSame(actor, registry.lookup("orders").orElseThrow());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            registry.close();
            assertThrows(IllegalStateException.class, () -> registry.lookup("orders"));
        }
    }
}
