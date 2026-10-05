package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class HeterogeneousTaskMapperTest {
    private final HeterogeneousTaskMapper mapper = new HeterogeneousTaskMapper();

    @Test
    void readReadMayOverlapButAliasingWritesSerialize() {
        var region = HeterogeneousTaskMapper.RegionSlice.root(1);
        var cpu = new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var readerA = new HeterogeneousTaskMapper.TaskSpec(
                "reader-a",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(cpu));
        var readerB = new HeterogeneousTaskMapper.TaskSpec(
                "reader-b",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(cpu));
        var writer = new HeterogeneousTaskMapper.TaskSpec(
                "writer",
                List.of(HeterogeneousTaskMapper.Access.write(region)),
                List.of(cpu));

        assertTrue(mapper.independent(readerA, readerB));
        assertFalse(mapper.independent(readerA, writer));
        assertFalse(mapper.independent(writer, writer));
    }

    @Test
    void certifiedDisjointPartitionsMayRunInParallel() {
        var left = new HeterogeneousTaskMapper.RegionSlice(7, 701, 99);
        var right = new HeterogeneousTaskMapper.RegionSlice(7, 702, 99);
        var cpu = new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);

        var a = new HeterogeneousTaskMapper.TaskSpec(
                "a",
                List.of(HeterogeneousTaskMapper.Access.write(left)),
                List.of(cpu));
        var b = new HeterogeneousTaskMapper.TaskSpec(
                "b",
                List.of(HeterogeneousTaskMapper.Access.write(right)),
                List.of(cpu));

        assertTrue(mapper.independent(a, b));
    }

    @Test
    void autoPlacementAccountsForGpuCrossoverAndDataResidency() {
        var region = HeterogeneousTaskMapper.RegionSlice.root(11);
        var task = new HeterogeneousTaskMapper.TaskSpec(
                "transform",
                List.of(HeterogeneousTaskMapper.Access.read(region)),
                List.of(
                        new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0),
                        new HeterogeneousTaskMapper.Variant("gpu", HeterogeneousTaskMapper.ProcessorKind.GPU, 100)));
        var machine = new HeterogeneousTaskMapper.Machine(true, 100);

        assertEquals(
                HeterogeneousTaskMapper.ProcessorKind.CPU,
                mapper.map(task, HeterogeneousTaskMapper.PlacementHint.AUTO, 150, machine, Set.of()).processor());

        assertEquals(
                HeterogeneousTaskMapper.ProcessorKind.GPU,
                mapper.map(task, HeterogeneousTaskMapper.PlacementHint.AUTO, 150, machine, Set.of(11L)).processor());

        assertEquals(
                HeterogeneousTaskMapper.ProcessorKind.GPU,
                mapper.map(task, HeterogeneousTaskMapper.PlacementHint.AUTO, 250, machine, Set.of()).processor());
    }

    @Test
    void explicitGpuPlacementFailsClosedWithoutGpuBackend() {
        var task = new HeterogeneousTaskMapper.TaskSpec(
                "gpu-only",
                List.of(),
                List.of(new HeterogeneousTaskMapper.Variant(
                        "gpu",
                        HeterogeneousTaskMapper.ProcessorKind.GPU,
                        0)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> mapper.map(
                        task,
                        HeterogeneousTaskMapper.PlacementHint.GPU,
                        1,
                        new HeterogeneousTaskMapper.Machine(false, 0),
                        Set.of()));
        assertTrue(failure.getMessage().contains("no GPU backend"));
    }

    @Test
    void reductionsRemainOrderedUntilOperatorSafetyIsRegistered() {
        var region = HeterogeneousTaskMapper.RegionSlice.root(21);
        var cpu = new HeterogeneousTaskMapper.Variant("cpu", HeterogeneousTaskMapper.ProcessorKind.CPU, 0);
        var reduceA = new HeterogeneousTaskMapper.TaskSpec(
                "reduce-a",
                List.of(HeterogeneousTaskMapper.Access.reduce(region, "sum")),
                List.of(cpu));
        var reduceB = new HeterogeneousTaskMapper.TaskSpec(
                "reduce-b",
                List.of(HeterogeneousTaskMapper.Access.reduce(region, "sum")),
                List.of(cpu));

        assertFalse(mapper.independent(reduceA, reduceB));
        assertThrows(
                IllegalArgumentException.class,
                () -> HeterogeneousTaskMapper.Access.reduce(region, ""));
    }
}
