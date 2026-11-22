package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Editor lexical contract probe run against a pinned, unmodified compiler
 * source tree. This file and its TSV live in the editor repo, not the
 * compiler's trusted source; CI explicitly verifies the source commit
 * before copying the tests into an isolated checkout.
 */
public final class EditorSyntaxLexerFixtureTest {
    private record Fixture(String id, String source, String lexer, String parser, String note) { }

    private List<Fixture> load() throws Exception {
        Path file = Path.of("src/test/resources/editor-syntax-lexer.tsv");
        assertTrue(Files.isRegularFile(file), "matrix missing in compiler checkout");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        var fixtures = new java.util.ArrayList<Fixture>();
        Set<String> ids = new java.util.HashSet<>();
        for (String line : lines) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] row = line.split("\t", -1);
            assertEquals(5, row.length, "malformed matrix line");
            assertTrue(row[0].matches("[a-z0-9-]+"), row[0]);
            assertTrue(ids.add(row[0]), "duplicate matrix ID: " + row[0]);
            String source = new String(HexFormat.of().parseHex(row[1]), StandardCharsets.UTF_8);
            assertTrue(List.of("S", "P", "N").contains(row[3]), row[0] + " invalid parser oracle");
            assertFalse(row[2].isEmpty() && row[3].equals("S"), row[0] + " has no oracle");
            fixtures.add(new Fixture(row[0], source, row[2], row[3], row[4]));
        }
        assertTrue(fixtures.size() >= 50, "expected >=50 individual compiler fixtures");
        return fixtures;
    }

    @TestFactory
    Stream<DynamicTest> compilerLexerAndParserContract() throws Exception {
        return load().stream().map(f -> DynamicTest.dynamicTest(f.id(), () -> {
            if (!f.lexer().isEmpty()) {
                if (f.lexer().startsWith("!")) {
                    var error = assertThrows(IllegalArgumentException.class,
                            () -> new Lexer(f.source()).scan());
                    assertTrue(error.getMessage().contains(f.lexer().substring(1)),
                            f.id() + " unexpected error: " + error);
                } else {
                    List<String> actual = new Lexer(f.source()).scan().stream()
                            .filter(t -> t.type() != Token.Type.EOF)
                            .map(t -> t.type().name())
                            .toList();
                    List<String> expected = Arrays.asList(f.lexer().split(","));
                    assertEquals(expected, actual, f.id() + " (" + f.note() + ")");
                }
            }
            if (f.parser().equals("P")) {
                assertDoesNotThrow(() -> Parser.parse(f.source()), f.id());
            } else if (f.parser().equals("N")) {
                assertThrows(IllegalArgumentException.class,
                        () -> Parser.parse(f.source()), f.id());
            }
        }));
    }
}
