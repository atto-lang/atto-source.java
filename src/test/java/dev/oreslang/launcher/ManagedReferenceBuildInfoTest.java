package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedReferenceBuildInfoTest {
    @Test
    void jvmBuildInfoReportsVmManagedReferenceMode() {
        String info = OresMain.buildInfoJson();
        assertTrue(info.contains("\"build_mode\":\"jvm\""));
        assertTrue(info.contains("\"managed_reference_mode\":\"jvm_managed\""));
        assertTrue(info.contains("\"guest_execution\":true,"),
                "native demo admission requires a JSON boolean, not a string");
        assertTrue(info.contains("\"guest_execution_mode\":\"runtime-selected\""));
        assertTrue(!info.contains("\"guest_execution\":\""),
                "human-readable execution mode must not masquerade as the boolean flag");
    }
}
