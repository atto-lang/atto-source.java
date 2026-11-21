package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Executable guest-language regression for all anonymous parameter spellings. */
final class AnonymousStructuralRuntimeParityTest {
    @Test
    void allFourAnonymousParameterSpellingsProjectTheSameFields() {
        String source = """
                fnc typeFirst(struct{id: int} row): int { return row.id; }
                fnc nameFirst(row: struct{id: int}): int { return row.id; }
                fnc keywordTypeFirst(structural struct{id: int} row): int {
                  return row.id;
                }
                fnc keywordNameFirst(row: structural struct{id: int}): int {
                  return row.id;
                }

                pub fnc main(): void {
                  const Point point = infer<Point> struct{id: 7, label: "wide"};
                  const row = record{id: 11, extra: "record"};
                  const int result = typeFirst(point) + nameFirst(row)
                      + keywordTypeFirst(point) + keywordNameFirst(row);
                  stdio.println(result);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source,
                        "anonymous-structural-parity.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });
        assertEquals("36\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void namedInterfaceStillNeedsStructuralOptInForRuntimeProjection() {
        String source = """
                define interface HasId
                  id: int;
                end
                define class Candidate as
                  pub val int id = 9;
                  pub val string label = "wide";
                end

                fnc read(structural HasId value): int { return value.id; }
                pub fnc main(): void {
                  stdio.println(read(new Candidate()));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(OresLanguage.ID, source,
                        "named-structural-opt-in.ores")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(output)
                    .build()) {
                context.eval(program);
            }
        });
        assertEquals("9\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
}
