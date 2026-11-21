package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.OwnershipChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

final class EntryCallableFieldLanguageTest {
    @Test
    void callableEntryValueWorksAcrossResumableDispatch() throws Exception {
        String code = """
                fnc increment(int value): int {
                  return value + 1;
                }

                async fnc base(): int {
                  return 41;
                }

                async fnc total(): int {
                  const entry = Entry.of("increment", increment);
                  return entry.value(await base());
                }

                pub fnc main(): void {
                  stdio.println(await total());
                  return;
                }
                """;

        var program = OresCompiler.parseAndTypeCheck(code);
        assertDoesNotThrow(() -> OwnershipChecker.check(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID, code, "entry-callable-value.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals(
                "42",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n")
                        .strip());
    }
}
