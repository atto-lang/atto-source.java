package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit local return in value-producing match arms. */
final class MatchAndReturnTest {
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
            ctx.eval(Source.newBuilder(OresLanguage.ID, source, "match-and-return.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void localReturnSuppliesValueWithoutExitingEnclosingCallable() throws Exception {
        assertEquals("11\n12\n", run("""
                fnc choose(bool flag): int {
                  const int value = match-and-return flag over
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
    void exhaustivelyHandlesPresentValuePresentEmptyAndMissing() throws Exception {
        assertEquals("42\n-1\n-2\n", run("""
                fnc classify(Option<Option<int>> entry): int {
                  val int result = match-and-return entry over
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
    void bracedMatchWorksAndArmMayRunStatementsBeforeReturning() throws Exception {
        assertEquals("3\n", run("""
                pub fnc main(): void {
                  val int result = match-and-return true {
                    true -> { val int base = 1; return base + 2; }
                    false -> { return 0; }
                  };
                  stdio.println(result);
                  return;
                }
                """));
    }

    @Test
    void ordinaryStatementMatchStillExitsCallableWhenReturnIsUsed() throws Exception {
        assertEquals("4\n", run("""
                fnc choose(bool flag): int {
                  match flag over
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
    void rejectsMissingOrValuelessArmReturns() {
        assertTrue(reject("""
                fnc bad(bool flag): int {
                  val x = match-and-return flag over
                    true -> { return 1; }
                    false -> { 2; }
                  end;
                  return x;
                }
                """).getMessage().contains("match-and-return"));
        assertTrue(reject("""
                fnc bad(bool flag): int {
                  val x = match-and-return flag {
                    true -> { return 1; }
                    false -> { return; }
                  };
                  return x;
                }
                """).getMessage().contains("match-and-return"));
        assertTrue(reject("""
                fnc bad(bool flag): int {
                  val x = match-and-return flag {
                    true -> 1
                    false -> { return 2; }
                  };
                  return x;
                }
                """).getMessage().contains("braced arms"));
    }

    @Test
    void rejectsNonExhaustiveAndMismatchedResults() {
        assertTrue(reject("""
                fnc bad(Option<Option<int>> value): int {
                  val x = match-and-return value over
                    Some(Some(inner)) -> { return inner; }
                    None -> { return 0; }
                  end;
                  return x;
                }
                """).getMessage().contains("non-exhaustive"));
        assertTrue(reject("""
                fnc bad(bool flag): int {
                  val x = match-and-return flag over
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
                  val int x = match-and-return outer over
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
                """).getMessage().contains("nested callable returns"));
    }

    @Test
    void rejectsUnreachableFallbackAndOverlappingNestedPatterns() {
        assertTrue(reject("""
                fnc bad(Option<Option<int>> value): int {
                  val x = match-and-return value over
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
                  val x = match-and-return value over
                    Some(inner) -> { return 1; }
                    Some(None) -> { return 2; }
                    None -> { return 3; }
                  end;
                  return x;
                }
                """).getMessage().matches("(?s).*?(overlapping|unreachable).*"));
    }

    @Test
    void rejectsSeparatedOperatorOrLambdaPipeAsPattern() {
        reject("""
                fnc bad(bool flag): int {
                  val x = match - and - return flag {
                    true -> { return 1; }
                    false -> { return 2; }
                  };
                  return x;
                }
                """);
        reject("""
                fnc bad(Option<int> value): int {
                  val x = match-and-return value over
                    Some(inner) -> { return inner; }
                    None -> { return 2; }
                    || -> { return 3; }
                  end;
                  return x;
                }
                """);
    }
}
