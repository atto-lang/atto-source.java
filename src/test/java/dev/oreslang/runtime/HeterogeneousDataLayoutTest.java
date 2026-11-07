package dev.oreslang.runtime;

import dev.oreslang.runtime.HeterogeneousDataLayout.Field;
import dev.oreslang.runtime.HeterogeneousDataLayout.Kind;
import dev.oreslang.runtime.HeterogeneousDataLayout.Plan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java/native parity for the four DOP layouts. The golden table is produced by the
 * native oreslang::dop::HeterogeneousPlan (oreslang-source.llvm,
 * tests/dop_heterogeneous_layout_golden_test.cpp) and checked in byte-for-byte in
 * both repositories; any geometry drift on either side fails here.
 */
final class HeterogeneousDataLayoutTest {
    // id:u32, x:f64, flag:bool, next:ref32 — the shared conformance field order.
    private static final List<Field> MIXED = List.of(
            new Field(4, 4), new Field(8, 8), new Field(1, 1), new Field(4, 4));
    private static final List<List<List<Integer>>> GROUPS = List.of(
            List.of(List.of(0, 2), List.of(1, 3)),
            List.of(List.of(3), List.of(2, 0, 1)));

    private static Plan plan(Kind kind, long rows, int lanes, int group) {
        return new Plan(kind, rows, MIXED, lanes,
                kind == Kind.SOAOS ? GROUPS.get(group) : List.of(), Long.MAX_VALUE, Long.MAX_VALUE);
    }

    private static List<String> golden() throws IOException {
        try (InputStream in = HeterogeneousDataLayoutTest.class
                .getResourceAsStream("/dop/heterogeneous-layout-golden-v1.txt")) {
            assertNotNull(in, "golden table missing");
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) lines.add(line);
            }
            return lines;
        }
    }

    private static String render(Plan p, int group) {
        StringBuilder out = new StringBuilder();
        out.append(p.kind()).append(' ').append(p.rows()).append(' ').append(p.lanes()).append(' ')
                .append(p.kind() == Kind.SOAOS ? group : -1).append(' ')
                .append(p.totalBytes()).append(' ').append(p.physicalBytes()).append(" S");
        for (var s : p.segments()) {
            out.append(' ').append(s.alignment()).append(',').append(s.bytes()).append(',')
                    .append(s.stride()).append(',').append(s.base());
        }
        out.append(" A");
        for (long r = 0; r < p.rows(); r++) {
            for (int f = 0; f < p.fieldCount(); f++) out.append(' ').append(p.absoluteAddress(r, f));
        }
        return out.toString();
    }

    @Test
    void everyAddressMatchesTheNativeGoldenTable() throws IOException {
        List<String> expected = golden();
        assertEquals(56, expected.size(), "golden corpus size");
        for (String line : expected) {
            String[] head = line.split(" ", 5);
            Kind kind = Kind.valueOf(head[0]);
            int group = Integer.parseInt(head[3]);
            Plan p = plan(kind, Long.parseLong(head[1]), Integer.parseInt(head[2]), Math.max(group, 0));
            assertEquals(line, render(p, group));
        }
    }

    @Test
    void sharedContractSpotValues() {
        assertEquals(32, plan(Kind.AOS, 3, 1, 0).absoluteAddress(1, 1));
        Plan soa = plan(Kind.SOA, 3, 1, 0);
        assertEquals(51, soa.totalBytes());
        assertEquals(56, soa.physicalBytes());
        assertEquals(32, soa.absoluteAddress(2, 1));
        assertEquals(144, plan(Kind.AOSOA, 5, 4, 0).physicalBytes());
        assertEquals(72, plan(Kind.AOSOA, 5, 4, 0).absoluteAddress(4, 0));
        Plan soaos = plan(Kind.SOAOS, 3, 1, 0);
        assertEquals(8, soaos.segments().get(0).stride());
        assertEquals(16, soaos.segments().get(1).stride());
        assertEquals(40, soaos.address(2, 3).byteOffset());
    }

    @Test
    void cellsAreNaturallyAlignedInBoundsAndBytewiseDisjoint() {
        for (Kind kind : Kind.values()) {
            for (int lanes : kind == Kind.AOSOA ? new int[] {1, 2, 4, 8} : new int[] {1}) {
                for (long rows : new long[] {0, 1, 2, 3, 5, 8, 17}) {
                    Plan p = plan(kind, rows, lanes, 0);
                    Set<Long> occupied = new HashSet<>();
                    for (long r = 0; r < rows; r++) {
                        for (int f = 0; f < MIXED.size(); f++) {
                            long at = p.absoluteAddress(r, f);
                            Field field = MIXED.get(f);
                            assertEquals(0, at % field.alignment(), kind + " misaligned");
                            assertTrue(at + field.bytes() <= p.physicalBytes(), kind + " escaped allocation");
                            for (int b = 0; b < field.bytes(); b++) {
                                assertTrue(occupied.add(at + b), kind + " aliased bytes");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void malformedShapesFailBeforeAllocation() {
        assertThrows(IllegalArgumentException.class, () -> new Field(3, 4));
        assertThrows(IllegalArgumentException.class, () -> new Field(8, 4));
        assertThrows(IllegalArgumentException.class, () -> new Field(4, 8192));
        assertThrows(IllegalArgumentException.class, () -> new Plan(Kind.SOA, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Plan(Kind.SOA, -1, MIXED));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.SOA, 1, 4, 0));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.AOSOA, 1, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> plan(Kind.AOSOA, 1, 2048, 0));
        assertThrows(IllegalArgumentException.class, () -> new Plan(Kind.SOAOS, 1, MIXED, 1,
                List.of(), Long.MAX_VALUE, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> new Plan(Kind.AOS, 1, MIXED, 1,
                List.of(List.of(0, 1, 2, 3)), Long.MAX_VALUE, Long.MAX_VALUE));
        for (List<List<Integer>> bad : List.<List<List<Integer>>>of(
                List.of(List.of(0, 1), List.of(1, 2, 3)),     // duplicate
                List.of(List.of(0, 1), List.of(2)),           // missing field 3
                List.of(List.of(0, 1, 2, 3), List.<Integer>of()),      // empty group
                List.of(List.of(0, 1, 2, 4)),                 // out of range
                List.of(List.of(-1, 0, 1, 2, 3)))) {          // negative
            assertThrows(IllegalArgumentException.class, () -> new Plan(Kind.SOAOS, 1, MIXED, 1,
                    bad, Long.MAX_VALUE, Long.MAX_VALUE), bad.toString());
        }
        Plan p = plan(Kind.SOA, 3, 1, 0);
        assertThrows(IndexOutOfBoundsException.class, () -> p.address(3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> p.address(0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> p.address(-1, 0));
    }

    @Test
    void quotasChargePhysicalAlignmentHolesAndOverflowIsChecked() {
        // SoA payload is 51 bytes, but the single allocation needs 56.
        assertThrows(IllegalStateException.class, () -> new Plan(Kind.SOA, 3, MIXED, 1, List.of(), Long.MAX_VALUE, 55));
        assertDoesNotThrow(() -> new Plan(Kind.SOA, 3, MIXED, 1, List.of(), Long.MAX_VALUE, 56));
        assertThrows(IllegalStateException.class, () -> new Plan(Kind.SOA, 3, MIXED, 1, List.of(), 23, Long.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> new Plan(Kind.AOS, Long.MAX_VALUE / 8, MIXED));
        assertThrows(ArithmeticException.class, () -> new Plan(Kind.SOA, Long.MAX_VALUE / 4, MIXED));
    }
}
