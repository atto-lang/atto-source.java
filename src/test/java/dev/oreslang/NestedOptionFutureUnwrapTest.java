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
 * Nested wrappers retain their type identity and stage/absence semantics.
 * Flattening must be explicit and agree between the compiler and evaluator.
 */
final class NestedOptionFutureUnwrapTest {

    @Test
    void nestedOptionRemainsThreeStateUntilExplicitFlatten() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Option<Option<int>> value = Some(Some(42));
                  val Option<int> one_layer = value.unwrap();
                  val int unwrapped = one_layer.unwrap();
                  val int flattened = value.flatten().unwrap();
                  stdio.stdout.write(unwrapped);
                  stdio.stdout.write(flattened);
                  val Option<Option<int>> inner_none = Some(None);
                  val Option<Option<int>> outer_none = None;
                  stdio.stdout.write(inner_none.is_some());
                  stdio.stdout.write(outer_none.is_none());
                  stdio.stdout.write(inner_none.flatten().is_none());
                  stdio.stdout.write(outer_none.flatten().is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("4242truetruetruetrue", run(program));
    }

    @Test
    void unwrapOnlyRemovesOneLayerAndInnerNoneStillPanicsWhenUnwrapped() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Option<Option<int>> inner_none = Some(None);
                  val Option<int> unwrapped_once = inner_none.unwrap();
                  val int value = unwrapped_once.unwrap();
                  stdio.stdout.write(value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        PolyglotException failure = assertThrows(PolyglotException.class, () -> run(program));
        assertTrue(failure.getMessage().contains("Option::unwrap"));
    }

    @Test
    void explicitFutureFlattenAndDoubleAwaitResolveTwoStages() throws Exception {
        String program = """
                async fnc answer(): int {
                  return 42;
                }

                pub fnc main(): void {
                  val Future<Future<int>> nested = answer().map(|int n| -> {
                    return answer();
                  });
                  val int explicit_flatten = await nested.flatten();
                  val int two_awaits = await await nested;
                  stdio.stdout.write(explicit_flatten);
                  stdio.stdout.write(two_awaits);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("4242", run(program));
    }

    @Test
    void nestedFutureAndOptionTypesAreNotPayloadTypeAliases() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc take(Future<int> future): void { return; }
                fnc wrong(Future<Future<int>> nested): void {
                  take(nested);
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc take(Option<int> option): void { return; }
                fnc wrong(Option<Option<int>> nested): void {
                  take(nested);
                  return;
                }
                """));
    }

    @Test
    void flattenRequiresAStaticallyProvenNestedWrapper() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc wrong(Option<int> plain): void {
                  plain.flatten();
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc wrong(Future<int> plain): void {
                  plain.flatten();
                  return;
                }
                """));
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
