package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end guest admission: closed scalar spreads and override order. */
final class AggregateScalarSpreadTest {
    @Test
    void recordAndStructScalarSpreadsProduceIndependentValues() throws Exception {
        String source = """
                pub fnc main(): void {
                  const original = record{id: 42, name: "Ada", age: 37};
                  const updated = record{...original, age: 38, active: true};
                  const person = infer struct{name: "Grace", score: 10};
                  const scored = infer struct{...person, score: 20};
                  const chosen = record{...updated, name: "Lin", ...record{age: 39}};
                  stdio.println(updated.age);
                  stdio.println(original.age);
                  stdio.println(updated.active);
                  stdio.println(scored.score);
                  stdio.println(person.score);
                  stdio.println(chosen.age);
                  stdio.println(chosen.name);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "scalar-spread.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(program);
        }
        assertEquals("38\n37\ntrue\n20\n10\n39\nLin\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void rejectsDynamicMapsAndNonCopyableFields() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc invalid(): void {
                  const source = record{items: arr[1, 2]};
                  const target = record{...source};
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc invalid(): void {
                  const source = record{child: record{age: 3}};
                  const target = record{...source};
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc invalid(): void {
                  const source = record{age: 37};
                  const target = struct{age: int}{...source};
                  return;
                }
                """));
    }

    @Test
    void rejectsExplicitDuplicateKeysEvenWithSpread() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc invalid(): void {
                  const source = record{age: 37};
                  const target = record{...source, age: 38, age: 39};
                  return;
                }
                """));
    }
}
