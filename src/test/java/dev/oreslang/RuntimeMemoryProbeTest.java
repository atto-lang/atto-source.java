package dev.oreslang;

import dev.oreslang.runtime.RuntimeMemoryProbe;
import java.util.OptionalLong;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Graal Polyglot guest execution with allocation/GC observations.
 * Not a benchmark certification: repeated Context.eval includes host dispatch,
 * and collector global counts can include unrelated JVM activity.
 */
final class RuntimeMemoryProbeTest {
    @Test
    void observationsAreNonnegativeOrExplicitlyUnavailable() {
        var before = RuntimeMemoryProbe.sample();
        var after = RuntimeMemoryProbe.sample();
        var delta = RuntimeMemoryProbe.difference(before, after);
        check(delta.currentThreadAllocatedBytes());
        check(delta.globalCollectionCount());
        check(delta.globalCollectionTimeMillis());
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeMemoryProbe.difference(null, after));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeMemoryProbe.difference(before, null));
    }

    private static void check(OptionalLong observed) {
        if (observed.isPresent()) assertTrue(observed.getAsLong() >= 0);
    }

    @Test
    void actualSourceGuestRunsAreMeasuredWithoutForcingGlobalGc() throws Exception {
        Source guest = Source.newBuilder(OresLanguage.ID, """
                pub fnc main(): int {
                  return 42;
                }
                """, "memory-probe-scalar.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            // This measured workload is intentionally explicit and finite.
            // Warmup is separate; do not mistake interpreter/JIT startup for
            // steady state or treat host Context.eval as an in-guest loop.
            for (int i = 0; i < 32; ++i) ctx.eval(guest);
            var start = RuntimeMemoryProbe.sample();
            for (int i = 0; i < 128; ++i) ctx.eval(guest);
            var end = RuntimeMemoryProbe.sample();
            var observed = RuntimeMemoryProbe.difference(start, end);
            check(observed.currentThreadAllocatedBytes());
            check(observed.globalCollectionCount());
            check(observed.globalCollectionTimeMillis());
            // Log raw measurements, never assert a zero-GC claim or a
            // universal performance threshold from one CI/JVM sample.
            System.err.printf(
                    "ORES_RUNTIME_MEMORY_PROBE_V1 workload=repeated-graal-context-eval"
                    + " warmup=32 samples=128 thread_bytes=%s gc_count_delta=%s"
                    + " gc_time_millis_delta=%s host_gc_may_run=true"
                    + " owned_guest_drops_proven=false%n",
                    render(observed.currentThreadAllocatedBytes()),
                    render(observed.globalCollectionCount()),
                    render(observed.globalCollectionTimeMillis()));
        }
    }

    private static String render(OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "UNAVAILABLE";
    }
}
