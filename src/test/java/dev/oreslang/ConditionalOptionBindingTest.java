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

/**
 * Conditional as const/let is an Option presence test, not an implicit
 * truthiness conversion. Phase 1 accepts Copy payloads only.
 */
final class ConditionalOptionBindingTest {
    @Test
    void copyOptionBindingsTypeCheckAndRunInNormalInterpreter() throws Exception {
        String program = """
                fnc increment(Option<int> maybe): int {
                  if maybe as let value then
                    value = value + 1;
                    return value;
                  else
                    return 0;
                  fi
                }

                fnc guard(Option<int> maybe): bool {
                  if maybe as const value && value eq 15 then
                    return true;
                  else
                    return false;
                  fi
                }

                fnc shorthand(Option<int> maybe): bool {
                  if (maybe as const value) eq 15 then
                    return true;
                  else
                    return false;
                  fi
                }

                fnc typed(Option<int> maybe): bool {
                  if (maybe as const value) is type int then
                    return true;
                  else
                    return false;
                  fi
                }

                fnc optionBool(Option<bool> maybe): bool {
                  if maybe as const value then
                    return value;
                  else
                    return true;
                  fi
                }

                pub fnc main(): void {
                  stdio.println(increment(Some(15)));
                  stdio.println(increment(None));
                  stdio.println(guard(Some(15)));
                  stdio.println(guard(Some(0)));
                  stdio.println(shorthand(Some(15)));
                  stdio.println(shorthand(None));
                  stdio.println(typed(Some(5)));
                  stdio.println(typed(None));
                  stdio.println(optionBool(Some(false)));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        assertEquals(List.of(
                "16", "0", "true", "false", "true", "false",
                "true", "false", "false"), run(program));
    }

    @Test
    void scopeMutabilityAndCarrierTypeFailClosed() {
        rejects("""
                fnc bad(Option<int> maybe): void {
                  if maybe as const value then
                    value = 2;
                  fi
                }
                """, "cannot reassign");

        rejects("""
                fnc bad(Option<int> maybe): int {
                  if maybe as let value then
                    return value;
                  else
                    return value;
                  fi
                }
                """, "unknown name");

        rejects("""
                fnc bad(int number): bool {
                  if number as const value then
                    return true;
                  else
                    return false;
                  fi
                }
                """, "requires Option");

        rejects("""
                define class Box as
                end
                fnc bad(Option<Box> maybe): bool {
                  if maybe as let value then
                    return true;
                  else
                    return false;
                  fi
                }
                """, "non-Copy");

        rejects("""
                fnc bad(Option<int> maybe): int {
                  val int number = maybe as const value;
                  return number;
                }
                """, "only valid in an if predicate");
    }

    @Test
    void guardsDoNotLeakBindingsAcrossShortCircuitOrOuterScope() {
        rejects("""
                fnc bad(Option<int> maybe): int {
                  if maybe as let value && value eq 15 then
                    return value;
                  fi
                  return value;
                }
                """, "unknown name");

        rejects("""
                fnc bad(Option<int> maybe): bool {
                  if maybe then
                    return true;
                  else
                    return false;
                  fi
                }
                """, "expected");
    }

    private static void rejects(String source, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static List<String> run(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "conditional-option-binding.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank()).toList();
    }
}
