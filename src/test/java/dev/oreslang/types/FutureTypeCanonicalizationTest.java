package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.Types.*;
import static org.junit.jupiter.api.Assertions.*;

final class FutureTypeCanonicalizationTest {
    @Test
    void futureNestingIsCanonicalInTheNominalTypeConstructor() {
        Type leaf = Primitive.INT;
        Type single = new Named("Future", List.of(leaf));
        Type triple = new Named("Future", List.of(new Named("Future", List.of(single))));
        assertEquals(single, triple);
        assertEquals(single.hashCode(), triple.hashCode());
        assertTrue(Types.isAssignable(triple, single));
        assertTrue(Types.isAssignable(single, triple));
    }

    @Test
    void mixedWrappersAndOptionNestingRemainDistinct() {
        Type leaf = Primitive.INT;
        Type optional = new Named("Option", List.of(leaf));
        Type nestedOptional = new Named("Option", List.of(optional));
        assertNotEquals(optional, nestedOptional);
        assertFalse(Types.isAssignable(nestedOptional, optional));
        assertEquals(new Named("Future", List.of(optional)),
                new Named("Future", List.of(new Named("Future", List.of(optional)))));
        assertNotEquals(new Named("Future", List.of(optional)), optional);
        assertNotEquals(new Named("Future", List.of(optional)),
                new Named("Option", List.of(new Named("Future", List.of(leaf)))));
    }
}
