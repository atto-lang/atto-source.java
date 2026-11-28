package dev.oreslang.types;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static dev.oreslang.types.EffectContracts.*;

final class RuntimeParkingEffectTest {
    @Test void propagatesRuntimeParkingWithoutAnnotationThroughRecursion() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("write", Set.of(Effect.IO, Effect.RUNTIME_PARK), List.of()));
        graph.add(Callable.inferred("a", Set.of(), List.of(Call.direct("b"))));
        graph.add(Callable.trapping("b", Set.of(Effect.THROW), List.of(Call.direct("a"), Call.direct("write"))));
        graph.add(Callable.inferred("pure", Set.of(), List.of()));
        var rows = graph.infer();
        for (String name : List.of("write", "a", "b")) {
            var policy = parkingPolicy(rows.get(name));
            assertTrue(policy.runtimePark());
            assertTrue(policy.requiresResumableFrame());
            assertFalse(policy.requiresSuspendAnnotation());
            assertFalse(policy.safeWithExclusiveLoan());
        }
        assertTrue(parkingPolicy(rows.get("pure")).safeWithExclusiveLoan());
        assertFalse(rows.get("a").contains(Effect.THROW));
    }

    @Test void explicitAwaitAndUnknownTargetsRemainSeparate() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("await", Set.of(Effect.SUSPEND), List.of()));
        graph.add(Callable.inferred("mixed", Set.of(Effect.RUNTIME_PARK), List.of(Call.direct("await"))));
        graph.add(Callable.inferred("unresolved", Set.of(), List.of(Call.direct("missing"))));
        var rows = graph.infer();
        assertTrue(parkingPolicy(rows.get("mixed")).requiresSuspendAnnotation());
        assertTrue(parkingPolicy(rows.get("mixed")).runtimePark());
        assertTrue(parkingPolicy(rows.get("unresolved")).unresolved());
        assertFalse(parkingPolicy(rows.get("unresolved")).safeWithExclusiveLoan());
        assertFalse(parkingPolicy(Set.of(Effect.BLOCK)).safeWithExclusiveLoan());
    }

    @Test void callbackSpecializationAndPurityCannotLaunderParking() {
        Graph graph = new Graph();
        graph.add(Callable.inferred("write", Set.of(Effect.RUNTIME_PARK, Effect.IO), List.of()));
        graph.add(Callable.inferred("apply", Set.of(), List.of(Call.callback("body"))));
        assertTrue(parkingPolicy(graph.infer().get("apply")).unresolved());
        assertTrue(parkingPolicy(graph.inferInvocation("apply", Map.of("body", "write"))).runtimePark());
        graph.add(Callable.pure("claimedPure", Set.of(), List.of(Call.direct("write"))));
        assertThrows(IllegalArgumentException.class, graph::validate);
    }
}
