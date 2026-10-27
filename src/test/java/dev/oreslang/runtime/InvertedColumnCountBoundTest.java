package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

final class InvertedColumnCountBoundTest {
    @Test
    void rejectsExcessColumnsAndAcceptsExactBound() {
        var kinds = new InvertedColumnStore.Kind[
                InvertedColumnStore.MAX_COLUMN_COUNT + 1];
        Arrays.fill(kinds, InvertedColumnStore.Kind.I64);
        assertThrows(IllegalArgumentException.class,
                () -> new InvertedColumnStore(kinds));
        try (var store = new InvertedColumnStore(
                Arrays.copyOf(kinds, InvertedColumnStore.MAX_COLUMN_COUNT))) {
            assertEquals(InvertedColumnStore.MAX_COLUMN_COUNT, store.fieldCount());
            assertEquals(0, store.size());
        }
    }
}
