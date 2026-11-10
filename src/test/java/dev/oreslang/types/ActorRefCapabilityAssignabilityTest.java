package dev.oreslang.types;

import dev.oreslang.types.Types.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRefCapabilityAssignabilityTest {
    private static final Named CART = new Named("Cart", List.of());
    private static final Named OTHER = new Named("Other", List.of());
    private static final Named CART_REF = new Named("ActorRef", List.of(CART));
    private static final Named OTHER_REF = new Named("ActorRef", List.of(OTHER));

    @Test
    void actorRefsStayNominalAndCannotBeConjuredFromUnknown() {
        assertTrue(Types.isAssignable(CART_REF, CART_REF));
        assertFalse(Types.isAssignable(OTHER_REF, CART_REF));
        assertFalse(Types.isAssignable(Unknown.INSTANCE, CART_REF));
        assertFalse(Types.isAssignable(new Generic("T"), CART_REF));
        // Ordinary data inference remains unchanged.
        assertTrue(Types.isAssignable(Unknown.INSTANCE, Primitive.INT));
    }

    @Test
    void genericWrappersCannotEraseActorReferenceProof() {
        Named expected = new Named("Option", List.of(CART_REF));
        Named ambiguous = new Named("Option", List.of(new Generic("T")));
        assertFalse(Types.isAssignable(ambiguous, expected));
        assertFalse(Types.isAssignable(
                new ListType(Unknown.INSTANCE), new ListType(CART_REF)));
        assertFalse(Types.isAssignable(
                new Named("Result", List.of(Unknown.INSTANCE, Primitive.STRING)),
                new Named("Result", List.of(CART_REF, Primitive.STRING))));
        assertTrue(Types.isAssignable(expected, expected));
    }

    @Test
    void readonlyRecordsAndFunctionResultsCannotForgeActorRefs() {
        Record expected = new Record(
                Map.of("cart", CART_REF), true, AggregateKind.RECORD);
        Record uncertain = new Record(
                Map.of("cart", Unknown.INSTANCE), true, AggregateKind.RECORD);
        assertFalse(Types.isAssignable(uncertain, expected));
        assertTrue(Types.isAssignable(expected, expected));
        assertFalse(Types.isAssignable(
                new Function(List.of(), Unknown.INSTANCE),
                new Function(List.of(), CART_REF)));
    }
}
