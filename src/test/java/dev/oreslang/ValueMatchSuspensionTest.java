package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Value-producing match arms are expression continuations, not procedural
 * RETURN sites or synchronous fallbacks. All three expression positions can
 * suspend and resume without re-evaluating the match subject.
 */
@Timeout(20)
final class ValueMatchSuspensionTest {

    @Test
    void pendingSubjectGuardAndArmValuesResumeOnSourceTask() throws Exception {
        String program = """
            pub async routine main(): void {
              val Channel<int> subjects = Channel.new<int>(0);
              val subjectWriter = nb writech subjects, 5;
              val int subjectResult = match<int> await (nb readch subjects) over
                on 5 -> { return 42; }
                on _ -> { return 0; }
              end;
              await subjectWriter;
              stdio.println(subjectResult);

              val Channel<bool> guards = Channel.new<bool>(0);
              val guardWriter = nb writech guards, false;
              val int guardResult = match<int> 9 over
                on int as n when await (nb readch guards) -> { return 100; }
                on _ -> { return 200; }
              end;
              await guardWriter;
              stdio.println(guardResult);

              val Channel<int> values = Channel.new<int>(0);
              val valueWriter = nb writech values, 20;
              val int armResult = match<int> true over
                on true -> { return (await (nb readch values)) + 2; }
                on false -> { return 0; }
              end;
              await valueWriter;
              stdio.println(armResult);
              return;
            }
            """;

        var parsed = Parser.parse(program);
        var typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "value-match-suspension.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42\n200\n22",
                output.toString(StandardCharsets.UTF_8)
                        .trim().replace("\r", ""));
    }

    @Test
    void groupedMatchGuardUsesArmArrowNotLambdaSyntax() {
        String guest = """
            fnc choice(bool selected): int {
              return match<int> selected over
                on true when (selected) -> { return 1; }
                on _ -> { return 2; }
              end;
            }
            """;
        var typed = TypeChecker.check(Parser.parse(guest));
        OwnershipChecker.check(typed);
    }

    @Test
    void canonicalBlockArmSuspendsAndPublishesOnlyArmResult() throws Exception {
        String program = """
            pub async routine main(): void {
              val Channel<int> values = Channel.new<int>(0);
              val writer = nb writech values, 39;

              val int result = match<int> true over
                on true -> ::
                  val int base = await (nb readch values);
                  return base + 3;
                end
                on false -> 0;
              end;

              await writer;
              stdio.println(result);
              return;
            }
            """;

        var parsed = Parser.parse(program);
        var typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "canonical-block-arm-await.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42",
                output.toString(StandardCharsets.UTF_8)
                        .trim().replace("\r", ""));
    }

}
