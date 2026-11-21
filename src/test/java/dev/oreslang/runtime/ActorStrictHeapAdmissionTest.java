package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * All actor kinds must reject shared-JVM placement BEFORE actor construction
 * when the supervisor requires an independent physical GC heap.
 */
final class ActorStrictHeapAdmissionTest {
    @Test
    void strictModeRejectsAllActorKindsBeforeInvokingFactoryOrReservingSlot() {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                ActorRuntime.HeapIsolationRequirement.REQUIRE_INDEPENDENT_COLLECTOR)) {
            var factoryCalls = new java.util.concurrent.atomic.AtomicInteger();
            for (var kind : ActorRuntime.ActorKind.values()) {
                var factory = (ActorRuntime.BehaviorFactory<String>) context -> {
                    factoryCalls.incrementAndGet();
                    return (mail, ctx) -> {};
                };
                IllegalStateException error = assertThrows(IllegalStateException.class,
                        () -> runtime.spawn(kind, IsolatePolicy.developer(), factory), kind.name());
                assertTrue(error.getMessage().contains("independent")
                        && error.getMessage().contains(kind.name()));
                assertEquals(0, runtime.actorCount(),
                        "strict placement must reject before creating an actor cell");
                assertEquals(0, factoryCalls.get(),
                        "rejected host factories must not run");
            }
            assertEquals(ActorRuntime.HeapIsolationRequirement.REQUIRE_INDEPENDENT_COLLECTOR,
                    runtime.heapIsolationRequirement());
        }
    }

    @Test
    void commandLineStrictHeapRequirementIsExplicitAndFailClosed() {
        var mode = ActorRuntime.HeapIsolationRequirement.fromApplicationArguments(
                new String[]{"--ores-actor-heaps=independent"});
        assertEquals(ActorRuntime.HeapIsolationRequirement.REQUIRE_INDEPENDENT_COLLECTOR,
                mode);
        assertEquals(ActorRuntime.HeapIsolationRequirement.ALLOW_SHARED_JVM,
                ActorRuntime.HeapIsolationRequirement.fromApplicationArguments(new String[0]));
        assertThrows(IllegalArgumentException.class, () ->
                ActorRuntime.HeapIsolationRequirement.fromApplicationArguments(
                        new String[]{"--ores-actor-heaps=isolated-looking"}));
        assertThrows(IllegalArgumentException.class, () ->
                ActorRuntime.HeapIsolationRequirement.fromApplicationArguments(
                        new String[]{"--ores-actor-heaps=shared-jvm",
                                     "--ores-actor-heaps=independent"}));
    }

    @Test
    void normalActorRuntimeMustNeverClaimIndependentGarbageCollection() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertEquals(ActorRuntime.HeapIsolationRequirement.ALLOW_SHARED_JVM,
                    runtime.heapIsolationRequirement());
            var ref = runtime.<String>spawnPrivate(
                    (ActorRuntime.BehaviorFactory<String>) ctx -> (mail, actor) -> {});
            assertFalse(runtime.heapStatusFor(ref).independentlyCollectable());
            assertThrows(IllegalStateException.class,
                    () -> runtime.requireIndependentGcHeap(ref));
        }
    }
}
