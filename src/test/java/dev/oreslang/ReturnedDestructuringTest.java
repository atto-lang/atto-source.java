package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReturnedDestructuringTest {
    @Test
    void unionArraysAndBindingKindPropagationTypeCheck() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type intOrBoolOrString = bool | int | string;

                pub fnc mixed() => Array<type intOrBoolOrString> {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  [const number, flag, answer] = mixed();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));
    }

    @Test
    void finiteTupleReturnCanUseListBackedValueAndPrefixConstPattern() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  const [number, flag, answer] = fixed();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));
    }

    @Test
    void finiteTupleReturnRejectsWrongArityAndWrongSlotType() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken() => [int, bool, string] {
                  return [3, true];
                }

                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken() => [int, bool, string] {
                  return [3, "not-bool", "yes"];
                }

                pub fnc main() => void { return; }
                """)));
    }

    @Test
    void explicitLetChangesPropagationForRemainingSequenceBindings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  [const number, let flag, answer] = fixed();
                  flag = false;
                  answer = "no";
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main() => void {
                  [const number, flag, answer] = fixed();
                  flag = false;
                  return;
                }
                """)));
    }

    @Test
    void recordReturnSupportsBothObjectDestructureSpellings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc result() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                fnc prefixed() => void {
                  const {foo, bar} = result();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }

                fnc inlineKinds() => void {
                  {const foo, const bar} = result();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }

                pub fnc main() => void {
                  prefixed();
                  inlineKinds();
                  return;
                }
                """)));
    }

    @Test
    void recordReturnsAndDestructuresRejectMissingOrWrongMembers() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken() => {foo: int, bar: string} {
                  return obj{foo: 5};
                }

                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken() => {foo: int, bar: string} {
                  return obj{foo: "wrong", bar: "x"};
                }

                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc result() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void {
                  const {foo, missing} = result();
                  return;
                }
                """)));
    }

    @Test
    void equivalentUnionAndRecordOrderingsHaveCanonicalSignatures() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                @Ret<string | int>
                fnc scalar() => int | string {
                  return 3;
                }

                @Ret<{bar: string, foo: int}>
                fnc object() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void { return; }
                """)));
    }

    @Test
    void prefixedPatternSyntaxDoesNotStealFiniteTupleOrRecordTypedBindings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [3, true, "yes"];
                }

                fnc result() => {foo: int, bar: string} {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void {
                  val [int, bool, string] tupleValue = fixed();
                  val {foo: int, bar: string} recordValue = result();
                  stdio.println(tupleValue[0]);
                  stdio.println(recordValue.foo);
                  return;
                }
                """)));
    }

    @Test
    void jvmStyleListOfAndMapOfReturnAndDestructure() throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return List.of(3, true, "yes");
                }

                fnc named() => {foo: int, bar: string} {
                  return Map.of("foo", 5, "bar", "x");
                }

                pub fnc main() => void {
                  [const num, b, answer] = fixed();
                  const {foo, bar} = named();
                  stdio.println(num);
                  stdio.println(b);
                  stdio.println(answer);
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """)));

        String output = run("""
                fnc fixed() => [int, bool, string] {
                  return List.of(3, true, "yes");
                }
                fnc named() => {foo: int, bar: string} {
                  return Map.of("foo", 5, "bar", "x");
                }
                pub fnc main() => void {
                  const [num, enabled, answer] = fixed();
                  const {foo, bar} = named();
                  stdio.println(num);
                  stdio.println(enabled);
                  stdio.println(answer);
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """);
        assertTrue(output.contains("3"));
        assertTrue(output.contains("true"));
        assertTrue(output.contains("yes"));
        assertTrue(output.contains("5"));
        assertTrue(output.contains("x"));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad() => [int, bool] {
                  return List.of(1, "wrong");
                }
                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad() => {foo: int} {
                  return Map.of("foo", 1, "dangling");
                }
                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad() => {foo: int} {
                  return Map.of(1, 2);
                }
                pub fnc main() => void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad() => {foo: int} {
                  return Map.of("foo", 1, "foo", 2);
                }
                pub fnc main() => void { return; }
                """)));
    }

    @Test
    void finiteTupleIndexesUseExactSlotTypesAndSafeDynamicWrites() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] {
                  return [1, true, "x"];
                }

                pub fnc main() => void {
                  let values = fixed();
                  val int number = values[0];
                  val bool enabled = values[1];
                  val string answer = values[2];
                  values[0] = 2;
                  stdio.println(number);
                  stdio.println(number);
                  stdio.println(enabled);
                  stdio.println(answer);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool] { return [1, true]; }
                pub fnc main() => void {
                  val values = fixed();
                  val int bad = values[2];
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool, string] { return [1, true, "x"]; }
                pub fnc main() => void {
                  val values = fixed();
                  let int index = 0;
                  val int narrowed = values[index];
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc fixed() => [int, bool] { return [1, true]; }
                pub fnc main() => void {
                  let values = fixed();
                  let int index = 0;
                  values[index] = 1;
                  return;
                }
                """)));
    }

    @Test
    void aliasedAndModuleQualifiedResultsPreserveExtractedCopyTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Fixed = [int, bool, string];
                type Named = {foo: int, bar: string};

                define module data
                  pub fnc fixed() => Fixed { return [9, true, "module"]; }
                  pub fnc named() => Named { return obj{foo: 11, bar: "record"}; }
                end

                define module app
                  pub fnc main() => void {
                    const [number, enabled, label] = data.fixed();
                    const {foo, bar} = data.named();
                    stdio.println(number);
                    stdio.println(number);
                    stdio.println(enabled);
                    stdio.println(label);
                    stdio.println(foo);
                    stdio.println(foo);
                    stdio.println(bar);
                    return;
                  }
                end
                """)));
    }

    @Test
    void finiteListAggregatesAreMoveOnlyEvenWhenSlotsAreCopy() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc main() => void {
                  val values = List.of(1, true);
                  val alias = values;
                  stdio.println(values[0]);
                  return;
                }
                """)));
    }

    @Test
    void parameterDestructuringRemainsUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(const [left, right]) => void {
                  return;
                }
                """));
    }

    @Test
    void runtimeReturnBoundaryRejectsUnknownWrongTupleAndRecordMembers() throws Exception {
        assertThrows(RuntimeException.class, () -> run("""
                fnc bad() => [int, int] {
                  return await [1, "wrong"];
                }

                pub fnc main() => void {
                  const [a, b] = bad();
                  stdio.println(a);
                  stdio.println(b);
                  return;
                }
                """));

        assertThrows(RuntimeException.class, () -> run("""
                fnc bad() => {foo: int} {
                  return await obj{foo: "wrong"};
                }

                pub fnc main() => void {
                  const {foo} = bad();
                  stdio.println(foo);
                  return;
                }
                """));
    }

    @Test
    void returnedTupleAndRecordDestructureAtRuntime() throws Exception {
        String program = """
                type FixedResult = [int, bool, string];
                type NamedResult = {foo: int, bar: string};

                fnc fixed() => FixedResult {
                  return [3, true, "yes"];
                }

                fnc result() => NamedResult {
                  return obj{foo: 5, bar: "x"};
                }

                pub fnc main() => void {
                  const [number, flag, answer] = fixed();
                  const {foo, bar} = result();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "returned-destructure.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("3"));
        assertTrue(text.contains("true"));
        assertTrue(text.contains("yes"));
        assertTrue(text.contains("5"));
        assertTrue(text.contains("x"));
    }


    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "returned-destructure-hardening.ores")
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
