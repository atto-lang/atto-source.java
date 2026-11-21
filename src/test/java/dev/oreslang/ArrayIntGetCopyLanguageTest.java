package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First source/compiler/evaluator slice: Array<int>.get_copy(i) -> Option<int>.
 * This is an owned scalar COPY, never Option<ReadLoan<T>>, NLL, actor sandbox
 * proof or native-image parity. Exact-head execution is still required.
 */
final class ArrayIntGetCopyLanguageTest {
    @Test
    void checkedCopyIsTaggedAndSurvivesIndependentMutationOnJVM() throws Exception {
        String program = """
                pub routine main(): void {
                  let mut Array<int> values = new Array<int>();
                  values.add(12);
                  values.add(30);
                  val owned = values.get_copy(0);
                  val last = values.get_copy(1);
                  val negative = values.get_copy(-1);
                  val high_bit = values.get_copy(2147483648);
                  val past_end = values.get_copy(2);

                  values.set(0, 99);
                  stdio.stdout.write(owned.unwrap_or(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(last.unwrap_or(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(negative.is_none());
                  stdio.stdout.write("|");
                  stdio.stdout.write(high_bit.is_none());
                  stdio.stdout.write("|");
                  stdio.stdout.write(past_end.is_none());
                  stdio.stdout.write("|");
                  stdio.stdout.write(values.get(0));
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "array-int-get-copy.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("12|30|true|true|true|99",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void copyWhileRootMutablyBorrowedIsRejectedEvenForMiss() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          let mut Array<int> values = new Array<int>();
                          values.add(42);
                          val borrow mut Array<int> exclusive = rt mut borrow values;
                          val missed = values.get_copy(-1);
                          stdio.stdout.write(exclusive.size);
                          return;
                        }
                        """)));
        assertTrue(rejected.getMessage().toLowerCase().contains("borrow"),
                rejected::toString);
    }

    @Test
    void temporaryCollectionCannotForgeNamedCopyRoot() {
        var candidate = Parser.parse("""
                pub fnc main(): void {
                  val copied = new Array<int>().get_copy(0);
                  return;
                }
                """);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(candidate));
        assertTrue(rejected.getMessage().contains("named builtin"),
                rejected::toString);
    }

    @Test
    void unsupportedMoveOnlyElementCopyNeverLowers() {
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Record as
                          pub let int number = 1;
                        end
                        pub fnc main(): void {
                          let mut Array<Record> values = new Array<Record>();
                          values.add(new Record());
                          val copied = values.get_copy(0);
                          return;
                        }
                        """)));
        assertTrue(rejected.getMessage().contains("get_copy"),
                rejected::toString);
    }
}
