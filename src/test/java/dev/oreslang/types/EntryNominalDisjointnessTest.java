package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.oreslang.types.Types.Primitive.INT;
import static org.junit.jupiter.api.Assertions.*;

final class EntryNominalDisjointnessTest {
    private static Types.Named entry() {
        return new Types.Named("Entry", List.of(INT, INT));
    }

    @Test
    void entryIsProvablyDisjointFromStructuralKeyValueRecord() {
        Types.Record record = new Types.Record(
                Map.of("key", INT, "value", INT),
                true,
                Types.AggregateKind.RECORD);

        assertTrue(Types.areProvablyDisjoint(entry(), record));
        assertTrue(Types.areProvablyDisjoint(record, entry()));
        assertFalse(Types.isAssignable(record, entry()));
        assertFalse(Types.isAssignable(entry(), record));
    }

    @Test
    void entryIsProvablyDisjointFromTupleAndSequenceProducts() {
        Types.Tuple pair = new Types.Tuple(List.of(INT, INT));
        Types.ListType sequence = new Types.ListType(INT);

        assertTrue(Types.areProvablyDisjoint(entry(), pair));
        assertTrue(Types.areProvablyDisjoint(pair, entry()));
        assertTrue(Types.areProvablyDisjoint(entry(), sequence));
        assertTrue(Types.areProvablyDisjoint(sequence, entry()));
    }

    @Test
    void entryRemainsCompatibleOnlyWithSameNominalEntryDomain() {
        Types.Named same = new Types.Named("Entry", List.of(INT, INT));
        Types.Named other = new Types.Named("Option", List.of(INT));

        assertFalse(Types.areProvablyDisjoint(entry(), same));
        assertTrue(Types.areProvablyDisjoint(entry(), other));
        assertTrue(Types.areProvablyDisjoint(other, entry()));
        assertTrue(Types.isAssignable(entry(), same));
    }

    @Test
    void unresolvedValuesDoNotBecomeFalseNegativeTypeProofs() {
        assertFalse(Types.areProvablyDisjoint(entry(), Types.Unknown.INSTANCE));
        assertFalse(Types.areProvablyDisjoint(
                entry(), new Types.Generic("T")));
    }
}
