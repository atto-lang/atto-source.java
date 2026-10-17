package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrapOptionSemanticsTest {

    @Test
    void trapCallsUseStructuredTwoSlotResultAndNestedSuccessProjection() throws Exception {
        String program = """
                trap fnc value(): int {
                  return 7;
                }

                trap fnc maybe(bool present): Option<int> {
                  if present; then
                    return Some(9);
                  fi
                  return None;
                }

                trap fnc done(): void {
                  return;
                }

                pub fnc main(): void {
                  [const [number], const number_error] = value();
                  [const [nested], const nested_error] = maybe(true);
                  [const void_result, const void_error] = done();

                  stdio.stdout.write(number.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(number_error.is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested.unwrap().unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested_error.is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(void_result.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(void_error.is_none());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("7:true:9:true:true:true", run(program));
    }

    @Test
    void ordinaryThrowIsCapturedAsTrapErrorAndPreservesMessage() throws Exception {
        String program = """
                fnc helper(): int {
                  throw new Error("missing user");
                }

                trap fnc load(): int {
                  return helper();
                }

                pub fnc main(): void {
                  [const [value], const err] = load();
                  stdio.stdout.write(value.is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(err.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(err.unwrap().kind);
                  stdio.stdout.write(":");
                  stdio.stdout.write(err.unwrap().message);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true:true:Thrown:missing user", run(program));
    }

    @Test
    void normalTrapDestructuringExposesSuccessBundleAndErrorOption() throws Exception {
        String program = """
                trap fnc value(): int { return 17; }
                pub fnc main(): void {
                  [const results, const err] = value();
                  stdio.stdout.write(results.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(results.unwrap()[0]);
                  stdio.stdout.write(":");
                  stdio.stdout.write(err.is_none());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true:17:true", run(program));
    }

    @Test
    void trapResultCannotBeDiscardedOrForgedAndErrorSlotCannotBeDiscarded() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        trap fnc value(): int { return 1; }
                        fnc bad(): void {
                          value();
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        trap fnc value(): int { return 1; }
                        fnc bad(): void {
                          [const [result], _] = value();
                          return;
                        }
                        """));

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc forge(): void {
                          const TrapResult<int> invalid = new TrapResult<int>();
                          return;
                        }
                        """));
    }

    @Test
    void trapFunctionValuesKeepStructuredReturnType() throws Exception {
        String source = """
                trap fnc value(int x): int { return x + 1; }
                pub fnc main(): void {
                  val Fnc<int, TrapResult<int>> callback = value;
                  [const [direct], const direct_error] = value(9);
                  [const [via_callback], const callback_error] = callback(9);
                  stdio.stdout.write(direct.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(via_callback.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(direct_error.is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(callback_error.is_none());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("10:10:true:true", run(source));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                trap fnc value(int x): int { return x; }
                fnc bad(): void {
                  val Fnc<int, int> callback = value;
                  return;
                }
                """));
    }

    @Test
    void moduleTrapCallsRemainStructured() throws Exception {
        String source = """
                define module safely as
                  pub trap fnc value(): int { return 23; }
                end
                pub fnc main(): void {
                  [const [result], const err] = safely.value();
                  stdio.stdout.write(result.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(err.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("23:true", run(source));
    }

    @Test
    void raiseAndPanicBypassTrapAndReachNearestRecover() throws Exception {
        String source = """
                trap fnc raises(): int {
                  raise new Error("abort");
                  return 1;
                }

                trap fnc panics(): int {
                  panic new Error("invariant");
                  return 2;
                }

                pub fnc main(): void {
                  recover {
                    [const [ignored], const ignored_error] = raises();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  stdio.stdout.write("|");
                  recover {
                    [const [ignored], const ignored_error] = panics();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("raise:abort|panic:invariant", run(source));
    }

    @Test
    void tryCatchCannotSwallowRaiseButRecoverCan() throws Exception {
        String source = """
                trap fnc escapes(): int {
                  try {
                    raise new Error("leave");
                  } catch (ordinary) {
                    stdio.stdout.write("wrong");
                  }
                  return 3;
                }

                pub fnc main(): void {
                  recover {
                    [const [ignored], const ignored_error] = escapes();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("raise", run(source));
    }

    @Test
    void cleanupFailuresFollowPanicRaiseThrowPrecedence() throws Exception {
        String source = """
                fnc cleanupThrow(): void { throw new Error("cleanup throw"); }
                fnc cleanupRaise(): void { raise new Error("cleanup raise"); }
                fnc cleanupPanic(): void { panic new Error("cleanup panic"); }

                trap fnc raising(): int {
                  defer cleanupThrow();
                  raise new Error("primary raise");
                  return 1;
                }

                trap fnc throwing(): int {
                  defer cleanupRaise();
                  throw new Error("primary throw");
                  return 2;
                }

                trap fnc raisingThenPanicking(): int {
                  defer cleanupPanic();
                  raise new Error("primary raise");
                  return 3;
                }

                pub fnc main(): void {
                  recover {
                    [const [ignored], const ignored_error] = raising();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  stdio.stdout.write("|");
                  recover {
                    [const [ignored], const ignored_error] = throwing();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  stdio.stdout.write("|");
                  recover {
                    [const [ignored], const ignored_error] = raisingThenPanicking();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(
                "raise:primary raise|raise:cleanup raise|panic:cleanup panic",
                run(source));
    }

    @Test
    void internalOptionUnwrapPanicStillBypassesTrapButCanBeRecovered() throws Exception {
        String source = """
                trap fnc panics(): int {
                  val Option<int> missing = None;
                  return missing.unwrap();
                }

                pub fnc main(): void {
                  recover {
                    [const [ignored], const ignored_error] = panics();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("panic:called Option::unwrap() on a None value", run(source));
    }

    @Test
    void bareSuccessTypeAndLegacyOptionTrapAbiAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                trap fnc value(): int { return 7; }
                fnc bad(): void {
                  val Option<int> unwrapped = value();
                  return;
                }
                """));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                trap fnc value(): int { return 7; }
                fnc hold(): void {
                  val TrapResult<int> result = value();
                  return;
                }
                """));
    }

    @Test
    void reservedExceptionalKeywordsParseIntoDedicatedAstNodes() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc control(): void {
                  try {
                    throw new Error("ordinary");
                  } catch (err) {
                    recover {
                      raise err;
                    } catch (signal) {
                      panic new Error(signal.kind);
                    }
                  }
                  return;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "trap-result.ores")
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
