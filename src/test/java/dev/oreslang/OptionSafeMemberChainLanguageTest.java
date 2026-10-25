package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Safe optional property access and lazy default conformance (Copy-field slice). */
final class OptionSafeMemberChainLanguageTest {
    @Test
    void safeMemberShortCircuitsNoneAndFlattensOnlyItsGeneratedLayer() throws Exception {
        String source = """
                define class Person as
                  pub val Option<string> name = Some("Ada");
                  pub val Option<Option<string>> deep = Some(None);
                end
                fnc nameOf(Option<Person> person): Option<string> {
                  return person?.name;
                }
                fnc deepOf(Option<Person> person): Option<Option<string>> {
                  return person?.deep;
                }
                pub fnc main(): void {
                  val Option<Person> present = Some(new Person());
                  val Option<Person> missing = None;
                  stdio.stdout.write(nameOf(present).unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nameOf(missing).is_none());
                  stdio.stdout.write(":");
                  val Option<Option<string>> nested = deepOf(Some(new Person()));
                  stdio.stdout.write(nested.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested.unwrap().is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("Ada:true:true:true", run(source));
    }

    @Test
    void coalesceIsLazyAndRespectsTernary() throws Exception {
        String source = """
                fnc fallback(): string {
                  stdio.stdout.write("F");
                  return "none";
                }
                fnc choose(bool test, Option<string> option): string {
                  return test ? (option ?? "inner") : "outer";
                }
                pub fnc main(): void {
                  val Option<string> present = Some("value");
                  val Option<string> absent = None;
                  stdio.stdout.write(present ?? fallback());
                  stdio.stdout.write(":");
                  stdio.stdout.write(absent ?? fallback());
                  stdio.stdout.write(":");
                  stdio.stdout.write(choose(true, absent));
                  stdio.stdout.write(":");
                  stdio.stdout.write(choose(false, present));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("value:Fnone:inner:outer", run(source));
    }

    @Test
    void chainedSafeFieldsNeverExecuteAfterNone() throws Exception {
        String source = """
                define class Node as
                  pub val Option<int> number = Some(7);
                end
                fnc pick(Option<Node> value): string {
                  return value?.number.is_some() ? "yes" : "no";
                }
                """;
        // Plain dot on the Option produced by ?. is not an implicit payload access.
        reject(source, "Option member");
    }

    @Test
    void rejectsResultReceiversAndUnknownOrInaccessibleProperties() {
        reject("""
                define class Item as
                  pub val string label = "x";
                end
                fnc wrong(Result<Item, string> value): Option<string> {
                  return value?.label;
                }
                """, "?. requires Option<T>");
        reject("""
                define class Item as
                  pub val string label = "x";
                end
                fnc wrong(Item value): Option<string> {
                  return value?.label;
                }
                """, "?. requires Option<T>");
        reject("""
                define class Item as
                  pub val string label = "x";
                end
                fnc wrong(Option<Item> value): Option<string> {
                  return value?.missing;
                }
                """, "unknown optional class field");
        reject("""
                define class Item as
                  val string secret = "x";
                end
                fnc wrong(Option<Item> value): Option<string> {
                  return value?.secret;
                }
                """, "private");
        reject("""
                fnc wrong(Option<int> x): string {
                  return x ?? "wrong";
                }
                """, "?? fallback");
        reject("""
                fnc wrong(int x): int {
                  return x ?? 7;
                }
                """, "?? requires Option<T>");
    }

    @Test
    void rejectsOptionalAssignmentAndMethodCallsAndMoveOnlyProjection() {
        reject("""
                define class Item as
                  pub let int count = 0;
                end
                fnc wrong(Option<Item> item): void {
                  item?.count = 3;
                  return;
                }
                """, "?. cannot be an assignment target");
        reject("""
                define class Item as
                  pub fnc value(): int { return 3; }
                end
                fnc wrong(Option<Item> item): int {
                  return item?.value();
                }
                """, "optional method calls");
        reject("""
                define class Box as
                end
                define class Item as
                  pub val Box nested = new Box();
                end
                fnc wrong(Option<Item> item): Option<Box> {
                  return item?.nested;
                }
                """, "Copy field");
    }

    private static void reject(String source, String fragment) {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage() != null && error.getMessage().contains(fragment),
                () -> "expected '" + fragment + "', got " + error.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "option-safe-member.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
