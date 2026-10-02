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
    void typesUnionFiniteArraysAndRecordResults() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  type intOrBoolOrString = bool | int | string;

                  pub fnc mixed() => Array<type intOrBoolOrString> {
                    return arr[3, true, "yes"];
                  }

                  pub fnc fixed() => [int, bool, string] {
                    return [3, true, "yes"];
                  }

                  pub fnc recordResult() => {foo: int, bar: string} {
                    return obj{foo: 5, bar: "x"};
                  }

                  pub fnc main() => void {
                    [const num, b, answer] = fixed();
                    const [again, _, text] = fixed();
                    const {foo, bar} = recordResult();
                    stdio.println(num);
                    stdio.println(num);
                    stdio.println(b);
                    stdio.println(answer);
                    stdio.println(again);
                    stdio.println(text);
                    stdio.println(foo);
                    stdio.println(foo);
                    stdio.println(bar);
                    return;
                  }
                end
                """)));
    }

    @Test
    void explicitObjectQualifiersAndQualifierPropagationWork() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc pair() => {foo: int, bar: string} {
                    return obj{foo: 7, bar: "ok"};
                  }

                  fnc values() => [int, int, int, int] {
                    return [1, 2, 3, 4];
                  }

                  pub fnc main() => void {
                    {const foo, const bar} = pair();
                    [const a, b, let c, d] = values();
                    c = 30;
                    d = 40;
                    stdio.println(foo);
                    stdio.println(bar);
                    stdio.println(a);
                    stdio.println(b);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc values() => [int, int] {
                    return [1, 2];
                  }

                  pub fnc main() => void {
                    [const a, b] = values();
                    b = 3;
                    return;
                  }
                end
                """)));
    }

    @Test
    void unionArrayBindingsRetainTheUnionType() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  type scalar = bool | int | string;

                  fnc values() => Array<scalar> {
                    return arr[1, true, "x"];
                  }

                  pub fnc main() => void {
                    const [first, second, third] = values();
                    val int narrowed = first;
                    return;
                  }
                end
                """)));
    }

    @Test
    void finiteArrayTypeSurvivesLocalBindingsAndArrRemainsHomogeneous() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc fixedThroughLocal() => [int, bool, string] {
                    val value = [3, true, "yes"];
                    return value;
                  }

                  pub fnc main() => void {
                    const [number, enabled, answer] = fixedThroughLocal();
                    stdio.println(number);
                    stdio.println(enabled);
                    stdio.println(answer);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc notFinite() => [int, int] {
                    return arr[1, 2];
                  }
                end
                """)));
    }

    @Test
    void finiteArrayIndexesUseSlotTypesAndDynamicWritesStaySafe() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    let values = [1, true, "x"];
                    val int number = values[0];
                    val bool enabled = values[1];
                    val string answer = values[2];
                    values[0] = 2;
                    stdio.println(number);
                    stdio.println(enabled);
                    stdio.println(answer);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    val values = [1, true, "x"];
                    let int index = 0;
                    val int narrowed = values[index];
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    let values = [1, true];
                    let int index = 0;
                    values[index] = 1;
                    return;
                  }
                end
                """)));
    }

    @Test
    void finiteListAggregatesMoveButExtractedScalarsRemainCopy() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    val values = [1, true, "x"];
                    [const number, enabled, answer] = values;
                    stdio.println(number);
                    stdio.println(number);
                    stdio.println(enabled);
                    stdio.println(answer);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main() => void {
                    val values = List.of(1, true);
                    val alias = values;
                    stdio.println(values[0]);
                    return;
                  }
                end
                """)));
    }

    @Test
    void finiteArrayReturnsRejectWrongArityAndMemberTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad() => [int, bool, string] {
                    return [1, true];
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad() => [int, bool, string] {
                    return [1, "wrong", "yes"];
                  }
                end
                """)));
    }

    @Test
    void recordReturnsAndDestructuresRejectMissingFields() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad() => {foo: int, bar: string} {
                    return obj{foo: 1};
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc value() => {foo: int, bar: string} {
                    return obj{foo: 1, bar: "x"};
                  }

                  pub fnc main() => void {
                    {const baz} = value();
                    return;
                  }
                end
                """)));
    }

    @Test
    void repeatedDiscardDoesNotDeclareOrConflict() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc values() => [int, bool, string] {
                    return [1, true, "kept"];
                  }

                  pub fnc main() => void {
                    [const _, _, answer] = values();
                    stdio.println(answer);
                    return;
                  }
                end
                """)));
    }

    @Test
    void jvmStyleListOfAndMapOfResultsDestructure() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc fixed() => [int, bool, string] {
                    return List.of(3, true, "yes");
                  }

                  pub fnc recordResult() => {foo: int, bar: string} {
                    return Map.of("foo", 5, "bar", "x");
                  }

                  pub fnc main() => void {
                    const [number, enabled, answer] = fixed();
                    const {foo, bar} = recordResult();
                    stdio.println(number);
                    stdio.println(enabled);
                    stdio.println(answer);
                    stdio.println(foo);
                    stdio.println(bar);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad() => [int, bool] {
                    return List.of(1, "wrong");
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad() => {foo: int} {
                    return Map.of("foo", 1, "dangling");
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad() => {foo: int} {
                    return Map.of(1, 2);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad() => {foo: int} {
                    return Map.of("foo", 1, "foo", 2);
                  }
                end
                """)));
    }

    @Test
    void moduleQualifiedReturnedResultsPreserveElementOwnership() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module data
                  pub fnc fixed() => [int, bool, string] {
                    return [9, true, "module"];
                  }

                  pub fnc recordResult() => {foo: int, bar: string} {
                    return obj{foo: 11, bar: "record"};
                  }
                end

                define module app
                  pub fnc main() => void {
                    const [number, enabled, label] = data.fixed();
                    const {foo, bar} = data.recordResult();
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
    void parameterDestructuringRemainsUnsupported() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc bad(const [left, right]) => void {
                    return;
                  }
                end
                """));
    }

    @Test
    void executesReturnedArrayAndObjectDestructuringThroughGraal() throws Exception {
        String program = """
                define module app
                  fnc fixed() => [int, bool, string] {
                    return List.of(3, true, "yes");
                  }

                  fnc recordResult() => {foo: int, bar: string} {
                    return Map.of("foo", 5, "bar", "x");
                  }

                  pub fnc main() => void {
                    [const num, b, answer] = fixed();
                    const {foo, bar} = recordResult();
                    stdio.println(num);
                    stdio.println(b);
                    stdio.println(answer);
                    stdio.println(foo);
                    stdio.println(bar);
                    return;
                  }
                end
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
}
