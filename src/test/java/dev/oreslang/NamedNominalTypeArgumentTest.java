package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Targeted nominal type-position named generic binding and negative cases. */
final class NamedNominalTypeArgumentTest {
    private static String withType(String type) {
        return """
                define class Box<A, B> as
                  A first
                  B second
                end
                fnc take("""
                + type + " value): int { return 1; }";
    }

    @Test
    void bindsNamesInDeclarationOrderRegardlessOfWrittenOrder() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                withType("Box<B=string, A=int>"))));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                withType("Box<A=int, B=string>"))));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                withType("Box<int, B=string>"))));
    }

    @Test
    void nestedNominalSpecializationsBindNamesIndependently() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                withType("Box<B=Box<A=string, B=int>, A=int>"))));
    }

    @Test
    void duplicateUnknownMissingExtraAndCaseMismatchFailClosed() {
        for (String type : new String[] {
                "Box<int, A=string>",
                "Box<A=int, A=string>",
                "Box<C=int, B=string>",
                "Box<a=int, B=string>",
                "Box<A=int>",
                "Box<int, string, bool>",
                "Box<A=42, B=string>",
                "Box<A=int, B=true>"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(withType(type))), type);
        }
    }

    @Test
    void positionalAfterNamedFailsAtParserBeforeCanonicalization() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(withType("Box<B=string, int>")));
    }

    @Test
    void namedValueArgumentsCannotBeMisreadAsTypeArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(withType("Box<A: int, B=string>")));
    }
}
