package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedReferenceBuildInfoTest {
    @Test
    void jvmBuildInfoReportsVmManagedReferenceMode() {
        String info = OresMain.buildInfoJson();
        assertTrue(info.contains("\"build_mode\":\"jvm\""));
        assertTrue(info.contains("\"managed_reference_mode\":\"jvm_managed\""));
    }
}
