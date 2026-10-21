package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ImmutableRecordWithTest {
    @Test
    void withAddsAndReplacesFieldsAndChangesTheStaticRowType() {
        String source = """
                pub fnc main(): void {
                  const base = record{name: "Ada", age: 37};
                  const updated = base with {
                    age: "thirty-seven",
                    active: true
                  };
                  stdio.println(updated.name);
                  stdio.println(updated.age);
                  stdio.println(updated.active);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "immutable-record-with.ores")
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

        assertEquals(
                "Ada\nthirty-seven\ntrue\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
    }

    @Test
    void withConsumesTheMoveOnlyBaseBinding() {
        var parsed = Parser.parse("""
                pub fnc main(): void {
                  const base = record{name: "Ada"};
                  const updated = base with {age: 37};
                  stdio.println(updated.age);
                  stdio.println(base.name);
                  return;
                }
                """);
        var typed = assertDoesNotThrow(() -> TypeChecker.check(parsed));
        IllegalArgumentException rejected =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> OwnershipChecker.check(typed));
        assertTrue(
                rejected.getMessage().toLowerCase().contains("moved")
                        || rejected.getMessage().toLowerCase().contains("consum"),
                rejected.getMessage());
    }

    @Test
    void withRejectsStructsAndStructuralContractViews() {
        IllegalArgumentException structRejected =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse("""
                                pub fnc main(): void {
                                  const point = infer struct{x: 1, y: 2};
                                  const changed = point with {x: "one", z: 3};
                                  return;
                                }
                                """)));
        assertTrue(
                structRejected.getMessage().contains("struct")
                        || structRejected.getMessage().contains("readonly structural record"),
                structRejected.getMessage());

        IllegalArgumentException projectionRejected =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse("""
                                fnc forbidden(structural {id: int} view): void {
                                  const widened = view with {extra: true};
                                  return;
                                }
                                """)));
        assertTrue(
                projectionRejected.getMessage().contains("structural contract")
                        || projectionRejected.getMessage().contains("readonly structural record"),
                projectionRejected.getMessage());
    }

    @Test
    void withRejectsDynamicOrDuplicateFieldNamesAtTheGrammarBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main(): void {
                          const base = record{name: "Ada"};
                          const key = "age";
                          const changed = base with {`key`: 37};
                          return;
                        }
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main(): void {
                          const base = record{name: "Ada"};
                          const changed = base with {age: 37, age: 38};
                          return;
                        }
                        """));
    }

    @Test
    void suspendableWithEvaluatesUpdateFieldsExactlyOnceLeftToRight() {
        String source = """
                async fnc mark(string value): string {
                  stdio.println(value);
                  return value;
                }

                pub async fnc main(): void {
                  const base = record{first: "base"};
                  const updated = base with {
                    second: await mark("second"),
                    third: await mark("third")
                  };
                  stdio.println(updated.first);
                  stdio.println(updated.second);
                  stdio.println(updated.third);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = assertDoesNotThrow(() ->
                Source.newBuilder(
                                OresLanguage.ID,
                                source,
                                "immutable-record-with-async.ores")
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

        assertEquals(
                "second\nthird\nbase\nsecond\nthird\n",
                output.toString(StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
    }

    @Test
    void chainedWithUpdatesKeepProducingClosedReadonlyRows() {
        String source = """
                pub fnc main(): void {
                  const final_row =
                      record{a: 1}
                      with {b: 2}
                      with {a: "one", c: 3};
                  stdio.println(final_row.a);
                  stdio.println(final_row.b);
                  stdio.println(final_row.c);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }
}
