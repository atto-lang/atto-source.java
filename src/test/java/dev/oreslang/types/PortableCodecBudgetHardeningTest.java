package dev.oreslang.types;

import dev.oreslang.types.PortableCodecRegistry.*;
import org.junit.jupiter.api.Test;
import java.util.AbstractList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class PortableCodecBudgetHardeningTest {
    @Test void rejectsOversizedIdentifierBeforeRegex() {
        String overlong = "a".repeat(1_000_000);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new Field(overlong, 0, new Scalar(ScalarKind.I64)));
        assertFalse(ex.getMessage().contains(overlong.substring(0, 512)));
        assertDoesNotThrow(() -> new Field("a".repeat(128), 0,
                new Scalar(ScalarKind.I64)));
        assertThrows(IllegalArgumentException.class,
                () -> new Field("a".repeat(129), 0, new Scalar(ScalarKind.I64)));
    }

    @Test void boundsRecordBeforeCopyingUntrustedList() {
        List<Field> hostile = new AbstractList<>() {
            @Override public Field get(int index) {
                throw new AssertionError("oversized schema list should not be traversed");
            }
            @Override public int size() { return 1_000_000; }
        };
        assertThrows(IllegalArgumentException.class, () -> new ObjectShape(hostile));
    }

    @Test void snapshotsBoundedIndicesWithoutUsingUntrustedToArrayOrIterator() {
        Field field = new Field("payload", 0, new Scalar(ScalarKind.I64));
        List<Field> deceptive = new AbstractList<>() {
            int reads;
            @Override public int size() { return ++reads == 1 ? 1 : 1_000_000; }
            @Override public Field get(int index) {
                if (index != 0) throw new AssertionError("snapshot read outside checked bound");
                return field;
            }
            @Override public Object[] toArray() {
                throw new AssertionError("hostile toArray must never run");
            }
            @Override public <T> T[] toArray(T[] target) {
                throw new AssertionError("hostile typed toArray must never run");
            }
        };
        ObjectShape shape = assertDoesNotThrow(() -> new ObjectShape(deceptive));
        assertEquals(List.of(field), shape.fields());
    }

    @Test void rejectsARecordThatShrinksDuringItsBoundedSnapshot() {
        Field field = new Field("first", 0, new Scalar(ScalarKind.I64));
        List<Field> shrinking = new AbstractList<>() {
            @Override public int size() { return 2; }
            @Override public Field get(int index) {
                if (index == 0) return field;
                throw new IndexOutOfBoundsException("record changed during snapshot");
            }
        };
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> new ObjectShape(shrinking));
        assertTrue(denied.getMessage().contains("changed during snapshot"));
    }

}
