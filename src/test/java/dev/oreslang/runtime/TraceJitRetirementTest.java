package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

/** Host control-plane proof only, not compiled AttoLang guest execution. */
final class TraceJitRetirementTest {
    @Test
    void retiredCodeAndRevokedPolicyCannotLeaveStaleExecutableAuthority() throws Exception {
        TraceJitRetirementSmoke.main(new String[0]);
    }
}
