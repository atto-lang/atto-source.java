package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * JVM host actor-runtime integration; does not certify physical native isolation
 * or Oreslang source-object allocator lowering.
 */
final class ActorRuntimeGraphQuotaTest {
    private static final class Payload implements AutoCloseable {
        private final AtomicInteger drops;
        Payload(AtomicInteger drops) { this.drops = drops; }
        @Override public void close() { drops.incrementAndGet(); }
    }

    @Test
    void privateActorGraphChargesQuotaAndRetiresAfterLastLease() throws Exception {
        AtomicInteger drops = new AtomicInteger();
        AtomicReference<ActorOwnedGraphArena<Payload>> graphRef = new AtomicReference<>();
        AtomicReference<ActorOwnedGraphArena<Payload>.Lease> leaseRef = new AtomicReference<>();
        AtomicReference<Throwable> errors = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivate(() -> (message, ctx) -> {
                try {
                    var slice = ctx.privateMemory().orElseThrow();
                    long before = slice.usedBytes();
                    assertThrows(IllegalArgumentException.class,
                            () -> slice.newGraphArena(65_536, 100));
                    assertEquals(before, slice.usedBytes(), "invalid bounds charge nothing");
                    var graph = slice.<Payload>newGraphArena(2, 2);
                    assertTrue(slice.usedBytes() - before
                            >= ActorOwnedGraphArena.estimatedMetadataBytes(2, 2));
                    var node = graph.add(() -> new Payload(drops));
                    graph.link(node, node);
                    var lease = graph.borrow(node);
                    assertThrows(IllegalStateException.class, () -> graph.erase(node));
                    graphRef.set(graph);
                    leaseRef.set(lease);
                    ctx.self().stop();
                } catch (Throwable error) {
                    errors.set(error);
                } finally {
                    ran.countDown();
                }
            });
            actor.send("start");
            assertTrue(ran.await(15, TimeUnit.SECONDS));
            if (errors.get() != null) fail(errors.get());
            assertTrue(actor.awaitTermination(15, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());
        }
        assertTrue(graphRef.get().retired(), "slice must retire graph during actor finalization");
        assertFalse(graphRef.get().fullyClosed(), "lease delays deterministic payload drop");
        assertThrows(IllegalStateException.class, () -> leaseRef.get().get());
        leaseRef.get().close();
        leaseRef.get().close();
        assertTrue(graphRef.get().fullyClosed());
        assertEquals(1, drops.get(), "exactly one payload destructor");
    }

    @Test
    void differentPrivateActorCannotBorrowOwnersGraph() throws Exception {
        AtomicReference<ActorOwnedGraphArena<Payload>> graph = new AtomicReference<>();
        AtomicReference<ActorOwnedGraphArena.NodeId> id = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch checked = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.<String>spawnPrivate(() -> (message, ctx) -> {
                try {
                    var region = ctx.privateMemory().orElseThrow();
                    var arena = region.<Payload>newGraphArena(1, 1);
                    id.set(arena.add(() -> new Payload(new AtomicInteger())));
                    graph.set(arena);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    created.countDown();
                }
            });
            var foreign = runtime.<String>spawnPrivate(() -> (message, ctx) -> {
                try {
                    assertThrows(IllegalStateException.class,
                            () -> graph.get().borrow(id.get()));
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    checked.countDown();
                }
            });
            owner.send("create");
            assertTrue(created.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            foreign.send("borrow");
            assertTrue(checked.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
        }
    }
}
