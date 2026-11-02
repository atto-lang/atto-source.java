package dev.oreslang.runtime.devtools;

import dev.oreslang.runtime.ActorRuntime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted host adapter over an actual ActorRuntime. Actor descriptors expose
 * only runtime-owned metadata, never sealed actor state or mutable guest values.
 */
public final class ActorRuntimeDevtools {
    private ActorRuntimeDevtools() { }

    public static LiveMcpServer start(ActorRuntime runtime, String hostToken, int port)
            throws IOException {
        Objects.requireNonNull(runtime, "runtime");
        if (ActorRuntime.inActorExecution()
                || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new SecurityException("actor and control turns cannot enable live devtools");
        }
        return LiveMcpServer.start(new LiveMcpServer.RuntimeView() {
            @Override public Map<String, Object> inspect() {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("backend", "java-graalvm");
                summary.put("actor_count", runtime.actorCount());
                summary.put("max_actors", runtime.maxActors());
                summary.put("carrier_backend", runtime.carrierBackend().name());
                summary.put("actor_state_snapshots", "NOT_IMPLEMENTED");
                summary.put("hot_upgrade_transactions", "NOT_IMPLEMENTED");
                summary.put("tasks_discovery", "NOT_IMPLEMENTED");
                return Map.copyOf(summary);
            }

            @Override public boolean hasActorDiscovery() { return true; }

            @Override public Map<String, Object> listActors(int limit) {
                List<Map<String, Object>> actors = runtime.devtoolsListActors(limit).stream()
                        .map(ActorRuntimeDevtools::toWire).toList();
                return Map.of(
                        "actors", actors,
                        "truncated", runtime.actorCount() > actors.size(),
                        "consistency", "WEAKLY_CONSISTENT",
                        "actor_count_approx", runtime.actorCount());
            }

            @Override public Map<String, Object> inspectActor(String id) {
                return runtime.devtoolsInspectActor(id)
                        .<Map<String, Object>>map(info -> Map.of(
                                "found", true, "actor", toWire(info),
                                "consistency", "WEAKLY_CONSISTENT"))
                        .orElseGet(() -> Map.of("found", false));
            }
        }, hostToken, port);
    }

    private static Map<String, Object> toWire(ActorRuntime.DevtoolsActorInfo info) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("actor_id", info.actorId());
        // Empty means root; never derive parent from physical thread identity.
        fields.put("parent_actor_id", info.parentActorId());
        fields.put("kind", info.kind());
        fields.put("generation", info.generation());
        fields.put("lifecycle", info.lifecycle());
        fields.put("mailbox_pending", info.mailboxPending());
        return Map.copyOf(fields);
    }
}
