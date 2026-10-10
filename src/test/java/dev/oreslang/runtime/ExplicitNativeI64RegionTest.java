package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Java25 native FFM backing: deterministic native release, JVM still managed. */
final class ExplicitNativeI64RegionTest {
    @Test
    void actorOwnsNativeStorageAndRefundsItsQuotaOnEarlyClose() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        AtomicReference<Throwable> problem = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    var heap = context.actorOwnedMemory().orElseThrow();
                    long before = heap.usedBytes();
                    var region = heap.newExplicitNativeI64Region(2);
                    assertEquals(before + 16, heap.usedBytes());
                    var a = region.allocate(6);
                    var b = region.allocate(region.read(a) * 7);
                    assertEquals(42, region.read(b));
                    assertThrows(IllegalStateException.class, () -> region.allocate(3));
                    region.drop(a);
                    assertThrows(IllegalStateException.class, () -> region.read(a));
                    var next = region.allocate(-1);
                    assertEquals(a.index(), next.index());
                    assertNotEquals(a.generation(), next.generation());
                    assertThrows(IllegalStateException.class, () -> region.write(a, 4));
                    region.close();
                    assertTrue(region.closed());
                    assertEquals(before, heap.usedBytes(), "closed FFM backing refunds quota");
                    assertThrows(IllegalStateException.class, () -> region.read(next));
                } catch (Throwable ex) { problem.set(ex); }
                finally { done.countDown(); }
            });
            ref.send("test");
            assertTrue(done.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
        }
    }

    @Test
    void foreignActorCannotReadNativeRegionAndRetirementRevokesIt() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        AtomicReference<ExplicitNativeI64Region> owner = new AtomicReference<>();
        AtomicReference<ExplicitNativeI64Region.Slot> value = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1), checked = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var first = runtime.<String>spawnPrivate(() -> (message, ctx) -> {
                try {
                    var region = ctx.actorOwnedMemory().orElseThrow().newExplicitNativeI64Region(1);
                    owner.set(region);
                    value.set(region.allocate(84));
                } catch (Throwable ex) { problem.compareAndSet(null, ex); }
                finally { ready.countDown(); }
            });
            var second = runtime.<String>spawnShared(() -> (message, ctx) -> {
                try {
                    assertThrows(IllegalStateException.class,
                            () -> owner.get().read(value.get()));
                } catch (Throwable ex) { problem.compareAndSet(null, ex); }
                finally { checked.countDown(); }
            });
            first.send("allocate");
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
            second.send("probe");
            assertTrue(checked.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
        }
        assertTrue(owner.get().closed(), "actor finalization closes FFM scope");
        assertThrows(IllegalStateException.class, () -> owner.get().read(value.get()));
    }

    @Test
    void untrustedActorMaintainsStatelessFactoryAndIndependentOwnership() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnUntrusted(ctx -> (message, actor) -> {
                var arena = actor.actorOwnedMemory().orElseThrow()
                        .newExplicitNativeI64Region(1);
                var id = arena.allocate(41);
                arena.write(id, arena.read(id) + 1);
                assertEquals(42, arena.read(id));
                arena.close();
                actor.self().stop();
            });
            ref.send("test");
            assertTrue(ref.awaitTermination(20, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> "actor failed: " + ref.failure());
        }
    }

    @Test
    void rejectsUnsupportedPhysicalArenaInsteadOfManagedFallback() {
        assertThrows(IllegalArgumentException.class, () -> {
            try (ActorRuntime runtime = new ActorRuntime()) {
                runtime.requireActorHeapBacking(null,
                        ActorRuntime.ActorHeapBacking.DETERMINISTIC_NATIVE_ARENA);
            }
        }); // null actor ref cannot masquerade as a verified native placement.
    }
}
