import dev.oreslang.compiler.GpuKernelCapacityPreflightV1;
import dev.oreslang.compiler.GpuKernelCapacityPreflightV1.Argument;
import dev.oreslang.compiler.GpuLaunchFootprints.*;
import java.util.List;
import java.util.SplittableRandom;

/**
 * HOST-ONLY exact-head smoke. No AttoLang parser/compiler, guest,
 * physical GPU, authenticated provider view or completion fence.
 */
public final class GpuKernelIntegratedCapacitySmoke {
    static final QueueScope QUEUE = new QueueScope("tenant-a", "gpu-0", "queue", 21);
    static final Limits BUDGET = new Limits(64, 1 << 22);
    static int assertions;

    static void check(boolean condition, String label) {
        ++assertions;
        if (!condition) throw new AssertionError(label + " at #" + assertions);
    }

    static void denied(Runnable work, String label) {
        ++assertions;
        try {
            work.run();
        } catch (IllegalArgumentException | SecurityException expected) {
            return;
        }
        throw new AssertionError(label + " was wrongly admitted");
    }

    static Argument buffer(String parameter, String allocation, long capacityBytes,
                           Access expectedAccess, Access actualAccess) {
        return new Argument(parameter, expectedAccess,
                new View("tenant-a", "gpu-0", allocation, 5, capacityBytes,
                        0, capacityBytes, 4, actualAccess));
    }

    static List<Argument> saxpy(long bytes) {
        return List.of(
                buffer("x", "alloc-x", bytes, Access.READ, Access.READ),
                buffer("y", "alloc-y", bytes, Access.READ, Access.READ),
                buffer("out", "alloc-out", bytes, Access.WRITE, Access.WRITE));
    }

    public static void main(String[] args) {
        var safe = GpuKernelCapacityPreflightV1.validateF32(
                QUEUE, 256, 256, saxpy(1024), BUDGET);
        check(safe.requiredBytesPerView() == 1024, "byte extent");
        check(safe.checkedViews() == 3, "source buffer count");
        check(safe.logicalItems() == 256, "logical extent");
        check(!safe.executable(), "no physical device authority");
        check(safe.physicalDispatchRequired(), "physical work requested");
        check(safe.evidence() == GpuKernelCapacityPreflightV1.Evidence
                .SNAPSHOT_CAPACITY_SHAPE_ONLY_NOT_EXECUTABLE, "source shape only");

        var empty = GpuKernelCapacityPreflightV1.validateF32(
                QUEUE, 0, 0, saxpy(4), BUDGET);
        check(!empty.executable() && !empty.physicalDispatchRequired(),
                "zero work never confers a dispatch permit");

        denied(() -> GpuKernelCapacityPreflightV1.validateF32(
                QUEUE, 256, 257, saxpy(1024), BUDGET), "mismatched logical n");
        denied(() -> GpuKernelCapacityPreflightV1.validateF32(
                QUEUE, 256, 256, saxpy(1020), BUDGET), "short source buffers");
        denied(() -> GpuKernelCapacityPreflightV1.validateF32(
                QUEUE, Long.MAX_VALUE, Long.MAX_VALUE, saxpy(1024), BUDGET),
                "overflowing n times f32 bytes");

        denied(() -> GpuKernelCapacityPreflightV1.validateF32(QUEUE, 256, 256,
                List.of(buffer("x", "a", 1024, Access.READ, Access.WRITE)), BUDGET),
                "read from write-only");
        denied(() -> GpuKernelCapacityPreflightV1.validateF32(QUEUE, 256, 256,
                List.of(buffer("out", "a", 1024, Access.WRITE, Access.READ)), BUDGET),
                "write into read-only");
        denied(() -> GpuKernelCapacityPreflightV1.validateF32(QUEUE, 256, 256,
                List.of(buffer("s", "a", 1024, Access.READ_WRITE, Access.READ)), BUDGET),
                "read-write requires exclusive view");
        denied(() -> GpuKernelCapacityPreflightV1.validateF32(QUEUE, 256, 256,
                List.of(buffer("out", "a", 1024, Access.WRITE, Access.WRITE),
                        buffer("other", "a", 1024, Access.READ, Access.READ)), BUDGET),
                "read/write alias overlap");
        denied(() -> GpuKernelCapacityPreflightV1.validateF32(QUEUE, 256, 256,
                List.of(buffer("out", "a", 1024, Access.WRITE, Access.WRITE),
                        buffer("out", "b", 1024, Access.WRITE, Access.WRITE)), BUDGET),
                "duplicate param binding");

        denied(() -> GpuKernelCapacityPreflightV1.validateF32(QUEUE, 256, 256,
                List.of(new Argument("out", Access.WRITE,
                        new View("foreign-tenant", "gpu-0", "a", 5, 1024,
                                0, 1024, 4, Access.WRITE))), BUDGET),
                "foreign tenant");
        denied(() -> GpuKernelCapacityPreflightV1.validateF32(QUEUE, 256, 256,
                List.of(new Argument("out", Access.WRITE,
                        new View("tenant-a", "gpu-0", "a", 5, 1024,
                                0, 1024, 1, Access.WRITE))), BUDGET),
                "insufficient alignment");

        // Deterministic boundary oracle, not trusted guest or provider data.
        SplittableRandom random = new SplittableRandom(0xA770B0DEL);
        for (int trial = 0; trial < 2048; trial++) {
            long n = random.nextInt(1, 1025);
            long availableItems = random.nextInt(1, 1025);
            boolean expected = availableItems >= n;
            try {
                var audit = GpuKernelCapacityPreflightV1.validateF32(
                        QUEUE, n, n, saxpy(availableItems * 4),
                        new Limits(64, 16384));
                check(expected && !audit.executable(),
                        "insufficient capacity or unauthorized execution");
            } catch (IllegalArgumentException rejected) {
                check(!expected, "capacity check rejected a fitting view");
            }
        }
        System.out.println("PASS 2048 Java host-only capacity boundary trials; "
                + assertions + " assertions; NOT AttoLang compiler/GPU execution");
    }
}
