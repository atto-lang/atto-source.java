package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deep extraction must agree between the static typechecker and both source
 * execution paths; nested values themselves are not normalized or erased.
 */
final class NestedOptionFutureUnwrapTest {

    @Test
    void nestedOptionRemainsThreeStateAndUnwrapReturnsInnermostPayload() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Option<Option<int>> value = Some(Some(42));
                  val int unwrapped = value.unwrap();
                  stdio.stdout.write(unwrapped);
                  val Option<Option<int>> inner_none = Some(None);
                  val Option<Option<int>> outer_none = None;
                  stdio.stdout.write(inner_none.is_some());
                  stdio.stdout.write(outer_none.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42truetrue", run(program));
    }

    @Test
    void deepUnwrapPanicsIfInnerOptionIsNone() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Option<Option<int>> inner_none = Some(None);
                  val int unwrapped = inner_none.unwrap();
                  stdio.stdout.write(unwrapped);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run(program));
        assertTrue(failure.getMessage().contains("Option::unwrap"));
    }

    @Test
    void awaitTransitivelyResolvesMappedNestedFutures() throws Exception {
        String program = """
                async fnc answer(): int {
                  return 42;
                }

                pub fnc main(): void {
                  val Future<Future<int>> nested = answer().map(|int n| -> {
                    return answer();
                  });
                  val int resolved = await nested;
                  stdio.stdout.write(resolved);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42", run(program));
    }

    @Test
    void anExplicitNestedFutureTypeIsNotAnAliasForFutureOfPayload() {
        String program = """
                fnc consume(Future<int> task): void {
                  return;
                }

                fnc wrong(Future<Future<int>> nested): void {
                  consume(nested);
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(program));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "nested-option-future.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
