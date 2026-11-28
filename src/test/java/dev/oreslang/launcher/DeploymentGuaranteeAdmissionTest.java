package dev.oreslang.launcher;

import dev.oreslang.runtime.NativeBuildMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Host admission only; this test does not certify guest machine code or zero GC. */
final class DeploymentGuaranteeAdmissionTest {
    @TempDir Path temp;

    @Test void buildInfoDistinguishesHostPackagingFromUnprovenGuestMemoryClaims() {
        String info = OresMain.buildInfoJson();
        assertTrue(info.contains("\"build_mode\":\"jvm\""));
        assertTrue(info.contains("\"guest_execution\":\"runtime-selected\""));
        assertTrue(info.contains("\"full_guest_aot_proven\":false"));
        assertTrue(info.contains("\"zero_guest_tracing_gc_proven\":false"));
        assertTrue(info.contains("\"zero_process_gc_proven\":false"));
        assertFalse(NativeBuildMode.fullGuestAotProven());
        assertFalse(NativeBuildMode.zeroGuestTracingGcProven());
        assertFalse(NativeBuildMode.zeroProcessGcProven());
    }

    @Test void strictProofRequestsFailBeforeOpeningOrExecutingGuestSource() {
        String absent = temp.resolve("does-not-exist.atto").toString();
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"--require-full-guest-aot", absent}));
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"--require-zero-guest-tracing-gc", absent}));
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"--require-full-guest-aot",
                        "--require-zero-guest-tracing-gc", absent}));
    }

    @Test void ordinaryAttoSourceAdmissionIsUnaffected() throws Exception {
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, "pub fnc main(): void { return; }");
        assertEquals(0, OresMain.exitStatusForArgs(
                new String[] {"--check", entry.toString()}));
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"--require-full-guest-aot", "--check", entry.toString()}));
        assertEquals(2, OresMain.exitStatusForArgs(
                new String[] {"--require-zero-guest-tracing-gc", "--check", entry.toString()}));
    }
}
