package dev.oreslang.runtime.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mutable preflight builder; never an actor-visible capability. Build freezes
 * a versioned plan before a controller can spawn any provider.
 */
public final class PipelinePlanner {
    private final ArrayList<PipelinePlan.Stage> stages = new ArrayList<>();

    public PipelinePlanner add(String name, String providerId, String domain,
                               PipelinePlan.ActorProvider provider) {
        return insert(stages.size(), name, providerId, domain, provider);
    }

    public PipelinePlanner insert(int index, String name, String providerId,
                                  String domain, PipelinePlan.ActorProvider provider) {
        if (index < 0 || index > stages.size()) throw new IndexOutOfBoundsException(index);
        if (stages.size() == 16) {
            throw new IllegalArgumentException("dynamic ActorStream stage cap is 16");
        }
        PipelinePlan.Stage candidate = new PipelinePlan.Stage(name, providerId, domain, provider);
        for (PipelinePlan.Stage existing : stages) {
            if (existing.name().equals(name)) {
                throw new IllegalArgumentException("duplicate pipeline stage: " + name);
            }
        }
        if (!stages.isEmpty() && !stages.getFirst().nominalDomain().equals(domain)) {
            throw new IllegalArgumentException("pipeline nominal domain mismatch");
        }
        stages.add(index, candidate);
        return this;
    }

    public PipelinePlan build() {
        return new PipelinePlan(List.copyOf(stages), 1);
    }

    public int size() { return stages.size(); }
}
