package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser admission checks for AttoLang named type argument ordering.
 *
 * These are syntax-only tests: they neither resolve generic declaration names
 * nor prove compiler-backed named specialization or guest execution.
 */
final class NamedTypeArgumentOrderingTest {
    private static String withType(String type) {
        return "fnc inspect(" + type + " value): void { return; }";
    }

    private static void rejectsLatePositional(String type) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(withType(type)));
        assertTrue(error.getMessage().contains(
                        "positional type argument cannot follow a named type argument"),
                error.getMessage());
    }

    @Test
    void acceptsNamedTypeArgumentsInEitherOrder() {
        assertDoesNotThrow(() -> Parser.parse(
                withType("Pair<First=int, Second=string>")));
        assertDoesNotThrow(() -> Parser.parse(
                withType("Pair<Second=string, First=int>")));
    }

    @Test
    void acceptsPositionalPrefixFollowedByNamedArguments() {
        assertDoesNotThrow(() -> Parser.parse(
                withType("Pair<int, Second=string>")));
        assertDoesNotThrow(() -> Parser.parse(
                withType("Triple<int, Middle=bool, Last=string>")));
    }

    @Test
    void rejectsPositionalAfterNamedTypeArgument() {
        rejectsLatePositional("Pair<First=int, string>");
        rejectsLatePositional("Triple<First=int, bool, Last=string>");
        rejectsLatePositional("Triple<Middle=bool, int, string>");
    }

    @Test
    void rejectsLatePositionalArgumentsInNestedTypes() {
        rejectsLatePositional("Box<Pair<First=int, string>>");
        rejectsLatePositional("Pair<First=Box<int>, string>");
    }

    @Test
    void rejectsDuplicateNamedTypeArgumentsBeforeSemanticBinding() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(withType("Pair<First=int, First=string>")));
        assertTrue(error.getMessage().contains("duplicate named type parameter"),
                error.getMessage());
    }

    @Test
    void acceptsPositionalNestedTypesWithoutChangingExistingGrammar() {
        assertDoesNotThrow(() -> Parser.parse(
                withType("Pair<Box<int>, Second=Vector<string>>")));
    }

    private static String withGenericCall(String typeArguments) {
        return "fnc identity<T, U>(T value, U ignored): T { return value; }\\n"
                + "pub routine main(): void { identity<"
                + typeArguments
                + ">(ignored: \\"x\\", value: 42); return; }\\n";
    }

    @Test
    void explicitGenericFunctionCallsPreserveLegalNamedPermutations() {
        assertDoesNotThrow(() -> Parser.parse(
                withGenericCall("T=int, U=string")));
        assertDoesNotThrow(() -> Parser.parse(
                withGenericCall("U=string, T=int")));
        assertDoesNotThrow(() -> Parser.parse(
                withGenericCall("int, U=string")));
    }

    @Test
    void explicitGenericFunctionCallsRejectLatePositionalArguments() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(withGenericCall("U=string, int")));
        assertTrue(error.getMessage().contains(
                        "positional generic arguments must precede named arguments"),
                error.getMessage());
    }
}
