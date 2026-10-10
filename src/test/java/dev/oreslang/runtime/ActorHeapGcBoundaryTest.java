package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JVM actor memory accounting is not a per-actor physical GC heap.
 * Verify the contract cannot be silently upgraded to an isolation claim,
 * and prove actor.gc() / actor-originated process.gc() never requests JVM GC.
 */
final class ActorHeapGcBoundaryTest {
    @Test
    void allActorKindsReportTruthfulSharedPhysicalGcHeap() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var isolated = runtime.<String>spawnPrivate(
                    () -> (mail, context) -> {});
            var shared = runtime.<String>spawnShared(
                    () -> (mail, context) -> {});
            var untrusted = runtime.<String>spawnUntrusted(
                    context -> (mail, actor) -> {});

            var statuses = Map.of(
                    ActorRuntime.ActorKind.PRIVATE, runtime.heapStatusFor(isolated),
                    ActorRuntime.ActorKind.SHARED, runtime.heapStatusFor(shared),
                    ActorRuntime.ActorKind.UNTRUSTED, runtime.heapStatusFor(untrusted));
            for (var kind : ActorRuntime.ActorKind.values()) {
                var status = statuses.get(kind);
                assertEquals(kind, status.kind());
                assertFalse(status.independentOfParentContextHeap(), kind.name());
                assertFalse(status.independentlyCollectable(), kind.name());
                assertThrows(IllegalStateException.class,
                        () -> runtime.requireIndependentGcHeap(
                                kind == ActorRuntime.ActorKind.PRIVATE ? isolated
                                        : kind == ActorRuntime.ActorKind.SHARED ? shared : untrusted));
            }
            assertTrue(runtime.heapStatusFor(isolated).actorOwnedMemorySlice());
            assertTrue(runtime.heapStatusFor(untrusted).actorOwnedMemorySlice());
            assertFalse(runtime.heapStatusFor(shared).actorOwnedMemorySlice());
            assertFalse(runtime.heapStatusFor(shared).canAllocateOwnerCheckedDirectMemory());
        }
    }

    @Test
    void everyGcRequestFromAnActorIsLimitedToItsLocalCleanupRegistry() throws Exception {
        AtomicInteger jvmCollections = new AtomicInteger();
        AtomicInteger actorCalls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(2);

        try (RuntimeGarbageCollector gc = new RuntimeGarbageCollector(
                    jvmCollections::incrementAndGet, Duration.ofHours(1));
             ActorRuntime runtime = new ActorRuntime()) {
            runtime.setActorExitHook(gc::retireActorDomain);

            // These are explicitly trusted test-only Supplier factories. They
            // capture the host collector; untrusted guest factories cannot.
            ActorRuntime.Behavior<String> check = (mail, ctx) -> {
                try {
                    assertThrows(SecurityException.class, gc::collectProcess,
                            "even GC_CONTROL may not request JVM-wide GC inside an actor");
                    Object live = new Object();
                    var cleanup = gc.track(live, () -> {});
                    var report = gc.collectCurrentActor();
                    assertEquals("actor", report.scope());
                    assertFalse(report.jvmGcRequested());
                    assertEquals("host_cleanup", report.asMap().get("collection_kind"));
                    assertEquals(false, report.asMap().get("actor_heap_independently_collected"));
                    assertEquals(0, report.cleaned(),
                            "actor.gc must not finalize still-reachable objects");
                    java.lang.ref.Reference.reachabilityFence(live);
                    cleanup.close();
                    actorCalls.incrementAndGet();
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    completed.countDown();
                }
            };
            var isolated = runtime.<String>spawnPrivate(() -> check);
            var shared = runtime.<String>spawnShared(() -> check);
            isolated.send("collect");
            shared.send("collect");
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            assertEquals(2, actorCalls.get());
            assertEquals(0, jvmCollections.get(),
                    "actor-local GC must never request a parent JVM collection");

            // Supervisor-level process GC is still an explicit, separate API.
            var report = gc.collectProcess();
            assertTrue(report.jvmGcRequested());
            assertEquals(1, jvmCollections.get());
        }
    }

    @Test
    void heapIntrospectionRejectsActorCallsAndForeignRefs() throws Exception {
        try (ActorRuntime first = new ActorRuntime();
             ActorRuntime second = new ActorRuntime()) {
            var other = second.<String>spawnPrivate(
                    () -> (mail, ctx) -> {});
            assertThrows(IllegalArgumentException.class, () -> first.heapStatusFor(other));

            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> result = new AtomicReference<>();
            var self = first.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    assertThrows(SecurityException.class,
                            () -> first.heapStatusFor(ctx.self()));
                } catch (Throwable error) {
                    result.set(error);
                } finally {
                    completed.countDown();
                }
            });
            self.send("check");
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            if (result.get() != null) fail(result.get());
        }
    }
}
