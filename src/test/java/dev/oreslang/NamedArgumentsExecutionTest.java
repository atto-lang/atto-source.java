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

final class NamedArgumentsExecutionTest {
    private static String run(String code) throws Exception {
        TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(Parser.parse(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "named-arguments.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void directCallsAcceptBothDelimitersAndReorderedLabels() throws Exception {
        assertEquals("5567", run("""
                fnc foo(int bar, bool a): int {
                  return a ? bar : 0;
                }
                pub routine main(): void {
                  stdio.stdout.write(foo(5, true));
                  stdio.stdout.write(foo(bar: 5, a: true));
                  stdio.stdout.write(foo(a: true, bar: 6));
                  stdio.stdout.write(foo(7, a: true));
                }
                """));
    }

    @Test
    void callsEvaluateNamedExpressionsInSourceOrder() throws Exception {
        assertEquals("AB6", run("""
                fnc signalA(): bool {
                  stdio.stdout.write("A");
                  return true;
                }
                fnc signalB(): int {
                  stdio.stdout.write("B");
                  return 6;
                }
                fnc foo(int bar, bool a): int {
                  return a ? bar : 0;
                }
                pub routine main(): void {
                  stdio.stdout.write(foo(a: signalA(), bar: signalB()));
                }
                """));
    }

    @Test
    void methodAndStaticFunctionKeepArityOnlyDispatch() throws Exception {
        assertEquals("89", run("""
                define class Box as
                  pub value(int bar, bool a): int {
                    return a ? bar : 0;
                  }
                  pub static fnc staticValue(int bar, bool a): int {
                    return a ? bar : 0;
                  }
                end
                pub routine main(): void {
                  val Box b = new Box();
                  stdio.stdout.write(b.value(a: true, bar: 8));
                  stdio.stdout.write(Box.staticValue(a: true, bar: 9));
                }
                """));
    }

    @Test
    void invalidLabelsAreRejectedByStaticChecker() {
        String header = """
                fnc foo(int bar, bool a): int { return a ? bar : 0; }
                fnc main(): int { return foo(%s); }
                """;
        for (String arguments : new String[] {
                "bar: 5, bar: 6",
                "bar: 5, typo: true",
                "a: true",
                "5, bar: 6",
                "a: true, 5"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse(header.formatted(arguments))),
                    arguments);
        }
    }
}
