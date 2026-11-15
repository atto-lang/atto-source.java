package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorHeapPlacementGuardTest {
    @Test
    void defaultPlacementDoesNotConfuseTrustedAndUntrustedActors() {
        assertEquals(ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                ActorRuntime.defaultHeapPlacement(ActorRuntime.ActorKind.PRIVATE));
        assertEquals(ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                ActorRuntime.defaultHeapPlacement(ActorRuntime.ActorKind.SHARED));
        assertEquals(ActorRuntime.ActorHeapPlacement.VM_ISOLATE,
                ActorRuntime.defaultHeapPlacement(ActorRuntime.ActorKind.UNTRUSTED));
        assertThrows(NullPointerException.class,
                () -> ActorRuntime.defaultHeapPlacement(null));
    }

    @Test
    void bootstrapRuntimeFailsClosedOnUnverifiedPhysicalPlacement() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            for (var kind : ActorRuntime.ActorKind.values()) {
                assertThrows(IllegalStateException.class,
                        () -> runtime.<String>spawnWithHeapPlacement(
                                kind, runtime.policyCeiling(),
                                ActorRuntime.ActorHeapPlacement.VM_ISOLATE,
                                ctx -> (mail, actor) -> {}));
                assertThrows(IllegalStateException.class,
                        () -> runtime.<String>spawnWithHeapPlacement(
                                kind, runtime.policyCeiling(),
                                ActorRuntime.ActorHeapPlacement.PROCESS_ISOLATE,
                                ctx -> (mail, actor) -> {}));
            }
            assertThrows(SecurityException.class,
                    () -> runtime.<String>spawnWithHeapPlacement(
                            ActorRuntime.ActorKind.UNTRUSTED,
                            IsolatePolicy.untrustedActor(),
                            ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                            ctx -> (mail, actor) -> {}));
            var trusted = runtime.<String>spawnWithHeapPlacement(
                    ActorRuntime.ActorKind.PRIVATE, runtime.policyCeiling(),
                    ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                    ctx -> (mail, actor) -> {});
            assertEquals(ActorRuntime.ActorKind.PRIVATE,
                    runtime.heapStatusFor(trusted).kind());
        }
    }

    @Test
    void hostHeapInspectionDoesNotClaimAnIndependentGcHeap() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var privateActor = runtime.<String>spawnPrivate(ctx -> (mail, actor) -> {});
            var sharedActor = runtime.<String>spawnShared(ctx -> (mail, actor) -> {});
            var untrustedActor = runtime.<String>spawnUntrusted(ctx -> (mail, actor) -> {});

            for (var ref : java.util.List.of(privateActor, sharedActor, untrustedActor)) {
                var status = runtime.heapStatusFor(ref);
                assertFalse(status.independentOfParentContextHeap());
                assertFalse(status.independentlyCollectable());
                assertThrows(IllegalStateException.class,
                        () -> runtime.requireIndependentGcHeap(ref));
                for (var backing : ActorRuntime.ActorHeapBacking.values()) {
                    if (backing == ActorRuntime.ActorHeapBacking.OWNER_CHECKED_REGION) {
                        continue;
                    }
                    assertThrows(IllegalStateException.class,
                            () -> runtime.requireActorHeapBacking(ref, backing));
                }
            }

            assertTrue(runtime.heapStatusFor(privateActor).actorOwnedMemorySlice());
            assertTrue(runtime.heapStatusFor(untrustedActor).actorOwnedMemorySlice());
            assertFalse(runtime.heapStatusFor(sharedActor).actorOwnedMemorySlice());
            runtime.requireActorHeapBacking(
                    privateActor, ActorRuntime.ActorHeapBacking.OWNER_CHECKED_REGION);
            assertThrows(IllegalStateException.class,
                    () -> runtime.requireActorHeapBacking(
                            sharedActor, ActorRuntime.ActorHeapBacking.OWNER_CHECKED_REGION));
        }
    }

    @Test
    void actorTurnCannotSelfCertifyPhysicalHeapOrInspectSupervisorBacking() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var completed = new java.util.concurrent.CountDownLatch(1);
            var actor = runtime.<String>spawnPrivate(() -> (mail, context) -> {
                try {
                    assertThrows(SecurityException.class,
                            () -> context.runtime().heapStatusFor(context.self()));
                    assertThrows(SecurityException.class,
                            () -> context.runtime().requireIndependentGcHeap(context.self()));
                    assertThrows(SecurityException.class,
                            () -> context.runtime().requireActorHeapBacking(
                                    context.self(),
                                    ActorRuntime.ActorHeapBacking.OWNER_CHECKED_REGION));
                    assertThrows(SecurityException.class,
                            () -> context.runtime().<String>spawnWithHeapPlacement(
                                    ActorRuntime.ActorKind.PRIVATE,
                                    context.runtime().policyCeiling(),
                                    ActorRuntime.ActorHeapPlacement.ACTOR_ARENA,
                                    child -> (input, next) -> {}));
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    completed.countDown();
                }
            });
            actor.send("verify");
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "actor should execute the supervisor-boundary test");
            assertNull(observed.get(), "actor must not obtain supervisor heap authority");
        }
    }

    @Test
    void inspectionRejectsForeignRuntimeAndImpossiblePhysicalGcClaim() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorRuntime.ActorHeapStatus(
                        ActorRuntime.ActorKind.PRIVATE, true, true, false, true));
        try (ActorRuntime first = new ActorRuntime();
             ActorRuntime second = new ActorRuntime()) {
            var ref = first.<String>spawnPrivate(ctx -> (mail, actor) -> {});
            assertThrows(IllegalArgumentException.class, () -> second.heapStatusFor(ref));
        }
    }
}
