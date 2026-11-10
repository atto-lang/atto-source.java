package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RangeAndTernaryBlockExpressionTest {
    private static final Path FIXTURE = Path.of("tests", "conformance", "ternary_blocks_ranges.atto");

    @Test
    void parsesTypechecksAndExecutesAttoGuestFixture() throws Exception {
        String source = Files.readString(FIXTURE, StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("123|123|42|42|42", run(source));
    }

    @Test
    void suspendableValueBlockRetainsSelectedArmAndLexicalScope() throws Exception {
        String source = Files.readString(
                Path.of("tests", "conformance", "ternary_blocks_ranges_async.atto"),
                StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("42", run(source));
    }

    @Test
    void recognizesRangesWithoutChangingArgumentSpread() {
        assertTrue(new Lexer("1..3").scan().stream().anyMatch(t -> t.type() == Token.Type.RANGE_EXCLUSIVE));
        assertTrue(new Lexer("1..=3").scan().stream().anyMatch(t -> t.type() == Token.Type.RANGE_INCLUSIVE));
        assertTrue(new Lexer("...").scan().stream().anyMatch(t -> t.type() == Token.Type.ELLIPSIS));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(wrap("for id in 1..2..3 { }")));
    }

    @Test
    void inclusiveRangeDoesNotOverflowAtSignedMax() throws Exception {
        String source = wrap("for id in 9223372036854775807..=9223372036854775807 { stdio.stdout.write(id); }");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("9223372036854775807", run(source));
    }

    @Test
    void exclusiveRangeAtSameEndpointIsEmpty() throws Exception {
        String source = wrap("for id in 9..9 { stdio.stdout.write(id); }");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("", run(source));
    }

    @Test
    void rejectsBadTypesAndMissingTailValues() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap("for id in 1..true { }"))));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap("val x = true ? { 1; } : { 2 };")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(wrap("val x = true ? { } : { 2 };")));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap(
                        "val x: int = true ? { val hidden = 1; hidden } : { hidden };"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrap(
                        "val x = 123 ? { 1 } : { 2 };"))));
    }

    @Test
    void ternaryRetainsRightAssociativityAndLazySelectedBranch() throws Exception {
        String source = wrap("""
                val result: int = false ? { 1 } : true ? {
                  val factor = 7;
                  factor * 6
                } : { 3 };
                stdio.stdout.write(result);
                """);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("42", run(source));
    }

    private static String wrap(String body) {
        return "pub routine main(): void { " + body + " }";
    }

    private static String run(String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "ternary-block-range.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
