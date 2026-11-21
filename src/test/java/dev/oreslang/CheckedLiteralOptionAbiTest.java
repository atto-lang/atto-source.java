package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.BoundInvocation;
import dev.oreslang.types.CheckedLiteralOptionAbi;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Concrete scalar source/Option ABI preview, deliberately not guest execution. */
final class CheckedLiteralOptionAbiTest {
    private static Ast.Program program(String call) {
        return Parser.parse("""
                define module app as
                    pub fnc target(int a, string? label, bool enabled): int {
                        return a;
                    }
                    pub fnc caller(): void {
                        const result = %s;
                        return;
                    }
                end
                """.formatted(call));
    }

    private static CheckedLiteralOptionAbi.Preview prepare(Ast.Program source) {
        Ast.FunctionDecl caller = (Ast.FunctionDecl)
                source.modules().getFirst().declarations().get(1);
        Ast.StagedCallExpr call = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
        return CheckedLiteralOptionAbi.materialize(
                TypeChecker.inspectStagedScalarInvocation(source, caller, call));
    }

    @Test
    void namedReorderingMaterializesInDeclarationOrder() {
        var preview = prepare(program("target(enabled: true, label: \"Alice\", a: 7)"));
        assertEquals(List.of(
                new CheckedLiteralOptionAbi.CapturedScalar(Ast.TypeRef.simple("bool"), true),
                new CheckedLiteralOptionAbi.CapturedScalar(Ast.TypeRef.simple("string"), "Alice"),
                new CheckedLiteralOptionAbi.CapturedScalar(Ast.TypeRef.simple("int"), 7L)),
                preview.sourceTemps());
        assertInstanceOf(CheckedLiteralOptionAbi.RequiredValue.class, preview.abiSlots().get(0));
        assertInstanceOf(CheckedLiteralOptionAbi.SomeValue.class, preview.abiSlots().get(1));
        assertInstanceOf(CheckedLiteralOptionAbi.RequiredValue.class, preview.abiSlots().get(2));
        assertEquals(7L, ((CheckedLiteralOptionAbi.RequiredValue)
                preview.abiSlots().get(0)).scalar().value());
        assertEquals("Alice", ((CheckedLiteralOptionAbi.SomeValue)
                preview.abiSlots().get(1)).scalar().value());
        assertEquals(true, ((CheckedLiteralOptionAbi.RequiredValue)
                preview.abiSlots().get(2)).scalar().value());
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, preview.readiness());
        assertThrows(IllegalStateException.class, preview::requireExecutable);
    }

    @Test
    void explicitAndImplicitOmissionProduceTypedNoneNotNull() {
        for (String call : List.of(
                "target(enabled: true, label: _, a: 7)",
                "target(enabled: true, a: 7)")) {
            var preview = prepare(program(call));
            var absent = assertInstanceOf(
                    CheckedLiteralOptionAbi.NoneValue.class, preview.abiSlots().get(1));
            assertEquals(Ast.TypeRef.simple("string"), absent.elementType());
            assertEquals(new Ast.TypeRef("Option",
                    List.of(Ast.TypeRef.simple("string")), false), absent.calleeType());
            assertEquals(2, preview.sourceTemps().size());
        }
    }

    @Test
    void sourceTempAndFormalAbiViewsCannotBeModified() {
        var preview = prepare(program("target(enabled: true, label: _, a: 7)"));
        assertThrows(UnsupportedOperationException.class, () -> preview.sourceTemps().clear());
        assertThrows(UnsupportedOperationException.class, () -> preview.abiSlots().clear());
    }

    @Test
    void suppliedNoneNameCannotForgeExistingTypedOption() {
        Ast.Program source = program("target(enabled: true, label: None, a: 7)");
        Ast.FunctionDecl caller = (Ast.FunctionDecl)
                source.modules().getFirst().declarations().get(1);
        Ast.StagedCallExpr call = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
        assertThrows(IllegalArgumentException.class,
                () -> CheckedLiteralOptionAbi.materialize(
                        TypeChecker.inspectStagedScalarInvocation(source, caller, call)));
    }

    @Test
    void malformedSomePayloadOrRequiredScalarTypeCannotMaterialize() {
        var exactInt = new CheckedLiteralOptionAbi.CapturedScalar(
                Ast.TypeRef.simple("int"), 7L);
        assertThrows(IllegalArgumentException.class, () ->
                new CheckedLiteralOptionAbi.SomeValue(Ast.TypeRef.simple("string"), exactInt));
        assertThrows(IllegalArgumentException.class, () ->
                new CheckedLiteralOptionAbi.CapturedScalar(Ast.TypeRef.simple("bool"), 7L));
    }

    @Test
    void sourcePermutationsCannotChangeFormalAbiPositions() {
        for (String source : List.of(
                "a: 7, label: \"Alice\", enabled: true",
                "a: 7, enabled: true, label: \"Alice\"",
                "label: \"Alice\", a: 7, enabled: true",
                "label: \"Alice\", enabled: true, a: 7",
                "enabled: true, a: 7, label: \"Alice\"",
                "enabled: true, label: \"Alice\", a: 7")) {
            var preview = prepare(program("target(" + source + ")"));
            assertEquals(3, preview.sourceTemps().size(), source);
            assertEquals(7L, ((CheckedLiteralOptionAbi.RequiredValue)
                    preview.abiSlots().get(0)).scalar().value(), source);
            assertEquals("Alice", ((CheckedLiteralOptionAbi.SomeValue)
                    preview.abiSlots().get(1)).scalar().value(), source);
            assertEquals(true, ((CheckedLiteralOptionAbi.RequiredValue)
                    preview.abiSlots().get(2)).scalar().value(), source);
        }
    }

    @Test
    void wrongLiteralTypeOrOmittedRequiredFormalCannotProducePreview() {
        for (String source : List.of(
                "target(enabled: 42, label: _, a: 7)",
                "target(enabled: true, label: _, a: _)",
                "target(enabled: true, label: _, a: \"seven\")",
                "target(enabled: true, label: _, a: 7, unknown: 2)")) {
            assertThrows(IllegalArgumentException.class,
                    () -> prepare(program(source)), source);
        }
    }


    @Test
    void forgedNonFiniteFloatPayloadsCannotBecomeCheckedScalarOrOptionValues() {
        for (double invalid : new double[] {
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
            // An AST LiteralExpr accepts any Object, but special IEEE-754
            // payloads have no independently validated source spelling here.
            assertThrows(IllegalArgumentException.class,
                    () -> new CheckedLiteralOptionAbi.CapturedScalar(
                            Ast.TypeRef.simple("float"), invalid));
        }
        // Finite scalar literals retain their exact float type and value,
        // including signed zero, which has real source-level semantics.
        for (double valid : new double[] { 0.0d, -0.0d, 2.5d, -123.25d }) {
            var captured = new CheckedLiteralOptionAbi.CapturedScalar(
                    Ast.TypeRef.simple("float"), valid);
            assertEquals(Ast.TypeRef.simple("float"), captured.type());
            assertEquals(
                    Double.doubleToRawLongBits(valid),
                    Double.doubleToRawLongBits((double) captured.value()));
        }
        var some = new CheckedLiteralOptionAbi.SomeValue(
                Ast.TypeRef.simple("float"),
                new CheckedLiteralOptionAbi.CapturedScalar(
                        Ast.TypeRef.simple("float"), 2.5d));
        assertEquals(new Ast.TypeRef("Option",
                        List.of(Ast.TypeRef.simple("float")), false),
                some.calleeType());
    }

    @Test
    void previewCanOnlyBeIssuedByTheMaterializer() {
        assertEquals(0, CheckedLiteralOptionAbi.Preview.class.getConstructors().length,
                "public preview constructors would allow forged checked ABI evidence");
        var preview = prepare(program("target(enabled: true, a: 7)"));
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, preview.readiness());
        assertThrows(IllegalStateException.class, preview::requireExecutable);
    }

    @Test
    void typedNoneRejectsVoidNestedGenericOptionalAndBorrowElements() {
        for (Ast.TypeRef invalid : List.of(
                Ast.TypeRef.simple("void"),
                Ast.TypeRef.simple("Option"),
                new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("int")), false),
                new Ast.TypeRef("string", List.of(Ast.TypeRef.simple("int")), false),
                Ast.TypeRef.inferred())) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CheckedLiteralOptionAbi.NoneValue(invalid),
                    "invalid None<T> element: " + invalid);
        }
        assertEquals(new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("bool")), false),
                new CheckedLiteralOptionAbi.NoneValue(Ast.TypeRef.simple("bool")).calleeType());
    }

    @Test
    void optionalPayloadMustHaveExactConcreteScalarElementType() {
        var payload = new CheckedLiteralOptionAbi.CapturedScalar(
                Ast.TypeRef.simple("int"), 12L);
        assertThrows(IllegalArgumentException.class,
                () -> new CheckedLiteralOptionAbi.SomeValue(Ast.TypeRef.simple("void"), payload));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckedLiteralOptionAbi.SomeValue(
                        new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("int")), false),
                        payload));
        assertEquals(new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("int")), false),
                new CheckedLiteralOptionAbi.SomeValue(
                        Ast.TypeRef.simple("int"), payload).calleeType());
    }

}
