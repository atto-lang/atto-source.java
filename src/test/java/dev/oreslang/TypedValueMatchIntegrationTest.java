package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class TypedValueMatchIntegrationTest {
    private static String execute(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, program, "typed-value-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(guest);
        }
        return out.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    private static void rejects(String program, String diagnostic) {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(program)));
        assertTrue(rejected.getMessage().contains(diagnostic),
                () -> "Expected '" + diagnostic + "', got " + rejected.getMessage());
    }

    @Test
    void typedValueMatchUsesExpressionLocalReturnAndFallbackAfterSpecificCases() throws Exception {
        assertEquals("42\n6", execute("""
                fnc classify(int n): int {
                  const result: int = match<ret=int> n over {
                    default { return 5; }
                    on 7 { return 41; }
                  };
                  return result + 1;
                }
                pub fnc main(): void {
                  stdio.println(classify(7));
                  stdio.println(classify(9));
                }
                """));
    }

    @Test
    void typedMatchResultTypeIsIndependentOfOuterCallableReturn() throws Exception {
        assertEquals("passed", execute("""
                fnc describe(int n): string {
                  const result: int = match<ret=int> n over {
                    on 7 { return 41; }
                    default { return 5; }
                  };
                  if result == 41 { return "passed"; }
                  else { return "failed"; } fi
                }
                pub fnc main(): void { stdio.println(describe(7)); }
                """));
    }

    @Test
    void returnMatchIsValueProducingButDoMatchStillReturnsFromCallable() throws Exception {
        assertEquals("42\n9", execute("""
                fnc value(int n): int {
                  return match<ret=int> n over {
                    on 7 { return 42; }
                    default { return 9; }
                  };
                }
                fnc procedural(int n): int {
                  do match<ret=int> n over {
                    on 7 { return 42; }
                    default { return 9; }
                  }
                }
                pub fnc main(): void {
                  stdio.println(value(7));
                  stdio.println(procedural(8));
                }
                """));
    }

    @Test
    void valueMatchDoesNotInferReturnContract() {
        rejects("""
                fnc invalid(int n): int {
                  const x: int = match n over {
                    on 7 { return 7; }
                    default { return 0; }
                  };
                  return x;
                }
                """, "requires <ret=Type>");
    }

    @Test
    void missingDefaultAndMismatchedReturnsFailClosed() {
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    on 7 { return 7; }
                  };
                }
                """, "non-exhaustive match");
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    on 7 { return "wrong"; }
                    default { return 9; }
                  };
                }
                """, "value match arm result");
    }

    @Test
    void complexArmsAndCallableResultExpressionsAreNotAccidentallyLowered() {
        rejects("""
                fnc compute(int n): int { return n; }
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    on 7 { const x: int = 42; return compute(x); }
                    default { return 9; }
                  };
                }
                """, "typed value match arm result must be a pure scalar expression");
        rejects("""
                fnc compute(): int { return 42; }
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    on 7 { return compute(); }
                    default { return 9; }
                  };
                }
                """, "typed value match arm result must be a pure scalar expression");
    }

    @Test
    void asyncAndOwnedValueResultsRemainGated() {
        rejects("""
                async fnc invalid(int n): int {
                  return match<ret=int> n over {
                    on 7 { return 7; }
                    default { return 9; }
                  };
                }
                """, "continuation lowering");
        rejects("""
                fnc invalid(int n): Option<int> {
                  return match<ret=Option<int>> n over {
                    on 7 { return Some(7); }
                    default { return None; }
                  };
                }
                """, "scalar");
    }

    @Test
    void finallyCannotUseAnArmLocalReturnToBypassLexicalBan() {
        rejects("""
                fnc invalid(int n): int {
                  try { return 7; }
                  catch (e) { return 9; }
                  finally {
                    const result: int = match<ret=int> n over {
                      on 7 { return 42; }
                      default { return 0; }
                    };
                  }
                }
                """, "return is forbidden directly inside finally");
    }

    @Test
    void enclosingDeferRunsBeforeCallerReceivesMatchValue() throws Exception {
        assertEquals("cleanup\n42", execute("""
                fnc classify(int n): int {
                  defer stdio.println("cleanup");
                  const value: int = match<ret=int> n over {
                    on 7 { return 42; }
                    default { return 9; }
                  };
                  return value;
                }
                pub fnc main(): void { stdio.println(classify(7)); }
                """));
    }

    @Test
    void enclosingFinallyRunsBeforeCallerReceivesMatchValue() throws Exception {
        assertEquals("finalized\n42", execute("""
                fnc classify(int n): int {
                  try {
                    const value: int = match<ret=int> n over {
                      on 7 { return 42; }
                      default { return 9; }
                    };
                    return value;
                  }
                  catch (e) { return 0; }
                  finally { stdio.println("finalized"); }
                }
                pub fnc main(): void { stdio.println(classify(7)); }
                """));
    }

    @Test
    void borrowedReturnContractMustNotBeErasedIntoAnOwnedScalar() {
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=&int> n over {
                    default { return n; }
                  };
                }
                """, "owned scalar result");
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=&mut int> n over {
                    default { return n; }
                  };
                }
                """, "owned scalar result");
    }

    @Test
    void unaryBorrowCannotAppearInDirectScalarResult() {
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    default { return &n; }
                  };
                }
                """, "value match arm result");
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    default { return &mut n; }
                  };
                }
                """, "value match arm result");
    }

    @Test
    void borrowedScalarNameCannotBeUsedAsOwnedMatchSubject() {
        rejects("""
                fnc invalid(int n): int {
                  const borrowed: &int = &n;
                  return match<ret=int> borrowed over {
                    default { return 9; }
                  };
                }
                """, "locally resolved owned scalar");
    }

    @Test
    void scalarPatternBindingsRemainLocallyScopedAndExecutable() throws Exception {
        assertEquals("16", execute("""
                fnc value(int n): int {
                  return match<ret=int> n over {
                    default as number { return number + 7; }
                  };
                }
                pub fnc main(): void { stdio.println(value(9)); }
                """));
    }

    @Test
    void closureCaptureAnalysisTraversesMatchSubjectAndArmValues() throws Exception {
        assertEquals("8\n11", execute("""
                fnc classify(int n): int {
                  const runner = || -> {
                    return match<ret=int> n over {
                      on 7 { return n + 1; }
                      default as number { return number + 2; }
                    };
                  };
                  return runner();
                }
                pub fnc main(): void {
                  stdio.println(classify(7));
                  stdio.println(classify(9));
                }
                """));
    }

    @Test
    void multiStatementArmsReturnLocallyWithDeferredCleanup() throws Exception {
        assertEquals("cleanup-seven\n42\ncleanup-other\n9", execute("""
                fnc classify(int n): int {
                  const result: int = match<ret=int> n over {
                    on 7 {
                      const value: int = 41;
                      defer stdio.println("cleanup-seven");
                      return value + 1;
                    }
                    default {
                      const value: int = 9;
                      defer stdio.println("cleanup-other");
                      return value;
                    }
                  };
                  return result;
                }
                pub fnc main(): void {
                  stdio.println(classify(7));
                  stdio.println(classify(9));
                }
                """));
    }

    @Test
    void branchwiseArmResultsMustBeExhaustive() throws Exception {
        assertEquals("10\n20", execute("""
                fnc classify(bool b): int {
                  const result: int = match<ret=int> b over {
                    default {
                      if b { return 10; }
                      else { return 20; } fi
                    }
                  };
                  return result;
                }
                pub fnc main(): void {
                  stdio.println(classify(true));
                  stdio.println(classify(false));
                }
                """));
    }

    @Test
    void nonReturningConditionalArmIsRejected() {
        rejects("""
                fnc invalid(bool b): int {
                  return match<ret=int> b over {
                    default { if b { return 10; } fi }
                  };
                }
                """, "must produce a local result on every reachable path");
    }

    @Test
    void armTryFinallyRunsBeforeCompletionWithoutCatchInterception() throws Exception {
        assertEquals("finally-arm\n42", execute("""
                fnc classify(int n): int {
                  return match<ret=int> n over {
                    default {
                      try { const value: int = n + 1; return value; }
                      catch (e) { return 0; }
                      finally { stdio.println("finally-arm"); }
                    }
                  };
                }
                pub fnc main(): void { stdio.println(classify(41)); }
                """));
    }

    @Test
    void nestedTypedMatchesResolveNearestLexicalArm() throws Exception {
        assertEquals("44", execute("""
                fnc classify(int n): int {
                  return match<ret=int> n over {
                    default {
                      const inner: int = match<ret=int> n over {
                        on 41 { return 42; }
                        default { return 0; }
                      };
                      return inner + 2;
                    }
                  };
                }
                pub fnc main(): void { stdio.println(classify(41)); }
                """));
    }

    @Test
    void armFinallyReturnIsForbidden() {
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    default {
                      try { return n; }
                      catch (e) { return 0; }
                      finally { return 1; }
                    }
                  };
                }
                """, "finally");
    }

    @Test
    void arbitraryArmDeferEffectsRemainRejected() {
        rejects("""
                fnc effect(): void { stdio.println("called"); }
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    default {
                      defer effect();
                      return n;
                    }
                  };
                }
                """, "synchronous stdio.print/println");
    }

    @Test
    void deferredFailureStillExecutesEveryRemainingDefer() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, """
                pub fnc main(): void {
                  defer stdio.println("later-cleanup");
                  defer 1 % 0;
                }
                """, "defer-all.ores").mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            assertThrows(org.graalvm.polyglot.PolyglotException.class,
                    () -> context.eval(guest));
        }
        assertEquals("later-cleanup", out.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void failedDeferIsSuppressedUnderOriginalExecutionFailure() throws Exception {
        Source guest = Source.newBuilder(OresLanguage.ID, """
                pub fnc main(): void {
                  defer 2 % 0;
                  const value: int = 1 % 0;
                  stdio.println(value);
                }
                """, "defer-suppressed.ores").mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            org.graalvm.polyglot.PolyglotException guestFailure =
                    assertThrows(org.graalvm.polyglot.PolyglotException.class,
                            () -> context.eval(guest));
            Throwable current = guestFailure.isHostException()
                    ? guestFailure.asHostException() : guestFailure;
            boolean foundSuppressed = false;
            for (int depth = 0; current != null && depth < 12; depth++) {
                if (current.getSuppressed().length > 0) {
                    foundSuppressed = true;
                    break;
                }
                current = current.getCause();
            }
            assertTrue(foundSuppressed,
                    "original execution failure must retain deferred failure as suppressed");
        }
    }
}
