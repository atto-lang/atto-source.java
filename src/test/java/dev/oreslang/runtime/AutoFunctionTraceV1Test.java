package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

/**
 * Exact same JVM smoke fixture used by the standalone funded-runner command.
 * Host-runtime only; guest .atto and native-image evidence are separate gates.
 */
final class AutoFunctionTraceV1Test {
    @Test
    void automaticTraceScopeAndCarrierMigration() throws Exception {
        AutoFunctionTraceSmoke.main(new String[0]);
    }
}
