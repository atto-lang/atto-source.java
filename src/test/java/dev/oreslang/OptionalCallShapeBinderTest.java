package dev.oreslang;

import dev.oreslang.types.OptionalCallShapeBinder;
import dev.oreslang.types.OptionalCallShapeBinder.Argument;
import dev.oreslang.types.OptionalCallShapeBinder.Parameter;
import dev.oreslang.types.OptionalCallShapeBinder.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pre-integration call-plan invariants. Passing these tests does not mean
 * the parser or interpreter currently accepts named/optional value syntax.
 */
final class OptionalCallShapeBinderTest {
    private static final List<Parameter> FOO = List.of(
            Parameter.required("a"),
            Parameter.optional("b"),
            Parameter.required("c"));

    @Test
    void fullPositionalInvocationHasIdentityPermutation() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positional(), Argument.positional()));
        assertEquals(List.of(0, 1, 2), plan.sourceIndexByParameter());
        assertEquals(3, plan.declaredArity());
    }

    @Test
    void optionalInteriorSlotCanBeOmittedByName() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("a"), Argument.named("c")));
        assertEquals(List.of(0, -1, 1), plan.sourceIndexByParameter());
        assertTrue(plan.omitted(1));
        assertFalse(plan.omitted(0));
        assertEquals(1, plan.sourceIndex(2));
    }

    @Test
    void prefixPositionalThenNamedIsLegal() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.named("c")));
        assertEquals(List.of(0, -1, 1), plan.sourceIndexByParameter());
    }

    @Test
    void namedArgumentsReorderOnlyBindingsNotSourceExpressions() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("c"), Argument.named("a"), Argument.named("b")));
        assertEquals(List.of(1, 2, 0), plan.sourceIndexByParameter());
        // Expression 0 must still execute first, regardless of destination slot.
    }

    @Test
    void trailingOptionalParametersCanBeOmittedPositionally() {
        Plan plan = OptionalCallShapeBinder.bind(List.of(
                Parameter.required("a"), Parameter.optional("b"), Parameter.optional("c")),
                List.of(Argument.positional()));
        assertEquals(List.of(0, -1, -1), plan.sourceIndexByParameter());
    }

    @Test
    void optionalMiddleDoesNotShiftPositionalSlots() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positional(), Argument.positional())));
        assertTrue(error.getMessage().contains("missing required argument 'c'"));
    }

    @Test
    void rejectsUnknownAndCaseMismatchedLabels() {
        for (String label : List.of("unknown", "A")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallShapeBinder.bind(FOO, List.of(
                            Argument.named("a"), Argument.named(label), Argument.named("c"))));
            assertTrue(error.getMessage().contains("unknown named argument"), label);
        }
    }

    @Test
    void rejectsDuplicateNamedAndPositionalNamedCollisions() {
        for (List<Argument> call : List.of(
                List.of(Argument.named("a"), Argument.named("a"), Argument.named("c")),
                List.of(Argument.positional(), Argument.named("a"), Argument.named("c")))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallShapeBinder.bind(FOO, call));
            assertTrue(error.getMessage().contains("more than once"));
        }
    }

    @Test
    void rejectsPositionalAfterNamedAndMissingRequiredArguments() {
        IllegalArgumentException positional = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.named("a"), Argument.positional(), Argument.named("c"))));
        assertTrue(positional.getMessage().contains("positional argument after named"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(Argument.named("a"))));
        assertTrue(missing.getMessage().contains("missing required argument 'c'"));
    }

    @Test
    void rejectsTooManyArgumentsAndDuplicateFormalNames() {
        IllegalArgumentException excess = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positional(), Argument.positional(),
                        Argument.positional(), Argument.positional())));
        assertTrue(excess.getMessage().contains("too many positional"));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(List.of(
                        Parameter.required("x"), Parameter.optional("x")), List.of()));
        assertTrue(duplicate.getMessage().contains("duplicate parameter name"));
    }

    @Test
    void equalArityDeclarationsCannotBeDisambiguatedByLabels() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("left")),
                        List.of(Parameter.required("right"))),
                        List.of(Argument.named("left"))));
        assertTrue(error.getMessage().contains("same-arity overloads"));
    }

    @Test
    void optionalArgumentsCannotOverrideOrDisambiguateByParameterType() {
        // foo(int a) and foo(int a, bool? b) are structurally ambiguous for foo(3).
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("a")),
                        List.of(Parameter.required("a"), Parameter.optional("b"))),
                        List.of(Argument.positional())));
        assertTrue(error.getMessage().contains("overlapping optional supplied-arity intervals"));
    }

    @Test
    void fullDeclaredArityIsPreservedAcrossUniqueOverloads() {
        OptionalCallShapeBinder.Selection selected = OptionalCallShapeBinder.bindUnique(
                List.of(
                        List.of(Parameter.required("a")),
                        FOO),
                List.of(Argument.named("a"), Argument.named("c")));
        assertEquals(1, selected.signatureIndex());
        assertEquals(3, selected.plan().declaredArity());
        assertEquals(List.of(0, -1, 1), selected.plan().sourceIndexByParameter());
    }

    @Test
    void overloadedCallWithNoMatchingShapeFailsClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("x")),
                        FOO),
                        List.of(Argument.named("a"))));
        assertTrue(error.getMessage().contains("no declared callable"));
    }

    @Test
    void distinctOptionalNamesStillMakeUnlabeledOmissionAmbiguous() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("a"), Parameter.optional("b")),
                        List.of(Parameter.required("a"), Parameter.optional("c"))),
                        List.of(Argument.named("a"))));
        assertTrue(error.getMessage().contains("overlapping optional supplied-arity intervals"));
    }

    @Test
    void conservativeArityOverlapCheckRejectsEvenDisjointNames() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                        List.of(
                                List.of(Parameter.required("left"), Parameter.optional("x")),
                                List.of(Parameter.required("right")))));
        assertTrue(error.getMessage().contains("overlapping optional supplied-arity intervals"));
    }

    @Test
    void whollyNonOptionalDifferentArityOverloadsStayAvailable() {
        assertDoesNotThrow(() -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                List.of(
                        List.of(Parameter.required("a")),
                        List.of(Parameter.required("a"), Parameter.required("b")))));
    }

    @Test
    void malformedOverloadCannotBeQuietlySkippedByCandidateFiltering() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("a")),
                        List.of(Parameter.required("x"), Parameter.optional("x"))),
                        List.of(Argument.named("a"))));
        assertTrue(error.getMessage().contains("duplicate parameter name"));
    }

    @Test
    void forgedCallPlansCannotDuplicateOrSkipSourceEvaluations() {
        for (List<Integer> invalid : List.of(
                List.of(0, 0), List.of(1, -1), List.of(-2), List.of(0, 3))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Plan(invalid), invalid.toString());
        }
        assertEquals(List.of(1, -1, 0),
                new Plan(List.of(1, -1, 0)).sourceIndexByParameter());
    }

    @Test
    void emptyCallAndConstructorReceiverErasureAreSimpleArityCases() {
        assertEquals(List.of(),
                OptionalCallShapeBinder.bind(List.of(), List.of()).sourceIndexByParameter());
        // The compiler omits the construction-scoped self binder before
        // supplying constructor user-value parameters to this utility.
        assertEquals(List.of(-1, 0),
                OptionalCallShapeBinder.bind(List.of(
                        Parameter.optional("caption"), Parameter.required("id")),
                        List.of(Argument.named("id"))).sourceIndexByParameter());
    }

    @Test
    void explicitOmitConsumesFixedSlotButDoesNotEvaluateAnArgumentExpression() {
        Plan plan = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positionalOmission(), Argument.positional()));
        assertEquals(List.of(0, -1, 1), plan.sourceIndexByParameter());
        assertEquals(List.of(1), plan.explicitlyOmittedParameterIndices());
        assertTrue(plan.explicitlyOmitted(1));
        assertTrue(plan.omitted(1));
        assertEquals(2, plan.suppliedArgumentCount());

        Plan named = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.named("c"), Argument.namedOmission("b"), Argument.named("a")));
        assertEquals(List.of(1, -1, 0), named.sourceIndexByParameter());
        assertTrue(named.explicitlyOmitted(1));
        assertEquals(2, named.suppliedArgumentCount());
    }

    @Test
    void omissionAndExplicitOptionNoneHaveDistinctCompileTimePlans() {
        Plan explicitOmit = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positionalOmission(), Argument.positional()));
        Plan explicitNone = OptionalCallShapeBinder.bind(FOO, List.of(
                Argument.positional(), Argument.positional(), Argument.positional()));
        assertTrue(explicitOmit.explicitlyOmitted(1));
        assertFalse(explicitNone.explicitlyOmitted(1));
        assertEquals(2, explicitOmit.suppliedArgumentCount());
        assertEquals(3, explicitNone.suppliedArgumentCount());
        assertEquals(OptionalCallShapeBinder.OptionalAdaptation.PASS_OPTION,
                OptionalCallShapeBinder.optionalAdaptation(Parameter.optional("b"), false,
                        OptionalCallShapeBinder.SuppliedForm.NONE_LITERAL));
    }

    @Test
    void omissionInRequiredOrRestOrDuplicateParameterFailsClosed() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positionalOmission(), Argument.positional(), Argument.positional())))
                .getMessage().contains("required parameter"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(List.of(Parameter.variadic("items")),
                        List.of(Argument.positionalOmission())))
                .getMessage().contains("rest tail"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(FOO, List.of(
                        Argument.positional(), Argument.positionalOmission(),
                        Argument.named("b"), Argument.named("c"))))
                .getMessage().contains("more than once"));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(), List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1, 1), -1, List.of(), List.of(1, 1)));
    }

    @Test
    void variadicTailCollectsAllRemainingPositionalExpressions() {
        List<Parameter> signature = List.of(
                Parameter.required("head"), Parameter.variadic("items"));
        Plan plan = OptionalCallShapeBinder.bind(signature, List.of(
                Argument.positional(), Argument.positional(), Argument.positional()));
        assertEquals(List.of(0, -1), plan.sourceIndexByParameter());
        assertEquals(1, plan.variadicParameterIndex());
        assertEquals(List.of(1, 2), plan.variadicSourceIndices());
        assertEquals(3, plan.suppliedArgumentCount());
        assertEquals(2, plan.declaredArity());
        assertFalse(plan.omitted(1), "zero or more rest values is never Option.None");
        assertThrows(IllegalArgumentException.class, () -> plan.sourceIndex(1));
    }

    @Test
    void emptyVariadicTailIsEmptyCollectionRatherThanOmittedOptional() {
        Plan plan = OptionalCallShapeBinder.bind(List.of(
                Parameter.required("id"), Parameter.variadic("items")),
                List.of(Argument.named("id")));
        assertEquals(List.of(), plan.variadicSourceIndices());
        assertFalse(plan.omitted(1));
        assertTrue(OptionalCallShapeBinder.bind(
                List.of(Parameter.variadic("items")), List.of()).variadicSourceIndices().isEmpty());
    }

    @Test
    void optionalBeforeVariadicMayBeOmittedOnlyByNamedCall() {
        List<Parameter> signature = List.of(
                Parameter.optional("prefix"), Parameter.variadic("tail"));
        Plan named = OptionalCallShapeBinder.bind(signature, List.of());
        assertTrue(named.omitted(0));
        assertFalse(named.omitted(1));
        assertEquals(List.of(), named.variadicSourceIndices());
        Plan positional = OptionalCallShapeBinder.bind(signature, List.of(
                Argument.positional(), Argument.positional()));
        assertFalse(positional.omitted(0));
        assertEquals(List.of(1), positional.variadicSourceIndices());
    }

    @Test
    void finalVariadicRequirementAndNamedRestWithoutCollectionProofFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new Parameter("rest", true, true));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(List.of(
                        Parameter.variadic("items"), Parameter.required("later")), List.of()))
                .getMessage().contains("must be final"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bind(List.of(
                        Parameter.required("a"), Parameter.variadic("items")),
                        List.of(Argument.named("a"), Argument.named("items"))))
                .getMessage().contains("named rest"));
    }

    @Test
    void variadicPlansCannotForgeDuplicateOrSkippedEvaluations() {
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), 1, List.of(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), 1, List.of(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), 0, List.of(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, 1), 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Plan(List.of(0, -1), -1, List.of(1)));
    }

    @Test
    void variadicOverloadsCollideWithAnyIntersectingFixedOrOptionalArity() {
        List<Parameter> rest = List.of(
                Parameter.required("a"), Parameter.variadic("tail"));
        for (List<Parameter> other : List.of(
                List.of(Parameter.required("a")),
                List.of(Parameter.required("a"), Parameter.required("b")),
                List.of(Parameter.required("a"), Parameter.optional("b")))) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                            List.of(rest, other))).getMessage().contains("overlapping"));
        }
        assertDoesNotThrow(() ->
                OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                        List.of(rest, List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.requireNonOverlappingOptionalArityIntervals(
                        List.of(rest, List.of(Parameter.variadic("other")))));
    }

    @Test
    void absenceNeverMeansNullOrVoidAndMustUseTypedOption() {
        Parameter optional = Parameter.optional("b");
        assertEquals(OptionalCallShapeBinder.OptionalAdaptation.INJECT_NONE,
                OptionalCallShapeBinder.optionalAdaptation(optional, true, null));
        assertEquals(OptionalCallShapeBinder.OptionalAdaptation.WRAP_SOME,
                OptionalCallShapeBinder.optionalAdaptation(optional, false,
                        OptionalCallShapeBinder.SuppliedForm.PLAIN_VALUE));
        for (OptionalCallShapeBinder.SuppliedForm form : List.of(
                OptionalCallShapeBinder.SuppliedForm.OPTION_VALUE,
                OptionalCallShapeBinder.SuppliedForm.NONE_LITERAL)) {
            assertEquals(OptionalCallShapeBinder.OptionalAdaptation.PASS_OPTION,
                    OptionalCallShapeBinder.optionalAdaptation(optional, false, form));
        }
        for (OptionalCallShapeBinder.SuppliedForm form : List.of(
                OptionalCallShapeBinder.SuppliedForm.NULL_LITERAL,
                OptionalCallShapeBinder.SuppliedForm.VOID_EXPRESSION)) {
            assertThrows(IllegalArgumentException.class,
                    () -> OptionalCallShapeBinder.optionalAdaptation(optional, false, form));
        }
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.optionalAdaptation(optional, true,
                        OptionalCallShapeBinder.SuppliedForm.NONE_LITERAL));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.optionalAdaptation(Parameter.variadic("items"), true, null));
    }
}
