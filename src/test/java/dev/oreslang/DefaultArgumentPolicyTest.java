package dev.oreslang;

import dev.oreslang.types.DefaultArgumentPolicy;
import dev.oreslang.types.DefaultArgumentPolicy.Annotation;
import dev.oreslang.types.DefaultArgumentPolicy.Formal;
import dev.oreslang.types.DefaultArgumentPolicy.Literal;
import dev.oreslang.types.DefaultArgumentPolicy.Lowering;
import dev.oreslang.types.DefaultArgumentPolicy.ScalarType;
import dev.oreslang.types.OptionFormalPresencePolicy;
import dev.oreslang.types.OptionFormalPresencePolicy.ActualKind;
import dev.oreslang.types.OptionFormalPresencePolicy.LanguageMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These prove only compile-time default classification. No guest-language
 * execution or LLVM/Graal lowering is inferred from passing JUnit tests.
 */
final class DefaultArgumentPolicyTest {
    private static final LanguageMode NEXT = LanguageMode.IMPLICIT_OPTION_OMISSION;

    private static Formal checked(
            OptionFormalPresencePolicy.Formal base,
            ScalarType type,
            Literal literal) {
        return Formal.checked(base, type, List.of(Annotation.defaultOf(literal)));
    }

    @Test
    void omittedPlainScalarUsesLiteralWithoutOptionWrapping() {
        var port = checked(OptionFormalPresencePolicy.Formal.value("port"),
                ScalarType.INT, Literal.integer(5432));
        var decision = DefaultArgumentPolicy.classify(port, NEXT, ActualKind.IMPLICIT_OMISSION);
        assertEquals(Lowering.DEFAULT_SCALAR, decision.lowering());
        assertEquals(Literal.integer(5432), decision.compileTimeDefault());
        assertTrue(DefaultArgumentPolicy.omittable(port, NEXT));
    }

    @Test
    void explicitlyOmittedPlainScalarAlsoUsesDefault() {
        var retry = checked(OptionFormalPresencePolicy.Formal.value("retries"),
                ScalarType.INT, Literal.integer(3));
        assertEquals(Lowering.DEFAULT_SCALAR,
                DefaultArgumentPolicy.classify(retry, NEXT, ActualKind.EXPLICIT_OMISSION).lowering());
    }

    @Test
    void omittedOptionWithDefaultIsSomeOfDefaultInsteadOfNone() {
        var label = checked(OptionFormalPresencePolicy.Formal.option("label"),
                ScalarType.STRING, Literal.string("guest"));
        var decision = DefaultArgumentPolicy.classify(label, NEXT, ActualKind.IMPLICIT_OMISSION);
        assertEquals(Lowering.DEFAULT_SOME, decision.lowering());
        assertEquals(Literal.string("guest"), decision.compileTimeDefault());
        assertEquals(Lowering.DEFAULT_SOME,
                DefaultArgumentPolicy.classify(label, NEXT, ActualKind.EXPLICIT_OMISSION).lowering());
    }

    @Test
    void omittedOptionWithoutDefaultBecomesTypedNoneNotAnOptionTypeObject() {
        var label = Formal.checked(OptionFormalPresencePolicy.Formal.option("label"),
                ScalarType.STRING, List.of());
        assertEquals(Lowering.OPTION_NONE_UNBOUND,
                DefaultArgumentPolicy.classify(label, NEXT, ActualKind.IMPLICIT_OMISSION).lowering());
        assertEquals(Lowering.OPTION_NONE_EXPLICIT_OMIT,
                DefaultArgumentPolicy.classify(label, NEXT, ActualKind.EXPLICIT_OMISSION).lowering());
    }

    @Test
    void suppliedTypedNoneOverridesNonNoneDefault() {
        var label = checked(OptionFormalPresencePolicy.Formal.option("label"),
                ScalarType.STRING, Literal.string("guest"));
        var decision = DefaultArgumentPolicy.classify(label, NEXT, ActualKind.TYPED_OPTION);
        assertEquals(Lowering.PASS_SUPPLIED_OPTION, decision.lowering());
        assertNull(decision.compileTimeDefault());
    }

    @Test
    void suppliedUnderlyingValueOverridesDefaultAndGetsSome() {
        var label = checked(OptionFormalPresencePolicy.Formal.option("label"),
                ScalarType.STRING, Literal.string("guest"));
        assertEquals(Lowering.SOME_OF_SUPPLIED_VALUE,
                DefaultArgumentPolicy.classify(label, NEXT, ActualKind.UNDERLYING_VALUE).lowering());
        assertEquals(Lowering.SOME_OF_SUPPLIED_VALUE,
                DefaultArgumentPolicy.classify(label, LanguageMode.LEGACY,
                        ActualKind.UNDERLYING_VALUE).lowering());
    }

    @Test
    void suppliedOrdinaryScalarOverridesScalarDefaultWithoutOption() {
        var retries = checked(OptionFormalPresencePolicy.Formal.value("retries"),
                ScalarType.INT, Literal.integer(3));
        assertEquals(Lowering.PASS_SUPPLIED_VALUE,
                DefaultArgumentPolicy.classify(retries, NEXT, ActualKind.UNDERLYING_VALUE).lowering());
        assertThrows(IllegalArgumentException.class,
                () -> DefaultArgumentPolicy.classify(retries, NEXT, ActualKind.TYPED_OPTION));
    }

