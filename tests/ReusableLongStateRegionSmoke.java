package dev.oreslang.runtime;

import java.lang.management.ManagementFactory;
import java.util.function.LongUnaryOperator;

/** Standalone JDK-only smoke; executes without Maven/JUnit. */
public final class ReusableLongStateRegionSmoke {
    private static final LongUnaryOperator INC = v -> v + 1;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejected(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("unsafe region access admitted");
    }
    public static void main(String[] args) {
        try (var region = new ReusableLongStateRegion(2);
             var other = new ReusableLongStateRegion(2)) {
            long a = region.insert(1);
            long b = region.insert(2);
            long foreign = other.insert(5);
            rejected(() -> region.read(foreign));
            rejected(() -> region.insert(100));
            for (int i = 0; i < 100_000; i++) region.update(a, INC);
            check(region.read(a) == 100_001, "in-place updates lost");
            region.write(b, 19);
            check(region.read(b) == 19, "primitive write lost");
            region.remove(a);
            rejected(() -> region.read(a));
            long recycled = region.insert(8);
            check(recycled != a && region.read(recycled) == 8, "generation recycled");

            region.update(recycled, previous -> {
                rejected(() -> region.read(recycled));
                rejected(() -> region.write(recycled, 0));
                check(!region.tryClose(), "reentrant close freed active writer");
                return previous + 1;
            });
            check(region.isRetiring(), "reentrant retirement missing");
            rejected(() -> region.read(recycled));
            check(region.tryClose() && region.isClosed(), "retirement did not close");
        }

        try (var region = new ReusableLongStateRegion(1)) {
            long handle = region.insert(0);
            for (int i = 0; i < 100_000; i++) region.write(handle, i);
            com.sun.management.ThreadMXBean mx =
                    ManagementFactory.getThreadMXBean() instanceof
                    com.sun.management.ThreadMXBean bean ? bean : null;
            long bytes = -1;
            if (mx != null && mx.isThreadAllocatedMemorySupported()) {
                if (!mx.isThreadAllocatedMemoryEnabled()) mx.setThreadAllocatedMemoryEnabled(true);
                long tid = Thread.currentThread().getId();
                long before = mx.getThreadAllocatedBytes(tid);
                for (int i = 0; i < 100_000; i++) region.write(handle, i);
                long after = mx.getThreadAllocatedBytes(tid);
                bytes = after - before;
            } else {
                for (int i = 0; i < 100_000; i++) region.write(handle, i);
            }
            check(region.read(handle) == 99_999, "measured writes incorrect");
            System.out.println("java_region_smoke=PASS writes=100000 java_allocated_bytes="
                    + (bytes < 0 ? "unsupported" : Long.toString(bytes))
                    + " measurement_scope=primitive_write_only");
        }
    }
}
