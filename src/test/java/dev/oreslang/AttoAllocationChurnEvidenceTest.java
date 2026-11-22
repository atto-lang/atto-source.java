package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.RuntimeMemoryProbe;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real .atto guest execution with host-boundary memory observations.
 *
 * NOT proof of guest-native heap layout or zero GC: current-thread allocated
 * bytes count JVM/Truffle dispatcher work and exclude other actor carrier
 * threads. Global GC MXBeans are not per-guest nor GC-pause histograms.
 */
final class AttoAllocationChurnEvidenceTest {
    private static final int WARMUP = 32;
    private static final int MEASURED = 128;

    private static Source fixture(String name) throws Exception {
        String location = "/atto/memory/" + name;
        String code;
        try (InputStream input = AttoAllocationChurnEvidenceTest.class.getResourceAsStream(location)) {
            assertNotNull(input, "canonical native guest fixture missing: " + location);
            code = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(code.length() > 64 && code.length() < 8192, "bounded fixture expected");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        return Source.newBuilder(OresLanguage.ID, code, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
    }

    private static String counter(OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "UNAVAILABLE";
    }

    private static void observe(String name) throws Exception {
        Source guest = fixture(name);

        // Verify actual guest behavior in a separate context, outside the
        // measured region; no Java source algorithm substitutes for .atto.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context check = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            check.eval(guest);
        }
        assertEquals("32640", output.toString(StandardCharsets.UTF_8).trim(),
                "guest vector and scalar controls must compute the same checksum");

        // Null sink removes expanding stdout capture buffers from measurement.
        // The runtime's Context.eval overhead is still included and disclosed.
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(OutputStream.nullOutputStream()).build()) {
            for (int i = 0; i < WARMUP; i++) ctx.eval(guest);
            RuntimeMemoryProbe.Snapshot start = RuntimeMemoryProbe.sample();
            for (int i = 0; i < MEASURED; i++) ctx.eval(guest);
            RuntimeMemoryProbe.Snapshot end = RuntimeMemoryProbe.sample();
            RuntimeMemoryProbe.Delta delta = RuntimeMemoryProbe.difference(start, end);
            if (delta.currentThreadAllocatedBytes().isPresent())
                assertTrue(delta.currentThreadAllocatedBytes().getAsLong() >= 0);
            if (delta.globalCollectionCount().isPresent())
                assertTrue(delta.globalCollectionCount().getAsLong() >= 0);
            if (delta.globalCollectionTimeMillis().isPresent())
                assertTrue(delta.globalCollectionTimeMillis().getAsLong() >= 0);
            String perEval = delta.currentThreadAllocatedBytes().isPresent()
                    ? Double.toString(delta.currentThreadAllocatedBytes().getAsLong() / (double) MEASURED)
                    : "UNAVAILABLE";
            System.err.println("ATTO_GUEST_MEMORY_OBSERVATION_V1"
                    + " source=" + name
                    + " warmup_context_eval=" + WARMUP
                    + " measured_context_eval=" + MEASURED
                    + " checksum=32640"
                    + " host_current_thread_allocated_bytes=" + counter(delta.currentThreadAllocatedBytes())
                    + " host_bytes_per_context_eval=" + perEval
                    + " process_gc_count_delta=" + counter(delta.globalCollectionCount())
                    + " process_gc_time_millis_delta=" + counter(delta.globalCollectionTimeMillis())
                    + " actor_guest_memory_bytes=UNAVAILABLE"
                    + " actor_deterministic_drops_proven=false"
                    + " native_image=false"
                    + " process_zero_gc_proven=false");
        }
    }

    @Test
    void scalarControlUsesRealAttoGuestLogic() throws Exception {
        observe("scalar-loop-control.atto");
    }

    @Test
    void ownedVectorChurnUsesRealAttoGuestLogic() throws Exception {
        observe("owned-vector-churn.atto");
    }
}
