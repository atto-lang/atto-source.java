package dev.oreslang.runtime.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, trusted-control-plane description of one bounded linear
 * actor pipeline. Provider closures remain host capabilities: they must not
 * be serialized into ActorMail or invoked on an untrusted actor carrier.
 *
 * This is an experimental Java reference contract, not a source-level
 * Oreslang built-in and not an active ActorRuntime route.
 */
public final class PipelinePlan {
    @FunctionalInterface
    public interface ActorProvider {
        /** Called by a trusted engine, producing a fresh actor per generation. */
        Object spawn();
    }

    public record Stage(String name, String providerId, String nominalDomain,
                        ActorProvider provider) {
        public Stage {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("invalid pipeline stage name");
            }
            if (providerId == null || !providerId.matches("[A-Za-z_][A-Za-z0-9_.:-]{0,127}")) {
                throw new IllegalArgumentException("invalid registered actor provider identity");
            }
            if (nominalDomain == null || nominalDomain.isBlank()
                    || nominalDomain.length() > 256) {
                throw new IllegalArgumentException("invalid closed nominal actor domain");
            }
            Objects.requireNonNull(provider, "actor provider");
        }
    }

    private final List<Stage> stages;
    private final long revision;

    PipelinePlan(List<Stage> stages, long revision) {
        this.stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        if (revision < 1) throw new IllegalArgumentException("invalid plan revision");
        if (this.stages.size() < 2 || this.stages.size() > 16) {
            throw new IllegalArgumentException("dynamic ActorStream requires 2..16 stages");
        }
        Set<String> names = new HashSet<>();
        String domain = this.stages.getFirst().nominalDomain();
        for (Stage stage : this.stages) {
            if (!names.add(stage.name())) {
                throw new IllegalArgumentException("duplicate pipeline stage: " + stage.name());
            }
            if (!domain.equals(stage.nominalDomain())) {
                throw new IllegalArgumentException("pipeline stages require one exact closed nominal domain");
            }
        }
        this.revision = revision;
    }

    public List<Stage> stages() { return stages; }
    public long revision() { return revision; }
    public int size() { return stages.size(); }
    public String nominalDomain() { return stages.getFirst().nominalDomain(); }

    private long nextRevision() {
        return Math.addExact(revision, 1);
    }

    public PipelinePlan replace(String name, Stage replacement) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(replacement);
        if (!name.equals(replacement.name())) {
            throw new IllegalArgumentException("replacement must preserve the logical stage name");
        }
        ArrayList<Stage> next = new ArrayList<>(stages);
        int index = indexOf(name);
        next.set(index, replacement);
        return new PipelinePlan(next, nextRevision());
    }

    public PipelinePlan insert(int index, Stage stage) {
        Objects.requireNonNull(stage);
        if (index < 0 || index > stages.size()) throw new IndexOutOfBoundsException(index);
        ArrayList<Stage> next = new ArrayList<>(stages);
        next.add(index, stage);
        return new PipelinePlan(next, nextRevision());
    }

    public PipelinePlan remove(String name) {
        ArrayList<Stage> next = new ArrayList<>(stages);
        next.remove(indexOf(name));
        return new PipelinePlan(next, nextRevision());
    }

    public int indexOf(String name) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).name().equals(name)) return i;
        }
        throw new IllegalArgumentException("unknown pipeline stage: " + name);
    }
}
