package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

/** Host-only JUnit gate for immutable logical trace context across task turns. */
final class TraceContinuationContextV1Test {
    @Test
    void suspendedTaskInheritsAndRestoresImmutableContext() throws Exception {
        TraceContinuationContextV1Smoke.main(new String[0]);
    }
}
