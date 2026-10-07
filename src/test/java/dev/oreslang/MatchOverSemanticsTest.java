package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Contextual match ... over statement vs expression semantics. */
final class MatchOverSemanticsTest {
    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    private static IllegalArgumentException reject(String source) {
        return assertThrows(IllegalArgumentException.class, () -> check(source));
    }

    private static String run(String source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            ctx.eval(Source.newBuilder(OresLanguage.ID, source, "match-over.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void localReturnSuppliesValueWithoutExitingEnclosingCallable() throws Exception {
        assertEquals("11\n12\n", run("""
                fnc choose(bool flag): int {
                  val int value = match flag over
                    true -> { return 1; }
                    false -> { return 2; }
                  end;
                  return value + 10;
                }
                pub fnc main(): void {
                  stdio.println(choose(true));
                  stdio.println(choose(false));
                  return;
                }
                """));
    }

    @Test
    void returnMatchOverReturnsFromTheFunctionNotTheArm() throws Exception {
        assertEquals("9\n8\n", run("""
                fnc choose(bool flag): int {
                  return match flag over
                    true -> { return 9; }
                    false -> { return 8; }
                  end;
                }
                pub fnc main(): void {
                  stdio.println(choose(true));
                  stdio.println(choose(false));
                  return;
                }
                """));
    }

    @Test
    void doMatchDiscardsIncompatibleBranchResultTypes() throws Exception {
        assertEquals("done\n", run("""
                pub fnc main(): void {
                  do match true over
                    true -> { return 4; }
                    false -> { return "different"; }
                  end
                  stdio.println("done");
                  return;
                }
                """));
    }

    @Test
    void exhaustivelyHandlesPresentValuePresentEmptyAndMissing() throws Exception {
        assertEquals("42\n-1\n-2\n", run("""
                fnc classify(Option<Option<int>> entry): int {
                  val int result = match entry over
                    Some(Some(value)) -> { return value; }
                    Some(None) -> { return -1; }
                    None -> { return -2; }
                  end;
                  return result;
                }
                pub fnc main(): void {
                  stdio.println(classify(Some(Some(42))));
                  stdio.println(classify(Some(None)));
                  stdio.println(classify(None));
                  return;
                }
                """));
    }

    @Test
    void matchOverArmsCanRunStatementsBeforeReturning() throws Exception {
        assertEquals("3\n", run("""
                pub fnc main(): void {
                  val int result = match true over
                    true -> { val int base = 1; return base + 2; }
                    false -> { return 0; }
                  end;
                  stdio.println(result);
                  return;
                }
                """));
    }

    @Test
    void legacyStatementMatchStillExitsCallableWhenReturnIsUsed() throws Exception {
        assertEquals("4\n", run("""
                fnc choose(bool flag): int {
                  match flag
                    true -> { return 4; }
                    false -> { return 5; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(choose(true));
                  return;
                }
                """));
    }

    @Test
    void ordinaryStatementMatchSupportsVoidCallbacksAndKeepsExecuting() throws Exception {
        assertEquals("hit\ncontinued\n", run("""
                pub fnc main(): void {
                  do match Some(5) over
                    Some(value) -> { stdio.println("hit"); }
                    None -> { stdio.println("miss"); }
                  end
                  stdio.println("continued");
                  return;
                }
                """));
    }

    @Test
    void explicitMatchReturnRejectsVoidArmResults() {
        assertTrue(reject("""
                fnc noop(): void {
                  return;
                }
                fnc bad(bool flag): void {
                  val result = match flag over
                    true -> { return noop(); }
                    false -> { return noop(); }
                  end;
                  return;
                }
                """).getMessage().contains("returns void"));
    }

    @Test
    void doMatchDiscardsArmLocalResultAndContinues() throws Exception {
        assertEquals("continued\n", run("""
                pub fnc main(): void {
                  do match Some(5) over
                    Some(value) -> { return value + 10; }
                    None -> { return 0; }
                  end
                  stdio.println("continued");
                  return;
                }
                """));
    }

    @Test
    void doMatchAllowsCallbackArmsWithoutReturn() throws Exception {
        assertEquals("side-effect\ncontinued\n", run("""
                pub fnc main(): void {
                  do match true over
                    true -> { stdio.println("side-effect"); }
                    false -> { stdio.println("other"); }
                  end
                  stdio.println("continued");
                  return;
                }
                """));
    }

    @Test
    void rejectsBareStatementOverWithoutExplicitDoMatch() {
        assertTrue(reject("""
                pub fnc main(): void {
                  match true over
                    true -> { stdio.println("side-effect"); }
                    false -> { stdio.println("other"); }
                  end
                  return;
                }
                """).getMessage().contains("do match"));
    }

    @Test
    void rejectsUsingDoMatchAsExpression() {
        reject("""
                pub fnc main(): void {
                  val x = do match true over
                    true -> { return 1; }
                    false -> { return 2; }
                  end;
                  return;
                }
                """);
    }

    @Test
    void doTrySelectLocalReturnCannotExitEnclosingFunction() throws Exception {
        assertEquals("continued\n", run("""
                pub fnc main(): void {
                  do try select {
                  default:
                    return 999;
                  }
                  stdio.println("continued");
                  return;
                }
                """));
    }

    @Test
    void rejectsLegacyHyphenatedNoReturnAndDoMatchWithoutOver() {
        reject("""
                pub fnc main(): void {
                  match true no-return over
                    true -> { stdio.println("obsolete"); }
                    false -> { stdio.println("obsolete"); }
                  end
                  return;
                }
                """);
        assertTrue(reject("""
                pub fnc main(): void {
                  do match true
                    true -> { stdio.println("missing separator"); }
                    false -> { stdio.println("missing separator"); }
                  end
                  return;
                }
                """).getMessage().contains("requires 'over'"));
    }

    @Test
    void rejectsMissingOrValuelessArmReturns() {
        assertTrue(reject("""
                fnc bad(bool flag): int {
                  val x = match flag over
                    true -> { return 1; }
                    false -> { 2; }
                  end;
                  return x;
                }
                """).getMessage().contains("value-producing"));
        assertTrue(reject("""
                fnc bad(bool flag): int {
                  val x = match flag over
                    true -> { return 1; }
                    false -> { return; }
                  end;
                  return x;
                }
                """).getMessage().contains("value-producing"));
        assertTrue(reject("""
                fnc bad(bool flag): int {
                  val x = match flag over
                    true -> 1
                    false -> { return 2; }
                  end;
                  return x;
                }
                """).getMessage().contains("braced arms"));
    }

    @Test
    void rejectsNonExhaustiveAndMismatchedResults() {
        assertTrue(reject("""
                fnc bad(Option<Option<int>> value): int {
                  val x = match value over
                    Some(Some(inner)) -> { return inner; }
                    None -> { return 0; }
                  end;
                  return x;
                }
                """).getMessage().contains("non-exhaustive"));
        assertTrue(reject("""
                fnc bad(bool flag): int {
                  val x = match flag over
                    true -> { return 1; }
                    false -> { return "wrong"; }
                  end;
                  return x;
                }
                """).getMessage().contains("incompatible"));
    }

    @Test
    void rejectsNestedEarlyReturnsInsteadOfEscapingCallable() {
        assertTrue(reject("""
                fnc bad(bool outer, bool inner): int {
                  val int x = match outer over
                    true -> {
                      if inner then
                        return 99;
                      fi
                      return 1;
                    }
                    false -> { return 2; }
                  end;
                  return x;
                }
                """).getMessage().contains("earlier or nested returns"));
    }

    @Test
    void rejectsUnreachableFallbackAndOverlappingNestedPatterns() {
        assertTrue(reject("""
                fnc bad(Option<Option<int>> value): int {
                  val x = match value over
                    Some(Some(inner)) -> { return inner; }
                    Some(None) -> { return 0; }
                    None -> { return 0; }
                    _ -> { return 2; }
                  end;
                  return x;
                }
                """).getMessage().contains("unreachable"));
        assertTrue(reject("""
                fnc bad(Option<Option<int>> value): int {
                  val x = match value over
                    Some(inner) -> { return 1; }
                    Some(None) -> { return 2; }
                    None -> { return 3; }
                  end;
                  return x;
                }
                """).getMessage().matches("(?s).*?(overlapping|unreachable).*"));
    }

    @Test
    void rejectsInventedMatchAndReturnSpellingOrLambdaPipeAsPattern() {
        reject("""
                fnc bad(bool flag): int {
                  val x = match-and-return flag over
                    true -> { return 1; }
                    false -> { return 2; }
                  end;
                  return x;
                }
                """);
        reject("""
                fnc bad(Option<int> value): int {
                  val x = match value over
                    Some(inner) -> { return inner; }
                    None -> { return 2; }
                    || -> { return 3; }
                  end;
                  return x;
                }
                """);
    }
}
