package dev.oreslang.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable CONTROL-owned snapshot of child supervisor incarnation tokens.
 * IDs are stable for a tree, but only (child ID, child epoch) identifies the
 * incarnation whose retirement may satisfy a parent's shutdown barrier.
 *
 * The epoch here is NOT an ActorRef or foreign-process generation number;
 * hot-reload code must provide its authenticated child-incarnation mapping
 * before this may guard restarted or remote child supervisors.
 */
public record SupervisorChildGenerationFence(Map<Long, Long> expectedEpochs) {
    public SupervisorChildGenerationFence {
        expectedEpochs = Map.copyOf(Objects.requireNonNull(expectedEpochs, "expectedEpochs"));
        for (var entry : expectedEpochs.entrySet()) {
            if (entry.getKey() < 1 || entry.getValue() < 1) {
                throw new IllegalArgumentException("invalid child identity or epoch");
            }
        }
    }

    public void requireReceipt(long childId, long expectedEpoch, long observedEpoch) {
        Long pinned = expectedEpochs.get(childId);
        if (pinned == null || pinned.longValue() != expectedEpoch
                || expectedEpoch != observedEpoch) {
            throw new IllegalStateException(
                    "stale, foreign or changed child incarnation receipt: " + childId);
        }
    }
}
