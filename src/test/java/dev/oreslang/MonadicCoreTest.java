package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class MonadicCoreTest {

    @Test
    void optionCompositionSatisfiesCoreMonadLaws() throws Exception {
        String program = """
                pub fnc main() => void {
                  val Option<int> m = Some(2);

                  val left_identity = Some(2).flat_map<int>(|x| -> {
                    return Some(x + 3);
                  });
                  stdio.println(left_identity.unwrap());

                  val right_identity = m.flat_map<int>(|x| -> {
                    return Some(x);
                  });
                  stdio.println(right_identity.unwrap());

                  val assoc_left = m
                    .flat_map<int>(|x| -> { return Some(x + 3); })
                    .flat_map<int>(|x| -> { return Some(x * 2); });
                  val assoc_right = m.flat_map<int>(|x| -> {
                    return Some(x + 3).flat_map<int>(|y| -> {
                      return Some(y * 2);
                    });
                  });

                  stdio.println(assoc_left.unwrap());
                  stdio.println(assoc_right.unwrap());
                  stdio.println(Some(20).map<int>(|x| -> {
                    return x + 1;
                  }).unwrap());

                  val Option<int> none = None;
                  stdio.println(none.or_else(|| -> {
                    return Some(9);
                  }).unwrap());

                  val Option<Option<int>> nested = Some(Some(12));
                  stdio.println(nested.flatten().unwrap());
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals(
                List.of("5", "2", "10", "10", "21", "9", "12"),
                outputLines(run(program, "option-monad-laws.ores")));
    }

    @Test
    void resultCompositionMapsSuccessAndRecoversErrors() throws Exception {
        String program = """
                pub fnc main() => void {
                  val Result<int, String> ok = Ok(10);
                  stdio.println(ok
                    .map<int>(|x| -> { return x + 2; })
                    .and_then<int>(|x| -> { return Ok(x * 2); })
                    .unwrap());

                  val Result<int, String> err = Err("bad");
                  stdio.println(err.map_err<int>(|message| -> {
                    return 7;
                  }).is_err());
                  stdio.println(err.or_else<String>(|message| -> {
                    return Ok(33);
                  }).unwrap());

                  val Result<Result<int, String>, String> nested =
                    Ok(Ok(44));
                  stdio.println(nested.flatten().unwrap());
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals(
                List.of("24", "true", "33", "44"),
                outputLines(run(program, "result-monad-core.ores")));
    }

    @Test
    void compositionCallbacksRunThroughTheTailCallTrampoline() throws Exception {
        String program = """
                fnc plus_one(int value) => int {
                  return value + 1;
                }

                pub fnc main() => void {
                  stdio.println(Some(40).map<int>(|value| -> {
                    return plus_one(value);
                  }).unwrap());

                  val Result<int, String> ok = Ok(8);
                  stdio.println(ok.map<int>(|value| -> {
                    return plus_one(value);
                  }).unwrap());
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals(
                List.of("41", "9"),
                outputLines(run(program, "composition-tail-trampoline.ores")));
    }

    @Test
    void shortCircuitBranchesDoNotInvokeCallbacks() throws Exception {
        String program = """
                pub fnc main() => void {
                  val Option<int> none = None;
                  val still_none = none.map<int>(|x| -> {
                    stdio.println("BAD_OPTION_MAP");
                    return x + 1;
                  });
                  stdio.println(still_none.is_none());

                  val Result<int, String> err = Err("bad");
                  val still_err = err.map<int>(|x| -> {
                    stdio.println("BAD_RESULT_MAP");
                    return x + 1;
                  });
                  stdio.println(still_err.is_err());

                  val Result<int, String> ok = Ok(1);
                  val still_ok = ok.map_err<int>(|message| -> {
                    stdio.println("BAD_MAP_ERR");
                    return 7;
                  });
                  stdio.println(still_ok.unwrap());
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        String output = run(program, "composition-short-circuit.ores");
        assertFalse(output.contains("BAD_"));
        assertEquals(List.of("true", "true", "1"), outputLines(output));
    }

    @Test
    void flatMapRequiresSameCompositionFamily() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main() => void {
                          val bad = Some(1).flat_map<int>(|x| -> {
                            return x + 1;
                          });
                          return;
                        }
                        """)));

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main() => void {
                          val Result<int, String> value = Ok(1);
                          val bad = value.flat_map<int>(|x| -> {
                            return Some(x + 1);
                          });
                          return;
                        }
                        """)));
    }

    @Test
    void transformingCompositionRequiresExplicitOutputType() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main() => void {
                          val bad = Some(1).map(|x| -> {
                            return x + 1;
                          });
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("explicit output type"));
    }

    @Test
    void polymorphicCompositionMembersCannotBeExtracted() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main() => void {
                          val mapper = Some(1).map;
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("direct-call-only"));
    }

    @Test
    void flattenRequiresTheSameNestedFamilyAndResultErrorType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main() => void {
                          val bad = Some(1).flatten();
                          return;
                        }
                        """)));

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Result<Result<int, bool>, String> value) => void {
                          val flattened = value.flatten();
                          return;
                        }
                        """)));
    }

    private static List<String> outputLines(String output) {
        return output.lines()
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
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
