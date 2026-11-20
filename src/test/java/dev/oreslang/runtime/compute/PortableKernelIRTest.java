package dev.oreslang.runtime.compute;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.oreslang.runtime.compute.PortableKernelIR.*;
import static org.junit.jupiter.api.Assertions.*;

final class PortableKernelIRTest {

    @Test
    void layoutOffsetsMatchPortableContract() {
        var aos = new Plan(Layout.AOS, 2, 3, 4, 16, 1);
        assertEquals(32, aos.byteLength());
        assertEquals(24, aos.byteOffset(1, 2));

        var soa = new Plan(Layout.SOA, 3, 2, 4, 8, 1);
        assertEquals(32, soa.byteLength());
        assertEquals(24, soa.byteOffset(2, 1));

        var aosoa = new Plan(Layout.AOSOA, 5, 2, 4, 16, 4);
        assertEquals(64, aosoa.byteLength());
        assertEquals(48, aosoa.byteOffset(4, 1));
    }

    @Test
    void cpuReferenceExecutesAllLayoutsAndOperations() {
        for (Layout layout : Layout.values()) {
            int lanes = layout == Layout.AOSOA ? 4 : 1;
            var plan = new Plan(layout, 5, 2, 4, 16, lanes);
            var input = new HostBuffer(plan);
            var output = new HostBuffer(plan);

            for (long row = 0; row < plan.rows(); row++) {
                for (int field = 0; field < plan.fields(); field++) {
                    input.write(row, field, (float) (row * 10 + field + 0.25));
                }
            }

            for (Operation op : Operation.values()) {
                var kernel = new Kernel(plan, Element.F32, op, 1.5f, -2.0f);
                CpuReferenceProvider.execute(kernel, input, output);
                for (long row = 0; row < plan.rows(); row++) {
                    for (int field = 0; field < plan.fields(); field++) {
                        float value = input.read(row, field);
                        float expected = switch (op) {
                            case SCALE -> value * 1.5f;
                            case BIAS -> value - 2.0f;
                            case SCALE_BIAS -> {
                                float product = value * 1.5f;
                                yield product - 2.0f;
                            }
                        };
                        assertEquals(
                                Float.floatToRawIntBits(expected),
                                Float.floatToRawIntBits(output.read(row, field)),
                                layout + " " + op + " row=" + row + " field=" + field);
                    }
                }
            }
        }
    }

