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
    void physicalCloseRetriesAfterCleanupCallbackThrows() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        var problem = new AtomicReference<Throwable>();
        var completed = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivate(() -> (message, ctx) -> {
                try {
                    var memory = ctx.actorOwnedMemory().orElseThrow();
                    long before = memory.usedBytes();
                    // This reservation uses the actor's normal logical budget
                    // while probing an injected onRetired callback exception.
                    // FFM itself is REAL; the native Arena must close once.
                    var reservation = memory.reserveHeap(8L);
                    var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
                    var region = new ExplicitNativeI64Region(1, reservation,
                            memory::requireOwnerActive, () -> {
                                if (failOnce.getAndSet(false)) {
                                    throw new IllegalStateException("injected unregister fault");
                                }
                            });
                    var slot = region.allocate(42);
                    assertEquals(42, region.read(slot));
                    assertThrows(IllegalStateException.class, region::retireFromActor);
                    assertTrue(region.closed());
                    assertFalse(region.physicallyClosed(),
                            "callback failure must not report complete cleanup");
                    assertEquals(before, memory.usedBytes(),
                            "reservation is already released before unregister retry");
                    region.retireFromActor();
                    region.retireFromActor();
                    assertTrue(region.physicallyClosed());
                    assertEquals(before, memory.usedBytes());
                    assertThrows(IllegalStateException.class, () -> region.read(slot));
                } catch (Throwable err) {
                    problem.compareAndSet(null, err);
                } finally {
                    completed.countDown();
                }
            });
            actor.send("test");
            assertTrue(completed.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
        }
    }

    @Test
    void lastLoanCanRetryFailedNativeRetirementCallback() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "JDK25 FFM required");
        var problem = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    var memory = ctx.actorOwnedMemory().orElseThrow();
                    long before = memory.usedBytes();
                    var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
                    var region = new ExplicitNativeI64Region(1, memory.reserveHeap(8L),
                            memory::requireOwnerActive, () -> {
                                if (failOnce.getAndSet(false)) {
                                    throw new IllegalStateException("injected completion cleanup fault");
                                }
                            });
                    var handle = region.allocate(42);
                    var loan = region.borrow(handle);
                    region.retireFromActor();
                    assertFalse(region.physicallyClosed(), "borrow must pin arena");
                    assertThrows(IllegalStateException.class, loan::close);
                    assertFalse(region.physicallyClosed(), "cleanup fault must stay visible");
                    assertEquals(before, memory.usedBytes(), "quota was released once");
                    // A second close on the already-released Loan retries the
                    // failed post-close callback without a second decrement.
                    assertDoesNotThrow(loan::close);
                    assertDoesNotThrow(loan::close);
                    assertTrue(region.physicallyClosed());
                    assertEquals(before, memory.usedBytes());
                } catch (Throwable err) {
                    problem.compareAndSet(null, err);
                } finally {
                    done.countDown();
                }
            });
            actor.send("test");
            assertTrue(done.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
        }
    }

    @Test
    void rejectsUnsupportedPhysicalArenaInsteadOfManagedFallback() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {});
            assertThrows(IllegalStateException.class,
                    () -> runtime.requireActorHeapBacking(ref,
                            ActorRuntime.ActorHeapBacking.DETERMINISTIC_NATIVE_ARENA),
                    "FFM data allocation alone does not prove full compiler-native actor placement");
        }
    }
}
