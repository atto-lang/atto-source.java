package dev.oreslang;

import dev.oreslang.runtime.accel.QuantumCircuitIR;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class QuantumCircuitLoweringTest {
    @Test
    void bellStateProducesCorrelatedMeasurementsAndOpenQasm() {
        var circuit = new QuantumCircuitIR.Builder(2, 2)
                .h(0).cx(0, 1).measure(0, 0).measure(1, 1).build();
        assertArrayEquals(new double[]{0.5, 0.0, 0.0, 0.5},
                QuantumCircuitIR.simulateProbabilities(circuit), 1e-12);
        String qasm = QuantumCircuitIR.lowerOpenQasm3(circuit);
        assertTrue(qasm.startsWith("OPENQASM 3.0;"));
        assertTrue(qasm.contains("cx q[0], q[1];"));
        assertTrue(qasm.contains("c[1] = measure q[1];"));
        long[] counts = QuantumCircuitIR.simulateShots(circuit, 1000, 42);
        assertEquals(1000, java.util.Arrays.stream(counts).sum());
        assertEquals(0, counts[1]);
        assertEquals(0, counts[2]);
        assertArrayEquals(counts, QuantumCircuitIR.simulateShots(circuit, 1000, 42));
    }

    @Test
    void circuitRejectsOversizedExternalListWithoutTraversingIt() {
        // Adversarial list: size() is cheap, but any iteration triggers
        // arbitrary work. Circuit admission must reject before List.copyOf.
        java.util.List<QuantumCircuitIR.Gate> oversized =
                new java.util.AbstractList<>() {
                    @Override public int size() { return 1025; }
                    @Override public QuantumCircuitIR.Gate get(int index) {
                        throw new AssertionError("oversized gate collection was traversed");
                    }
                };
        assertThrows(IllegalArgumentException.class,
                () -> new QuantumCircuitIR.Circuit(1, 1, oversized));
    }

    @Test
    void highDimensionalShotsRemainBoundedAndSeedDeterministic() {
        // This shape previously required about 6.5 billion comparisons at
        // 100,000 shots; sampling must scale logarithmically in bit patterns.
        var builder = new QuantumCircuitIR.Builder(16, 16);
        for (int bit = 0; bit < 16; bit++) builder.h(bit);
        for (int bit = 0; bit < 16; bit++) builder.measure(bit, bit);
        var circuit = builder.build();
        long[] counts = QuantumCircuitIR.simulateShots(circuit, 100_000, 918273);
        assertEquals(100_000L, java.util.Arrays.stream(counts).sum());
        assertArrayEquals(counts, QuantumCircuitIR.simulateShots(circuit, 100_000, 918273));
    }

    @Test
    void zeroProbabilityPatternsRemainUnsampledAndBuilderQuotaIsImmediate() {
        var circuit = new QuantumCircuitIR.Builder(2, 2)
                .x(0).measure(0, 0).measure(1, 1).build();
        long[] counts = QuantumCircuitIR.simulateShots(circuit, 100_000, 7);
        assertEquals(100_000L, counts[1]);
        assertEquals(0L, counts[0]);
        assertEquals(0L, counts[2]);
        assertEquals(0L, counts[3]);

        var builder = new QuantumCircuitIR.Builder(1, 1);
        for (int n = 0; n < 1024; n++) builder.x(0);
        assertThrows(IllegalArgumentException.class, () -> builder.measure(0, 0));
    }

    @Test
    void rejectsIllegalGateShapesCloningViaRepeatedMeasurementAndLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new QuantumCircuitIR.Builder(17, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new QuantumCircuitIR.Builder(2, 2).cx(0, 0).measure(0, 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> new QuantumCircuitIR.Builder(2, 2).measure(0, 0).measure(0, 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> new QuantumCircuitIR.Builder(2, 2).measure(0, 0).measure(1, 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> new QuantumCircuitIR.Builder(2, 2).measure(0, 0).h(1).build());
        assertThrows(IllegalArgumentException.class,
                () -> new QuantumCircuitIR.Builder(2, 2).h(0).build());
        var circuit = new QuantumCircuitIR.Builder(1, 1).x(0).measure(0, 0).build();
        assertArrayEquals(new double[]{0, 1}, QuantumCircuitIR.simulateProbabilities(circuit), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> QuantumCircuitIR.simulateShots(circuit, 1_000_001, 1));
    }
}
