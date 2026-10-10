package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor owned arenas allow cyclic non-owning graph links, with deterministic
 * handle invalidation and no cycle tracing on actor shutdown.
 *
 * This does NOT prove independent Java GC or native allocator deallocation.
 */
final class ActorGraphArenaContractTest {
    @Test
    void cyclicGraphSupportsGenerationalReuseAndActorExitRevocation() throws Exception {
        var finished = new CountDownLatch(1);
        var errors = new AtomicReference<Throwable>();
        var arenaRef = new AtomicReference<ActorGraphArena>();
        var staleRef = new AtomicReference<ActorGraphArena.NodeId>();
        var liveRef = new AtomicReference<ActorGraphArena.NodeId>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivate(() -> (message, ctx) -> {
                try {
                    var arena = ctx.actorGraphArena().orElseThrow();
                    arenaRef.set(arena);
                    var a = arena.allocate(new byte[] {1, 2, 3});
                    var b = arena.allocate(new byte[] {4, 5, 6});
                    assertTrue(arena.link(a, b));
                    assertTrue(arena.link(b, a));
                    assertTrue(arena.link(a, a));
                    assertFalse(arena.link(a, a));
                    assertEquals(3, arena.liveLinks());
                    assertEquals(2, arena.liveNodes());
                    assertEquals(List.of(b, a).size(), arena.edges(a).size());
                    assertArrayEquals(new byte[] {4, 5, 6}, arena.read(b));
                    arena.write(a, new byte[] {8, 9, 10});
                    assertArrayEquals(new byte[] {8, 9, 10}, arena.read(a));

                    arena.remove(a);
                    staleRef.set(a);
                    assertThrows(IllegalStateException.class, () -> arena.read(a));
                    var reused = arena.allocate(new byte[] {11, 12, 13});
                    liveRef.set(reused);
                    assertEquals(a.slot(), reused.slot());
                    assertTrue(reused.generation() > a.generation());
                    assertThrows(IllegalStateException.class, () -> arena.read(a),
                            "slot reuse cannot make an ABA-stale handle valid");
                    assertTrue(arena.link(b, reused));
                } catch (Throwable error) {
                    errors.set(error);
                } finally {
                    finished.countDown();
                }
            });

            assertEquals(ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                    runtime.heapIdentityFor(actor).placement());
            assertEquals(actor.id(), runtime.heapIdentityFor(actor).actor());
            actor.send("run");
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            if (errors.get() != null) fail(errors.get());
            assertEquals(arenaRef.get().arenaId(),
                    runtime.heapIdentityFor(actor).graphArenaId());
            assertEquals(0, runtime.sharedMemoryBytes(),
                    "private actor graph edges do not allocate Shared/SyncCell memory");
            runtime.stop(actor);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.privateMemoryBytes(),
                    "actor exit must release all actor-owned memory reservations");
            assertThrows(IllegalStateException.class,
                    () -> arenaRef.get().read(liveRef.get()));
        }
        assertNotNull(staleRef.get());
    }

    @Test
    void sharedActorsHavePrivateGraphArenasAndCannotFollowForeignHandles() throws Exception {
        var aGraph = new AtomicReference<ActorGraphArena>();
        var aNode = new AtomicReference<ActorGraphArena.NodeId>();
        var failures = new AtomicReference<Throwable>();
        var created = new CountDownLatch(1);
        var crossChecked = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var alice = runtime.<String>spawnShared(() -> (message, ctx) -> {
                try {
                    var arena = ctx.actorGraphArena().orElseThrow();
                    aGraph.set(arena);
                    aNode.set(arena.allocate(new byte[] {42}));
                } catch (Throwable error) { failures.compareAndSet(null, error); }
                finally { created.countDown(); }
            });
            var bob = runtime.<String>spawnShared(() -> (message, ctx) -> {
                try {
                    var own = ctx.actorGraphArena().orElseThrow();
                    assertNotEquals(own.arenaId(), aGraph.get().arenaId());
                    assertThrows(IllegalStateException.class,
                            () -> aGraph.get().read(aNode.get()),
                            "another shared actor cannot read private heap of first actor");
                    assertThrows(SecurityException.class,
                            () -> own.read(aNode.get()),
                            "foreign arena handles are never resolved in another arena");
                } catch (Throwable error) { failures.compareAndSet(null, error); }
                finally { crossChecked.countDown(); }
            });
            alice.send("create");
            assertTrue(created.await(10, TimeUnit.SECONDS));
            bob.send("check");
            assertTrue(crossChecked.await(10, TimeUnit.SECONDS));
            if (failures.get() != null) fail(failures.get());
            runtime.stop(alice);
            runtime.stop(bob);
            assertTrue(alice.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(bob.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.privateMemoryBytes());
        }
    }

    @Test
    void actorGraphHandlesCannotEscapeIntoSharedCellsOrActorMailboxes() {
        var untrustedHandle = new ActorGraphArena.NodeId(
                java.util.UUID.randomUUID(), 0, 1);
        assertThrows(SecurityException.class,
                () -> ActorRuntime.freeze(untrustedHandle));
        assertThrows(SecurityException.class,
                () -> ActorRuntime.freeze(List.of("ordinary", untrustedHandle)));

        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(SecurityException.class,
                    () -> runtime.shareReadonly(untrustedHandle),
                    "a handle into an actor-local heap is not immutable shared data");
            var receiver = runtime.<Object>spawnShared(
                    () -> (message, actor) -> {});
            assertThrows(SecurityException.class,
                    () -> receiver.send(untrustedHandle));
            assertThrows(SecurityException.class,
                    () -> receiver.send(List.of("nested", untrustedHandle)));
        }
    }

    @Test
    void placementIsExplicitAndCannotSilentlyFallbackToHostHeap() {
        assertEquals(ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                ActorRuntime.defaultHeapPlacement(ActorRuntime.ActorKind.PRIVATE));
        assertEquals(ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                ActorRuntime.defaultHeapPlacement(ActorRuntime.ActorKind.SHARED));
        assertEquals(ActorRuntime.ActorHeapPlacement.VM_ISOLATE,
                ActorRuntime.defaultHeapPlacement(ActorRuntime.ActorKind.UNTRUSTED));

        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(IllegalStateException.class, () ->
                    runtime.<String>spawnWithHeapPlacement(
                            ActorRuntime.ActorKind.UNTRUSTED,
                            IsolatePolicy.untrustedActor(),
                            ActorRuntime.ActorHeapPlacement.VM_ISOLATE,
                            ctx -> (mail, actor) -> {}));
            assertThrows(IllegalStateException.class, () ->
                    runtime.<String>spawnWithHeapPlacement(
                            ActorRuntime.ActorKind.PRIVATE,
                            IsolatePolicy.developer(),
                            ActorRuntime.ActorHeapPlacement.PROCESS_ISOLATE,
                            ctx -> (mail, actor) -> {}));
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawnWithHeapPlacement(
                            ActorRuntime.ActorKind.UNTRUSTED,
                            IsolatePolicy.untrustedActor(),
                            ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                            ctx -> (mail, actor) -> {}));
            var trusted = runtime.<String>spawnWithHeapPlacement(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                    ctx -> (mail, actor) -> {});
            assertEquals(ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                    runtime.heapIdentityFor(trusted).placement());
        }
    }
}
