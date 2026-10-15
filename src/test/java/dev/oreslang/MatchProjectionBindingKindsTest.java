package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Record names, tuple positions, and per-slot binding kinds are distinct contracts. */
final class MatchProjectionBindingKindsTest {
    private static Ast.Program checks(String source) {
        Ast.Program program = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(program);
        return program;
    }

    private static String outputOf(String source) throws Exception {
        checks(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID, source, "match-projection-kinds.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(program);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void wholePatternConstProvidesReadonlyLocal() throws Exception {
        String source = """
                fnc extract(Option<{v: int}> item): int {
                  do match item over
                    on Some as const {v} -> { return v; }
                    on None -> { return 0; }
                  end
                }
                pub routine main(): void {
                  val Option<{v: int}> item = Some(struct{v: int}{v: 42});
                  stdio.stdout.write(extract(item));
                  return;
                }
                """;
        assertEquals("42", outputOf(source));
    }

    @Test
    void recordProjectionUsesFieldNamesAndMixedKinds() throws Exception {
        String source = """
                fnc extract(Option<{v: int, b: int}> item): int {
                  do match item over
                    on Some as {const v, let b} -> {
                      b = b + 1;
                      return v + b;
                    }
                    on None -> { return 0; }
                  end
                }
                pub routine main(): void {
                  val Option<{v: int, b: int}> item =
                      Some(struct{v: int, b: int}{b: 2, v: 6});
                  stdio.stdout.write(extract(item));
                  return;
                }
                """;
        assertEquals("9", outputOf(source));
    }

    @Test
    void wholePatternDefaultCanBeOverriddenPerField() throws Exception {
        String source = """
                fnc extract(Option<{v: int, b: int}> item): int {
                  do match item over
                    on Some as const {v, let b} -> {
                      b = b + 1;
                      return v + b;
                    }
                    on None -> { return 0; }
                  end
                }
                pub routine main(): void {
                  val Option<{v: int, b: int}> item =
                      Some(struct{v: int, b: int}{v: 6, b: 2});
                  stdio.stdout.write(extract(item));
                  return;
                }
                """;
        assertEquals("9", outputOf(source));
    }

    @Test
    void tupleProjectionUsesPositionsAndIndependentKinds() throws Exception {
        String source = """
                fnc extract(Option<Tuple[int, int]> item): int {
                  do match item over
                    on Some as (const first, let second) -> {
                      second = second + 5;
                      return first + second;
                    }
                    on None -> { return 0; }
                  end
                }
                pub routine main(): void {
                  val Option<Tuple[int, int]> item = Some((7, 8));
                  stdio.stdout.write(extract(item));
                  return;
                }
                """;
        assertEquals("20", outputOf(source));
    }

    @Test
    void tupleUnderscoreDiscardsOnePosition() throws Exception {
        String source = """
                fnc extract(Option<Tuple[int, int]> item): int {
                  do match item over
                    on Some as (_, const second) -> { return second; }
                    on None -> { return 0; }
                  end
                }
                pub routine main(): void {
                  val Option<Tuple[int, int]> item = Some((11, 23));
                  stdio.stdout.write(extract(item));
                  return;
                }
                """;
        assertEquals("23", outputOf(source));
    }

    @Test
    void recordMismatchAndPositionalRecordNeverSilentlyBind() {
        String body = """
                fnc f(Option<{v: int, b: int}> item): int {
                  do match item over
                    on Some as %s -> { return 1; }
                    on None -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException badNames = assertThrows(IllegalArgumentException.class,
                () -> checks(body.formatted("{const vx, let bx}")));
        assertTrue(badNames.getMessage().contains("does not exist"), badNames.getMessage());
        IllegalArgumentException wrongShape = assertThrows(IllegalArgumentException.class,
                () -> checks(body.formatted("(const vx, let bx)")));
        assertTrue(wrongShape.getMessage().contains("requires a Tuple"), wrongShape.getMessage());
    }

    @Test
    void namedTupleAndWrongArityAreRejected() {
        String body = """
                fnc f(Option<Tuple[int, int]> item): int {
                  do match item over
                    on Some as %s -> { return 1; }
                    on None -> { return 0; }
                  end
                }
                """;
        IllegalArgumentException wrongShape = assertThrows(IllegalArgumentException.class,
                () -> checks(body.formatted("{const first, let second}")));
        assertTrue(wrongShape.getMessage().contains("requires a statically known record"),
                wrongShape.getMessage());
        IllegalArgumentException wrongArity = assertThrows(IllegalArgumentException.class,
                () -> checks(body.formatted("(const onlyOne, let second, val third)")));
        assertTrue(wrongArity.getMessage().contains("tuple match projection arity"),
                wrongArity.getMessage());
    }

    @Test
    void constProjectedBindingCannotBeReassigned() {
        assertThrows(IllegalArgumentException.class, () -> checks("""
                fnc f(Option<{v: int}> item): int {
                  do match item over
                    on Some as const {v} -> {
                      v = 99;
                      return v;
                    }
                    on None -> { return 0; }
                  end
                }
                """));
    }

    @Test
    void mutableRebindOfMoveOnlyProjectionFailsClosed() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> checks("""
                fnc f(Option<{items: List<int>}> item): int {
                  do match item over
                    on Some as {let items} -> { return 1; }
                    on None -> { return 0; }
                  end
                }
                """));
        assertTrue(failure.getMessage().contains("non-Copy field"), failure.getMessage());
    }

    @Test
    void matchConditionsPreserveLetBindings() throws Exception {
        String source = """
                fnc choose(Option<{v: int}> item): int {
                  if item matches Some as {let v} then
                    v = v + 1;
                    return v;
                  fi
                  return 0;
                }
                pub routine main(): void {
                  val Option<{v: int}> input = Some(struct{v: int}{v: 40});
                  stdio.stdout.write(choose(input));
                  return;
                }
                """;
        assertEquals("41", outputOf(source));
    }

    @Test
    void andConditionSeesProjectionBindingsOnRightHandSide() throws Exception {
        String source = """
                fnc choose(Option<{v: int}> item): int {
                  if item matches Some as {let v} && v > 5 then
                    v = v + 2;
                    return v;
                  fi
                  return 0;
                }
                pub routine main(): void {
                  val Option<{v: int}> item = Some(struct{v: int}{v: 7});
                  stdio.stdout.write(choose(item));
                  return;
                }
                """;
        assertEquals("9", outputOf(source));
    }

    @Test
    void conditionConjunctionRejectsDuplicateProjectionNames() {
        String source = """
                fnc choose(Option<{v: int}> item): int {
                  if item matches Some as {v} && item matches Some as {v} then
                    return v;
                  fi
                  return 0;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checks(source));
        assertTrue(failure.getMessage().contains("duplicate binding"),
                failure.getMessage());
    }

    @Test
    void inlineTupleScrutineePreservesCopyOwnership() throws Exception {
        String source = """
                pub routine main(): void {
                  do match Some((7, 8)) over
                    on Some as (const first, let second) -> {
                      second = second + 1;
                      stdio.stdout.write(first + second);
                    }
                    on None -> {
                      stdio.stdout.write(0);
                    }
                  end
                  return;
                }
                """;
        assertEquals("16", outputOf(source));
    }

    @Test
    void noncopyProjectionFromUntrackedExpressionIsRejected() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> checks("""
                    fnc identity(Option<{items: List<int>}> item): Option<{items: List<int>}> {
                      return item;
                    }
                    fnc f(Option<{items: List<int>}> item): int {
                      do match identity(item) over
                        on Some as {items} -> { return 1; }
                        on None -> { return 0; }
                      end
                    }
                    """));
        assertTrue(failure.getMessage().contains("tracked source owner"),
                failure.getMessage());
    }

    @Test
    void duplicateRecordAndTupleBindingsAreParseErrors() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(Option<{v: int}> item): void {
                  do match item over
                    on Some as {const v, let v} -> { return; }
                    on None -> { return; }
                  end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(Option<Tuple[int, int>> item): void {
                  do match item over
                    on Some as (const v, let v) -> { return; }
                    on None -> { return; }
                  end
                }
                """));
    }

    @Test
    void selectRecordProjectionRespectsMixedKinds() throws Exception {
        String source = """
                pub routine main(): void {
                  val Channel<{v: int, b: int}> ch = Channel.new<{v: int, b: int}>(1);
                  writech ch, struct{v: int, b: int}{v: 6, b: 2};
                  do select {
                    when readch ch: const {v, let b} -> {
                      b = b + 1;
                      stdio.stdout.write(v + b);
                    }
                  }
                  return;
                }
                """;
        assertEquals("9", outputOf(source));
    }
    @Test
    void objectRestSnapshotsCanBeMatchedFromBothCallableAndRoutine() throws Exception {
        String source = """
                fnc extract(): int {
                  const {first, ...remaining} =
                      struct{first: int, value: int}{first: 1, value: 42};
                  do match Some(remaining) over
                    on Some as {value} -> { return value; }
                    on None -> { return 0; }
                  end
                }
                pub routine main(): void {
                  const {first, ...remaining} =
                      struct{first: int, value: int}{first: 2, value: 43};
                  do match Some(remaining) over
                    on Some as {value} -> { stdio.stdout.write(value); }
                    on None -> { stdio.stdout.write(0); }
                  end
                  stdio.stdout.write(":" + extract());
                  return;
                }
                """;
        assertEquals("43:42", outputOf(source));
    }

    @Test
    void selectObjectRestCanFeedConstructorRecordProjection() throws Exception {
        String source = """
                pub routine main(): void {
                  val Channel<{first: int, value: int}> ch =
                      Channel.new<{first: int, value: int}>(1);
                  writech ch, struct{first: int, value: int}{first: 5, value: 44};
                  do select {
                    when readch ch: const {first, ...remaining} -> {
                      do match Some(remaining) over
                        on Some as {value} -> {
                          stdio.stdout.write(first + value);
                        }
                        on None -> {
                          stdio.stdout.write(0);
                        }
                      end
                    }
                  }
                  return;
                }
                """;
        assertEquals("49", outputOf(source));
    }

    @Test
    void objectRestFromMutableStructIsStaticallyReadonly() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checks("""
                    fnc invalid(): int {
                      const {first, ...remaining} =
                          struct{first: int, value: int}{first: 1, value: 2};
                      remaining.value = 99;
                      return remaining.value;
                    }
                    """));
        assertTrue(failure.getMessage().contains("readonly")
                        || failure.getMessage().contains("mutate"), failure.getMessage());
    }

    @Test
    void nestedObjectRestRemainsProjectableAsRecord() throws Exception {
        String source = """
                pub routine main(): void {
                  const {a, ...tail} = struct{a: int, b: int, c: int}{a: 2, b: 3, c: 4};
                  const {b, ...remaining} = tail;
                  do match Some(remaining) over
                    on Some as {c} -> { stdio.stdout.write(a + b + c); }
                    on None -> { stdio.stdout.write(0); }
                  end
                  return;
                }
                """;
        assertEquals("9", outputOf(source));
    }

    @Test
    void objectRestWithMoveOnlyChannelFieldStillFailsClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checks("""
                    fnc consume(Channel<{first: int, items: List<int>}> ch): void {
                      do select {
                        when readch ch: const {first, ...remaining} -> {
                          stdio.println(first);
                        }
                      }
                      return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("non-Copy select read projection"),
                failure.getMessage());
    }

    @Test
    void ordinaryObjectRestRejectsMoveOnlyCapturedFieldsWithoutTransferLowering() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checks("""
                    fnc invalid({first: int, payload: List<int>} input): void {
                      const {first, ...remaining} = input;
                      return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("non-Copy object-rest projection"),
                failure.getMessage());
    }

    @Test
    void objectRestUnionCannotHideMoveOnlyCapturedFields() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checks("""
                    fnc invalid(
                        {first: int, payload: List<int>} | {first: int, payload: List<string>} input
                    ): void {
                      const {first, ...remaining} = input;
                      return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("non-Copy object-rest projection"),
                failure.getMessage());
    }

    @Test
    void copyOnlyObjectRestUnionPreservesStaticReadonlySnapshots() {
        assertDoesNotThrow(() -> checks("""
            fnc valid({first: int, a: int} | {first: int, b: int} input): void {
              const {first, ...remaining} = input;
              return;
            }
            """));
    }

    @Test
    void selectedUnionObjectRestCannotHideMoveOnlyFields() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> checks("""
                    fnc invalid(Channel<
                        {first: int, payload: List<int>} | {first: int, payload: List<string>}
                    > ch): void {
                      do select {
                        when readch ch: const {first, ...remaining} -> {
                          stdio.println(first);
                        }
                      }
                      return;
                    }
                    """));
        assertTrue(failure.getMessage().contains("non-Copy select read projection"),
                failure.getMessage());
    }

}
