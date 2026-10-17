package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Execute cross-family Oreslang syntax using the real Truffle runtime. */
final class OreslangSyntaxRuntimeTest {
    private record Example(String name, String source, String output) { }

    private static final List<Example> PROGRAMS = List.of(
            new Example("named struct and tuple, field access and indexing", """
                    pub fnc main(): void {
                      const T row = infer<T> struct {id: "acct_42", active: true};
                      const U pair = infer<U> tuple ("first", 5);
                      stdio.stdout.write(row.id);
                      stdio.stdout.write("|");
                      stdio.stdout.write(pair[1]);
                      return;
                    }
                    """, "acct_42|5"),
            new Example("callable, condition and arithmetic", """
                    fnc choose(int value): int {
                      if value < 0; then
                        return -1;
                      elif value == 0; then
                        return 0;
                      else
                        return value * 2;
                      fi
                    }
                    pub fnc main(): void {
                      stdio.stdout.write(choose(-1));
                      stdio.stdout.write("|");
                      stdio.stdout.write(choose(3));
                      return;
                    }
                    """, "-1|6"),
            new Example("modules, routine and lexical closure", """
                    namespace syntax;
                    type IntFn = typeof fnc(int value) => int;
                    define module math
                      pub fnc add(int x, int y): int { return x + y; }
                    end
                    fnc makeAdder(int base): IntFn {
                      return |value| -> { return base + value; };
                    }
                    pub routine main(): void {
                      val IntFn callback = makeAdder(10);
                      stdio.stdout.write(math.add(2, 3));
                      stdio.stdout.write("|");
                      stdio.stdout.write(callback(7));
                      return;
                    }
                    """, "5|17"),
            new Example("static select and channel communication", """
                    pub fnc main(): void {
                      val Channel<int> channel = Channel.new<int>(1);
                      writech channel, 11;
                      do select first {
                        case readch channel: val value {
                          stdio.stdout.write(value);
                        }
                        default: {
                          stdio.stdout.write("none");
                        }
                      }
                      return;
                    }
                    """, "11"),
            new Example("destructuring and mutable binding", """
                    pub fnc main(): void {
                      [const first, _, let last] = tuple(3, 8, 12);
                      last = last + first;
                      stdio.stdout.write(last);
                      return;
                    }
                    """, "15")
    );

    @TestFactory
    Stream<DynamicTest> runtimeAgreement() {
        return PROGRAMS.stream().map(test -> DynamicTest.dynamicTest(test.name(), () -> {
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(test.source())), test.source());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertDoesNotThrow(() -> {
                Source source = Source.newBuilder(OresLanguage.ID, test.source(), "syntax-runtime.ores")
                        .mimeType(OresLanguage.MIME_TYPE).build();
                try (Context context = Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .out(output)
                        .build()) {
                    context.eval(source);
                }
            }, test.source());
            assertEquals(test.output(), output.toString(StandardCharsets.UTF_8), test.name());
        }));
    }
}
