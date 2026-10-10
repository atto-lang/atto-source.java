package dev.oreslang.types;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression suite for identity provenance at callable and captured-control-flow boundaries. */
final class IdentityReturnProvenanceTest {
    private static final Types.Type OBJECT = new Types.Named("T", List.of());

    private static Types.Function callable(int origin) {
        return new Types.Function(List.of(OBJECT, OBJECT), List.of(false, false), false, OBJECT, origin);
    }

    @Test void nominalCallableCannotBePromotedToIdentityGuarantee() {
        assertFalse(Types.isAssignable(callable(Types.Function.NO_IDENTITY), callable(0)));
    }

    @Test void identityCallableCanBeUsedWhereOnlyNominalReturnIsRequired() {
        assertTrue(Types.isAssignable(callable(0), callable(Types.Function.NO_IDENTITY)));
    }

    @Test void differentParameterIdentitySlotsAreNotInterchangeable() {
        assertFalse(Types.isAssignable(callable(0), callable(1)));
        assertFalse(Types.isAssignable(callable(1), callable(0)));
    }

    @Test void receiverIdentityCannotBeForgedByParameterIdentity() {
        assertFalse(Types.isAssignable(callable(0), callable(Types.Function.RECEIVER_IDENTITY)));
        assertFalse(Types.isAssignable(callable(Types.Function.RECEIVER_IDENTITY), callable(0)));
    }

    @Test void identicalIdentityProvenanceRemainsAssignable() {
        assertTrue(Types.isAssignable(callable(0), callable(0)));
        assertTrue(Types.isAssignable(callable(Types.Function.RECEIVER_IDENTITY),
                callable(Types.Function.RECEIVER_IDENTITY)));
    }

    @Test void invalidProvenanceIndexIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> callable(-3));
        assertThrows(IllegalArgumentException.class, () -> callable(2));
    }

    @Test void opaqueValuesCannotForgeIdentityCallablePromises() {
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, callable(0)));
        assertFalse(Types.isAssignable(new Types.Generic("U"), callable(0)));
        assertTrue(Types.isAssignable(Types.Unknown.INSTANCE, callable(Types.Function.NO_IDENTITY)));
    }

    @Test void nestedStructuralPromisesAlsoRejectUnknownOrigins() {
        Types.Record contract = new Types.Record(Map.of("relay", callable(0)));
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, contract));
        assertFalse(Types.isAssignable(new Types.Generic("U"), contract));
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE,
                new Types.Named("Option", List.of(contract))));
    }

    private static void rejectsDetached(String statements) {
        String source = """
                define class T as
                end
                fnc decorate(T mut v): v {
                  do select {
                    default: {
                """ + statements + """
                    }
                  }
                  return v;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> IdentityReturnChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("identity return rebind"), error::getMessage);
    }

    @Test void detachedSelectCannotRebindCapturedIdentity() {
        rejectsDetached("v = new T();\n");
    }

    @Test void detachedSelectNestedConditionalCannotRebindCapturedIdentity() {
        rejectsDetached("if true { v = new T(); } fi\n");
    }

    @Test void detachedSelectDeferredAssignmentCannotRebindCapturedIdentity() {
        rejectsDetached("defer (v = new T());\n");
    }

    @Test void discardedArmReturnDoesNotProveOrWeakenEnclosingFunctionReturn() {
        String source = """
                define class T as
                end
                fnc decorate(T v): v {
                  do select {
                    default: { return new T(); }
                  }
                  return v;
                }
                """;
        assertDoesNotThrow(() -> IdentityReturnChecker.check(Parser.parse(source)));
    }
}
