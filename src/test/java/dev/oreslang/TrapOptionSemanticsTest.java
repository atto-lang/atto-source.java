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
    void trappedCallsPreserveNestedOptionAndVoidSuccess() throws Exception {
        String program = """
                trap fnc value(): int { return 7; }
                trap fnc present(): Option<int> { return Some(9); }
                trap fnc missing(): Option<int> { return None; }
                trap routine done(): void { return; }

                pub fnc main(): void {
                  val Option<int> number = value();
                  val Option<Option<int>> nested = present();
                  val Option<Option<int>> nested_none = missing();
                  val Option<void> done_result = done();
                  stdio.stdout.write(number.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested.unwrap().unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested_none.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested_none.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(done_result.is_some());
                  return;
                }
                """;
        assertEquals("7:9:true:true:true", run(program));
    }

    @Test
    void ordinaryThrowBecomesNoneAndCanBeDiscarded() throws Exception {
        String program = """
                fnc helper(): int { throw new Error("missing user"); }
                trap fnc load(): int { return helper(); }
                pub fnc main(): void {
                  val Option<int> result = load();
                  load();
                  stdio.stdout.write(result.is_none());
                  return;
                }
                """;
        assertEquals("true", run(program));
    }

    @Test
    void explicitTryCatchHandlesThrowBeforeTrapBoundary() throws Exception {
        String program = """
                trap fnc catches(): int {
                  try {
                    throw new Error("handled locally");
                  } catch (err) {
                    return 5;
                  }
                  return 0;
                }
                pub fnc main(): void {
                  stdio.stdout.write(catches().unwrap());
                  return;
                }
                """;
        assertEquals("5", run(program));
    }

    @Test
    void trapConsumesOrdinaryThrowBeforeOuterRecovery() throws Exception {
        String program = """
                trap fnc ordinary(): int {
                  throw new Error("ordinary");
                }
                pub fnc main(): void {
                  recover {
                    stdio.stdout.write(ordinary().is_none());
                  } catch (signal) {
                    stdio.stdout.write("unexpected recovery");
                  }
                  return;
                }
                """;
        assertEquals("true", run(program));
    }

    @Test
    void trapFunctionValuesKeepOptionReturnType() throws Exception {
        String program = """
                trap fnc value(int x): int { return x + 1; }
                pub fnc main(): void {
                  val Fnc<int, Option<int>> callback = value;
                  stdio.stdout.write(value(9).unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(callback(9).unwrap());
                  return;
                }
                """;
        assertEquals("10:10", run(program));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                trap fnc value(int x): int { return x; }
                fnc bad(): void {
                  val Fnc<int, int> callback = value;
                  return;
                }
                """));
    }

    @Test
    void moduleTrapCallsReturnOption() throws Exception {
        String program = """
                define module safely as
                  pub trap fnc value(): int { return 23; }
                end
                pub fnc main(): void {
                  val Option<int> result = safely.value();
                  stdio.stdout.write(result.unwrap());
                  return;
                }
                """;
        assertEquals("23", run(program));
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
                    raises();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  stdio.stdout.write("|");
                  recover {
                    panics();
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
                    escapes();
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
                    raising();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  stdio.stdout.write("|");
                  recover {
                    throwing();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(signal.message);
                  }
                  stdio.stdout.write("|");
                  recover {
                    raisingThenPanicking();
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
                  throws();
                  stdio.stdout.write("throw|");
                  recover {
                    raises();
                  } catch (signal) {
                    stdio.stdout.write(signal.kind);
                    stdio.stdout.write("|");
                  }
                  recover {
                    panics();
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
                    panics();
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
    void bareSuccessTypeAndUnshippedTrapResultAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                trap fnc value(): int { return 7; }
                fnc bad(): void {
                  val int unwrapped = value();
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

    @Test
    void recordedSourceLocationsDoNotPreventRecoveryOrHideTerminalCleanup() {
        var raised = new dev.oreslang.runtime.GuestControlSignal.Raise("raise");
        dev.oreslang.runtime.SourceBoundaryTrace.record(raised, "test.atto", "callee", "await");
        var wrapped = new java.util.concurrent.CompletionException(raised);
        dev.oreslang.runtime.SourceBoundaryTrace.record(wrapped, "test.atto", "caller", "await");
        assertSame(raised, dev.oreslang.runtime.GuestControlSignal.recoverableRaise(wrapped));
        raised.addSuppressed(new dev.oreslang.runtime.GuestControlSignal.Panic("cleanup panic"));
        assertNull(dev.oreslang.runtime.GuestControlSignal.recoverableRaise(wrapped));
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void actorPanicAfterAwaitUnwindsCleanupAndEscapesCatch() throws Exception {
        var fixture = java.nio.file.Path.of(
                "src/test/resources/exception-boundaries/actor_panic_after_await.atto");
        var failure = assertPanicEscapes(java.nio.file.Files.readString(fixture),
                "resumed|inner|outer|", "Oreslang panic signal");
        var trace = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(trace));
        assertTrue(trace.toString().contains("resumed source task receive"), trace::toString);
    }

    private static PolyglotException assertPanicEscapes(
            String program, String expectedOutput, String diagnostic)
            throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "panic-boundary.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false)
                .out(output).build()) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval(source));
            assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
            assertEquals(expectedOutput, output.toString(StandardCharsets.UTF_8));
            return failure;
        }
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