    @Test
    void invalidLayoutsCoefficientsAndAliasingFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(Layout.AOS, -1, 1, 4, 4, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(Layout.AOS, 1, 0, 4, 4, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(Layout.AOS, 1, 1, 3, 4, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(Layout.SOA, 1, 1, 4, 8192, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(Layout.AOS, 1, 1, 4, 4, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(Layout.AOSOA, 1, 1, 4, 4, 2048));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(Layout.AOS, Long.MAX_VALUE, 2, 4, 4, 1));

        var plan = new Plan(Layout.AOS, 2, 1, 4, 4, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new Kernel(plan, Element.F32, Operation.SCALE, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Kernel(plan, Element.F32, Operation.BIAS, 1, Float.POSITIVE_INFINITY));

        var buffer = new HostBuffer(plan);
        var kernel = new Kernel(plan, Element.F32, Operation.SCALE, 2, 0);
        assertThrows(IllegalArgumentException.class,
                () -> CpuReferenceProvider.execute(kernel, buffer, buffer));
    }

    @Test
    void hostBufferUsesLittleEndianF32SlotsWithoutPaddingCorruption() {
        var plan = new Plan(Layout.AOS, 2, 2, 4, 16, 1);
        var buffer = new HostBuffer(plan);
        buffer.write(0, 0, 1.0f);
        buffer.write(0, 1, -0.0f);
        buffer.write(1, 0, Float.intBitsToFloat(0x7fc01234));
        buffer.write(1, 1, Float.MIN_VALUE);

        assertEquals(0x3f800000, Float.floatToRawIntBits(buffer.read(0, 0)));
        assertEquals(0x80000000, Float.floatToRawIntBits(buffer.read(0, 1)));
        assertEquals(0x7fc01234, Float.floatToRawIntBits(buffer.read(1, 0)));
        assertEquals(0x00000001, Float.floatToRawIntBits(buffer.read(1, 1)));

        byte[] raw = buffer.snapshotBytes();
        assertEquals(32, raw.length);
        assertEquals((byte) 0x00, raw[8], "AoS padding must remain untouched");
        assertEquals((byte) 0x00, raw[15], "AoS padding must remain untouched");
    }

    @Test
    void openClLoweringMatchesG2AdmissionAndNeverFallsBack() {
        var plan = new Plan(Layout.AOSOA, 5, 2, 4, 16, 4);
        var kernel = new Kernel(plan, Element.F32, Operation.SCALE_BIAS, 1.5f, -2.0f);
        var artifact = lowerOpenCl(kernel, new GpuTarget(64, 4096, 100, true));

        assertEquals("oreslang_dop_f32", artifact.kernelName());
        assertEquals(plan.byteLength(), artifact.requiredBytes());
        assertEquals(5, artifact.globalRows());
        assertEquals(2, artifact.globalFields());
        assertEquals(1, artifact.artifactAbiVersion());
        assertEquals(64, artifact.requiredAddressBits());
        assertTrue(artifact.requiredFloat32());
        assertTrue(artifact.requiredLittleEndian());
        assertTrue(artifact.deviceSource().startsWith("#pragma OPENCL FP_CONTRACT OFF\n"));
        assertTrue(artifact.deviceSource().contains("get_global_id(1)"));
        assertTrue(artifact.deviceSource().contains("dst[slot]"));
        assertTrue(artifact.deviceSource().contains("1.50000000e+00f"));
        assertTrue(artifact.deviceSource().contains("-2.00000000e+00f"));

        assertThrows(IllegalArgumentException.class,
                () -> lowerOpenCl(kernel, new GpuTarget(32, 4096, 100, true)));
        assertThrows(IllegalArgumentException.class,
                () -> lowerOpenCl(kernel, new GpuTarget(64, 0, 100, true)));
        assertThrows(IllegalArgumentException.class,
                () -> lowerOpenCl(kernel, new GpuTarget(64, 4096, 0, true)));
        assertThrows(IllegalArgumentException.class,
                () -> lowerOpenCl(kernel, new GpuTarget(64, 4096, 100, false)));
        assertThrows(IllegalArgumentException.class,
                () -> lowerOpenCl(kernel, new GpuTarget(64, plan.byteLength() - 1, 100, true)));
        assertThrows(IllegalArgumentException.class,
                () -> lowerOpenCl(kernel, new GpuTarget(64, 4096, 4, true)));

        var empty = new Plan(Layout.AOS, 0, 1, 4, 4, 1);
        var emptyKernel = new Kernel(empty, Element.F32, Operation.SCALE, 1.0f, 0.0f);
        assertThrows(IllegalArgumentException.class,
                () -> lowerOpenCl(emptyKernel, new GpuTarget(64, 4096, 100, true)));
    }

    @Test
    void cpuReferenceExecutionSerializesReversedBufferPairsWithoutDeadlock() throws Exception {
        var plan = new Plan(Layout.AOS, 1, 1, 4, 4, 1);
        var kernel = new Kernel(plan, Element.F32, Operation.SCALE, 2.0f, 0.0f);

        for (int iteration = 0; iteration < 100; iteration++) {
            var a = new HostBuffer(plan);
            var b = new HostBuffer(plan);
            a.write(0, 0, 1.0f);
            b.write(0, 0, 3.0f);

            var start = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread left = new Thread(() -> {
                try {
                    start.await();
                    CpuReferenceProvider.execute(kernel, a, b);
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            Thread right = new Thread(() -> {
                try {
                    start.await();
                    CpuReferenceProvider.execute(kernel, b, a);
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                }
            });
            left.start();
            right.start();
            start.countDown();

            left.join(TimeUnit.SECONDS.toMillis(5));
            right.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(left.isAlive(), "left CPU execution deadlocked");
            assertFalse(right.isAlive(), "right CPU execution deadlocked");
            if (failure.get() != null) {
                fail(failure.get());
            }

            float av = a.read(0, 0);
            float bv = b.read(0, 0);
            boolean leftFirst = av == 4.0f && bv == 2.0f;
            boolean rightFirst = av == 6.0f && bv == 12.0f;
            assertTrue(leftFirst || rightFirst,
                    () -> "non-serializable CPU result: a=" + av + " b=" + bv);
        }
    }

    @Test
    void referenceProviderRejectsBuffersBeyondJavaArrayBackendLimit() {
        var huge = new Plan(Layout.AOS, ((long) Integer.MAX_VALUE / 4) + 1, 1, 4, 4, 1);
        assertThrows(IllegalArgumentException.class, () -> new HostBuffer(huge));
    }
}
