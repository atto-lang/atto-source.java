package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

/** Exact same smoke contract as the standalone JDK test. */
final class TraceInstrumentationControlV1Test {
    @Test
    void aotAndAdaptivePolicyMustFailClosed() {
        TraceInstrumentationSmoke.main(new String[0]);
    }
}
