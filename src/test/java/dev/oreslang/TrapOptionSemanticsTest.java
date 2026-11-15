package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

                trap routine done(): void {
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
    void explicitTryCatchHandlesThrowBeforeTrapBoundary() throws Exception {
        String source = """
                trap fnc catches(): int {
                  try {
                    throw new Error("handled locally");
                  } catch (err) {
                    return 5;
                  }
                  return 0;
                }

                pub fnc main(): void {
                  [const [value], const err] = catches();
                  stdio.stdout.write(value.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(err.is_none());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("5:true", run(source));
    }

    @Test
    void recoverDoesNotCatchOrdinaryThrow() throws Exception {
        String source = """
                trap fnc ordinary(): int {
                  recover {
                    throw new Error("not a recover signal");
                  } catch (signal) {
                    return 99;
                  }
                  return 1;
                }

                pub fnc main(): void {
                  [const [value], const err] = ordinary();
                  stdio.stdout.write(value.is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(err.unwrap().kind);
                  stdio.stdout.write(":");
                  stdio.stdout.write(err.unwrap().message);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:Thrown:not a recover signal", run(source));
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

                  val TrapResult<int> stored = value();
                  [const [nested], const stored_error] = stored;
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(stored_error.is_none());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true:17:true:17:true", run(program));
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
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("""
                        fnc forge(): void {
                          const TrapResult<int> invalid = 1 as TrapResult<int>;
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
    void raiseReachesRecoverButPanicEscapes() throws Exception {
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
        assertPanicEscapes(source, "raise:abort|", "Oreslang panic signal");
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
    void raiseInsideRecoverHandlerSkipsThatSameRecoverFrame() throws Exception {
        String source = """
                pub fnc main(): void {
                  recover {
                    recover {
                      raise new Error("inner");
                    } catch (inner) {
                      raise new Error("outer");
                    }
                  } catch (outer) {
                    stdio.stdout.write(outer.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(outer.message);
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("raise:outer", run(source));
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
        assertPanicEscapes(source, "raise:primary raise|raise:cleanup raise|", "Oreslang panic signal");
    }

    @Test
    void deferRunsExactlyOnceBeforeTrapAndRecoveryObservation() throws Exception {
        String source = """
                fnc mark(): void { stdio.stdout.write("D"); }

                trap fnc throws(): int {
                  defer mark();
                  throw new Error("ordinary");
                  return 1;
                }

                trap fnc raises(): int {
                  defer mark();
                  raise new Error("elevated");
                  return 2;
                }

                trap fnc panics(): int {
                  defer mark();
                  panic new Error("fatal");
                  return 3;
                }

                pub fnc main(): void {
                  [const [unused], const err] = throws();
                  stdio.stdout.write("throw|");
                  recover {
                    [const [unusedRaise], const errorRaise] = raises();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write("|");
                  }
                  recover {
                    [const [unusedPanic], const errorPanic] = panics();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                  }
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertPanicEscapes(source, "Dthrow|Draise|D", "Oreslang panic signal");
    }

    @Test
    void internalOptionUnwrapPanicBypassesTrapAndRecover() throws Exception {
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
        assertPanicEscapes(source, "", "called Option::unwrap() on a None value");
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
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
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

    @Test
    void allExceptionalEffectsRejectScalarPayload() throws Exception {
        var fixture = java.nio.file.Path.of("src/test/resources/exception-boundaries/non_error.atto");
        String template = java.nio.file.Files.readString(fixture);
        for (String keyword : java.util.List.of("throw", "raise", "panic")) {
            String program = template.replace("throw 43", keyword + " 43");
            assertDoesNotThrow(() -> Parser.parse(program));
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program));
            assertTrue(failure.getMessage().contains(keyword
                    + " requires an owned compiler-provided Error"), failure::getMessage);
        }
    }

    @Test
    void recoveryRejectsTerminalFailuresBehindAsyncWrappersAndCleanup() {
        var raised = new dev.oreslang.runtime.GuestControlSignal.Raise("raise");
        var wrapped = new java.util.concurrent.CompletionException(
                new java.util.concurrent.ExecutionException(raised));
        assertSame(raised, dev.oreslang.runtime.GuestControlSignal.recoverableRaise(wrapped));
        raised.addSuppressed(new dev.oreslang.runtime.GuestControlSignal.Throw("cleanup"));
        assertSame(raised, dev.oreslang.runtime.GuestControlSignal.recoverableRaise(wrapped));
        raised.addSuppressed(new java.util.concurrent.CancellationException("cancelled"));
        assertNull(dev.oreslang.runtime.GuestControlSignal.recoverableRaise(wrapped));

        var panic = new dev.oreslang.runtime.GuestControlSignal.Panic("panic");
        assertNull(dev.oreslang.runtime.GuestControlSignal.recoverableRaise(
                new java.util.concurrent.CompletionException(panic)));
        var primary = new dev.oreslang.runtime.GuestControlSignal.Raise("primary");
        primary.addSuppressed(panic);
        assertNull(dev.oreslang.runtime.GuestControlSignal.recoverableRaise(primary));
    }

    @Test
    void recoveryRejectsHostWrapperSubclassesWithoutReadingTheirCause() {
        var raised = new dev.oreslang.runtime.GuestControlSignal.Raise("raise");
        var causeReads = new java.util.concurrent.atomic.AtomicInteger();
        var hostWrapper = new java.util.concurrent.CompletionException(raised) {
            @Override public Throwable getCause() {
                causeReads.incrementAndGet();
                return raised;
            }
        };
        assertNull(dev.oreslang.runtime.GuestControlSignal.recoverableRaise(hostWrapper));
        assertNull(dev.oreslang.runtime.GuestControlSignal.recoverableRaise(
                new java.util.concurrent.ExecutionException(hostWrapper)));
        assertEquals(0, causeReads.get());
    }

    private static void assertPanicEscapes(String program, String expectedOutput, String diagnostic)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "panic-boundary.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                .out(output).build()) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
        }
        assertEquals(expectedOutput, output.toString(StandardCharsets.UTF_8));
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
