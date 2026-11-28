package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class ConditionalDelimiterAuditTest {
    @Test
    void fiClosesTheLastBracedBranchWithoutAClosingBrace() {
        assertEquals("123", run("""
                pub fnc main(): void {
                  if true { stdio.stdout.write(1); fi
                  if false { stdio.stdout.write(9);
                  } elif true { stdio.stdout.write(2); fi
                  if false { stdio.stdout.write(9);
                  } elif false { stdio.stdout.write(9);
                  } else { stdio.stdout.write(3); fi
                  return;
                }
                """));
    }

    @Test
    void nestedConditionalsDoNotConsumeFunctionLoopOrLambdaBraces() {
        assertEquals("12", run("""
                fnc answer(): int {
                  if true {
                    if true { return 1; } else { return 9; fi
                  } else { return 9; fi
                }
                pub fnc main(): void {
                  const callback = || -> {
                    if true { return 2; } else { return 9; fi
                  };
                  while true {
                    if true { stdio.stdout.write(answer()); break; fi
                  }
                  stdio.stdout.write(callback());
                  return;
                }
                """));
    }

    @Test
    void keywordAndBracedFormsHaveTheSameBindingSemantics() {
        assertEquals("42", run("""
                pub fnc main(): void {
                  if 4 as let value when value > 0 then
                    stdio.stdout.write(value);
                  fi
                  if 2 as let value when value > 0 {
                    stdio.stdout.write(value);
                  fi
                  return;
                }
                """));
    }

    @Test
    void redundantHistoricalClosureIsDiagnosedAndMissingFiIsRejected() {
        var old = Parser.parseWithWarnings("fnc f():void{if true {return;} fi}");
        assertEquals(1, old.warnings().size());
        assertTrue(old.warnings().getFirst().message().contains("redundant '}'"));
        var canonical = Parser.parseWithWarnings("fnc f():void{if true {return; fi}");
        assertTrue(canonical.warnings().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f():void{if true {return;} }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc f():void{if true {return; elif false {return; fi}"));
    }

    private static String run(String source) {
        var parsed = Parser.parseWithWarnings(source);
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        OwnershipChecker.check(TypeChecker.check(parsed.program()));
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID).out(output).allowAllAccess(false).build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
