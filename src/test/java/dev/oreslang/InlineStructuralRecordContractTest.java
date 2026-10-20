package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Read-only inline shape contracts: struct{...}, record{...}, structural {...}. */
final class InlineStructuralRecordContractTest {
    @Test
    void structAndRecordTypeSpellingsNormalizeToTheSameReadonlyShape() {
        Ast.Program ast = Parser.parse("""
                fnc byStruct(struct{foo: int} value): int { return value.foo; }
                fnc byRecord(record{foo: int} value): int { return value.foo; }
                """);
        Ast.ModuleDecl root = ast.modules().getFirst();
        Ast.FunctionDecl byStruct = (Ast.FunctionDecl) root.declarations().get(0);
        Ast.FunctionDecl byRecord = (Ast.FunctionDecl) root.declarations().get(1);
        assertEquals(byStruct.parameters().getFirst().type(), byRecord.parameters().getFirst().type());
        assertTrue(byStruct.parameters().getFirst().type().isReadonlyRecordType());
    }

    @Test
    void extraFieldsAreAcceptedByAllThreeReadOnlyContracts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc byStruct(struct{foo: int} value): int {
                    return value.foo;
                }
                pub fnc byStructural(structural {foo: int} value): int {
                    return value.foo;
                }
                pub fnc byRecord(record{foo: int} value): int {
                    return value.foo;
                }
                fnc ok(): int {
                    const value = infer struct{foo: 3, bar: "seven"};
                    return byStruct(value) + byStructural(value) + byRecord(value);
                }
                """)));
    }

    @Test
    void explicitClosedStructWithExtraFieldsAlsoSatisfiesContract() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc take(record{foo: int} value): int { return value.foo; }
                fnc ok(): int {
                    const value = struct{foo: int, bar: string}{foo: 3, bar: "seven"};
                    return take(value);
                }
                """)));
    }

    @Test
    void missingAndWronglyTypedRequiredFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc take(struct{foo: int} value): int { return value.foo; }
                fnc bad(): int { return take(infer struct{bar: 3}); }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc take(record{foo: int} value): int { return value.foo; }
                fnc bad(): int { return take(infer struct{foo: "three", bar: 4}); }
                """)));
    }

    @Test
    void readonlyContractNeverGrantsFieldWriteAuthority() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(struct{foo: int} value): void {
                    value.foo = 9;
                    return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(record{foo: int} mut value): void {
                    value.foo = 9;
                    return;
                }
                """)));
    }

    @Test
    void readonlyInlineShapesCannotBecomeMutableAsyncOrActorParameters() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(record{foo: int} value): int {
                    return value.foo;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(struct{foo: int} value): void {
                    return;
                }
                """)));
    }

    @Test
    void suspendCallableCannotRetainReadonlyShapeView() {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                suspend fnc bad(record{foo: int} value): int {
                    return value.foo;
                }
                """)));
        assertTrue(problem.getMessage().contains("outlive"), problem.getMessage());
    }

    @Test
    void suspendCallableCannotRetainExplicitStructuralViewEither() {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                suspend fnc bad(structural {foo: int} value): int {
                    return value.foo;
                }
                """)));
        assertTrue(problem.getMessage().contains("outlive"), problem.getMessage());
    }

    @Test
    void suspensionAndActorCannotHideReadonlyShapeViewsInsideContainers() {
        IllegalArgumentException suspended = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                suspend fnc bad(List<record{foo: int}> values): void { return; }
                """)));
        assertTrue(suspended.getMessage().contains("outlive"), suspended.getMessage());
        IllegalArgumentException actor = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(List<struct{foo: int}> values): void { return; }
                """)));
        assertTrue(actor.getMessage().contains("mailbox boundary"), actor.getMessage());
    }

    @Test
    void duplicateFieldNamesFailAtParseTime() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(record{foo: int, foo: string} v): void { return; }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(struct{foo: int, foo: int} v): void { return; }
                """));
    }

    @Test
    void recordRemainsAnOrdinaryIdentifierOutsideTypeShapePosition() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc record(): int { return 2; }
                fnc ok(): int { return record(); }
                """)));
    }
    @Test
    void readonlyFieldViewCannotPretendNumericWideningOccurred() {
        var source = new dev.oreslang.types.Types.Record(
                java.util.Map.of("foo", dev.oreslang.types.Types.Primitive.INT,
                        "bar", dev.oreslang.types.Types.Primitive.STRING), true);
        var safe = new dev.oreslang.types.Types.Record(
                java.util.Map.of("foo", dev.oreslang.types.Types.Primitive.INT), true);
        var unsafe = new dev.oreslang.types.Types.Record(
                java.util.Map.of("foo", dev.oreslang.types.Types.Primitive.FLOAT), true);
        assertTrue(dev.oreslang.types.Types.isAssignable(source, safe));
        assertFalse(dev.oreslang.types.Types.isAssignable(source, unsafe),
                "field projection is a view, not an int-to-float materialization");

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc take(record{foo: float} value): float { return value.foo; }
                fnc bad(): float {
                    const value = infer struct{foo: 3, bar: "seven"};
                    return take(value);
                }
                """)));
    }

    @Test
    void nestedReadonlyShapeCannotConcealUnsafeFieldRepresentation() {
        var innerInt = new dev.oreslang.types.Types.Record(
                java.util.Map.of("value", dev.oreslang.types.Types.Primitive.INT), true);
        var innerFloat = new dev.oreslang.types.Types.Record(
                java.util.Map.of("value", dev.oreslang.types.Types.Primitive.FLOAT), true);
        var nestedListInt = new dev.oreslang.types.Types.Record(java.util.Map.of(
                "items", new dev.oreslang.types.Types.ListType(innerInt)), true);
        var nestedListFloat = new dev.oreslang.types.Types.Record(java.util.Map.of(
                "items", new dev.oreslang.types.Types.ListType(innerFloat)), true);
        assertFalse(dev.oreslang.types.Types.isAssignable(nestedListInt, nestedListFloat));
        var tupleInt = new dev.oreslang.types.Types.Record(java.util.Map.of(
                "items", new dev.oreslang.types.Types.Tuple(java.util.List.of(
                        dev.oreslang.types.Types.Primitive.INT))), true);
        var tupleFloat = new dev.oreslang.types.Types.Record(java.util.Map.of(
                "items", new dev.oreslang.types.Types.Tuple(java.util.List.of(
                        dev.oreslang.types.Types.Primitive.FLOAT))), true);
        assertFalse(dev.oreslang.types.Types.isAssignable(tupleInt, tupleFloat));
    }

    @Test
    void writableRecordFieldsStayInvariantEvenAgainstAny() {
        var value = new dev.oreslang.types.Types.Record(java.util.Map.of(
                "foo", dev.oreslang.types.Types.Primitive.INT), false);
        var widenedWritable = new dev.oreslang.types.Types.Record(java.util.Map.of(
                "foo", new dev.oreslang.types.Types.Named("Any", java.util.List.of())), false);
        assertFalse(dev.oreslang.types.Types.isAssignable(value, widenedWritable));
        assertFalse(dev.oreslang.types.Types.isAssignable(
                new dev.oreslang.types.Types.Record(value.members(), true), value),
                "readonly aliases must not recover a field writer");
    }

}
