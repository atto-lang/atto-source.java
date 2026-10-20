package dev.oreslang.compiler;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Host/compiler reference for model run/version fencing.
 * This is not yet wired to guest model execution or mutation publication.
 */
public final class ModelRunFencing {
    private ModelRunFencing() { }

    public record Owner(
            String modelTypeId,
            String graphDigest,
            long graphGeneration,
            String instanceId,
            long parameterVersion,
            String executionProfileDigest) {
        public Owner {
            requireId(modelTypeId, "MODEL_INVALID_TYPE_ID");
            requireDigest(graphDigest, "MODEL_INVALID_GRAPH_DIGEST");
            if (graphGeneration < 0) throw new IllegalArgumentException("MODEL_INVALID_GRAPH_GENERATION");
            requireId(instanceId, "MODEL_INVALID_INSTANCE_ID");
            if (parameterVersion < 0) throw new IllegalArgumentException("MODEL_INVALID_PARAMETER_VERSION");
            requireDigest(executionProfileDigest, "MODEL_INVALID_PROFILE_DIGEST");
        }
    }

    public record RunStamp(
            String runId,
            String modelTypeId,
            String graphDigest,
            long graphGeneration,
            String instanceId,
            long parameterVersion,
            String executionProfileDigest) {
        public RunStamp {
            requireId(runId, "MODEL_INVALID_RUN_ID");
            requireId(modelTypeId, "MODEL_INVALID_TYPE_ID");
            requireDigest(graphDigest, "MODEL_INVALID_GRAPH_DIGEST");
            if (graphGeneration < 0) throw new IllegalArgumentException("MODEL_INVALID_GRAPH_GENERATION");
            requireId(instanceId, "MODEL_INVALID_INSTANCE_ID");
            if (parameterVersion < 0) throw new IllegalArgumentException("MODEL_INVALID_PARAMETER_VERSION");
            requireDigest(executionProfileDigest, "MODEL_INVALID_PROFILE_DIGEST");
        }
    }

    public record Ledger(Set<String> committedRunIds) {
        public Ledger {
            committedRunIds = Set.copyOf(committedRunIds);
        }
        public static Ledger empty() { return new Ledger(Set.of()); }
    }

    private static void requireId(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 256)
            throw new IllegalArgumentException(code);
    }

    private static void requireDigest(String value, String code) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException(code);
    }

    public static long nextGraphGeneration(long current) {
        if (current < 0) throw new IllegalArgumentException("MODEL_INVALID_GRAPH_GENERATION");
        if (current == Long.MAX_VALUE) throw new IllegalStateException("MODEL_GRAPH_GENERATION_OVERFLOW");
        return current + 1;
    }

    public static long nextParameterVersion(long current) {
        if (current < 0) throw new IllegalArgumentException("MODEL_INVALID_PARAMETER_VERSION");
        if (current == Long.MAX_VALUE) throw new IllegalStateException("MODEL_PARAMETER_VERSION_OVERFLOW");
        return current + 1;
    }

    /**
     * Validate every semantic ownership/version component before publication.
     * The returned immutable ledger represents a successful exactly-once commit.
     */
    public static Ledger validateAndCommit(Owner owner, RunStamp stamp, Ledger ledger) {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(stamp);
        Objects.requireNonNull(ledger);
        if (ledger.committedRunIds().contains(stamp.runId()))
            throw new IllegalStateException("MODEL_RUN_REPLAY");
        if (!owner.modelTypeId().equals(stamp.modelTypeId()))
            throw new IllegalStateException("MODEL_WRONG_TYPE");
        if (!owner.graphDigest().equals(stamp.graphDigest()))
            throw new IllegalStateException("MODEL_STALE_GRAPH");
        if (owner.graphGeneration() != stamp.graphGeneration())
            throw new IllegalStateException("MODEL_STALE_GRAPH_GENERATION");
        if (!owner.instanceId().equals(stamp.instanceId()))
            throw new IllegalStateException("MODEL_WRONG_INSTANCE");
        if (owner.parameterVersion() != stamp.parameterVersion())
            throw new IllegalStateException("MODEL_STALE_PARAMETER_VERSION");
        if (!owner.executionProfileDigest().equals(stamp.executionProfileDigest()))
            throw new IllegalStateException("MODEL_WRONG_EXECUTION_PROFILE");
        Set<String> next = new HashSet<>(ledger.committedRunIds());
        next.add(stamp.runId());
        return new Ledger(next);
    }
}
