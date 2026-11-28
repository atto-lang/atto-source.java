package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import dev.oreslang.parser.Parser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class SuspensionEffectAliasTest {
    private static final List<String> ALIASES = List.of("suspends", "susp", "⧖", "∂");

    @Test void lexemesRetainedAndTokenKindShared() {
        for (String spelling : ALIASES) {
            var tokens = new Lexer(spelling + " fnc").scan();
            assertEquals(Token.Type.SUSPENDS, tokens.get(0).type());
            assertEquals(spelling, tokens.get(0).lexeme());
            assertEquals(Token.Type.FNC, tokens.get(1).type());
        }
        assertEquals(Token.Type.IDENT, new Lexer("suspension").scan().get(0).type());
        assertEquals(Token.Type.IDENT, new Lexer("suspends2").scan().get(0).type());
        assertEquals(Token.Type.IDENT, new Lexer("π").scan().get(0).type());
        assertEquals(Token.Type.STRING, new Lexer("\"⧖ ∂ susp\"").scan().get(0).type());
        assertEquals(Token.Type.EOF, new Lexer("// ⧖ ∂ susp").scan().get(0).type());
    }

    @Test void allAliasesPreserveEffectChecks() {
        for (String spelling : ALIASES) {
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                    spelling + " fnc read_blocking(): int { return 1; }"), spelling);
            assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                    spelling + " fnc inner(): int { return 7; } fnc invalid(): int { return inner(); }"), spelling);
            for (String invalid : List.of("critical " + spelling, spelling + " critical",
                    "suspends " + spelling, spelling + " suspends")) {
                assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                        invalid + " fnc work(): void { return; }"), invalid);
            }
        }
    }

    @Test void awaitingCannotHideASuspendingCallFromTheEffectChecker() {
        for (String spelling : ALIASES) {
            assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                    spelling + " fnc inner(): int { return 7; } fnc invalid(): int { return await inner(); }"), spelling);
        }
    }

    @Test void everyAliasRunsNestedTaskContinuationAndDefer() throws Exception {
        String fixture = java.nio.file.Files.readString(java.nio.file.Path.of(
                "tests", "fixtures", "suspends-await-v1", "inline-nested-defer.atto"),
                StandardCharsets.UTF_8);
        for (String spelling : ALIASES) {
            String code = fixture.replace("suspends fnc", spelling + " fnc");
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code), spelling);
            var output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                context.eval(Source.newBuilder(OresLanguage.ID, code, "alias-nested-defer.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
            }
            assertEquals("defer:42|root", output.toString(StandardCharsets.UTF_8), spelling);
        }
    }

    @Test void everyPairOfAliasesIsADuplicateEffect() {
        for (String first : ALIASES) {
            for (String second : ALIASES) {
                String code = first + " " + second + " fnc bad(): void { return; }";
                assertThrows(IllegalArgumentException.class, () -> Parser.parse(code), code);
            }
        }
    }

    @Test void allAliasesExecuteTheSameGuest() throws Exception {
        for (String spelling : ALIASES) {
            String code = """
                    %1$s fnc fetch(): int { return 41; }
                    %1$s fnc compute(): int {
                        let int value = await fetch();
                        return value + 1;
                    }
                    pub %1$s fnc main(): void {
                        stdio.stdout.write(compute());
                        return;
                    }
                    """.formatted(spelling);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code), spelling);
            var output = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(output).build()) {
                context.eval(Source.newBuilder(OresLanguage.ID, code, "suspension-alias.atto")
                        .mimeType(OresLanguage.MIME_TYPE).build());
            }
            assertEquals("42", output.toString(StandardCharsets.UTF_8).trim(), spelling);
        }
    }
}
