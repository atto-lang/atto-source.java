package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class EntryComponentDisjointnessTest {
    @Test
    void concreteEntryComponentsProveDisjointnessButOpenComponentsDoNot() {
        Types.Named left = new Types.Named(
                "Entry",
                List.of(Types.Primitive.INT, Types.Primitive.STRING));
        Types.Named right = new Types.Named(
                "Entry",
                List.of(Types.Primitive.STRING, Types.Primitive.STRING));
        Types.Named open = new Types.Named(
                "Entry",
                List.of(Types.Unknown.INSTANCE, Types.Primitive.STRING));

        assertTrue(Types.areProvablyDisjoint(left, right));
        assertTrue(Types.areProvablyDisjoint(right, left));
        assertFalse(Types.areProvablyDisjoint(left, open));
        assertFalse(Types.areProvablyDisjoint(open, right));
    }
}
