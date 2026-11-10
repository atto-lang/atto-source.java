package dev.oreslang;

import dev.oreslang.compiler.dop.PhysicalLayoutV1;
import dev.oreslang.compiler.dop.PhysicalLayoutV1.Kind;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Host-compiled typed physical-buffer tests; not an AttoLang guest witness. */
final class PhysicalLayoutV1Test {
    private static final List<PhysicalLayoutV1.Field> FIELDS = List.of(
            new PhysicalLayoutV1.Field("position", 4, 4),
            new PhysicalLayoutV1.Field("velocity", 8, 8),
            new PhysicalLayoutV1.Field("flags", 2, 2));

    private static PhysicalLayoutV1.Plan layout(Kind kind) {
        return PhysicalLayoutV1.lower(kind, 5, FIELDS, kind == Kind.AOSOA ? 4 : 1,
                kind == Kind.SOAOS ? List.of(List.of(0, 2), List.of(1)) : List.of(),
                Long.MAX_VALUE);
    }

    @Test
    void addressVectorsMatchSharedHeterogeneousLayoutContract() {
        var kinds = new Kind[]{Kind.AOS, Kind.SOA, Kind.AOSOA, Kind.SOAOS};
        var physical = new long[]{120, 74, 112, 80};
        var samples = new long[][][]{
                {{0, 8, 16}, {72, 80, 88}, {96, 104, 112}},
                {{0, 24, 64}, {12, 48, 70}, {16, 56, 72}},
                {{0, 16, 48}, {12, 40, 54}, {56, 72, 104}},
                {{0, 40, 4}, {24, 64, 28}, {32, 72, 36}}
        };
        var rows = new int[]{0, 3, 4};
        for (int k = 0; k < kinds.length; k++) {
            var plan = layout(kinds[k]);
            assertEquals(physical[k], plan.physicalBytes(), kinds[k].name());
            for (int r = 0; r < rows.length; r++) {
                for (int field = 0; field < 3; field++) {
                    assertEquals(samples[k][r][field], plan.address(rows[r], field).absolute(),
                            kinds[k] + " row=" + rows[r] + " field=" + field);
                }
            }
        }
    }

    @Test
    void actualPhysicalByteBufferWritesAndReadsUseLoweredOffsets() {
        for (Kind kind : Kind.values()) {
            var plan = layout(kind);
            var buffer = ByteBuffer.allocateDirect(Math.toIntExact(plan.physicalBytes()))
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < 5; row++) {
                for (int field = 0; field < FIELDS.size(); field++) {
                    int slot = Math.toIntExact(plan.address(row, field).absolute());
                    int value = 1000 + row * 100 + field;
                    switch (FIELDS.get(field).bytes()) {
                        case 2 -> buffer.putShort(slot, (short) value);
                        case 4 -> buffer.putInt(slot, value);
                        case 8 -> buffer.putLong(slot, value);
                        default -> fail("unknown scalar width");
                    }
                }
            }
            for (int row = 0; row < 5; row++) {
                for (int field = 0; field < FIELDS.size(); field++) {
                    int slot = Math.toIntExact(plan.address(row, field).absolute());
                    long actual = switch (FIELDS.get(field).bytes()) {
                        case 2 -> buffer.getShort(slot);
                        case 4 -> buffer.getInt(slot);
                        case 8 -> buffer.getLong(slot);
                        default -> throw new AssertionError("unknown width");
                    };
                    assertEquals(1000L + row * 100L + field, actual, kind.name());
                }
            }
        }
    }

    @Test
    void allAllocatedCellByteRangesAreDistinctWithinEveryLayout() {
        for (Kind kind : Kind.values()) {
            var plan = layout(kind);
            Set<Long> occupied = new HashSet<>();
            for (int row = 0; row < 5; row++) {
                for (int field = 0; field < FIELDS.size(); field++) {
                    long address = plan.address(row, field).absolute();
                    for (int n = 0; n < FIELDS.get(field).bytes(); n++) {
                        long byteAddress = address + n;
                        assertTrue(byteAddress < plan.physicalBytes());
                        assertTrue(occupied.add(byteAddress), "overlap " + kind + "@" + byteAddress);
                    }
                }
            }
        }
    }

    @Test
    void rejectsIncompleteDuplicateAndCrossLayoutFieldPartitions() {
        for (var groups : List.of(
                List.of(List.of(0, 2)),
                List.of(List.of(0, 2), List.of(2, 1)),
                List.of(List.of(0), List.of(1)),
                List.of(List.of(0, 3), List.of(1, 2)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> PhysicalLayoutV1.lower(Kind.SOAOS, 5, FIELDS, 1, groups, 1024));
        }
        assertThrows(IllegalArgumentException.class,
                () -> PhysicalLayoutV1.lower(Kind.AOS, 5, FIELDS, 1,
                        List.of(List.of(0, 1, 2)), 1024));
        assertThrows(IllegalArgumentException.class,
                () -> PhysicalLayoutV1.lower(Kind.SOAOS, 5, FIELDS, 1, List.of(), 1024));
    }

    @Test
    void rejectsOverflowBadLanesQuotasAndOutOfBoundsAccess() {
        assertThrows(ArithmeticException.class,
                () -> PhysicalLayoutV1.lower(Kind.AOS, Long.MAX_VALUE, FIELDS,
                        1, List.of(), Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> PhysicalLayoutV1.lower(Kind.AOS, 5, FIELDS, 1, List.of(), 119));
        assertThrows(IllegalArgumentException.class,
                () -> PhysicalLayoutV1.lower(Kind.AOSOA, 5, FIELDS, 3, List.of(), 1024));
        var soa = layout(Kind.SOA);
        assertThrows(IndexOutOfBoundsException.class, () -> soa.address(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> soa.address(5, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> soa.address(0, 3));
    }
}
