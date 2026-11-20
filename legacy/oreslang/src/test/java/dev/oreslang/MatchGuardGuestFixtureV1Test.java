package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Real compiler admission and positive Graal guest for exact .atto sources. */
final class MatchGuardGuestFixtureV1Test {
    private static final Path FIXTURES =
            Path.of("tests/fixtures/ownership-match-guard-v1");

    private static String fixture(String name) throws Exception {
        assertTrue(name.matches("(accept|reject)-[a-z-]+\\.atto"));
        Path path = FIXTURES.resolve(name);
        assertTrue(Files.isRegularFile(path) && !Files.isSymbolicLink(path),
                "fixture missing or a symlink: " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void semanticReject(String name) throws Exception {
        String source = fixture(name);
        assertDoesNotThrow(() -> Parser.parse(source),
                "a parser-only rejection is NOT evidence of a borrow proof");
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(exception.getMessage().contains("moved"),
                "wrong diagnostic for a guard MOVE: " + exception.getMessage());
    }

    @Test
    void statementGuardRejectsRealCompiledSource() throws Exception {
        semanticReject("reject-statement-guard-move.atto");
    }

    @Test
    void valueMatchGuardRejectsRealCompiledSource() throws Exception {
        semanticReject("reject-value-guard-move.atto");
    }

    @Test
    void borrowedGuardActuallyExecutesFallbackGuest() throws Exception {
        String source = fixture("accept-borrowed-match-guard.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true).out(stdout).build()) {
            context.eval(Source.newBuilder("ores", source,
                    "accept-borrowed-match-guard.atto").build());
        }
        assertEquals("7", stdout.toString(StandardCharsets.UTF_8),
                "actual guest must evaluate the false guard then its fallback");
    }
    @Test
    void borrowedValueMatchGuardActuallyExecutesFallbackGuest() throws Exception {
        String name = "accept-borrowed-value-guard.atto";
        String source = fixture(name);
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true).out(stdout).build()) {
            context.eval(Source.newBuilder("ores", source, name).build());
        }
        assertEquals("7", stdout.toString(StandardCharsets.UTF_8),
                "value match false guard must reach the fallback with owner intact");
    }

}
