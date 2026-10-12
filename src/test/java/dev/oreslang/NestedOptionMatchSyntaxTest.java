package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class NestedOptionMatchSyntaxTest {

    private static void accepts(String program) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
    }

    private static IllegalArgumentException rejects(String program) {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void threeStatesHaveCompleteNestedCoverage() {
        accepts("""
                fnc classify(Option<Option<int>> entry): int {
                  match entry
                    Some(Some(value)) -> { return value; }
                    Some(None) -> { return -1; }
                    None -> { return -2; }
                  end
                }
                """);
    }

    @Test
    void fallbackUsesWildcardOrElse() {
        accepts("""
                fnc classify(Option<Option<int>> entry): int {
                  match entry

                    Some(Some(value)) -> { return value; }
                    _ -> { return -1; }
                  end
                }
                """);
        accepts("""
                fnc classify(Option<int> entry): int {
                  match entry
                    Some(value) -> { return value; }
                    _ -> { return 0; }
                  end
                }
                """);
        accepts("""
                fnc classify(Option<int> entry): int {
                  match entry

                    Some(value) -> { return value; }
                    _ -> { return 0; }
                  end
                }
                """);
    }

    @Test
    void deeplyNestedConstructorCoverageIsNotInferredFromOuterSome() {
        IllegalArgumentException err = rejects("""
                fnc incomplete(Option<Option<int>> entry): int {
                  match entry
                    Some(Some(value)) -> { return value; }
                    None -> { return 0; }
                  end
                }
                """);
        assertTrue(err.getMessage().contains("non-exhaustive"), err.getMessage());
    }

    @Test
    void overlappingNestedConstructorsAreRejected() {
        IllegalArgumentException err = rejects("""
                fnc overlap(Option<Option<int>> entry): int {
                  match entry
                    Some(value) -> { return 1; }
                    Some(None) -> { return 2; }
                    None -> { return 3; }
                  end
                }
                """);
        assertTrue(err.getMessage().contains("overlapping match arms")
                || err.getMessage().contains("unreachable"), err.getMessage());
    }

    @Test
    void unmatchedWildcardFunctionLiteralIsNotAPattern() {
        IllegalArgumentException err = rejects("""
                fnc invalid(Option<int> entry): int {
                  match entry
                    Some(value) -> { return value; }
                    None -> { return 0; }
                    || -> { return 1; }
                  end
                }
                """);
        assertTrue(err.getMessage().contains("pattern"), err.getMessage());
    }

    @Test
    void fallbacksAfterCompleteSomeNoneCoverageAreUnreachable() {
        for (String fallback : new String[]{"_", "else"}) {
            IllegalArgumentException err = rejects("""
                    fnc invalid(Option<int> entry): int {
                      match entry
                        Some(value) -> { return value; }
                        None -> { return 0; }
                    """ + "    " + fallback + " -> { return 1; }\n" + """
                      end
                    }
                    """);
            assertTrue(err.getMessage().contains("unreachable"), err.getMessage());
        }
    }

    @Test
    void nestedResultAlsoRequiresEveryVariant() {
        accepts("""
                fnc classify(Result<Option<int>, bool> entry): int {
                  match entry
                    Ok(Some(value)) -> { return value; }
                    Ok(None) -> { return 0; }
                    Err(flag) -> { return -1; }
                  end
                }
                """);
        assertTrue(rejects("""
                fnc incomplete(Result<Option<int>, bool> entry): int {
                  match entry
                    Ok(Some(value)) -> { return value; }
                    Err(flag) -> { return 0; }
                  end
                }
                """).getMessage().contains("non-exhaustive"));
    }

    @Test
    void runtimeDispatchesAllThreeStates() throws Exception {
        String code = """
                fnc classify(Option<Option<int>> entry): int {
                  match entry
                    Some(Some(value)) -> { return value; }
                    Some(None) -> { return -1; }
                    None -> { return -2; }
                  end
                }

                pub fnc main(): void {
                  stdio.println(classify(Some(Some(42))));
                  stdio.println(classify(Some(None)));
                  stdio.println(classify(None));
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source src = Source.newBuilder(OresLanguage.ID, code, "nested-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(src);
        }
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("42"), text);
        assertTrue(text.contains("-1"), text);
        assertTrue(text.contains("-2"), text);
    }

    @Test
    void missingOuterNoneIsStillNonExhaustiveWithBothPresentShapesHandled() {
        IllegalArgumentException err = rejects("""
                fnc incomplete(Option<Option<int>> entry): int {
                  match entry
                    Some(Some(value)) -> { return value; }
                    Some(None) -> { return -1; }
                  end
                }
                """);
        assertTrue(err.getMessage().contains("non-exhaustive"), err.getMessage());
    }

    @Test
    void someInnerIsAnOptionNotImplicitlyUnwrapped() {
        rejects("""
                fnc invalid(Option<Option<int>> entry): int {
                  match entry
                    Some(inner) -> { return inner; }
                    None -> { return -1; }
                  end
                }
                """);
        accepts("""
                fnc valid(Option<Option<int>> entry): int {
                  match entry
                    Some(inner) -> {
                      match inner
                        Some(value) -> { return value; }
                        None -> { return -1; }
                      end
                    }
                    None -> { return -2; }
                  end
                }
                """);
    }

    @Test
    void optionIsNotTruthyAndCannotBeUsedAsImplicitIfUnwrap() {
        rejects("""
                fnc invalid(Option<bool> entry): int {
                  if entry then
                    return 1;
                  fi
                  return 0;
                }
                """);
        rejects("""
                fnc invalid(Option<int> entry): int {
                  if val value = entry then
                    return value;
                  fi
                  return 0;
                }
                """);
    }

    @Test
    void patternsCannotPretendOptionIntContainsStoredNone() {
        rejects("""
                fnc invalid(Option<int> entry): int {
                  match entry
                    Some(None) -> { return 1; }
                    None -> { return 0; }
                  end
                }
                """);
    }

    @Test
    void guardedArmDoesNotMakePartialSomeCoverageExhaustive() {
        IllegalArgumentException err = rejects("""
                fnc invalid(Option<int> entry): int {
                  match entry
                    Some(value) when value > 0 -> { return value; }
                    None -> { return 0; }
                  end
                }
                """);
        assertTrue(err.getMessage().contains("non-exhaustive"), err.getMessage());
    }

    @Test
    void constructorBindingsDoNotEscapeTheirLexicalArm() {
        rejects("""
                fnc invalid(Option<int> entry): int {
                  match entry
                    Some(value) -> {}
                    None -> {}
                  end
                  return value;
                }
                """);
    }

    @Test
    void nestedBoolOptionRetainsFalsePresentNoneAndMissing() throws Exception {
        String code = """
                fnc classify(Option<Option<bool>> entry): int {
                  match entry
                    Some(Some(false)) -> { return 10; }
                    Some(Some(true)) -> { return 20; }
                    Some(None) -> { return 30; }
                    None -> { return 40; }
                  end
                }

                pub fnc main(): void {
                  stdio.println(classify(Some(Some(false))));
                  stdio.println(classify(Some(Some(true))));
                  stdio.println(classify(Some(None)));
                  stdio.println(classify(None));
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source src = Source.newBuilder(OresLanguage.ID, code, "nested-bool-map-states.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(src);
        }
        assertEquals("10\n20\n30\n40",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").strip());
    }

    @Test
    void singleScrutineeEvaluationForNestedOptionMatch() throws Exception {
        String code = """
                fnc lookup(): Option<Option<int>> {
                  stdio.println("lookup");
                  return Some(None);
                }

                pub fnc main(): void {
                  match lookup()
                    Some(Some(value)) -> { stdio.println("stored"); }
                    Some(None) -> { stdio.println("empty"); }
                    None -> { stdio.println("missing"); }
                  end
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source src = Source.newBuilder(OresLanguage.ID, code, "once-map-lookup.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(src);
        }
        assertEquals("lookup\nempty",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").strip());
    }
}
