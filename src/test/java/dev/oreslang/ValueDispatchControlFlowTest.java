package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ValueDispatchControlFlowTest {
    private static void admitted(String source) {
        var program = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(program);
    }

    private static String execute(String source) throws Exception {
        admitted(source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source built = Source.newBuilder(OresLanguage.ID, source, "value-dispatch.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(built);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void expressionArmsYieldLocallyWhileDoArmsReturnFromFunction() throws Exception {
        String output = execute("""
                fnc matched(int v): string {
                  const x = match v over
                    on 1 { return "one"; }
                    on _ { return "other"; }
                  end
                  return x;
                }

                fnc returnedMatch(int v): string {
                  return match v over
                    on 1 { return "one"; }
                    on _ { return "other"; }
                  end
                }

                fnc discardedMatch(int v): string {
                  match v over
                    on _ { return "discarded"; }
                  end
                  return "continued";
                }

                fnc proceduralMatch(int v): string {
                  do match v over
                    on 1 { return "stopped"; }
                  end
                  return "continued";
                }

                fnc terminalDoMatch(int v): string {
                  return do match v over
                    on 1 { return "stopped"; }
                    on _ { return "fallback"; }
                  end
                }

                fnc switched(int v): string {
                  const x = switch v
                    case 1 { return "one"; }
                    default { return "other"; }
                  end
                  return x;
                }

                fnc returnedSwitch(int v): string {
                  return switch v
                    case 1 { return "one"; }
                    default { return "other"; }
                  end
                }

                fnc proceduralSwitch(int v): string {
                  do switch v
                    case 1 { return "stopped"; }
                  end
                  return "continued";
                }

                fnc terminalDoSwitch(int v): string {
                  return do switch v
                    case 1 { return "stopped"; }
                    default { return "fallback"; }
                  end
                }

                pub fnc main(): void {
                  stdio.println(matched(1));
                  stdio.println(returnedMatch(2));
                  stdio.println(discardedMatch(2));
                  stdio.println(proceduralMatch(2));
                  stdio.println(terminalDoMatch(1));
                  stdio.println(switched(1));
                  stdio.println(returnedSwitch(2));
                  stdio.println(proceduralSwitch(2));
                  stdio.println(terminalDoSwitch(1));
                  return;
                }
                """);
        assertEquals("one\nother\ncontinued\ncontinued\nstopped\none\nother\ncontinued\nstopped\n", output);
    }

    @Test
    void rejectNonExhaustiveAndMissingArmValueButAllowPartialDo() {
        IllegalArgumentException noMatch = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int x): string {
                          return match x over
                            on 1 { return "one"; }
                          end
                        }
                        """));
        assertTrue(noMatch.getMessage().contains("non-exhaustive"), noMatch.getMessage());

        IllegalArgumentException noYield = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int x): string {
                          return match x over
                            on _ { stdio.println("side effect"); }
                          end
                        }
                        """));
        assertTrue(noYield.getMessage().contains("does not return a value"), noYield.getMessage());

        IllegalArgumentException noSwitch = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int x): string {
                          return switch x
                            case 1 { return "one"; }
                          end
                        }
                        """));
        assertTrue(noSwitch.getMessage().contains("non-exhaustive"), noSwitch.getMessage());

        assertDoesNotThrow(() -> admitted("""
                fnc okay(int x): string {
                  do match x over
                    on 1 { return "one"; }
                  end
                  do switch x
                    case 2 { return "two"; }
                  end
                  return "other";
                }
                """));

        IllegalArgumentException noReturn = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int x): string {
                          return do switch x
                            case 1 { return "one"; }
                            default { stdio.println("not terminal"); }
                          end
                        }
                        """));
        assertTrue(noReturn.getMessage().contains("must return"), noReturn.getMessage());
    }

    @Test
    void nestedDoMatchRetainsFunctionReturnBoundary() throws Exception {
        assertEquals("function\n", execute("""
                fnc nested(int v): string {
                  const result = match v over
                    on _ {
                      do match v over
                        on 1 { return "function"; }
                      end
                      return "expression";
                    }
                  end
                  return result;
                }

                pub fnc main(): void {
                  stdio.println(nested(1));
                  return;
                }
                """));
    }
}
