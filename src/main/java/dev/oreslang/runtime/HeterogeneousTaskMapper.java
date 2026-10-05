package dev.oreslang.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure planning contract for Regent/Legion-inspired task placement.
 *
 * <p>This class does not execute guest code and does not expose a GPU API to
 * Oreslang. It models the information the compiler/runtime must prove before a
 * future heterogeneous backend may launch work: logical region effects,
 * certified-disjoint partitions, processor variants, and data residency.</p>
 *
 * <p>Correctness and placement are deliberately separate. {@link #independent}
 * is conservative and may reject parallelism; {@link #map} may choose a
 * processor only after correctness has already been established.</p>
 */
public final class HeterogeneousTaskMapper {
    public enum ProcessorKind { CPU, GPU }
    public enum PlacementHint { AUTO, CPU, GPU }
    public enum Privilege { READ, WRITE, REDUCE }

    /**
     * Logical region/slice identity. A non-zero disjointFamilyId is a
     * compiler/runtime-issued proof that distinct slice ids in the same family
     * do not overlap. Guest source must never be allowed to forge this proof.
     */
    public record RegionSlice(long rootRegionId, long sliceId, long disjointFamilyId) {
        public RegionSlice {
            if (rootRegionId <= 0) throw new IllegalArgumentException("rootRegionId must be positive");
            if (sliceId <= 0) throw new IllegalArgumentException("sliceId must be positive");
            if (disjointFamilyId < 0) throw new IllegalArgumentException("disjointFamilyId cannot be negative");
        }

        public static RegionSlice root(long regionId) {
            return new RegionSlice(regionId, regionId, 0);
        }

        public boolean mayAlias(RegionSlice other) {
            Objects.requireNonNull(other, "other");
            if (rootRegionId != other.rootRegionId) return false;
            if (sliceId == other.sliceId) return true;
            return disjointFamilyId == 0
                    || other.disjointFamilyId == 0
                    || disjointFamilyId != other.disjointFamilyId;
        }
    }

    /**
     * Empty fields means the entire logical slice. A non-empty set is a
     * compiler-validated field footprint. This mirrors Regent-style field
     * privileges without exposing physical layout or device addresses.
     */
    public record Access(
            RegionSlice slice,
            Set<String> fields,
            Privilege privilege,
            String reductionOperator) {
        public Access {
            Objects.requireNonNull(slice, "slice");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(privilege, "privilege");
            fields = Set.copyOf(fields);
            for (String field : fields) {
                if (field == null || field.isBlank()) {
                    throw new IllegalArgumentException("task access field names cannot be blank");
                }
            }
            if (privilege == Privilege.REDUCE) {
                if (reductionOperator == null || reductionOperator.isBlank()) {
                    throw new IllegalArgumentException("reduction access requires a named reduction operator");
                }
            } else if (reductionOperator != null) {
                throw new IllegalArgumentException("only reduction accesses may name a reduction operator");
            }
        }

        public Access(RegionSlice slice, Privilege privilege, String reductionOperator) {
            this(slice, Set.of(), privilege, reductionOperator);
        }

        public static Access read(RegionSlice slice) {
            return new Access(slice, Set.of(), Privilege.READ, null);
        }

        public static Access readFields(RegionSlice slice, String... fields) {
            return new Access(slice, Set.of(fields), Privilege.READ, null);
        }

        public static Access write(RegionSlice slice) {
            return new Access(slice, Set.of(), Privilege.WRITE, null);
        }

        public static Access writeFields(RegionSlice slice, String... fields) {
            return new Access(slice, Set.of(fields), Privilege.WRITE, null);
        }

        public static Access reduce(RegionSlice slice, String operator) {
            return new Access(slice, Set.of(), Privilege.REDUCE, operator);
        }

        public static Access reduceFields(RegionSlice slice, String operator, String... fields) {
            return new Access(slice, Set.of(fields), Privilege.REDUCE, operator);
        }

        public boolean mayOverlapFields(Access other) {
            Objects.requireNonNull(other, "other");
            if (fields.isEmpty() || other.fields.isEmpty()) return true;
            for (String field : fields) {
                if (other.fields.contains(field)) return true;
            }
            return false;
        }
    }

    public record Variant(String name, ProcessorKind processor, long minimumWorkItems) {
        public Variant {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("variant name cannot be blank");
            Objects.requireNonNull(processor, "processor");
            if (minimumWorkItems < 0) throw new IllegalArgumentException("minimumWorkItems cannot be negative");
        }
    }

    public record TaskSpec(String name, List<Access> accesses, List<Variant> variants) {
        public TaskSpec {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("task name cannot be blank");
            accesses = List.copyOf(Objects.requireNonNull(accesses, "accesses"));
            variants = List.copyOf(Objects.requireNonNull(variants, "variants"));
            if (variants.isEmpty()) throw new IllegalArgumentException("task requires at least one processor variant");
        }
    }

    /**
     * gpuCrossoverWorkItems is a mapper heuristic, never a correctness rule.
     * Each non-resident logical root multiplies the crossover threshold so
     * AUTO placement accounts for transfer cost conservatively.
     */
    public record Machine(boolean gpuAvailable, long gpuCrossoverWorkItems) {
        public Machine {
            if (gpuCrossoverWorkItems < 0) {
                throw new IllegalArgumentException("gpuCrossoverWorkItems cannot be negative");
            }
        }
    }

    public record Mapping(Variant variant, ProcessorKind processor) {
        public Mapping {
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(processor, "processor");
            if (variant.processor() != processor) {
                throw new IllegalArgumentException("variant/processor mismatch");
            }
        }
    }

    /**
     * Conservative non-interference check. Disjoint region partitions and
     * disjoint field footprints may overlap. Aliasing read/read accesses are
     * independent. Any overlapping write or reduction is ordered for now.
     * Reduction relaxation requires a separately registered associative/
     * commutative operator contract and is intentionally not guessed here.
     */
    public boolean independent(TaskSpec left, TaskSpec right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        for (Access a : left.accesses()) {
            for (Access b : right.accesses()) {
                if (!a.slice().mayAlias(b.slice())) continue;
                if (!a.mayOverlapFields(b)) continue;
                if (a.privilege() == Privilege.READ && b.privilege() == Privilege.READ) continue;
                return false;
            }
        }
        return true;
    }

    /**
     * Maps a task to a processor variant. AUTO prefers the GPU only when a GPU
     * backend exists, a GPU variant is eligible, and the work estimate exceeds
     * both the variant threshold and a transfer-aware crossover threshold.
     */
    public Mapping map(
            TaskSpec task,
            PlacementHint hint,
            long workItems,
            Machine machine,
            Set<Long> gpuResidentRootRegions) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(gpuResidentRootRegions, "gpuResidentRootRegions");
        if (workItems < 0) throw new IllegalArgumentException("workItems cannot be negative");

        Variant cpu = firstEligible(task, ProcessorKind.CPU, workItems);
        Variant gpu = firstEligible(task, ProcessorKind.GPU, workItems);

        if (hint == PlacementHint.CPU) {
            if (cpu == null) throw new IllegalStateException("task has no eligible CPU variant");
            return new Mapping(cpu, ProcessorKind.CPU);
        }
        if (hint == PlacementHint.GPU) {
            if (!machine.gpuAvailable()) throw new IllegalStateException("GPU placement requested but no GPU backend is available");
            if (gpu == null) throw new IllegalStateException("task has no eligible GPU variant");
            return new Mapping(gpu, ProcessorKind.GPU);
        }

        if (machine.gpuAvailable() && gpu != null) {
            Set<Long> roots = new LinkedHashSet<>();
            for (Access access : task.accesses()) roots.add(access.slice().rootRegionId());
            long nonResident = roots.stream().filter(root -> !gpuResidentRootRegions.contains(root)).count();
            long transferMultiplier = Math.max(1L, nonResident + 1L);
            long crossover;
            try {
                crossover = Math.multiplyExact(machine.gpuCrossoverWorkItems(), transferMultiplier);
            } catch (ArithmeticException overflow) {
                crossover = Long.MAX_VALUE;
            }
            long threshold = Math.max(gpu.minimumWorkItems(), crossover);
            if (workItems >= threshold) return new Mapping(gpu, ProcessorKind.GPU);
        }

        if (cpu != null) return new Mapping(cpu, ProcessorKind.CPU);
        if (machine.gpuAvailable() && gpu != null) return new Mapping(gpu, ProcessorKind.GPU);
        throw new IllegalStateException("task has no eligible variant for this machine/work estimate");
    }

    private Variant firstEligible(TaskSpec task, ProcessorKind processor, long workItems) {
        for (Variant variant : task.variants()) {
            if (variant.processor() == processor && workItems >= variant.minimumWorkItems()) {
                return variant;
            }
        }
        return null;
    }
}
