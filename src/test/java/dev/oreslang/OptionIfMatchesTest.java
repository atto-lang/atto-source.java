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

/**
 * The canonical Option refinement is an explicit, branch-local pattern test:
 * if m2 matches Some(foo) then ... fi.
 *
 * In particular, Foo is never silently promoted to Option<Foo>.
 */
final class OptionIfMatchesTest {
    private static final String FOO = """
            define class Foo as
              pub run(): int {
                return 42;
              }
            end
            """;

    @Test
    void optionalFooCanBeInitializedLaterAndCalledOnlyInsideSuccessfulMatch() throws Exception {
        String program = FOO + """
                fnc callIfPresent(Option<Foo> m2): int {
                  if m2 matches Some(foo) then
                    return foo.run();
                  fi
                  return -1;
                }

                pub fnc main(): void {
                  stdio.println(callIfPresent(None));
                  stdio.println(callIfPresent(Some(new Foo())));

                  let Option<Foo> m2 = None;
                  if m2 matches Some(foo) then
                    stdio.println(999);
                  fi

                  // A failed match must short-circuit its right-hand side.
                  if m2 matches Some(foo) && foo.run() == 42 then
                    stdio.println(999);
                  fi

                  m2 = Some(new Foo());
                  if m2 matches Some(foo) then
                    stdio.println(foo.run());
                  fi
                  if m2 matches Some(foo) && foo.run() == 42 then
                    stdio.println(24);
                  fi
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        OwnershipChecker.check(typed);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "option-if-matches.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        assertEquals("-1\n42\n42\n24",
                out.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void payloadAliasDoesNotEscapeThenBranch() {
        String program = FOO + """
                fnc bad(Option<Foo> m2): int {
                  if m2 matches Some(foo) then
                    return foo.run();
                  fi
                  return foo.run();
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void payloadAliasDoesNotLeakToElseBranch() {
        String program = FOO + """
                fnc bad(Option<Foo> m2): int {
                  if m2 matches Some(foo) then
                    return foo.run();
                  else
                    return foo.run();
                  fi
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void disjunctionNeverSpeculativelyExposesSomePayload() {
        // The left edge can be false (None), so foo is not a valid name
        // in either the right operand or the body of a disjunction.
        String rightSide = FOO + """
                fnc bad(Option<Foo> m2): bool {
                  if m2 matches Some(foo) || foo.run() == 42 then
                    return true;
                  fi
                  return false;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(rightSide)));

        String body = FOO + """
                fnc bad(Option<Foo> m2): int {
                  if m2 matches Some(foo) || true then
                    return foo.run();
                  fi
                  return -1;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(body)));
    }

    @Test
    void payloadAliasDoesNotLeakIntoElif() {
        String program = FOO + """
                fnc bad(Option<Foo> m2): int {
                  if m2 matches Some(foo) then
                    return foo.run();
                  elif true then
                    return foo.run();
                  fi
                  return -1;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void nestedSomeBindingsResolveOwnershipThroughLeftAndScope() {
        String program = """
                define class Box as
                end

                fnc inspect(Option<Option<Box>> outer): int {
                  if outer matches Some(inner) && inner matches Some(payload) then
                    return 42;
                  fi
                  return 0;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void nestedAndCannotMovePayloadThenMoveItsOptionOwner() {
        String program = """
                define class Box as
                end

                fnc consumeBox(Box box): void { return; }
                fnc consumeInner(Option<Box> value): void { return; }

                fnc bad(Option<Option<Box>> outer): void {
                  if outer matches Some(inner) && inner matches Some(payload) then
                    consumeBox(payload);
                    consumeInner(inner);
                  fi
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(program));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> OwnershipChecker.check(typed));
        assertTrue(error.getMessage().contains("moved value"), error::getMessage);
    }

    @Test
    void ordinaryFooIsNotAutomaticallyOptional() {
        String program = FOO + """
                fnc bad(Foo m2): int {
                  if m2 matches Some(foo) then
                    return foo.run();
                  fi
                  return 0;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void optionDoesNotPermitUncheckedMemberCalls() {
        String program = FOO + """
                fnc bad(Option<Foo> m2): int {
                  return m2.run();
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }
}