    @Test
    void explicitDefaultNoneIsAllowedOnlyOnOptionParameters() {
        var label = checked(OptionFormalPresencePolicy.Formal.option("label"),
                ScalarType.STRING, Literal.none());
        assertEquals(Lowering.DEFAULT_NONE,
                DefaultArgumentPolicy.classify(label, NEXT, ActualKind.IMPLICIT_OMISSION).lowering());
        assertThrows(IllegalArgumentException.class,
                () -> checked(OptionFormalPresencePolicy.Formal.value("label"),
                        ScalarType.STRING, Literal.none()));
    }

    @Test
    void requiredOptionCannotHaveDefaultOrBeOmitted() {
        assertThrows(IllegalArgumentException.class,
                () -> checked(OptionFormalPresencePolicy.Formal.requiredOption("token"),
                        ScalarType.STRING, Literal.string("anonymous")));
        var required = Formal.checked(OptionFormalPresencePolicy.Formal.requiredOption("token"),
                ScalarType.STRING, List.of());
        assertFalse(DefaultArgumentPolicy.omittable(required, NEXT));
        assertThrows(IllegalArgumentException.class,
                () -> DefaultArgumentPolicy.classify(required, NEXT, ActualKind.IMPLICIT_OMISSION));
        assertEquals(Lowering.PASS_SUPPLIED_OPTION,
                DefaultArgumentPolicy.classify(required, NEXT, ActualKind.TYPED_OPTION).lowering());
    }

    @Test
    void defaultCreatesOmittabilityOnLegacyRequiredScalarWithoutChangingItsType() {
        var scalar = checked(OptionFormalPresencePolicy.Formal.value("retry"),
                ScalarType.INT, Literal.integer(4));
        assertTrue(DefaultArgumentPolicy.omittable(scalar, LanguageMode.LEGACY));
        assertEquals(Lowering.DEFAULT_SCALAR, DefaultArgumentPolicy.classify(
                scalar, LanguageMode.LEGACY, ActualKind.IMPLICIT_OMISSION).lowering());
    }

    @Test
    void defaultsMustHaveVerifiedExactScalarTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> checked(OptionFormalPresencePolicy.Formal.value("retry"),
                        ScalarType.INT, Literal.string("3")));
        assertThrows(IllegalArgumentException.class,
                () -> checked(OptionFormalPresencePolicy.Formal.option("ok"),
                        ScalarType.BOOL, Literal.integer(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Literal(DefaultArgumentPolicy.LiteralKind.NONE, "None"));
        assertThrows(IllegalArgumentException.class,
                () -> new Literal(DefaultArgumentPolicy.LiteralKind.INT, "7"));
    }

    @Test
    void duplicateMalformedOrAngleBracketAnnotationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DefaultArgumentPolicy.extractDefault(
                List.of(Annotation.defaultOf(Literal.integer(1)),
                        Annotation.defaultOf(Literal.integer(2)))));
        assertThrows(IllegalArgumentException.class, () -> DefaultArgumentPolicy.extractDefault(
                List.of(new Annotation("Default", List.of(), true))));
        assertThrows(IllegalArgumentException.class, () -> DefaultArgumentPolicy.extractDefault(
                List.of(new Annotation("Default", List.of(Literal.integer(3)), false))));
    }

    @Test
    void unrelatedAnnotationsDoNotBecomeDefaults() {
        assertNull(DefaultArgumentPolicy.extractDefault(
                List.of(new Annotation("Ret", List.of(), true))));
        assertEquals(Literal.bool(false), DefaultArgumentPolicy.extractDefault(
                List.of(new Annotation("Other", List.of(), true),
                        Annotation.defaultOf(Literal.bool(false)))));
    }

    @Test
    void defaultOnVariadicOrBorrowedFormalIsRejected() {
        var rest = new OptionFormalPresencePolicy.Formal(
                "rest", OptionFormalPresencePolicy.TypeKind.VALUE,
                OptionFormalPresencePolicy.Presence.INFER_FROM_TYPE,
                true, false, false);
        var borrowed = new OptionFormalPresencePolicy.Formal(
                "name", OptionFormalPresencePolicy.TypeKind.INTRINSIC_OPTION,
                OptionFormalPresencePolicy.Presence.INFER_FROM_TYPE,
                false, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> checked(rest, ScalarType.STRING, Literal.string("x")));
        assertThrows(IllegalArgumentException.class,
                () -> checked(borrowed, ScalarType.STRING, Literal.string("x")));
    }

    @Test
    void nestedOptionRejectsAmbiguousInnerValueDefault() {
        var nested = new OptionFormalPresencePolicy.Formal(
                "value", OptionFormalPresencePolicy.TypeKind.INTRINSIC_OPTION,
                OptionFormalPresencePolicy.Presence.INFER_FROM_TYPE,
                false, false, true);
        assertThrows(IllegalArgumentException.class,
                () -> checked(nested, ScalarType.STRING, Literal.string("inner")));
        var outerNone = checked(nested, ScalarType.STRING, Literal.none());
        assertEquals(Lowering.DEFAULT_NONE,
                DefaultArgumentPolicy.classify(outerNone, NEXT, ActualKind.IMPLICIT_OMISSION).lowering());
    }

    @Test
    void defaultWitnessCannotBeForgedAsProvidedValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultArgumentPolicy.Decision(Lowering.DEFAULT_SCALAR, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultArgumentPolicy.Decision(Lowering.PASS_SUPPLIED_VALUE,
                        Literal.integer(3)));
    }
}
