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
                """, "return value");
    }

    @Test
    void complexArmsAndCallableResultExpressionsAreNotAccidentallyLowered() {
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    on 7 { const x: int = 42; return x; }
                    default { return 9; }
                  };
                }
                """, "one direct return");
        rejects("""
                fnc compute(): int { return 42; }
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    on 7 { return compute(); }
                    default { return 9; }
                  };
                }
                """, "one direct return");
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
                """, "one direct return");
        rejects("""
                fnc invalid(int n): int {
                  return match<ret=int> n over {
                    default { return &mut n; }
                  };
                }
                """, "one direct return");
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
}
