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
    void optionalArgumentsCannotOverrideOrDisambiguateByParameterType() {
        // foo(int a) and foo(int a, bool? b) are structurally ambiguous for foo(3).
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OptionalCallShapeBinder.bindUnique(List.of(
                        List.of(Parameter.required("a")),
                        List.of(Parameter.required("a"), Parameter.optional("b"))),
                        List.of(Argument.positional())));
        assertTrue(error.getMessage().contains("ambiguous optional/named"));
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
        assertTrue(error.getMessage().contains("ambiguous optional/named"));
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
}
