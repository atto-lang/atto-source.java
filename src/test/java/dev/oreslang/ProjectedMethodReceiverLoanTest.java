package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProjectedMethodReceiverLoanTest {
    private static final String TYPES = """
        define class Item as
          pub let int value = 1;
          pub read(int ignored): int { return self.value; }
          pub update(mut self)(int ignored): int { self.value = ignored; return self.value; }
        end
        define class Holder as
          pub let mut Item item;
        end
        fnc mutate(borrow mut Holder holder): int { holder.item.value = 2; return 0; }
        fnc consume(Holder holder): int { return 0; }
        """;
    private static void rejects(String expression) {
        var parsed = Parser.parse(TYPES + "fnc bad(Holder mut holder): int { return " + expression + "; }");
        var failure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"), failure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
    }
    @Test void sharedReceiverBlocksRootMutationInLaterArgument() {
        rejects("holder.item.read(mutate(rt borrow mut holder))");
    }
    @Test void sharedReceiverBlocksRootMoveInLaterArgument() {
        rejects("holder.item.read(consume(rt take holder))");
    }
    @Test void exclusiveReceiverBlocksRootMutationInLaterArgument() {
        rejects("holder.item.update(mutate(rt borrow mut holder))");
    }
    @Test void independentArgumentRemainsValid() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(TYPES + """
            fnc good(Holder mut left, Holder mut right): int {
              return left.item.read(mutate(rt borrow mut right));
            }
            """)));
    }
    @Test void sharedReceiverBlocksMutationThroughBorrowedAlias() {
        var parsed = Parser.parse(TYPES + """
            fnc bad(borrow mut Holder holder): int {
              return holder.item.read(mutate(rt borrow mut holder));
            }
            """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
    }
    @Test void indexedReceiverBlocksContainerMove() {
        var parsed = Parser.parse(TYPES + """
            fnc consumeItems(List<Item> items): int { return 0; }
            fnc bad(List<Item> mut items): int {
              return items[0].read(consumeItems(rt take items));
            }
            """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
    }
    @Test void sharedProjectedReceiversCanNestAndReleaseLoans() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(TYPES + """
            fnc good(Holder mut holder): int {
              let int result = holder.item.read(holder.item.read(0));
              return mutate(rt borrow mut holder) + result;
            }
            """)));
    }
    @Test void unstableIndexedReceiverFailsClosed() {
        var parsed = Parser.parse(TYPES + """
            fnc index(): int { return 0; }
            fnc bad(List<Item> items): int { return items[index()].read(0); }
            """);
        var failure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(failure.getMessage().contains("stable"), failure.getMessage());
    }

    @Test void receiverLoanCannotCrossSuspendingArgument() {
        var parsed = Parser.parse(TYPES + """
            fnc bad(Holder holder, Channel<int> input): int {
              return holder.item.read(readch input);
            }
            """);
        var failure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(failure.getMessage().contains("ordinary borrow is live"), failure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
    }

    @Test void exclusiveProjectedReceiverReleasesLoanAfterCall() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(TYPES + """
            fnc good(Holder mut holder): int {
              let int result = holder.item.update(42);
              return mutate(rt borrow mut holder) + result;
            }
            """)));
    }
    @Test void stableIndexedReadReceiverRemainsValid() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(TYPES + """
            fnc good(List<Item> items, int i): int {
              return items[i].read(items[i].read(0));
            }
            """)));
    }
    @Test void sharedProjectedReceiverRejectsNestedMutatingReceiver() {
        rejects("holder.item.read(holder.item.update(2))");
    }

}
