package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Token;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OptionalPropertyChainLanguageTest {
    @Test
    void maximalMunchDoesNotChangeTernaryOrTypeQuestion() {
        List<Token> tokens = new Lexer("v?.name ?? defaultValue").scan();
        assertEquals(Token.Type.QUESTION_DOT, tokens.get(1).type());
        assertEquals(Token.Type.QUESTION_QUESTION, tokens.get(3).type());
        List<Token> ternary = new Lexer("flag ? 1 : 2").scan();
        assertEquals(Token.Type.QUESTION, ternary.get(1).type());
    }

    @Test
    void safeMemberOnlyExecutesPresentFieldsAndDefaultIsLazy() throws Exception {
        String source = """
                define class Box as
                    pub val int count = 42;
                    pub val Option<int> optional_count;
                    // Main forbids calls (including Some(...)) in eager field
                    // initializers; construct the Option in the constructor.
                    constructor() { self.optional_count = Some(9); }
                end
                fnc fallback(): int {
                    stdio.stdout.write("F");
                    return 99;
                }
                pub fnc main(): void {
                    val Option<Box> yes = Some(new Box());
                    val Option<Box> no = None;
                    stdio.stdout.write(yes?.count ?? fallback());
                    stdio.stdout.write(":");
                    stdio.stdout.write(no?.count ?? fallback());
                    stdio.stdout.write(":");
                    stdio.stdout.write(yes?.optional_count ?? 0);
                    stdio.stdout.write(":");
                    stdio.stdout.write(no?.optional_count ?? 7);
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42:F99:9:7", run(source));
    }

    @Test
    void rejectsInvalidOptionalAccessAndFallback() {
        assertRejects("""
                fnc bad(int v): Option<int> {
                    return v?.count;
                }
                """, "?. requires");
        assertRejects("""
                fnc bad(Result<int, String> v): Option<int> {
                    return v?.count;
                }
                """, "?. requires");
        assertRejects("""
                fnc bad(Option<int> v): int {
                    return v ?? "not an int";
                }
                """, "?? fallback");
        assertRejects("""
                fnc bad(int v): int {
                    return v ?? 7;
                }
                """, "?? requires");
        assertRejects("""
                define class Box as
                    pub val int count = 1;
                end
                fnc bad(Option<Box> v): Option<int> {
                    return v?.missing;
                }
                """, "missing");
        assertRejects("""
                define class Box as
                    pub val int count = 1;
                end
                fnc bad(Option<Box> v): void {
                    v?.count = 2;
                    return;
                }
                """, "assignment target");
    }

    private static void assertRejects(String source, String expected) {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage() != null
                        && failure.getMessage().contains(expected),
                () -> "Expected '" + expected + "', got: " + failure.getMessage());
    }

    private static String run(String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "optional-chain.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
