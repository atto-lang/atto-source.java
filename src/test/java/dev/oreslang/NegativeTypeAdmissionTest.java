package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Draft admission tests: negative types are proofs, not dynamic casts. */
final class NegativeTypeAdmissionTest {
    @Test
    void nestedCallableUncertaintyCannotProveComplementDisjointness() {
        Types.Type unknownParameter = new Types.Function(
                List.of(Types.Unknown.INSTANCE), Types.Primitive.INT);
        Types.Type unknownResult = new Types.Function(
                List.of(Types.Primitive.INT), Types.Unknown.INSTANCE);
        for (Types.Type callable : List.of(unknownParameter, unknownResult)) {
            assertFalse(Types.areProvablyDisjoint(Types.notOf(callable), callable));
            assertFalse(Types.areProvablyDisjoint(
                    Types.notOf(new Types.Borrow(callable, false)),
                    new Types.Borrow(callable, false)));
            assertFalse(Types.areProvablyDisjoint(
                    Types.notOf(new Types.SelfType(callable)), new Types.SelfType(callable)));
        }
    }

    @Test
    void parserDistinguishesUnaryTypeComplementFromBooleanValueNegation() {
        assertEquals(Ast.TypeRef.simple("string"),
                Ast.TypeRef.complement(Ast.TypeRef.complement(Ast.TypeRef.simple("string"))));
        assertTrue(Ast.TypeRef.complement(Ast.TypeRef.simple("string")).isComplement());
        assertDoesNotThrow(() -> Parser.parse("""
                type NonText = !string;
                type NonPrimitive = !(string | int);
                type NotTrue = !true;
                type NotFalse = !false;
                type MissingIntShape = !struct{foo: int};
                type MissingRecordShape = !record{foo: int};
                fnc accept(!string value): void {}
                """));
    }

    @Test
    void compilerAcceptsProvablyDisjointPrimitiveArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type NonText = !string;
                fnc accept(NonText value): void {}
                fnc accepted(): void {
                    accept(42);
                    accept(false);
                }
                """)));
    }

    @Test
    void compilerRejectsExcludedPrimitiveArguments() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc accept(!string value): void {}
                fnc rejected(): void { accept("not allowed"); }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc accept(!(string | int) value): void {}
                fnc rejected(): void { accept(42); }
                """)));
    }

    @Test
    void uncertainValuesCannotSatisfyNegativeTypeConstraints() {
        Types.Type notString = Types.notOf(Types.Primitive.STRING);
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, notString));
        assertFalse(Types.isAssignable(new Types.Generic("T"), notString));
        assertTrue(Types.isAssignable(Types.Primitive.INT, notString));
        assertFalse(Types.isAssignable(Types.Primitive.STRING, notString));
        assertFalse(Types.isAssignable(Types.unionOf(
                Types.Primitive.INT, Types.Primitive.STRING), notString));
        assertEquals(Types.Primitive.STRING, Types.notOf(notString));
    }

    @Test
    void singletonBooleanComplementKeepsItsUniversalMeaning() {
        Types.Type notTrue = Types.notOf(new Types.BoolLiteral(true));
        assertTrue(Types.isAssignable(new Types.BoolLiteral(false), notTrue));
        assertFalse(Types.isAssignable(new Types.BoolLiteral(true), notTrue));
        assertFalse(Types.isAssignable(Types.Primitive.BOOL, notTrue));
        assertTrue(Types.isAssignable(Types.Primitive.STRING, notTrue));
    }

    @Test
    void structuralExclusionProvesConflictingFieldsButNotMissingFields() {
        Types.Type excluded = new Types.Record(Map.of("foo", Types.Primitive.INT));
        Types.Type nonMatchingField = new Types.Record(Map.of("foo", Types.Primitive.STRING));
        Types.Type unknownExtraFields = new Types.Record(Map.of("bar", Types.Primitive.INT));
        Types.Type matchingWithExtras = new Types.Record(Map.of(
                "foo", Types.Primitive.INT, "bar", Types.Primitive.STRING));
        Types.Type notExcluded = Types.notOf(excluded);

        assertTrue(Types.isAssignable(nonMatchingField, notExcluded));
        assertFalse(Types.isAssignable(unknownExtraFields, notExcluded));
        assertFalse(Types.isAssignable(matchingWithExtras, notExcluded));
        assertTrue(Types.isAssignable(Types.Primitive.BOOL, notExcluded));
    }

    @Test
    void negativeTypesDoNotBecomeArgumentOverloadDiscriminators() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc consume(string value): void {}
                fnc consume(!string value): void {}
                """)));
    }
}
