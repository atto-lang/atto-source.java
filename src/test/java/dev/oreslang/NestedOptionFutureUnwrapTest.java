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
                  val Future<Future<int>> nested_for_flatten = answer().map(|int n| -> {
                    return answer();
                  });
                  val Future<Future<int>> nested_for_await = answer().map(|int n| -> {
                    return answer();
                  });
                  val int explicit_flatten = await nested_for_flatten.flatten();
                  val int two_awaits = await await nested_for_await;
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

    @Test
    void flattenConsumesMoveOnlyNestedOption() {
        String program = """
                define class Box as
                  pub let int value = 1;
                end

                fnc wrong(): void {
                  val Option<Option<Box>> nested = Some(Some(new Box()));
                  val Option<Box> once = nested.flatten();
                  val Option<Box> twice = nested.flatten();
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void flattenConsumesMoveOnlyFutureHandle() {
        String program = """
                fnc wrong(Future<Future<int>> nested): void {
                  val Future<int> once = nested.flatten();
                  val Future<int> twice = nested.flatten();
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void threeLayerOptionFlattenIsExplicitAndAssociative() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Option<Option<Option<int>>> full = Some(Some(Some(17)));
                  val Option<Option<int>> outer = full.flatten();
                  val Option<int> inner = outer.flatten();
                  stdio.stdout.write(inner.unwrap());
                  stdio.stdout.write(":");

                  val Option<Option<Option<int>>> missing_middle = Some(None);
                  stdio.stdout.write(missing_middle.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(missing_middle.flatten().is_none());
                  stdio.stdout.write(":");

                  val Option<Option<Option<int>>> missing_inner = Some(Some(None));
                  stdio.stdout.write(missing_inner.flatten().is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(missing_inner.flatten().flatten().is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("17:true:true:true:true", run(program));
    }

    @Test
    void threeLayerFutureRequiresTwoExplicitFlattenCalls() throws Exception {
        String program = """
                async fnc answer(): int {
                  return 17;
                }

                pub fnc main(): void {
                  val Future<Future<Future<int>>> three = answer().map(|int n| -> {
                    return answer().map(|int value| -> {
                      return answer();
                    });
                  });
                  val Future<int> one = three.flatten().flatten();
                  stdio.stdout.write(await one);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("17", run(program));
    }

    @Test
    void innerFutureFailureMustNotResolveFlattenedFutureSuccessfully() throws Exception {
        String program = """
                async fnc answer(): int {
                  return 17;
                }
                async fnc fail_inner(): int {
                  throw "inner-stage failure";
                }

                pub fnc main(): void {
                  val Future<Future<int>> nested = answer().map(|int value| -> {
                    return fail_inner();
                  });
                  val int value = await nested.flatten();
                  stdio.stdout.write(value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertThrows(PolyglotException.class, () -> run(program));
    }

    @Test
    void outerFutureFailureMustNotInvokeInnerFlattenStage() throws Exception {
        String program = """
                async fnc fail_outer(): int {
                  throw "outer-stage failure";
                }
                async fnc answer(): int {
                  return 17;
                }

                pub fnc main(): void {
                  val Future<Future<int>> nested = fail_outer().map(|int value| -> {
                    return answer();
                  });
                  val int value = await nested.flatten();
                  stdio.stdout.write(value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertThrows(PolyglotException.class, () -> run(program));
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
