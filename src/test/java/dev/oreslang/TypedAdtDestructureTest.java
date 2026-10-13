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

final class TypedAdtDestructureTest {
    private static void accepts(String source) {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse(source));
            OwnershipChecker.check(typed);
        });
    }

    private static IllegalArgumentException rejects(String source) {
        return assertThrows(IllegalArgumentException.class, () -> {
            var typed = TypeChecker.check(Parser.parse(source));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void sequenceBindingsAcceptTypeFirstAndNameColonTypeWithoutChangingKinds() {
        accepts("""
                fnc pair(): [int, string] { return (7, "seven"); }
                fnc first(): void {
                  const [int id, string label] = pair();
                  stdio.println(id);
                  stdio.println(label);
                  return;
                }
                fnc second(): void {
                  const [id: int, label: string] = pair();
                  stdio.println(id);
                  stdio.println(label);
                  return;
                }
                fnc third(): void {
                  [const int id, let label: string] = pair();
                  label = "updated";
                  stdio.println(id);
                  stdio.println(label);
                  return;
                }
                """);
    }

    @Test
    void recordBindingsAcceptBothAnnotatedSpellingsAndTypedRest() {
        accepts("""
                fnc record(): {foo: int, bar: string} {
                  return struct{foo: int, bar: string}{foo: 42, bar: "ok"};
                }
                fnc named(): void {
                  const {foo: int, bar: string} = record();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                fnc typeFirst(): void {
                  const {int foo, string bar} = record();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                fnc withRest(): void {
                  const {foo: int, ...tail: {bar: string}} = record();
                  stdio.println(foo);
                  stdio.println(tail.bar);
                  return;
                }
                """);
    }

    @Test
    void typedSequenceIterationIsCheckedAgainstElementShape() {
        accepts("""
                fnc visit(): void {
                  for const [int left, int right] of arr[(1, 2), (3, 4)] do
                    stdio.println(left + right);
                  done
                  return;
                }
                """);
        IllegalArgumentException wrong = rejects("""
                fnc visit(): void {
                  for const [string left, int right] of arr[(1, 2)] do
                    stdio.println(right);
                  done
                  return;
                }
                """);
        assertTrue(wrong.getMessage().contains("typed destructure binding"), wrong.getMessage());
    }

    @Test
    void refusesIncompatiblePositionalAndObjectAnnotations() {
        IllegalArgumentException slot = rejects("""
                fnc pair(): [int, string] { return (7, "seven"); }
                fnc bad(): void {
                  const [string id, string label] = pair();
                  return;
                }
                """);
        assertTrue(slot.getMessage().contains("typed destructure binding"), slot.getMessage());

        IllegalArgumentException field = rejects("""
                fnc record(): {foo: int, bar: string} {
                  return struct{foo: int, bar: string}{foo: 42, bar: "ok"};
                }
                fnc bad(): void {
                  const {foo: string, bar: string} = record();
                  return;
                }
                """);
        assertTrue(field.getMessage().contains("typed destructure binding"), field.getMessage());
    }

    @Test
    void rejectsUnsafeNarrowingAcrossUnionVariantsAndRestShapes() {
        IllegalArgumentException union = rejects("""
                fnc choice(bool flag): [int, string] | [bool, string] {
                  if flag; do
                    return (3, "number");
                  else
                    return (true, "boolean");
                  fi
                }
                fnc bad(): void {
                  const [int value, string label] = choice(true);
                  return;
                }
                """);
        assertTrue(union.getMessage().contains("typed destructure binding"), union.getMessage());

        IllegalArgumentException tail = rejects("""
                fnc pair(): [int, string] { return (7, "seven"); }
                fnc bad(): void {
                  const [int id, ...tail: Array<int>] = pair();
                  return;
                }
                """);
        assertTrue(tail.getMessage().contains("typed destructure binding"), tail.getMessage());
    }

    @Test
    void sumConstructorPayloadPatternsSupportExplicitNominalTypes() {
        accepts("""
                fnc option(Option<int> maybe): int {
                  match maybe over
                    on Some(int value) -> { return value; }
                    on None -> { return 0; }
                  end
                }
                fnc result(Result<int, string> outcome): int {
                  match outcome over
                    on Ok(int value) -> { return value; }
                    on Err(string message) -> { return 0; }
                  end
                }
                """);

        IllegalArgumentException wrong = rejects("""
                fnc bad(Option<int> maybe): int {
                  match maybe over
                    on Some(string value) -> { return 1; }
                    on None -> { return 0; }
                  end
                }
                """);
        assertTrue(wrong.getMessage().contains("unreachable type pattern"), wrong.getMessage());
    }

    @Test
    void typedDestructureRunsWithExistingRuntimeExtraction() throws Exception {
        String program = """
                fnc pair(): [int, string] { return (7, "seven"); }
                fnc record(): {foo: int, bar: string} {
                  return struct{foo: int, bar: string}{foo: 42, bar: "ok"};
                }
                pub fnc main(): void {
                  const [int id, string label] = pair();
                  const {foo: int, bar: string} = record();
                  stdio.println(id);
                  stdio.println(label);
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "typed-adt-destructure.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("7"));
        assertTrue(text.contains("seven"));
        assertTrue(text.contains("42"));
        assertTrue(text.contains("ok"));
    }
}
