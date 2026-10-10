package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorOwnedGraphArenaTest {
    private static final class Payload implements AutoCloseable {
        private final AtomicInteger drops;
        Payload(AtomicInteger drops) { this.drops = drops; }
        @Override public void close() { drops.incrementAndGet(); }
    }

    @Test
    void cyclicLinksAreNonOwningAndDropExactlyOnceOnActorTermination() throws Exception {
        AtomicInteger drops = new AtomicInteger();
        AtomicReference<ActorOwnedGraphArena<Payload>> graph = new AtomicReference<>();
        AtomicReference<ActorOwnedGraphArena.NodeId> stale = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    var memory = ctx.actorOwnedMemory().orElseThrow();
                    var arena = memory.<Payload>newGraphArena(3, 2);
                    graph.set(arena);
                    var a = arena.add(() -> new Payload(drops));
                    var b = arena.add(() -> new Payload(drops));
                    arena.link(a, a);
                    arena.link(a, b);
                    arena.link(b, a);
                    assertEquals(2, arena.links(a).size());
                    assertEquals(1, arena.links(b).size());
                    assertThrows(IllegalStateException.class, () ->
                            arena.add(() -> {
                                throw new IllegalStateException("failed factory");
                            }).toString().equals("never"));
                    assertEquals(2, arena.size(), "failed constructors may not publish a slot");
                    arena.erase(a);
                    assertEquals(1, drops.get());
                    assertTrue(arena.links(b).isEmpty(), "stale incoming link must be pruned");
                    var again = arena.add(() -> new Payload(drops));
                    assertEquals(a.slot(), again.slot());
                    assertNotEquals(a.generation(), again.generation());
                    assertThrows(IllegalStateException.class, () -> arena.borrow(a));
                    assertThrows(IllegalStateException.class, () -> arena.link(b, a));
                    stale.set(a);
                } catch (Throwable issue) {
                    failure.set(issue);
                } finally {
                    completed.countDown();
                }
            });
            actor.send("run");
            assertTrue(completed.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            assertNotNull(graph.get());
            assertNotNull(stale.get());
            assertEquals(1, drops.get());
        }
        assertTrue(graph.get().retired());
        assertTrue(graph.get().fullyClosed());
        assertEquals(3, drops.get(), "remaining payloads dropped on actor close");
    }

    @Test
    void finalHostLeaseDefersDestructionButDoesNotAllowActorResurrection() throws Exception {
        AtomicInteger drops = new AtomicInteger();
        AtomicReference<ActorOwnedGraphArena<Payload>> graph = new AtomicReference<>();
        AtomicReference<ActorOwnedGraphArena<Payload>.Lease> borrowed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var arena = ctx.actorOwnedMemory().orElseThrow()
                            .<Payload>newGraphArena(2, 2);
                    var id = arena.add(() -> new Payload(drops));
                    arena.link(id, id);
                    var lease = arena.borrow(id);
                    assertNotNull(lease.get());
                    assertThrows(IllegalStateException.class, () -> arena.erase(id));
                    graph.set(arena);
                    borrowed.set(lease);
                } catch (Throwable issue) {
                    failure.set(issue);
                } finally {
                    completed.countDown();
                }
            });
            actor.send("borrow");
            assertTrue(completed.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
        }
        assertTrue(graph.get().retired());
        assertFalse(graph.get().fullyClosed(), "live lease prevents destruction");
        assertEquals(0, drops.get());
        assertThrows(IllegalStateException.class, () -> borrowed.get().get());
        borrowed.get().close();
        borrowed.get().close(); // no double-drop
        assertEquals(1, drops.get());
        assertTrue(graph.get().fullyClosed());
    }

    @Test
    void sharedActorsCannotReadOtherActorGraphPayloads() throws Exception {
        AtomicReference<ActorOwnedGraphArena<Payload>> otherGraph = new AtomicReference<>();
        AtomicReference<ActorOwnedGraphArena.NodeId> otherId = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch examined = new CountDownLatch(1);
        AtomicInteger drops = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    var region = ctx.actorOwnedMemory().orElseThrow();
                    assertTrue(ctx.privateMemory().isEmpty());
                    long before = region.usedBytes();
                    assertThrows(IllegalArgumentException.class,
                            () -> region.newGraphArena(65536, 100));
                    assertEquals(before, region.usedBytes(),
                            "invalid arena must not charge owner quota");
                    var arena = region.<Payload>newGraphArena(1, 1);
                    assertTrue(region.usedBytes() > before,
                            "arena metadata competes for actor's bounded heap quota");
                    otherId.set(arena.add(() -> new Payload(drops)));
                    otherGraph.set(arena);
                    ready.countDown();
                } catch (Throwable issue) {
                    failure.set(issue);
                    ready.countDown();
                }
            });
            var foreign = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    assertNotNull(ctx.actorOwnedMemory().orElseThrow());
                    assertThrows(IllegalStateException.class,
                            () -> otherGraph.get().borrow(otherId.get()));
                    assertThrows(IllegalStateException.class,
                            () -> otherGraph.get().links(otherId.get()));
                } catch (Throwable issue) {
                    failure.set(issue);
                } finally {
                    examined.countDown();
                }
            });
            owner.send("allocate");
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            foreign.send("probe");
            assertTrue(examined.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
        }
        assertEquals(1, drops.get());
    }
}
