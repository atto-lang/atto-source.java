package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Host-only lifecycle gates; NOT native actor-heap or Graal isolate certification. */
final class ActorOwnedGraphArenaLifecycleTest {
    private static final class Payload implements AutoCloseable {
        private final AtomicInteger drops;
        Payload(AtomicInteger drops) { this.drops = drops; }
        @Override public void close() { drops.incrementAndGet(); }
    }

    @Test
    void cyclicEdgesDoNotKeepPayloadsAliveAndStaleHandlesCannotRevive() {
        AtomicInteger drops = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();
        var graph = new ActorOwnedGraphArena<Payload>(2, 2, () -> {}, g -> callbacks.incrementAndGet());
        var a = graph.add(() -> new Payload(drops));
        var b = graph.add(() -> new Payload(drops));
        graph.link(a, b);
        graph.link(b, a);
        assertEquals(1, graph.links(a).size());
        graph.erase(a);
        assertEquals(1, drops.get());
        assertThrows(IllegalStateException.class, () -> graph.borrow(a));
        var reused = graph.add(() -> new Payload(drops));
        assertNotEquals(a, reused);
        graph.close();
        graph.close();
        assertTrue(graph.fullyClosed());
        assertEquals(3, drops.get());
        assertEquals(1, callbacks.get());
        assertThrows(IllegalStateException.class, () -> graph.borrow(reused));
    }

    @Test
    void leaseFencesRetirementUntilFinalReleaseWithoutResurrection() {
        AtomicInteger drops = new AtomicInteger();
        var graph = new ActorOwnedGraphArena<Payload>(1, 1, () -> {}, g -> {});
        var id = graph.add(() -> new Payload(drops));
        var lease = graph.borrow(id);
        graph.retireFromActor();
        assertTrue(graph.retired());
        assertFalse(graph.fullyClosed());
        assertEquals(0, drops.get());
        assertThrows(IllegalStateException.class, lease::get);
        lease.close();
        lease.close();
        assertEquals(1, drops.get());
        assertTrue(graph.fullyClosed());
    }

    @Test
    void failedRetirementCallbackCannotSkipDropsAndFailuresStayVisible() {
        AtomicInteger drops = new AtomicInteger();
        var graph = new ActorOwnedGraphArena<Payload>(2, 1, () -> {},
                g -> { throw new IllegalStateException("reservation callback failed"); });
        graph.add(() -> new Payload(drops));
        graph.add(() -> new Payload(drops));
        graph.close();
        assertTrue(graph.fullyClosed());
        assertEquals(2, drops.get());
        assertEquals("reservation callback failed",
                graph.cleanupFailure().orElseThrow().getMessage());
    }

    @Test
    void oneThrowingDestructorCannotSkipSiblingsAndVmFatalWins() {
        AtomicInteger drops = new AtomicInteger();
        var graph = new ActorOwnedGraphArena<AutoCloseable>(2, 1, () -> {}, g -> {});
        graph.add(() -> () -> { drops.incrementAndGet(); throw new IllegalStateException("drop failed"); });
        graph.add(() -> drops::incrementAndGet);
        graph.close();
        assertEquals(2, drops.get());
        assertEquals("drop failed", graph.cleanupFailure().orElseThrow().getMessage());

        AtomicInteger fatalDrops = new AtomicInteger();
        var fatal = new ActorOwnedGraphArena<AutoCloseable>(2, 1, () -> {},
                g -> { throw new IllegalStateException("ordinary callback failure"); });
        fatal.add(() -> fatalDrops::incrementAndGet);
        fatal.add(() -> () -> { throw new OutOfMemoryError("synthetic fatal"); });
        assertThrows(OutOfMemoryError.class, fatal::close);
        assertEquals(1, fatalDrops.get());
        Throwable observed = fatal.cleanupFailure().orElseThrow();
        assertInstanceOf(OutOfMemoryError.class, observed);
        assertEquals(1, observed.getSuppressed().length);
    }

    @Test
    void ownerGuardAndForeignHandlesCannotCrossArenaBoundary() {
        AtomicBoolean authorized = new AtomicBoolean(true);
        var a = new ActorOwnedGraphArena<Payload>(1, 1, () -> {
            if (!authorized.get()) throw new SecurityException("not owner");
        }, g -> {});
        var b = new ActorOwnedGraphArena<Payload>(1, 1, () -> {}, g -> {});
        var id = a.add(() -> new Payload(new AtomicInteger()));
        assertThrows(IllegalStateException.class, () -> b.borrow(id));
        authorized.set(false);
        assertThrows(SecurityException.class, () -> a.borrow(id));
        authorized.set(true);
        a.close();
        b.close();
    }

    @Test
    void rejectsOversizedGraphMetadataBeforeAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> ActorOwnedGraphArena.estimatedMetadataBytes(65_536, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorOwnedGraphArena<Payload>(0, 1, () -> {}, g -> {}));
        assertTrue(ActorOwnedGraphArena.estimatedMetadataBytes(2, 2) > 0);
    }
}
