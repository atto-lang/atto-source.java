package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The slim arrow closes the arm header; "do" changes value collection, not return scope. */
final class MatchSelectArrowDestructureContractTest {
    private static Ast.Program checks(String source) {
        Ast.Program parsed = Parser.parse(source);
        Ast.Program typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);
        return typed;
    }

    private static String outputOf(String source) throws Exception {
        checks(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "match-select-arrows.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(program);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void doSelectRecordProjectionReturnsFromEnclosingCallable() throws Exception {
        String source = """
                fnc add(Channel<{x: int, y: int}> values): int {
                  do select {
                    when readch values: let {x,y} -> {
                      return x + y;
                    }
                  }
                  return -1;
                }

                pub routine main(): void {
                  val Channel<{x: int, y: int}> values =
                      Channel.new<{x: int, y: int}>(1);
                  writech values, struct{x: int, y: int}{x: 2, y: 3};
                  stdio.stdout.write(add(values));
                  return;
                }
                """;
        assertEquals("5", outputOf(source));
    }

    @Test
    void doMatchConstructorRecordProjectionReturnsFromEnclosingCallable() throws Exception {
        String source = """
                fnc evaluate(Option<{v: int}> value): int {
                  do match value over
                    on Some as {v} -> {
                      return v;
                    }
                    on _ -> {
                      return 0;
                    }
                  end
                  return -1;
                }

                pub routine main(): void {
                  val Option<{v: int}> input = Some(struct{v: int}{v: 42});
                  stdio.stdout.write(evaluate(input));
                  return;
                }
                """;
        assertEquals("42", outputOf(source));
    }

    @Test
    void typedAndDestructuredSelectArmsCannotOmitSlimArrow() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse("""
                        fnc choose(Channel<{x: int, y: int}> input): void {
                          do select {
                            when readch input: let {x,y} {
                              stdio.println(x);
                            }
                          }
                        }
                        """));
        assertTrue(missing.getMessage().contains("require '->'"), missing.getMessage());
    }

    @Test
    void selectFatArrowCannotMasqueradeAsPatternBodyDelimiter() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse("""
                        fnc choose(Channel<{x: int, y: int}> input): void {
                          do select {
                            when readch input: let {x,y} => {
                              stdio.println(x);
                            }
                          }
                        }
                        """));
        assertTrue(error.getMessage().contains("->"), error.getMessage());
    }

    @Test
    void matchAlwaysRequiresSlimArrowAndRejectsFatArrow() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse("""
                        fnc choose(Option<{v: int}> input): void {
                          do match input over
                            on Some as {v} {
                              stdio.println(v);
                            }
                            on _ -> {}
                          end
                        }
                        """));
        assertTrue(missing.getMessage().contains("->"), missing.getMessage());

        IllegalArgumentException fat = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse("""
                        fnc choose(Option<{v: int}> input): void {
                          do match input over
                            on Some as {v} => {
                              stdio.println(v);
                            }
                            on _ -> {}
                          end
                        }
                        """));
        assertTrue(fat.getMessage().contains("slim arrow"), fat.getMessage());
    }

    @Test
    void armLocalProjectionVariablesDoNotLeakOutsideTheirArm() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checks("""
                        fnc choose(Channel<{x: int, y: int}> input): void {
                          do select {
                            when readch input: let {x,y} -> {
                              stdio.println(x);
                            }
                          }
                          stdio.println(y);
                        }
                        """));
        assertNotNull(failure.getMessage());
    }

    @Test
    void doSelectReturnTypeIsCheckedAgainstEnclosingFunction() {
        IllegalArgumentException wrong = assertThrows(
                IllegalArgumentException.class, () -> checks("""
                        fnc choose(Channel<{x: int, y: int}> input): int {
                          do select {
                            when readch input: let {x,y} -> {
                              return "not an integer";
                            }
                          }
                          return 0;
                        }
                        """));
        assertNotNull(wrong.getMessage());
    }

    @Test
    void bracedConstructorProjectionRequiresSlimArrowEvenWithLegacyBracedArms() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse("""
                        fnc extract(Option<{v: int}> input): int {
                          do match input over {
                            on Some as {v} { return v; }
                            on None -> { return 0; }
                          }
                          return -1;
                        }
                        """));
        assertTrue(missing.getMessage().contains("->"), missing.getMessage());
    }

}
