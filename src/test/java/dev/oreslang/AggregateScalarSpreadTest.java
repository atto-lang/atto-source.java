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
    void suspendableFieldEvaluationSnapshotsPriorSpreads() throws Exception {
        String source = """
                async fnc answer(): int { return 7; }

                pub async fnc main(): void {
                  val source = infer struct{age: 37, name: "Ada"};
                  const snapshot = record{
                    ...source,
                    answer: await answer(),
                    mutation_result: (source.age = 99)
                  };
                  stdio.println(snapshot.age);
                  stdio.println(source.age);
                  stdio.println(snapshot.answer);
                  stdio.println(snapshot.mutation_result);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "spread-across-await.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(program);
        }
        assertEquals("37\n99\n7\n99\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void supportsCrossCategoryScalarProjectionWithoutImplicitTypeErasure() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                fnc accepted(): int {
                  const initial = record{age: 37};
                  const as_struct = infer struct{...initial, active: true};
                  const as_record = record{...as_struct, age: 40};
                  return as_record.age;
                }
                """));
    }

    @Test
    void rejectsSpreadFromStructurallyNarrowedRecordView() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                    type Thin = {name: string};
                    fnc invalid(): void {
                      const Thin hidden = record{name: "Ada", private_note: "secret"};
                      const leaked = record{...hidden};
                      return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("proven exact source shape"),
                failure.getMessage());
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
