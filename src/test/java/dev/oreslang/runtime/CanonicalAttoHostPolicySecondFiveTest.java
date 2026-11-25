package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Five new pure guest algorithms; Java still owns every privileged operation. */
final class CanonicalAttoHostPolicySecondFiveTest {
    private static final Set<String> POLICIES = Set.of(
            "cpu_budget_policy", "source_budget_policy",
            "cooperation_policy", "http_response_phase_policy",
            "admission_deadline_policy");
    @TempDir Path temp;

    @Test
    void canonicalFiveHaveNoHostSourceOrLegacyAliases() throws Exception {
        for (String stem : POLICIES) {
            String importPath = "ores.std/" + stem + ".atto";
            assertTrue(CoreLibrarySources.packagedSourcePaths().contains(stem + ".atto"));
            assertEquals("@std/" + stem + ".atto", CoreLibrarySources.unitId(importPath));
            assertEquals(CoreLibrarySources.unitId(importPath),
                    CoreLibrarySources.unitId("std/" + stem));
            assertFalse(Files.exists(Path.of("stdlib", stem + ".ores")));
            String source = Files.readString(Path.of("stdlib", stem + ".atto"));
            assertEquals(source, CoreLibrarySources.source(importPath));
            var unit = MixedSourceUnit.parse(
                    CoreLibrarySources.unitId(importPath),
                    CoreLibrarySources.fileName(importPath), source);
            assertTrue(unit.hasOresSource(), stem);
            assertFalse(unit.hasJavaSource(), stem);
            assertFalse(source.contains("java:"), stem);
            assertFalse(source.contains("stdio."), stem);
            assertFalse(source.contains("network."), stem);
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source("std/" + stem + ".ores"));
        }
    }

    private void expectGuest(String stem, String expected) throws Exception {
        Path path = Path.of("src/test/resources/stdlib-parity/portable-java-policy-second-five",
                stem + ".atto");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(
                path, IsolatePolicy.developer(), ExecutionProfile.serverJit(), out, err);
        assertTrue(linked.units().containsKey("@std/" + stem + ".atto"),
                () -> "guest did not compile/link " + stem);
        assertEquals(expected,
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> stem + " guest stderr: " + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cpuSizingGuestMatchesJavaCgroupArithmetic() throws Exception {
        assertEquals(4, CpuCarrierBudget.clamp(8, 4, 6));
        assertEquals(1, CpuCarrierBudget.clamp(0, -1, 0));
        assertEquals(2, CpuCarrierBudget.parseCpuMax("150000 100000"));
        assertEquals(2, CpuCarrierBudget.parseCpuMax("200000 100000"));
        assertEquals(0, CpuCarrierBudget.parseCpuMax("-1 100"));
        var pools = CpuCarrierBudget.defaults(8);
        assertEquals(2, pools.privateWorkers());
        assertEquals(4, pools.sharedWorkers());
        assertEquals(2, pools.untrustedWorkers());
        assertEquals(1, CpuCarrierBudget.defaults(2).privateWorkers());
        assertEquals(1, CpuCarrierBudget.defaults(2).sharedWorkers());
        expectGuest("cpu_budget_policy", "4\n1\n2\n2\n0\n2\n4\n2\n1\n1");
    }

    @Test
    void sourceClosureBudgetGuestMatchesTrustedJavaAdmission() throws Exception {
        var budget = new SourceAdmissionBudget();
        assertDoesNotThrow(() -> budget.checkDepth(96));
        assertThrows(IllegalArgumentException.class, () -> budget.checkDepth(97));
        assertDoesNotThrow(() -> new SourceAdmissionBudget().admit(2, 1));
        var full = new SourceAdmissionBudget();
        full.admit(67108864, 0);
        assertThrows(IllegalArgumentException.class, () -> full.admit(1, 1));
        var almost = new SourceAdmissionBudget();
        almost.admit(67108860, 0);
        assertDoesNotThrow(() -> almost.admit(4, 96));
        assertThrows(IllegalArgumentException.class, () -> new SourceAdmissionBudget().admit(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new SourceAdmissionBudget().admit(-1, 0));
        expectGuest("source_budget_policy", "true\nfalse\ntrue\nfalse\ntrue\nfalse\nfalse\nfalse\n64\n1");
    }

    @Test
    void cooperationGuestMatchesSoftPollQuantumWithoutPublishingYield() throws Exception {
        AtomicLong clock = new AtomicLong(0);
        var quantum = new SourceCooperationQuantum(clock::get);
        quantum.beginDispatch();
        int interval = SourceCooperationQuantum.LOOP_CHECK_INTERVAL;
        for (int backedge = 1; backedge <= interval; backedge++) {
            assertEquals(backedge == interval, quantum.completedBackedgeNeedsCheck());
        }
        assertFalse(quantum.shouldCooperateAtCheck());
        clock.set(SourceCooperationQuantum.SOFT_DISPATCH_QUANTUM_NANOS);
        assertFalse(quantum.shouldCooperateAtCheck(true), "poll ticket was consumed");
        for (int backedge = interval + 1; backedge <= 2 * interval; backedge++) {
            assertEquals(backedge == 2 * interval, quantum.completedBackedgeNeedsCheck());
        }
        assertTrue(quantum.shouldCooperateAtCheck());
        for (int backedge = 2 * interval + 1; backedge <= 5 * interval; backedge++) {
            assertEquals(backedge % interval == 0, quantum.completedBackedgeNeedsCheck());
        }
        assertTrue(quantum.shouldCooperateAtCheck());
        expectGuest("cooperation_policy", "false\nfalse\ntrue\nfalse\ntrue\nfalse\ntrue\n1\n9223372036854775807\nfalse\ntrue\ntrue\ntrue");
    }

    @Test
    void wirePhaseGuestMatchesJavaMonitoredHeaderState() throws Exception {
        var phase = new HttpResponseWirePhase();
        assertTrue(phase.maySendError(false, false));
        assertTrue(phase.begin(true, false));
        assertFalse(phase.begin(true, false));
        assertFalse(phase.maySendError(false, false));
        phase.markSent();
        assertTrue(phase.sent());
        assertThrows(IllegalStateException.class, phase::markSent);
        assertThrows(java.io.IOException.class,
                () -> HttpResponseWirePhase.requireLiveFinish(true));
        assertDoesNotThrow(() -> HttpResponseWirePhase.requireLiveFinish(false));
        expectGuest("http_response_phase_policy",
                "true\nfalse\nfalse\ntrue\nfalse\ntrue\nfalse\nfalse\ntrue\nfalse");
    }

    @Test
    void deadlineGuestMatchesJavaBoundsButNotTimerOrLeaseAuthority() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new GuestAdmissionDeadline(Duration.ZERO, () -> {}).close());
        assertThrows(IllegalArgumentException.class,
                () -> new GuestAdmissionDeadline(Duration.ofNanos(300000000001L), () -> {}).close());
        try (var deadline = new GuestAdmissionDeadline(Duration.ofMinutes(5), () -> {})) {
            assertTrue(deadline.commitIfLive());
        }
        expectGuest("admission_deadline_policy",
                "true\ntrue\nfalse\nfalse\ntrue\nfalse\nfalse\ntrue\ntrue\nfalse\ntrue\nfalse");
    }

    @Test
    void compilerOwnedPolicyImportRejectsLocalShadow() throws Exception {
        Path shadow = Files.createDirectories(temp.resolve("std"));
        Files.writeString(shadow.resolve("source_budget_policy.atto"),
                "define module source_budget_policy as "
                + "pub fnc valid_depth(int n): bool { return false; } end");
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import module source_budget_policy from "std/source_budget_policy.atto";
                pub routine main(): void {
                    stdio.println(source_budget_policy.valid_depth(96));
                    return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertTrue(linked.units().containsKey("@std/source_budget_policy.atto"));
        assertEquals("true", out.toString(StandardCharsets.UTF_8).trim());
    }
}
