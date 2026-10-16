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
}
