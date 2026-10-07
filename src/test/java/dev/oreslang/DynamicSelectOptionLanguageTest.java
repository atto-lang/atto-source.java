package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import dev.oreslang.compiler.OresCompiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class DynamicSelectOptionLanguageTest {

    @Test
    void blockingAndNonblockingDynamicSelectUseOptionResults() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> first = Channel.new<int>(1);
                  writech first, 7;
                  val SelectSet blocking_cases =
                      SelectSet.new([SelectCase.read(first)]);
                  val Option<SelectResult> blocking =
                      select first from blocking_cases;

                  stdio.stdout.write(blocking.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(blocking.unwrap().value);

                  val Channel<int> second = Channel.new<int>(1);
                  writech second, 9;
                  val SelectSet async_cases =
                      SelectSet.new([SelectCase.read(second)]);
                  val Future<Option<SelectResult>> pending =
                      nb select first from async_cases;
                  val Option<SelectResult> async_result = await pending;

                  stdio.stdout.write(":");
                  stdio.stdout.write(async_result.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(async_result.unwrap().value);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true:7:true:9", run(program));
    }

    @Test
    void explicitDoStaticSelectCommitsOneCaseAndDefaultDoesNotConsume() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> ready = Channel.new<int>(1);
                  val Channel<int> empty = Channel.new<int>(1);
                  writech ready, 3;

                  do select first {
                    case readch ready: const value {
                      stdio.stdout.write(value);
                    }
                    case readch empty: const ignored {
                      stdio.stdout.write(99);
                    }
                    default: {
                      stdio.stdout.write(0);
                    }
                  }

                  stdio.stdout.write(":");

                  do select first {
                    case readch empty: const ignored {
                      stdio.stdout.write(2);
                    }
                    default: {
                      stdio.stdout.write(9);
                    }
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("3:9", run(program));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "select-option.ores")
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
