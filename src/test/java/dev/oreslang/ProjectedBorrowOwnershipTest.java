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
    void moveOnlyProjectedValuesCannotBecomeOwnedAliases() {
        IllegalArgumentException field = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(Holder holder): void {
                          val List<Item> alias = holder.items;
                          return;
                        }
                        """)));
        assertTrue(field.getMessage().contains("move-only field projection"),
                field.getMessage());

        IllegalArgumentException indexed = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(Holder holder): void {
                          val Item alias = holder.items[0];
                          return;
                        }
                        """)));
        assertTrue(indexed.getMessage().contains("move-only indexed projection"),
                indexed.getMessage());
    }

    @Test
    void listGetCannotExtractMoveOnlyElementByAlias() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(Holder holder): void {
                          val Item alias = holder.items.get(0);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("sequence element"),
                failure.getMessage());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(List<int> values): int {
                  return values.get(0);
                }
                """)));
    }

    @Test
    void forOfCannotMintOwnedAliasesFromMoveOnlySequenceElements() {
        IllegalArgumentException synchronous = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(Holder holder): void {
                          for const item of holder.items {
                            stdio.stdout.write(item.number);
                          }
                          return;
                        }
                        """)));
        assertTrue(synchronous.getMessage().contains("aliasing sequence"),
                synchronous.getMessage());

        IllegalArgumentException asynchronousAdapter = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        async fnc bad(Holder holder): void {
                          for await const item of holder.items {
                            stdio.stdout.write(item.number);
                          }
                          return;
                        }
                        """)));
        assertTrue(asynchronousAdapter.getMessage().contains("aliasing sequence"),
                asynchronousAdapter.getMessage());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(List<int> values): int {
                  let int total = 0;
                  for const value of values {
                    total = total + value;
                  }
                  return total;
                }
                """)));
    }

    @Test
    void projectedPatternBindingsCannotMintMoveOnlyOwners() {
        IllegalArgumentException matchProjection = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(Holder holder): void {
                          match holder.items[0] over
                            on Item item -> {
                              stdio.stdout.write(item.number);
                              return;
                            }
                          end
                        }
                        """)));
        assertTrue(matchProjection.getMessage().contains("pattern/refinement binding"),
                matchProjection.getMessage());

        IllegalArgumentException conditionProjection = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(TYPES + """
                        fnc bad(Holder holder): void {
                          if holder.items[0] is type Item item then
                            stdio.stdout.write(item.number);
                          fi
                          return;
                        }
                        """)));
        assertTrue(conditionProjection.getMessage().contains("pattern/refinement binding"),
                conditionProjection.getMessage());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class NumberBox as
                  pub let int value = 7;
                end

                fnc ok(NumberBox box): int {
                  match box.value over
                    on int value -> { return value; }
                  end
                }
                """)));
    }

    @Test
    void collectionBoundMethodsAreDirectCallOnly() {
        IllegalArgumentException getter = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(List<int> values): void {
                          val Fnc<int, int> get = values.get;
                          return;
                        }
                        """)));
        assertTrue(getter.getMessage().contains("direct-call-only"),
                getter.getMessage());

        IllegalArgumentException mutator = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(List<int> values): void {
                          val Fnc<int, void> add = values.add;
                          return;
                        }
                        """)));
        assertTrue(mutator.getMessage().contains("direct-call-only"),
                mutator.getMessage());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc good(): int {
                  let List<int> values = [1];
                  values.add(2);
                  values.set(0, 3);
                  return values.get(0);
                }
                """)));
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
                          val view = rt mut borrow holder.items[0];
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
