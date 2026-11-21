package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class QuestionPropagationLanguageTest {
    @Test
    void resultOptionAndTernaryRemainDistinctInOneProgram() throws Exception {
        String program = """
                fnc result_step(Result<int, String> value): Result<int, String> {
                  val int x = value?;
                  return Ok(x + 2);
                }

                fnc result_chain(Result<int, String> value): Result<int, String> {
                  val int x = result_step(value)?;
                  return Ok(x * 3);
                }

                fnc optional_step(Option<int> value): Option<int> {
                  val int x = value?;
                  return Some(x + 4);
                }

                pub fnc main(): void {
                  val Result<int, String> ok = Ok(3);
                  val Result<int, String> err = Err("no");
                  val Option<int> some = Some(2);
                  val Option<int> none = None;
                  stdio.stdout.write(result_chain(ok).unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(result_chain(err).is_err());
                  stdio.stdout.write(":");
                  stdio.stdout.write(optional_step(some).unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(optional_step(none).is_none());
                  stdio.stdout.write(":");
                  val int classic = true ? 7 : 8;
                  stdio.stdout.write(classic);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("15:true:6:true:7", run(program));
    }

    @Test
    void earlyPropagationRunsFinallyWithoutEnteringCatch() throws Exception {
        String program = """
                fnc guarded(Result<int, String> value): Result<int, String> {
                  try {
                    val int x = value?;
                    return Ok(x);
                  } catch (error) {
                    stdio.stdout.write("UNEXPECTED-CATCH");
                    return Err("caught");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                }

                pub fnc main(): void {
                  val Result<int, String> missing = Err("no");
                  stdio.stdout.write(guarded(missing).is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("finally:true", run(program));
    }

    @Test
    void rejectsDifferentErrorKindsOrUnsupportedCallables() {
        reject("""
                fnc wrong(Option<int> v): Result<int, String> {
                  val int n = v?;
                  return Ok(n);
                }
                """, "matching Result<T,E> or Option<T>");
        reject("""
                fnc wrong(Result<int, String> v): Result<int, int> {
                  val int n = v?;
                  return Ok(n);
                }
                """, "error type must exactly match");
        reject("""
                fnc wrong(Result<int, String> v): int {
                  return v?;
                }
                """, "matching Result<T,E> or Option<T>");
        reject("""
                async fnc wrong(Option<int> v): Option<int> {
                  return v?;
                }
                """, "direct binding initializer");
        reject("""
                fnc wrong(Option<int> v): Option<int> {
                  return trap v?;
                }
                """, "trap");
        reject("""
                fnc wrong(Option<int> v): Option<int> {
                  val Option<int> x = v;
                  return x? + 1;
                }
                """, "expected ':'");
        reject("""
                async fnc pending(): Result<int, String> {
                  return Ok(4);
                }
                fnc bad(): Result<int, String> {
                  val int x = (await pending())?;
                  return Ok(x);
                }
                """, "cannot enclose await");

    }

    @Test
    void rejectsSourceTaskPromotionOutsideQuestionOperand() {
        reject("""
                async fnc fetch_value(): int {
                  return 3;
                }
                fnc compute(Option<int> source): Option<int> {
                  val int item = source?;
                  val int answer = await fetch_value();
                  return Some(item + answer);
                }
                """, null);
        reject("""
                async fnc fetch_value(): int {
                  return 3;
                }
                fnc helper(): int {
                  return await fetch_value();
                }
                fnc compute(Option<int> source): Option<int> {
                  val int item = source?;
                  val int answer = helper();
                  return Some(item + answer);
                }
                """, null);
    }

    @Test
    void rejectsQuestionInsideDeferredCallbacks() {
        reject("""
                fnc compute(Option<int> source): Option<int> {
                  val Option<int> mapped = source.and_then<int>(|int x| -> {
                    val Option<int> inner = Some(x);
                    val int item = inner?;
                    return Some(item);
                  });
                  return mapped;
                }
                """, "local Result/Option return boundary");
    }

    @Test
    void awaitedResultQuestionReturnsThroughAsyncSourceFlowAndFinally() throws Exception {
        String program = """
                async fnc read_good(): Result<int, String> {
                  return Ok(7);
                }

                async fnc read_bad(): Result<int, String> {
                  return Err("network");
                }

                async fnc success(): Result<int, String> {
                  val int item = (await read_good())?;
                  return Ok(item + 2);
                }

                async fnc failure(): Result<int, String> {
                  try {
                    val int item = (await read_bad())?;
                    stdio.stdout.write("UNEXPECTED-NEXT:");
                    return Ok(item);
                  } catch (error) {
                    stdio.stdout.write("UNEXPECTED-CATCH:");
                    return Err("caught");
                  } finally {
                    stdio.stdout.write("cleanup:");
                  }
                }

                pub fnc main(): void {
                  val Result<int, String> first = await success();
                  stdio.stdout.write(first.unwrap());
                  stdio.stdout.write(":");
                  val Result<int, String> second = await failure();
                  stdio.stdout.write(second.is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("9:cleanup:true", run(program));
    }

    @Test
    void awaitedErrorRunsDeferredCleanupWithoutResumingTheBody() throws Exception {
        String program = """
                async fnc remote_result(): Result<int, String> {
                  return Err("failed");
                }

                async fnc consume(): Result<int, String> {
                  defer stdio.stdout.write("defer:");
                  val int item = (await remote_result())?;
                  stdio.stdout.write("UNEXPECTED-AFTER:");
                  return Ok(item);
                }

                pub fnc main(): void {
                  val Result<int, String> result = await consume();
                  stdio.stdout.write(result.is_err());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("defer:true", run(program));
    }

    @Test
    void questionCannotReplacePendingFinallyOrDeferCompletion() {
        reject("""
                fnc illegal_finally(Option<int> input): Option<int> {
                  try {
                    return Some(9);
                  } catch (failure) {
                    return Some(4);
                  } finally {
                    val int item = input?;
                  }
                }
                """, "cannot exit from defer or finally cleanup");

        reject("""
                fnc illegal_defer(Option<int> input): Option<int> {
                  defer input?;
                  return Some(1);
                }
                """, "cannot exit from defer or finally cleanup");

        reject("""
                async fnc illegal_async_finally(Option<int> input): Option<int> {
                  try {
                    return Some(9);
                  } catch (failure) {
                    return Some(4);
                  } finally {
                    val int item = input?;
                  }
                }
                """, "cannot exit from defer or finally cleanup");
    }

    @Test
    void opaqueImportPreventsIncorrectSynchronousQuestionAdmission() {
        // An imported member may select the SourceTask evaluator even when
        // an unrelated sum operand is purely scalar. The checker must not
        // assume an imported member call cannot suspend.
        reject("""
                import module Service from './service';

                fnc evaluate(Option<int> source): Option<int> {
                  val int value = source?;
                  stdio.stdout.write(value);
                  return Some(value);
                }
                """, "SourceTask continuation");
    }

    @Test
    void asyncOptionQuestionRunsNestedCleanupOnceOnFailureAndSuccess() throws Exception {
        String program = """
                async fnc read_missing(): Option<int> {
                  return None;
                }

                async fnc read_present(): Option<int> {
                  return Some(5);
                }

                async fnc attempt_missing(): Option<int> {
                  defer stdio.stdout.write("outer:");
                  try {
                    defer stdio.stdout.write("inner:");
                    val int item = (await read_missing())?;
                    stdio.stdout.write("UNEXPECTED:");
                    return Some(item);
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                }

                async fnc attempt_present(): Option<int> {
                  defer stdio.stdout.write("outer:");
                  try {
                    defer stdio.stdout.write("inner:");
                    val int item = (await read_present())?;
                    return Some(item + 1);
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                }

                pub fnc main(): void {
                  val Option<int> missing = await attempt_missing();
                  stdio.stdout.write(missing.is_none());
                  stdio.stdout.write(":");
                  val Option<int> present = await attempt_present();
                  stdio.stdout.write(present.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("inner:finally:outer:true:inner:finally:outer:6", run(program));
    }

    @Test
    void resumedExpressionFailureDoesNotReplayCatchFinallyOrDefer() throws Exception {
        String program = """
                async fnc fetch_present(): Option<int> {
                  return Some(3);
                }

                async fnc guarded(): Option<int> {
                  defer stdio.stdout.write("outer:");
                  try {
                    val int value = (await fetch_present())?;
                    val Array<int> empty = new Array<int>();
                    val int invalid = empty.get(0);
                    return Some(value + invalid);
                  } catch (error) {
                    stdio.stdout.write("caught:");
                    return None;
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                }

                pub fnc main(): void {
                  val Option<int> result = await guarded();
                  stdio.stdout.write(result.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        // Ordinary failures after a resumed expression do not re-deliver the
        // earlier value or run the enclosing completion handlers twice.
        assertEquals("caught:finally:outer:true", run(program));
    }

    @Test
    void resumedFailureDoesNotCompleteEnclosingStatementTwice() throws Exception {
        String program = """
                async fnc source_value(): Result<int, String> {
                  return Ok(2);
                }

                async fnc failing(): Result<int, String> {
                  defer stdio.stdout.write("cleanup:");
                  val int value = (await source_value())?;
                  val Array<int> empty = new Array<int>();
                  return Ok(value + empty.get(0));
                }

                pub fnc main(): void {
                  try {
                    val Result<int, String> result = await failing();
                    stdio.stdout.write("UNEXPECTED:");
                  } catch (error) {
                    stdio.stdout.write("caught");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("cleanup:caught", run(program));
    }

    @Test
    void asyncQuestionOnlyAcceptsDirectBindingInitializer() {
        reject("""
                async fnc invalid(Option<int> input): Option<int> {
                  return Some((input?) + 1);
                }
                """, "direct binding initializer");
        reject("""
                async fnc invalid(Option<int> input): Option<int> {
                  val int x = input? + 1;
                  return Some(x);
                }
                """, "expected ':'");
    }

    @Test
    void questionNeverCoercesBetweenSumKindsOrUnawaitedFutures() {
        reject("""
                fnc bad(Result<int, String> value): Option<int> {
                  val int item = value?;
                  return Some(item);
                }
                """, "matching Result<T,E> or Option<T>");
        reject("""
                fnc bad(Option<int> value): Result<int, String> {
                  val int item = value?;
                  return Ok(item);
                }
                """, "matching Result<T,E> or Option<T>");
        reject("""
                fnc bad(Result<int, String> value): Result<int, bool> {
                  val int item = value?;
                  return Ok(item);
                }
                """, "error type must exactly match");
        reject("""
                fnc bad(Future<Result<int, String>> value): Result<int, String> {
                  val int item = value?;
                  return Ok(item);
                }
                """, "matching Result<T,E> or Option<T>");
    }

    private static void reject(String source, String expected) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        if (expected != null) {
            assertTrue(error.getMessage().contains(expected),
                    () -> "Expected '" + expected + "' in: " + error.getMessage());
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "question.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            ctx.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
