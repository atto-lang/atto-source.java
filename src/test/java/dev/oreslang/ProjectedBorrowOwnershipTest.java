package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Executable and negative controls for root-tracked, read-only projected loans. */
final class ProjectedBorrowOwnershipTest {
    private static final String TYPES = """
            define class Item as
              pub val int number;
            end

            define class Holder as
              pub let mut List<Item> items;
            end
            """;

    @Test
    void borrowingNestedIndexedValuePreservesSourceOwnership() throws Exception {
        String program = TYPES + """
                fnc inspect(borrow Item item): int { return item.number; }

                fnc valueAt(borrow Holder holder, int i): int {
                  val borrow Item view = rt borrow holder.items[i];
                  return inspect(view);
                }

                pub routine main(): void {
                  val List<Item> values = new List<Item>();
                  values.add(new Item(42));
                  val Holder holder = new Holder(values);
                  stdio.stdout.write(valueAt(rt borrow holder, 0));
                  stdio.stdout.write(inspect(rt borrow holder.items[0]));
                  return;
                }
                """;
        assertEquals("4242", run(program));
    }

    @Test
    void storedProjectionGuardsEntireOwnerAgainstMove() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc consume(Holder h): void { return; }
                        fnc bad(Holder holder): void {
                          val borrow Item view = rt borrow holder.items[0];
                          consume(holder);
                          stdio.stdout.write(view.number);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("borrow"), failure.getMessage());
    }

    @Test
    void storedProjectionGuardsOwnerAgainstMutation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(Holder mut holder): void {
                          val borrow Item view = rt borrow holder.items[0];
                          holder.items.add(new Item(2));
                          stdio.stdout.write(view.number);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("borrow"), failure.getMessage());
    }

    @Test
    void cannotReturnProjectionOfOwnedLocal() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(): borrow Item {
                          val List<Item> values = new List<Item>();
                          values.add(new Item(1));
                          return rt borrow values[0];
                        }
                        """)));
        assertTrue(failure.getMessage().contains("outlive")
                || failure.getMessage().contains("returned borrows"), failure.getMessage());
    }

    @Test
    void computedIndexAndTemporaryOwnerStillFailClosed() {
        IllegalArgumentException computed = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc index(): int { return 0; }
                        fnc bad(borrow Holder holder): int {
                          val borrow Item value = rt borrow holder.items[index()];
                          return value.number;
                        }
                        """)));
        assertTrue(computed.getMessage().contains("stable"), computed.getMessage());

        IllegalArgumentException temporary = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(): void {
                          val borrow Item view = rt borrow (new Item(42));
                          stdio.stdout.write(view.number);
                          return;
                        }
                        """)));
        assertTrue(temporary.getMessage().contains("named owner")
                || temporary.getMessage().contains("stable field/index"), temporary.getMessage());
    }

    @Test
    void mutableProjectedBorrowIsNotYetAdmitted() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(Holder mut holder): void {
                          val view = &mut holder.items[0];
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("path-exclusive")
                || failure.getMessage().contains("mutable projected"), failure.getMessage());
    }

    @Test
    void lexicalProjectionBorrowCannotCrossAwait() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        async fnc answer(): int { return 42; }
                        async fnc bad(borrow Holder holder): int {
                          val borrow Item view = rt borrow holder.items[0];
                          val int result = await answer();
                          return view.number + result;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("borrow"), failure.getMessage());
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "projected-borrow.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
