package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorOwnedI64RegionTest {
    @Test
    void sharedActorAllocatesStackCompatibleI64AndRejectsStaleAndForeignHandles() throws Exception {
        AtomicReference<ActorOwnedI64Region> region = new AtomicReference<>();
        AtomicReference<ActorOwnedI64Region.Slot> stale = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    var heap = ctx.actorOwnedMemory().orElseThrow();
                    long before = heap.usedBytes();
                    var slots = heap.newI64Region(2);
                    assertEquals(before + 16, heap.usedBytes());
                    var first = slots.allocate(6);
                    var second = slots.allocate(slots.read(first) * 7);
                    assertEquals(42, slots.read(second));
                    assertThrows(IllegalStateException.class, () -> slots.allocate(1));
                    slots.drop(first);
                    assertThrows(IllegalStateException.class, () -> slots.read(first));
                    var reused = slots.allocate(-7);
                    assertEquals(first.index(), reused.index());
                    assertNotEquals(first.generation(), reused.generation());
                    assertThrows(IllegalStateException.class, () -> slots.write(first, 19));
                    var other = heap.newI64Region(1);
                    var foreign = other.allocate(11);
                    assertThrows(IllegalStateException.class, () -> slots.read(foreign));
                    other.close();
                    assertThrows(IllegalStateException.class, () -> other.read(foreign));
                    slots.write(second, 43);
                    assertEquals(43, slots.read(second));
                    region.set(slots);
                    stale.set(first);
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    finished.countDown();
                }
            });
            actor.send("test");
            assertTrue(finished.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            assertNotNull(region.get());
        }
        assertTrue(region.get().closed());
        assertThrows(IllegalStateException.class, () -> region.get().read(stale.get()));
    }

    @Test
    void privateActorCannotShareRawScalarStorageWithAnotherActor() throws Exception {
        AtomicReference<ActorOwnedI64Region> first = new AtomicReference<>();
        AtomicReference<ActorOwnedI64Region.Slot> token = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch tested = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var a = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var storage = ctx.privateMemory().orElseThrow().newI64Region(1);
                    token.set(storage.allocate(12));
                    first.set(storage);
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                } finally { ready.countDown(); }
            });
            var b = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    assertThrows(IllegalStateException.class,
                            () -> first.get().read(token.get()));
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                } finally { tested.countDown(); }
            });
            a.send("allocate");
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            b.send("attempt");
            assertTrue(tested.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
        }
        assertTrue(first.get().closed());
    }

    @Test
    void untrustedActorAllocatesWithoutCapturingHostState() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnUntrusted(context -> (mail, ctx) -> {
                var region = ctx.actorOwnedMemory().orElseThrow().newI64Region(1);
                var slot = region.allocate(41);
                region.write(slot, region.read(slot) + 1);
                assertEquals(42, region.read(slot));
                region.close();
                assertTrue(region.closed());
                ctx.self().stop();
            });
            actor.send("test");
            assertTrue(actor.awaitTermination(15, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () ->
                    "untrusted actor failed: " + actor.failure());
        }
    }
}
