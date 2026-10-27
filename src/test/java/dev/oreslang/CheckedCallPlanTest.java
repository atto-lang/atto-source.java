package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.CheckedCallPlan;
import dev.oreslang.types.FixedProductCallPlan;
import dev.oreslang.types.OptionalCallPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.CheckedCallPlan.InputSlot.named;
import static dev.oreslang.types.CheckedCallPlan.InputSlot.namedProjection;
import static dev.oreslang.types.CheckedCallPlan.InputSlot.positional;
import static dev.oreslang.types.CheckedCallPlan.InputSlot.positionalProjection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CheckedCallPlanTest {
    @Test
    void requiredPlanPreservesOneLexicalSourceToAbiMapping() {
        CheckedCallPlan plan = CheckedCallPlan.required(
                List.of("a", "b", "c"),
                List.of(
                        positional(0),
                        namedProjection("c", 1, 0),
                        namedProjection("b", 1, 1)));

        assertEquals(List.of(0, 2, 1), plan.abiToSource());
        assertEquals(
                List.of(0, 2, 1),
                plan.sourceOrder().stream()
                        .map(CheckedCallPlan.BoundSlot::parameterIndex)
                        .toList());
        assertEquals(
                List.of(0, 1, 1),
                plan.sourceOrder().stream()
                        .map(CheckedCallPlan.BoundSlot::callItemIndex)
                        .toList());
        assertEquals(
                List.of(-1, 0, 1),
                plan.sourceOrder().stream()
                        .map(CheckedCallPlan.BoundSlot::projectionIndex)
                        .toList());
        assertEquals(0, plan.omittedCount());
        assertFalse(plan.isOmitted(1));
    }

    @Test
    void optionalPlanRepresentsAbsenceWithoutInventingSourceValues() {
        CheckedCallPlan plan = CheckedCallPlan.bind(
                List.of("a", "b", "c"),
                List.of(false, true, false),
                List.of(
                        positional(0),
                        named("c", 1)));

        assertEquals(List.of(0, -1, 1), plan.abiToSource());
        assertEquals(2, plan.suppliedCount());
        assertEquals(1, plan.omittedCount());
        assertTrue(plan.isOmitted(1));
        assertEquals(0, plan.parameterForSource(0));
        assertEquals(2, plan.parameterForSource(1));
    }

    @Test
    void publicConstructorRejectsLabelFormalAndPresenceForgery() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 1, "a", 0, -1),
                                new CheckedCallPlan.BoundSlot(
                                        1, 0, "b", 1, -1)),
                        List.of(1, 0)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 0, null, 0, -1)),
                        List.of(0, -1)));
    }

    @Test
    void publicConstructorRejectsMixedOrReorderedProvenance() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 0, null, 0, -1),
                                new CheckedCallPlan.BoundSlot(
                                        1, 1, null, -1, -1)),
                        List.of(0, 1)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 0, null, 1, 0),
                                new CheckedCallPlan.BoundSlot(
                                        1, 1, null, 0, 0)),
                        List.of(0, 1)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(
                                        0, 0, null, 0, 1),
                                new CheckedCallPlan.BoundSlot(
                                        1, 1, null, 0, 2)),
                        List.of(0, 1)));
    }

    @Test
    void checkedPlanMetadataIsIdentityBoundAndConflictChecked() {
        Ast.CallExpr source = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(new Ast.LiteralExpr(1L)));
        Ast.CallExpr structurallyEqual = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(new Ast.LiteralExpr(1L)));
        CheckedCallPlan plan = CheckedCallPlan.required(
                List.of("value"),
                List.of(positional(0)));

        CheckedCallPlan.record(source, plan);
        assertSame(plan, CheckedCallPlan.require(source));
        assertNull(
                CheckedCallPlan.lookup(structurallyEqual),
                "structurally equal ASTs must not share checked authority");

        Ast.CallExpr rewritten = source.rewrite(
                source.callee(),
                source.typeArguments(),
                source.typeArgumentsPresent(),
                source.arguments());
        Ast.copyCheckedMetadata(source, rewritten);
        assertEquals(plan, CheckedCallPlan.require(rewritten));

        CheckedCallPlan conflicting = CheckedCallPlan.required(
                List.of("other"),
                List.of(positional(0)));
        assertThrows(
                IllegalStateException.class,
                () -> CheckedCallPlan.record(source, conflicting));
        assertNull(
                CheckedCallPlan.lookup(source),
                "conflicting republication must revoke stale checked authority");
        assertThrows(
                IllegalStateException.class,
                () -> CheckedCallPlan.require(source));
        assertNull(CheckedCallPlan.lookup(structurallyEqual));
        assertEquals(
                plan,
                CheckedCallPlan.require(rewritten),
                "an independent AST retains its own admitted plan");

        // The rejected site is not permanently poisoned: a complete,
        // successful recheck may publish a fresh proof after revocation.
        CheckedCallPlan.record(source, conflicting);
        assertSame(conflicting, CheckedCallPlan.require(source));
    }

    @Test
    void failedRevalidationRevokesPreviouslyPublishedCallProofs() {
        Ast.CallExpr source = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(new Ast.LiteralExpr(1L)));
        Ast.CallExpr independent = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(new Ast.LiteralExpr(2L)));
        CheckedCallPlan proof = CheckedCallPlan.required(
                List.of("value"),
                List.of(positional(0)));
        CheckedCallPlan.record(source, proof);
        CheckedCallPlan.record(independent, proof);

        assertThrows(IllegalArgumentException.class, () -> {
            try (CheckedCallPlan.ValidationScope attempt =
                    CheckedCallPlan.beginValidation()) {
                CheckedCallPlan.beginRecheck(source);
                // Simulates a type/arity failure before a new plan can be
                // published for this exact AST identity.
                throw new IllegalArgumentException("missing required parameter");
            }
        });
        assertNull(CheckedCallPlan.lookup(source));
        assertEquals(proof, CheckedCallPlan.require(independent));

        // A later successful complete recheck may re-admit the same identity.
        try (CheckedCallPlan.ValidationScope attempt =
                CheckedCallPlan.beginValidation()) {
            CheckedCallPlan.beginRecheck(source);
            CheckedCallPlan.record(source, proof);
            attempt.commit();
        }
        assertEquals(proof, CheckedCallPlan.require(source));

        assertThrows(IllegalArgumentException.class, () -> {
            try (CheckedCallPlan.ValidationScope outer =
                    CheckedCallPlan.beginValidation()) {
                try (CheckedCallPlan.ValidationScope typePass =
                        CheckedCallPlan.beginValidation()) {
                    CheckedCallPlan.beginRecheck(source);
                    CheckedCallPlan.record(source, proof);
                    typePass.commit();
                }
                // Ownership failure invalidates a successful earlier type pass.
                throw new IllegalArgumentException("ownership failure");
            }
        });
        assertNull(CheckedCallPlan.lookup(source));
        assertEquals(proof, CheckedCallPlan.require(independent));
    }

    @Test
    void checkedPlanMetadataDoesNotCrossBindingShapeChanges() {
        Ast.CallExpr namedSource = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(
                        new Ast.NamedArgExpr("a", new Ast.LiteralExpr(1L)),
                        new Ast.NamedArgExpr("b", new Ast.LiteralExpr(2L))));
        CheckedCallPlan namedPlan = CheckedCallPlan.required(
                List.of("a", "b"),
                List.of(named("a", 0), named("b", 1)));
        CheckedCallPlan.record(namedSource, namedPlan);

        Ast.CallExpr relabeled = namedSource.rewrite(
                namedSource.callee(),
                namedSource.typeArguments(),
                namedSource.typeArgumentsPresent(),
                List.of(
                        new Ast.NamedArgExpr("b", new Ast.LiteralExpr(1L)),
                        new Ast.NamedArgExpr("a", new Ast.LiteralExpr(2L))));
        Ast.copyCheckedMetadata(namedSource, relabeled);
        assertNull(
                CheckedCallPlan.lookup(relabeled),
                "changing named labels must require a fresh checked plan");

        Ast.CallExpr positional = namedSource.rewrite(
                namedSource.callee(),
                namedSource.typeArguments(),
                namedSource.typeArgumentsPresent(),
                List.of(
                        new Ast.LiteralExpr(1L),
                        new Ast.LiteralExpr(2L)));
        Ast.copyCheckedMetadata(namedSource, positional);
        assertNull(
                CheckedCallPlan.lookup(positional),
                "named-to-positional rewrites must not inherit call plans");

        Ast.CallExpr spreadSource = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(new Ast.SpreadExpr(
                        new Ast.TupleExpr(List.of(
                                new Ast.LiteralExpr(1L),
                                new Ast.LiteralExpr(2L))))));
        CheckedCallPlan spreadPlan = CheckedCallPlan.required(
                List.of("a", "b"),
                List.of(
                        positionalProjection(0, 0),
                        positionalProjection(0, 1)));
        CheckedCallPlan.record(spreadSource, spreadPlan);

        Ast.CallExpr unwrapped = spreadSource.rewrite(
                spreadSource.callee(),
                spreadSource.typeArguments(),
                spreadSource.typeArgumentsPresent(),
                List.of(new Ast.TupleExpr(List.of(
                        new Ast.LiteralExpr(1L),
                        new Ast.LiteralExpr(2L)))));
        Ast.copyCheckedMetadata(spreadSource, unwrapped);
        assertNull(
                CheckedCallPlan.lookup(unwrapped),
                "spread-to-scalar rewrites must require a fresh checked plan");

        Ast.CallExpr valueOnlyRewrite = namedSource.rewrite(
                namedSource.callee(),
                namedSource.typeArguments(),
                namedSource.typeArgumentsPresent(),
                List.of(
                        new Ast.NamedArgExpr("a", new Ast.LiteralExpr(10L)),
                        new Ast.NamedArgExpr("b", new Ast.LiteralExpr(20L))));
        Ast.copyCheckedMetadata(namedSource, valueOnlyRewrite);
        assertEquals(
                namedPlan,
                CheckedCallPlan.require(valueOnlyRewrite),
                "rewriting only named argument values preserves binding shape");
    }

    @Test
    void checkedPlanTransferRejectsRetargetedCallableOrConstructor() {
        Ast.CallExpr direct = new Ast.CallExpr(
                new Ast.NameExpr("f"),
                List.of(new Ast.LiteralExpr(1L)));
        CheckedCallPlan plan = CheckedCallPlan.required(
                List.of("value"), List.of(positional(0)));
        CheckedCallPlan.record(direct, plan);

        Ast.CallExpr otherCallee = direct.rewrite(
                new Ast.NameExpr("g"),
                direct.typeArguments(),
                direct.typeArgumentsPresent(),
                direct.arguments());
        Ast.copyCheckedMetadata(direct, otherCallee);
        assertNull(CheckedCallPlan.lookup(otherCallee),
                "changing the resolved callable must revoke copied binding authority");

        Ast.CallExpr changedTypeArguments = direct.rewrite(
                direct.callee(),
                List.of(Ast.TypeRef.simple("int")),
                true,
                direct.arguments());
        Ast.copyCheckedMetadata(direct, changedTypeArguments);
        assertNull(CheckedCallPlan.lookup(changedTypeArguments),
                "changing explicit generics must require a fresh checked call");

        Ast.CallExpr sameTarget = direct.rewrite(
                new Ast.NameExpr("f"),
                direct.typeArguments(),
                direct.typeArgumentsPresent(),
                List.of(new Ast.LiteralExpr(9L)));
        Ast.copyCheckedMetadata(direct, sameTarget);
        assertEquals(plan, CheckedCallPlan.require(sameTarget),
                "value-only rewrites on the same callable retain the plan");

        Ast.NewExpr firstConstructor = new Ast.NewExpr(
                Ast.TypeRef.simple("Foo"),
                List.of(new Ast.LiteralExpr(1L)));
        CheckedCallPlan.record(firstConstructor, plan);
        Ast.NewExpr otherConstructor = new Ast.NewExpr(
                Ast.TypeRef.simple("Bar"),
                firstConstructor.arguments());
        Ast.copyCheckedMetadata(firstConstructor, otherConstructor);
        assertNull(CheckedCallPlan.lookup(otherConstructor),
                "changing the constructor type must require a new binding proof");

        Ast.NewExpr sameConstructor = new Ast.NewExpr(
                Ast.TypeRef.simple("Foo"),
                List.of(new Ast.LiteralExpr(2L)));
        Ast.copyCheckedMetadata(firstConstructor, sameConstructor);
        assertEquals(plan, CheckedCallPlan.require(sameConstructor));
    }

    @Test
    void legacyPlansDelegateToExactlyTheSameCanonicalBinding() {
        List<String> names = List.of("a", "b", "c");
        List<CheckedCallPlan.InputSlot> checkedFixedInputs =
                List.of(
                        positionalProjection(0, 0),
                        positionalProjection(0, 1),
                        named("c", 1));
        List<FixedProductCallPlan.InputSlot> legacyFixedInputs =
                checkedFixedInputs.stream()
                        .map(input -> new FixedProductCallPlan.InputSlot(
                                input.label(),
                                input.callItemIndex(),
                                input.projectionIndex()))
                        .toList();

        CheckedCallPlan checkedRequired =
                CheckedCallPlan.required(names, checkedFixedInputs);
        FixedProductCallPlan legacyRequired =
                FixedProductCallPlan.bind(names, legacyFixedInputs);
        assertEquals(
                checkedRequired.abiToSource(),
                legacyRequired.abiToSource());

        List<Boolean> omittable = List.of(false, true, false);
        List<CheckedCallPlan.InputSlot> checkedOptionalInputs =
                List.of(positional(0), named("c", 1));
        List<FixedProductCallPlan.InputSlot> legacyOptionalInputs =
                checkedOptionalInputs.stream()
                        .map(input -> new FixedProductCallPlan.InputSlot(
                                input.label(),
                                input.callItemIndex(),
                                input.projectionIndex()))
                        .toList();
        CheckedCallPlan checkedOptional =
                CheckedCallPlan.bind(
                        names, omittable, checkedOptionalInputs);
        OptionalCallPlan legacyOptional =
                OptionalCallPlan.bind(
                        names, omittable, legacyOptionalInputs);

        assertEquals(
                checkedOptional.abiToSource(),
                legacyOptional.abiToSource());
        assertEquals(
                checkedOptional.sourceOrder().stream()
                        .map(CheckedCallPlan.BoundSlot::parameterIndex)
                        .toList(),
                legacyOptional.sourceOrder().stream()
                        .map(OptionalCallPlan.BoundSlot::parameterIndex)
                        .toList());
    }
    @Test
    void oneRawSpreadCannotMixTupleAndRecordProjectionKinds() {
        List<String> formals = List.of("a", "b");
        List<CheckedCallPlan.InputSlot> forged =
                List.of(
                        positionalProjection(0, 0),
                        namedProjection("b", 0, 1));

        assertThrows(
                IllegalArgumentException.class,
                () -> CheckedCallPlan.required(formals, forged));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        formals,
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new CheckedCallPlan.BoundSlot(1, 1, "b", 0, 1)),
                        List.of(0, 1)));

        List<FixedProductCallPlan.InputSlot> legacy =
                List.of(
                        FixedProductCallPlan.InputSlot.positionalProjection(0, 0),
                        FixedProductCallPlan.InputSlot.namedProjection("b", 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(formals, legacy));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(
                                        0, 0, null, 0, 0),
                                new FixedProductCallPlan.BoundSlot(
                                        1, 1, "b", 0, 1)),
                        List.of(0, 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> OptionalCallPlan.bind(
                        formals,
                        List.of(false, true),
                        legacy));
        assertThrows(
                IllegalArgumentException.class,
                () -> new OptionalCallPlan(
                        List.of(
                                new OptionalCallPlan.BoundSlot(
                                        0, 0, null, 0, 0),
                                new OptionalCallPlan.BoundSlot(
                                        1, 1, "b", 0, 1)),
                        List.of(0, 1),
                        List.of(false, true)));

        // Adjacent projections of different raw call items are legal, as is
        // a tuple argument followed by a separately named scalar argument.
        CheckedCallPlan separate = CheckedCallPlan.required(
                formals,
                List.of(
                        positionalProjection(0, 0),
                        named("b", 1)));
        assertEquals(List.of(0, 1), separate.abiToSource());
    }

    @Test
    void rejectsForgedTupleProjectionGapsThroughBinderAndPublicConstructors() {
        List<CheckedCallPlan.InputSlot> gap =
                List.of(
                        positionalProjection(0, 0),
                        positionalProjection(0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.required(List.of("a", "b"), gap));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b"),
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new CheckedCallPlan.BoundSlot(1, 1, null, 0, 2)),
                        List.of(0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(
                        List.of("a", "b"),
                        List.of(
                                FixedProductCallPlan.InputSlot.positionalProjection(0, 0),
                                FixedProductCallPlan.InputSlot.positionalProjection(0, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new FixedProductCallPlan.BoundSlot(1, 1, null, 0, 2)),
                        List.of(0, 1)));
    }

    @Test
    void rejectsForgedRecordProjectionGapsAcrossOptionalAdapters() {
        List<CheckedCallPlan.InputSlot> gap =
                List.of(
                        namedProjection("b", 0, 0),
                        namedProjection("a", 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.bind(
                        List.of("a", "b", "c"),
                        List.of(false, false, true),
                        gap));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        List.of("a", "b", "c"),
                        List.of(false, false, true),
                        List.of(
                                new CheckedCallPlan.BoundSlot(0, 1, "b", 0, 0),
                                new CheckedCallPlan.BoundSlot(1, 0, "a", 0, 2)),
                        List.of(1, 0, -1)));
        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallPlan.bind(
                        List.of("a", "b", "c"),
                        List.of(false, false, true),
                        List.of(
                                FixedProductCallPlan.InputSlot.namedProjection("b", 0, 0),
                                FixedProductCallPlan.InputSlot.namedProjection("a", 0, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallPlan(
                        List.of(
                                new OptionalCallPlan.BoundSlot(0, 1, "b", 0, 0),
                                new OptionalCallPlan.BoundSlot(1, 0, "a", 0, 2)),
                        List.of(1, 0, -1),
                        List.of(false, false, true)));
    }

    @Test
    void oneSourceItemCannotForgeBothTupleAndRecordProjectionKinds() {
        List<String> names = List.of("a", "b");
        List<CheckedCallPlan.InputSlot> mixed = List.of(
                positionalProjection(0, 0),
                namedProjection("b", 0, 1));

        // This used to pass binding: contiguous indices and correct ABI
        // mapping do not prove two projections have the same shape.
        assertThrows(IllegalArgumentException.class,
                () -> CheckedCallPlan.required(names, mixed));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckedCallPlan(
                        names,
                        List.of(false, false),
                        List.of(
                                new CheckedCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new CheckedCallPlan.BoundSlot(1, 1, "b", 0, 1)),
                        List.of(0, 1)));

        assertThrows(IllegalArgumentException.class,
                () -> FixedProductCallPlan.bind(names, List.of(
                        FixedProductCallPlan.InputSlot.positionalProjection(0, 0),
                        FixedProductCallPlan.InputSlot.namedProjection("b", 0, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new FixedProductCallPlan(
                        List.of(
                                new FixedProductCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new FixedProductCallPlan.BoundSlot(1, 1, "b", 0, 1)),
                        List.of(0, 1)));

        assertThrows(IllegalArgumentException.class,
                () -> OptionalCallPlan.bind(
                        names, List.of(false, false), List.of(
                                FixedProductCallPlan.InputSlot.positionalProjection(0, 0),
                                FixedProductCallPlan.InputSlot.namedProjection("b", 0, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallPlan(
                        List.of(
                                new OptionalCallPlan.BoundSlot(0, 0, null, 0, 0),
                                new OptionalCallPlan.BoundSlot(1, 1, "b", 0, 1)),
                        List.of(0, 1),
                        List.of(false, false)));
    }

    @Test
    void distinctRawItemsMayChangeProjectionKinds() {
        CheckedCallPlan plan = CheckedCallPlan.required(
                List.of("a", "b", "c"),
                List.of(
                        positionalProjection(0, 0),
                        positionalProjection(0, 1),
                        namedProjection("c", 1, 0)));
        assertEquals(List.of(0, 1, 2), plan.abiToSource());

        CheckedCallPlan reorderedRecord = CheckedCallPlan.required(
                List.of("a", "b"),
                List.of(
                        namedProjection("b", 0, 0),
                        namedProjection("a", 0, 1)));
        assertEquals(List.of(1, 0), reorderedRecord.abiToSource());
    }

}
