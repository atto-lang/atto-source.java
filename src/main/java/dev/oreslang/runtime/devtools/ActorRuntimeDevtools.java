package dev.oreslang.runtime.devtools;

import dev.oreslang.runtime.ActorRuntime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted host adapter over an actual ActorRuntime instance.
 * Deliberately does not enumerate, reflect, or borrow actor state.
 */
public final class ActorRuntimeDevtools {
    private ActorRuntimeDevtools() { }

    public static LiveMcpServer start(ActorRuntime runtime, String hostToken, int port)
            throws IOException {
        Objects.requireNonNull(runtime, "runtime");
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException("guest actor turns cannot enable live devtools");
        }
        return LiveMcpServer.start(() -> {
            if (ActorRuntime.inActorExecution()) {
                throw new SecurityException("live inspection requires host supervisor context");
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("backend", "java-graalvm");
            summary.put("actor_count", runtime.actorCount());
            summary.put("max_actors", runtime.maxActors());
            summary.put("carrier_backend", runtime.carrierBackend().name());
            summary.put("actor_state_snapshots", "NOT_IMPLEMENTED");
            summary.put("hot_upgrade_transactions", "NOT_IMPLEMENTED");
            summary.put("full_actor_registry", "NOT_IMPLEMENTED");
            return Map.copyOf(summary);
        }, hostToken, port);
    }
}
