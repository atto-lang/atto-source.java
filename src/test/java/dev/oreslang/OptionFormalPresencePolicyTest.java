package dev.oreslang;

import dev.oreslang.types.OptionFormalPresencePolicy;
import dev.oreslang.types.OptionalCallShapeBinder;
import dev.oreslang.types.OptionalCallShapeBinder.Argument;
import java.util.List;
import dev.oreslang.types.OptionFormalPresencePolicy.ActualKind;
import dev.oreslang.types.OptionFormalPresencePolicy.Formal;
import dev.oreslang.types.OptionFormalPresencePolicy.LanguageMode;
import dev.oreslang.types.OptionFormalPresencePolicy.Lowering;
import dev.oreslang.types.OptionFormalPresencePolicy.Presence;
import dev.oreslang.types.OptionFormalPresencePolicy.TypeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OptionFormalPresencePolicyTest {
    private static final LanguageMode NEXT = LanguageMode.IMPLICIT_OPTION_OMISSION;

    @Test
    void typeLevelOptionAutomaticallyMakesFormalOmittableInNextMode() {
        assertTrue(OptionFormalPresencePolicy.omittable(Formal.option("note"), NEXT));
        assertTrue(OptionFormalPresencePolicy.omittable(Formal.questionMark("note"), NEXT));
        assertFalse(OptionFormalPresencePolicy.omittable(Formal.value("id"), NEXT));
    }

    @Test
    void legacyModeDoesNotBreakExistingRequiredOptionParameters() {
        assertFalse(OptionFormalPresencePolicy.omittable(
                Formal.option("note"), LanguageMode.LEGACY));
        assertTrue(OptionFormalPresencePolicy.omittable(
                Formal.questionMark("note"), LanguageMode.LEGACY));
        assertThrows(IllegalArgumentException.class, () -> OptionFormalPresencePolicy.classify(
                Formal.option("note"), LanguageMode.LEGACY, ActualKind.IMPLICIT_OMISSION));
    }

    @Test
    void explicitRequiredOptionNeedsAProvidedValue() {
        Formal parameter = Formal.requiredOption("note");
        assertFalse(OptionFormalPresencePolicy.omittable(parameter, NEXT));
        assertEquals(Lowering.PASS_OPTION, OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.TYPED_OPTION));
        assertThrows(IllegalArgumentException.class, () -> OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.IMPLICIT_OMISSION));
        assertThrows(IllegalArgumentException.class, () -> OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.EXPLICIT_OMISSION));
        assertThrows(IllegalArgumentException.class, () -> OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.UNDERLYING_VALUE));
    }

    @Test
    void implicitOmissionAndExplicitNoneProduceSameOptionValueButDistinctPlanOperations() {
        Formal parameter = Formal.option("note");
        assertEquals(Lowering.NONE_UNBOUND, OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.IMPLICIT_OMISSION));
        assertEquals(Lowering.NONE_EXPLICIT_OMIT, OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.EXPLICIT_OMISSION));
        assertEquals(Lowering.PASS_OPTION, OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.TYPED_OPTION));
        assertEquals(Lowering.SOME_OF_VALUE, OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.UNDERLYING_VALUE));
    }

    @Test
    void questionMarkIsSyntacticSugarForOptionalTypeAtCallBoundary() {
        for (ActualKind actual : ActualKind.values()) {
            assertEquals(OptionFormalPresencePolicy.classify(Formal.questionMark("n"), NEXT, actual),
                    OptionFormalPresencePolicy.classify(Formal.option("n"), NEXT, actual));
        }
    }

    @Test
    void ordinaryRequiredValuesDoNotAcceptOmissionOrOption() {
        Formal parameter = Formal.value("id");
        assertEquals(Lowering.PASS_REQUIRED_VALUE, OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.UNDERLYING_VALUE));
        assertThrows(IllegalArgumentException.class, () -> OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.IMPLICIT_OMISSION));
        assertThrows(IllegalArgumentException.class, () -> OptionFormalPresencePolicy.classify(
                parameter, NEXT, ActualKind.TYPED_OPTION));
    }

    @Test
    void doubleOptionalAndRequiredQuestionMarkAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Formal(
                "note", TypeKind.QUESTION_MARK_SUGAR, Presence.REQUIRED, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new Formal(
                "notes", TypeKind.QUESTION_MARK_SUGAR, Presence.INFER_FROM_TYPE,
                true, false, false));
    }

    @Test
    void mutableAndBorrowedOptionalParametersRemainFailClosed() {
        Formal mutable = new Formal(
                "note", TypeKind.INTRINSIC_OPTION, Presence.INFER_FROM_TYPE,
                false, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> OptionFormalPresencePolicy.omittable(mutable, NEXT));
    }

    @Test
    void nestedOptionDoesNotPerformAmbiguousImplicitInnerValueLifting() {
        Formal nested = new Formal(
                "value", TypeKind.INTRINSIC_OPTION, Presence.INFER_FROM_TYPE,
                false, false, true);
        assertEquals(Lowering.NONE_UNBOUND, OptionFormalPresencePolicy.classify(
                nested, NEXT, ActualKind.IMPLICIT_OMISSION));
        assertEquals(Lowering.PASS_OPTION, OptionFormalPresencePolicy.classify(
                nested, NEXT, ActualKind.TYPED_OPTION));
        assertThrows(IllegalArgumentException.class, () -> OptionFormalPresencePolicy.classify(
                nested, NEXT, ActualKind.UNDERLYING_VALUE));
    }

    @Test
    void provenIntrinsicOptionFormalBindsInteriorOmissionAndRetainsFullAbiArity() {
        List<OptionalCallShapeBinder.Parameter> formals =
                OptionalCallShapeBinder.provenOptionParameterShapes(
                        List.of(Formal.value("a"), Formal.option("b"), Formal.value("c")), NEXT);
        var plan = OptionalCallShapeBinder.bind(formals,
                List.of(Argument.named("c"), Argument.named("a")));
        assertEquals(List.of(1, -1, 0), plan.sourceIndexByParameter());
        assertEquals(3, plan.declaredArity());
        assertEquals(2, plan.suppliedArgumentCount());
    }

    @Test
    void requiredOptionRestoresPresenceChecksAndLegacyCompatibility() {
        List<Formal> verified = List.of(
                Formal.value("a"), Formal.requiredOption("b"), Formal.value("c"));
        var required = OptionalCallShapeBinder.provenOptionParameterShapes(verified, NEXT);
        assertThrows(IllegalArgumentException.class, () -> OptionalCallShapeBinder.bind(
                required, List.of(Argument.named("a"), Argument.named("c"))));

        var legacy = OptionalCallShapeBinder.provenOptionParameterShapes(
                List.of(Formal.value("a"), Formal.option("b"), Formal.value("c")),
                LanguageMode.LEGACY);
        assertThrows(IllegalArgumentException.class, () -> OptionalCallShapeBinder.bind(
                legacy, List.of(Argument.named("a"), Argument.named("c"))));
    }

    @Test
    void inferredOptionRequiresCollisionRejectionAcrossEffectiveArities() {
        var older = OptionalCallShapeBinder.provenOptionParameterShapes(
                List.of(Formal.value("id")), NEXT);
        var newer = OptionalCallShapeBinder.provenOptionParameterShapes(
                List.of(Formal.value("id"), Formal.option("label")), NEXT);
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                        List.of(older, newer)));
    }

    @Test
    void provenOptionVariadicDoesNotInjectAbsentOption() {
        var fixedAndRest = OptionalCallShapeBinder.provenOptionParameterShapes(
                List.of(Formal.value("id"), new Formal(
                        "options", TypeKind.INTRINSIC_OPTION, Presence.INFER_FROM_TYPE,
                        true, false, false)), NEXT);
        assertTrue(fixedAndRest.getLast().variadic());
        assertFalse(fixedAndRest.getLast().optional());
        var plan = OptionalCallShapeBinder.bind(
                fixedAndRest, List.of(Argument.positional()));
        assertTrue(plan.variadicSourceIndices().isEmpty());
    }

    @Test
    void variadicOptionElementsAreNotAnOptionalRestPack() {
        Formal rest = new Formal(
                "items", TypeKind.INTRINSIC_OPTION, Presence.INFER_FROM_TYPE,
                true, false, false);
        assertFalse(OptionFormalPresencePolicy.omittable(rest, NEXT));
        assertThrows(IllegalArgumentException.class, () -> OptionFormalPresencePolicy.classify(
                rest, NEXT, ActualKind.IMPLICIT_OMISSION));
    }
}
