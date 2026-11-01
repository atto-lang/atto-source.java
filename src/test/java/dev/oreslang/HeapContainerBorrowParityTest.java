package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guest-source heap/container lifetime admission. Runtime smart-pointer host
 * tests alone cannot prove .ores compiler borrow checking or backend parity.
 * These cases use the actual Java parser/typechecker/ownership checker and,
 * for the positive List scenario, the Graal guest interpreter.
 */
final class HeapContainerBorrowParityTest {
    private static final String CELL = """
            define class Cell as
              pub let int value = 21;
            end
            """;

    private static void accepts(String source) {
        var ast = assertDoesNotThrow(() -> Parser.parse(source));
        assertDoesNotThrow(() -> TypeChecker.check(ast));
        assertDoesNotThrow(() -> OwnershipChecker.check(ast));
    }

    private static void rejectsBorrow(String source) {
        var ast = assertDoesNotThrow(() -> Parser.parse(source));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(ast));
        assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("borrow")
                        || failure.getMessage().toLowerCase(Locale.ROOT).contains("outlive"),
                "must be diagnosed by semantic loan/lifetime admission: "
                        + failure.getMessage());
    }

    @Test
    void sharedHeapOwnerCanBeBorrowedTwiceForReadOnlyFunction() {
        accepts(CELL + """
                fnc read_two(borrow Cell a, borrow Cell b): int {
                  return a.value + b.value;
                }
                pub fnc main(): int {
                  let mut Cell item = new Cell();
                  return read_two(rt borrow item, rt borrow item);
                }
                """);
    }

    @Test
    void distinctHeapOwnersPermitIndependentExclusiveLoans() {
        accepts(CELL + """
                fnc read_two(borrow mut Cell a, borrow mut Cell b): int {
                  return a.value + b.value;
                }
                pub fnc main(): int {
                  let mut Cell a = new Cell();
                  let mut Cell b = new Cell();
                  return read_two(rt mut borrow a, rt mut borrow b);
                }
                """);
    }

    @Test
    void sharedAndExclusiveLoanOfSameHeapReferentIsRejected() {
        rejectsBorrow(CELL + """
                fnc read_two(borrow Cell a, borrow mut Cell b): int {
                  return a.value + b.value;
                }
                pub fnc main(): int {
                  let mut Cell item = new Cell();
                  return read_two(rt borrow item, rt mut borrow item);
                }
                """);
    }

    @Test
    void duplicateExclusiveLoanOfSameHeapReferentIsRejected() {
        rejectsBorrow(CELL + """
                fnc read_two(borrow mut Cell a, borrow mut Cell b): int {
                  return a.value + b.value;
                }
                pub fnc main(): int {
                  let mut Cell item = new Cell();
                  return read_two(rt mut borrow item, rt mut borrow item);
                }
                """);
    }

    @Test
    void resizableListCannotGrowWhileElementViewIsLive() {
        rejectsBorrow("""
                define class Item as
                  pub val int number;
                end
                define class Holder as
                  pub let mut List<Item> items;
                end
                fnc invalid(Holder mut holder): void {
                  val borrow Item view = rt borrow holder.items[0];
                  holder.items.add(new Item(3));
                  stdio.stdout.write(view.number);
                  return;
                }
                """);
    }

    @Test
    void projectedElementBorrowCannotEscapeOwnedLocalHeap() {
        rejectsBorrow("""
                define class Item as
                  pub val int number;
                end
                fnc invalid(): borrow Item {
                  val List<Item> items = new List<Item>();
                  items.add(new Item(42));
                  return rt borrow items[0];
                }
                """);
    }

    @Test
    void guestListGrowsAndReadsThroughNonEscapingBorrow() throws Exception {
        var source = """
                define class Item as
                  pub val int number;
                end
                fnc inspect(borrow Item item): int { return item.number; }
                pub routine main(): void {
                  val List<Item> values = new List<Item>();
                  values.add(new Item(21));
                  values.add(new Item(21));
                  stdio.stdout.write(inspect(rt borrow values[0])
                      + inspect(rt borrow values[1]));
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source ores = Source.newBuilder(OresLanguage.ID, source, "heap-list.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(ores);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void resizableVectorLiteralMustRespectDeclaredMaxCapacity() {
        var overflow = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val values: Vector<max_capacity=1>[int] = [21, 21];
                          return;
                        }
                        """)));
        assertTrue(overflow.getMessage().contains("max_capacity"),
                overflow.getMessage());

        var zero = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val values: Vector<max_capacity=0>[int] = [1];
                          return;
                        }
                        """)));
        assertTrue(zero.getMessage().contains("max_capacity"), zero.getMessage());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val values: Vector<max_capacity=2>[int] = [21, 21];
                  return;
                }
                """)));
    }

    @Test
    void inconsistentResizableCapacityMetadataIsRejected() {
        for (String family : new String[]{"Vector", "List", "Array"}) {
            String code = """
                    pub fnc main(): void {
                      val values: %s<inline_capacity=3,max_capacity=2>[int] = [1];
                      return;
                    }
                    """.formatted(family);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(code)));
            assertTrue(failure.getMessage().contains("inline_capacity")
                            && failure.getMessage().contains("max_capacity"),
                    failure.getMessage());
        }
    }

    @Test
    void resizableListArrayAndVectorLiteralQuotaIsTypechecked() {
        for (String family : new String[]{"Vector", "List", "Array"}) {
            String code = """
                    pub fnc main(): void {
                      val values: %s<max_capacity=1>[int] = [1, 2];
                      return;
                    }
                    """.formatted(family);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(code)));
            assertTrue(failure.getMessage().contains("max_capacity"), failure.getMessage());
        }
    }
}
