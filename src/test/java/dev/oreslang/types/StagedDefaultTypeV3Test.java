package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StagedDefaultTypeV3Test {
    private static Ast.TypeRef type(String name) { return Ast.TypeRef.simple(name); }
    private static Ast.TypeRef number(String value) { return Ast.TypeRef.intLiteral(value); }
    private static Ast.TypeRef option(String name) {
        return new Ast.TypeRef("Option", java.util.List.of(type(name)), false);
    }

    @Test void signedAndUnsignedWidthsAreCheckedExactly() {
        assertEquals(127L, StagedDefaultTypeV3.verifyKnownBuiltin(type("i8"), number("127")).value());
        assertEquals(-128L, StagedDefaultTypeV3.verifyKnownBuiltin(type("i8"), number("-128")).value());
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("i8"), number("128")));
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("i8"), number("-129")));
        assertEquals(255L, StagedDefaultTypeV3.verifyKnownBuiltin(type("u8"), number("255")).value());
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("u8"), number("-1")));
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("u8"), number("256")));
    }

    @Test void unsigned64AboveSignedLongRequiresFutureAbiNotWraparound() {
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("u64"),
                        number("18446744073709551615")));
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("i64"),
                        number("9223372036854775808")));
        assertEquals(Long.MIN_VALUE, StagedDefaultTypeV3.verifyKnownBuiltin(
                type("int"), number("-9223372036854775808")).value());
    }

    @Test void builtinScalarDefaultKindsCannotBeCoerced() {
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("int"),
                        Ast.TypeRef.boolLiteral(true)));
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("bool"), number("1")));
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("string"), number("42")));
        assertEquals("hello", StagedDefaultTypeV3.verifyKnownBuiltin(type("string"),
                Ast.TypeRef.stringLiteral("hello")).value());
        assertEquals(true, StagedDefaultTypeV3.verifyKnownBuiltin(type("bool"),
                Ast.TypeRef.boolLiteral(true)).value());
    }

    @Test void noneMustBelongToOptionAndSomeValueMustMatchInner() {
        Ast.TypeRef none = type("None");
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(type("bool"), none));
        assertEquals(DefaultCallShapeV3.ConstantKind.NONE,
                StagedDefaultTypeV3.verifyKnownBuiltin(option("bool"), none).kind());
        assertThrows(IllegalArgumentException.class, () ->
                StagedDefaultTypeV3.verifyKnownBuiltin(option("bool"), number("1")));
        assertEquals(DefaultCallShapeV3.ConstantKind.BOOLEAN,
                StagedDefaultTypeV3.verifyKnownBuiltin(option("bool"),
                        Ast.TypeRef.boolLiteral(false)).kind());
    }

    @Test void nestedOptionScalarDefaultDoesNotSilentlyLoseSomeLayer() {
        Ast.TypeRef nested = new Ast.TypeRef("Option",
                java.util.List.of(option("int")), false);
        assertThrows(IllegalArgumentException.class,
                () -> StagedDefaultTypeV3.verifyKnownBuiltin(nested, number("5")));
        assertEquals(DefaultCallShapeV3.ConstantKind.NONE,
                StagedDefaultTypeV3.verifyKnownBuiltin(nested, type("None")).kind());
    }

    @Test void unresolvedAliasesCannotMintTrustedBuiltins() {
        assertNull(StagedDefaultTypeV3.verifyKnownBuiltin(type("MyInt"), number("4")));
        assertNull(StagedDefaultTypeV3.verifyKnownBuiltin(type("OptionAlias"), type("None")));
    }

    @Test void checkerRejectsMismatchedDefaultBeforeGenericStageGate() {
        Ast.Program p = Parser.parse("""
                define module m
                  @DefaultArgVals(x: 999)
                  pub fnc bad(i8 x?): void { return; }
                end
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(p));
        assertTrue(e.getMessage().contains("overflows"));
    }

    @Test void acceptedClosedLiteralStillCannotExecuteUnloweredGuestCall() {
        Ast.Program p = Parser.parse("""
                define module m
                  @DefaultArgVals(x: 12)
                  pub fnc staged(i8 x?): void { return; }
                end
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(p));
        assertTrue(e.getMessage().contains("staged")
                || e.getMessage().contains("not implemented")
                || e.getMessage().contains("parse-only"));
    }
}
