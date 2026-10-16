package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TypedMapKeyContractTest {
    private static Ast.TypeRef option(Ast.TypeRef inner) {
        return new Ast.TypeRef("Option", List.of(inner), false);
    }

    @Test
    void peelsExactlyOnePresenceLayerAndPreservesNestedOption() {
        Ast.TypeRef inner = option(Ast.TypeRef.simple("bool"));
        Ast.TypeRef declared = option(inner);

        assertEquals(inner, TypedMapKeyContract.storedType(declared));
        Ast.TypeRef lookup = TypedMapKeyContract.lookupType(declared);
        assertEquals("Option", lookup.name());
        assertEquals(List.of(inner), lookup.arguments());
        assertEquals(declared, lookup,
                "lookup must preserve missing vs stored None as Option<Option<T>>");
    }

    @Test
    void keyVisibilityFailsClosedBecauseKeysAreNotClassMembers() {
        Ast.TypeRef declared = option(Ast.TypeRef.simple("string"));
        assertThrows(IllegalArgumentException.class, () ->
                new TypedMapKeyContract.Schema(List.of(
                        new TypedMapKeyContract.KeyDecl(
                                "token", declared,
                                TypedMapKeyContract.VisibilitySyntax.PUBLIC))));
        assertThrows(IllegalArgumentException.class, () ->
                new TypedMapKeyContract.Schema(List.of(
                        new TypedMapKeyContract.KeyDecl(
                                "token", declared,
                                TypedMapKeyContract.VisibilitySyntax.PRIVATE))));
    }

    @Test
    void schemaRejectsDuplicatesMalformedNamesAndMissingOuterOption() {
        Ast.TypeRef declared = option(Ast.TypeRef.simple("int"));

        assertThrows(IllegalArgumentException.class, () ->
                new TypedMapKeyContract.Schema(List.of(
                        new TypedMapKeyContract.KeyDecl(
                                "count", declared,
                                TypedMapKeyContract.VisibilitySyntax.NONE),
                        new TypedMapKeyContract.KeyDecl(
                                "count", declared,
                                TypedMapKeyContract.VisibilitySyntax.NONE))));

        assertThrows(IllegalArgumentException.class, () ->
                new TypedMapKeyContract.Schema(List.of(
                        new TypedMapKeyContract.KeyDecl(
                                "", declared,
                                TypedMapKeyContract.VisibilitySyntax.NONE))));

        assertThrows(IllegalArgumentException.class, () ->
                TypedMapKeyContract.storedType(Ast.TypeRef.simple("int")));
        assertThrows(IllegalArgumentException.class, () ->
                TypedMapKeyContract.storedType(
                        new Ast.TypeRef("Option", List.of(), false)));
    }

    @Test
    void keySchemaUsesSameUtf8BoundAsRuntimeMap() {
        Ast.TypeRef declared = option(Ast.TypeRef.simple("int"));
        new TypedMapKeyContract.Schema(List.of(
                new TypedMapKeyContract.KeyDecl(
                        "x".repeat(16_384), declared,
                        TypedMapKeyContract.VisibilitySyntax.NONE)));

        assertThrows(IllegalArgumentException.class, () ->
                new TypedMapKeyContract.Schema(List.of(
                        new TypedMapKeyContract.KeyDecl(
                                "x".repeat(16_385), declared,
                                TypedMapKeyContract.VisibilitySyntax.NONE))));
        assertThrows(IllegalArgumentException.class, () ->
                new TypedMapKeyContract.Schema(List.of(
                        new TypedMapKeyContract.KeyDecl(
                                "\uD800", declared,
                                TypedMapKeyContract.VisibilitySyntax.NONE))));
    }

    @Test
    void interceptionHooksAreSealedFromOrdinaryCalls() {
        for (String hook : TypedMapKeyContract.SEALED_HOOKS) {
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypedMapKeyContract.rejectDirectHookCall(hook));
            assertTrue(denied.getMessage().contains("cannot be called directly"));
        }
        assertDoesNotThrow(() ->
                TypedMapKeyContract.rejectDirectHookCall("ordinary_method"));
    }
}
