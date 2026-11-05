package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Canonical Atto guest policies are source-owned; host capability gates remain in Java. */
final class CanonicalAttoHostPolicyExtractionTest {
    private static final Set<String> POLICIES = Set.of(
            "layout_policy", "region_policy", "dense_index",
            "actor_result_policy", "task_parallel_policy");
    @TempDir Path temp;

    @Test
    void fivePoliciesAreCanonicalAttoWithoutAmbientJavaOrLegacyAliases() throws Exception {
        for (String stem : POLICIES) {
            String path = "ores.std/" + stem + ".atto";
            assertTrue(CoreLibrarySources.packagedSourcePaths().contains(stem + ".atto"));
            assertEquals(stem + ".atto", CoreLibrarySources.fileName(path));
            assertEquals("@std/" + stem + ".atto", CoreLibrarySources.unitId(path));
            assertEquals(CoreLibrarySources.unitId(path),
                    CoreLibrarySources.unitId("std/" + stem));
            assertFalse(Files.exists(Path.of("stdlib", stem + ".ores")));
            assertEquals(Files.readString(Path.of("stdlib", stem + ".atto")),
                    CoreLibrarySources.source(path));
            String source = CoreLibrarySources.source(path);
            var parsed = MixedSourceUnit.parse(
                    CoreLibrarySources.unitId(path),
                    CoreLibrarySources.fileName(path), source);
            assertTrue(parsed.hasOresSource(), stem);
            assertFalse(parsed.hasJavaSource(), stem);
            assertFalse(source.contains("java:"), stem);
            assertFalse(source.contains("stdio."), stem);
            assertFalse(source.contains("network."), stem);
            assertFalse(source.contains("socket."), stem);
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.source("std/" + stem + ".ores"),
                    "new Atto policies never have an implicit legacy .ores alias");
        }
    }

    private void expectGuest(String stem, String expected) throws Exception {
        Path path = Path.of("src/test/resources/stdlib-parity/portable-java-policy",
                stem + ".atto");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(path,
                IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                stdout, stderr);
        assertTrue(linked.units().containsKey("@std/" + stem + ".atto"));
        assertEquals(expected,
                stdout.toString(StandardCharsets.UTF_8).trim().replace("\r", ""),
                () -> stderr.toString(StandardCharsets.UTF_8));
    }

    @Test
    void guestLayoutMatchesJavaHostBoundedSubset() throws Exception {
        assertEquals(24, new DataOrientedLayout.Plan(
                DataOrientedLayout.Kind.AOS, 3, 2, 4, 8, 1).byteLength());
        assertEquals(20, new DataOrientedLayout.Plan(
                DataOrientedLayout.Kind.AOS, 3, 2, 4, 8, 1).byteOffset(2, 1));
        assertEquals(32, new DataOrientedLayout.Plan(
                DataOrientedLayout.Kind.SOA, 3, 2, 4, 8, 1).byteLength());
        assertEquals(24, new DataOrientedLayout.Plan(
                DataOrientedLayout.Kind.SOA, 3, 2, 4, 8, 1).byteOffset(2, 1));
        assertEquals(64, new DataOrientedLayout.Plan(
                DataOrientedLayout.Kind.AOSOA, 5, 2, 4, 8, 4).byteLength());
        assertEquals(48, new DataOrientedLayout.Plan(
                DataOrientedLayout.Kind.AOSOA, 5, 2, 4, 8, 4).byteOffset(4, 1));
        expectGuest("layout_policy", "true\n24\n20\n32\n24\n64\n48\nfalse\ntrue");
    }

    @Test
    void guestRegionMatchesReadReadAndWriteConflictHostSemantics() throws Exception {
        var root = DopRegionSchedule.Root.trustedCreate(8, 2);
        var read = new DopRegionSchedule.Access(root, 0, 0, 5,
                DopRegionSchedule.Mode.READ, null);
        var overlappingRead = new DopRegionSchedule.Access(root, 0, 2, 6,
                DopRegionSchedule.Mode.READ, null);
        var overlappingWrite = new DopRegionSchedule.Access(root, 0, 2, 6,
                DopRegionSchedule.Mode.WRITE, null);
        assertTrue(DopRegionSchedule.independent(read, overlappingRead));
        assertFalse(DopRegionSchedule.independent(read, overlappingWrite));
        expectGuest("region_policy", "true\ntrue\nfalse\ntrue\ntrue\ntrue\nfalse");
    }

    @Test
    void guestDense32MatchesUnsignedHostBits() throws Exception {
        assertEquals(1, ManagedReferencePolicy.encodeDenseIndex32(0));
        assertEquals(4294967295L,
                Integer.toUnsignedLong(ManagedReferencePolicy.encodeDenseIndex32(4294967294L)));
        assertEquals(4294967294L,
                ManagedReferencePolicy.decodeDenseIndex32(-1).orElseThrow());
        expectGuest("dense_index", "1\n0\n4294967295\n-1\ntrue\ntrue\ntrue");
    }

    @Test
    void guestActorResultMetadataMatchesBoundAndRejectsDuplicateIds() throws Exception {
        assertEquals(65536, ActorResults.MAX_FAN_IN);
        expectGuest("actor_result_policy", "true\nfalse\ntrue\nfalse\nfalse\nfalse");
    }

    @Test
    void guestTaskParallelAdmissionIsBudgetOnly() throws Exception {
        expectGuest("task_parallel_policy", "true\n3\n1\n1\n0\n-1\nfalse");
    }

    @Test
    void compilerOwnedPolicyCannotBeShadowedByApplicationFile() throws Exception {
        Path local = Files.createDirectories(temp.resolve("std"));
        Files.writeString(local.resolve("dense_index.atto"),
                "define module dense_index as pub fnc encode32(int v): int { return 999; } end");
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import module dense_index from "std/dense_index.atto";
                pub routine main(): void {
                    stdio.println(dense_index.encode32(0).unwrap());
                    return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var linked = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertTrue(linked.units().containsKey("@std/dense_index.atto"));
        assertEquals("1", out.toString(StandardCharsets.UTF_8).trim());
    }
}
