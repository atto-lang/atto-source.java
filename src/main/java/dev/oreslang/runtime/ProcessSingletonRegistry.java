package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One immutable module snapshot per canonical module key in this host process.
 *
 * <p>Not an isolate-local substitute for a trusted cross-Graal-isolate coordinator:
 * an actual spawned Graal isolate must use an explicitly installed bridge or
 * fail closed. The language/runtime must not return this handle to guest code.
 * Unlike a mutable process-global service, reads need no state lock/mailbox.</p>
 */
public final class ProcessSingletonRegistry {
    private static final int MAX_SINGLETONS = 4_096;
    private static final int MAX_KEY_CHARS = 2_048;
    private static final Object REGISTRY_LOCK = new Object();
    private static final Map<String, Cell> CELLS = new ConcurrentHashMap<>();

    private ProcessSingletonRegistry() { }

    /** Opaque, permanent, deeply frozen snapshot. Does not expose the factory input. */
    public record Snapshot(String schema, Map<String, Object> fields) {
        public Snapshot {
            schema = Objects.requireNonNull(schema, "schema");
            if (schema.isBlank()) throw new IllegalArgumentException("singleton schema cannot be blank");
            Objects.requireNonNull(fields, "fields");
            // The same bounded graph validator and ownership rules as actor data
            // freeze apply to singletons: no actor refs, host objects, or mutable handles.
            @SuppressWarnings("unchecked")
            Map<String, Object> frozen = (Map<String, Object>)
                    ActorRuntime.freeze(new LinkedHashMap<>(fields));
            fields = frozen;
        }
    }

    private record Cell(UUID instanceId, Snapshot snapshot) { }

    public static Handle getOrCreate(
            String key,
            String schema,
            IsolatePolicy policy,
            Supplier<? extends Map<String, Object>> initializer) {
        requireAuthority(policy, "singleton lookup/create");
        String normalized = normalizeKey(key);
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(initializer, "initializer");

        // Deliberately serialize FIRST initialization across all keys:
        // no caller sees a partially initialized object and cardinality bounds
        // cannot be bypassed by concurrent insertions of distinct keys.
        synchronized (REGISTRY_LOCK) {
            Cell existing = CELLS.get(normalized);
            if (existing != null) {
                requireSchema(normalized, schema, existing.snapshot.schema());
                return new Handle(existing, policy);
            }
            if (CELLS.size() >= MAX_SINGLETONS) {
                throw new IllegalStateException("process singleton capacity exceeded");
            }
            Snapshot snapshot = new Snapshot(schema,
                    Objects.requireNonNull(initializer.get(), "singleton initializer returned null"));
            Cell created = new Cell(UUID.randomUUID(), snapshot);
            CELLS.put(normalized, created); // atomic publish after deep freeze
            return new Handle(created, policy);
        }
    }

    public static int count(IsolatePolicy policy) {
        requireAuthority(policy, "singleton count");
        return CELLS.size();
    }

    /**
     * A process-local static registry cannot implement a process singleton
     * across separately spawned Graal native isolates. Explicitly reject that
     * backend until the host supervisor installs a coordinator bridge.
     */
    public static void requireBackendFor(boolean spawnedGraalIsolate) {
        if (spawnedGraalIsolate) {
            throw new IllegalStateException(
                    "process singleton requires trusted host coordinator for spawned Graal isolates");
        }
    }

    private static void requireSchema(String key, String expected, String actual) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "process singleton schema changed for " + Integer.toHexString(key.hashCode())
                            + "; explicit versioned migration required");
        }
    }

    private static String normalizeKey(String key) {
        String normalized = Objects.requireNonNull(key, "key").trim();
        if (normalized.isEmpty() || normalized.length() > MAX_KEY_CHARS) {
            throw new IllegalArgumentException("invalid process singleton key");
        }
        return normalized;
    }

    private static void requireAuthority(IsolatePolicy policy, String operation) {
        Objects.requireNonNull(policy, "policy").require(
                IsolatePolicy.Capability.SINGLETON_STATE, operation);
        IsolatePolicy active = ActorRuntime.currentActorPolicy();
        if (active != null) active.require(IsolatePolicy.Capability.SINGLETON_STATE, operation);
    }

    /**
     * Non-Sendable and sandbox-forbidden; not a source-language data value.
     * A trusted actor may re-acquire by canonical key, never mail this handle.
     */
    public static final class Handle implements SandboxForbiddenCapability {
        private final Cell cell;
        private final IsolatePolicy admissionPolicy;

        private Handle(Cell cell, IsolatePolicy policy) {
            this.cell = cell;
            this.admissionPolicy = policy;
        }

        public UUID instanceId() {
            requireAuthority(admissionPolicy, "singleton identity");
            return cell.instanceId;
        }

        public Snapshot snapshot() {
            requireAuthority(admissionPolicy, "singleton read");
            return cell.snapshot;
        }
    }
}
