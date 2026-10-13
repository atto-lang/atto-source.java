package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A borrowed argument remains protected throughout evaluation of later arguments. */
final class CallArgumentEvaluationOrderTest {
    private static final String HEAD = """
            define class Cell as
              pub let int value = 1;
              pub bump(mut self)() -> int {
                self.value = self.value + 1;
                return self.value;
              }
            end
            fnc consume(borrow mut Cell owner, int value) -> int {
              return value;
            }
            fnc consumeRead(borrow Cell owner, int value) -> int {
              return value;
            }
            """;

    @Test
    void exclusiveBorrowBlocksLaterReadArgument() {
        var program = Parser.parse(HEAD + """
                fnc bad(): int {
                  val Cell q = new Cell();
                  return consume(rt borrow mut q, q.value);
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program),
                "later arguments cannot read an owner still exclusively lent to the call");
    }

    @Test
    void exclusiveBorrowBlocksLaterMutatingMethodArgument() {
        var program = Parser.parse(HEAD + """
                fnc bad(): int {
                  val Cell q = new Cell();
                  return consume(rt borrow mut q, q.bump());
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program),
                "later arguments cannot mutate an owner still exclusively lent to the call");
    }

    @Test
    void sharedBorrowBlocksLaterWriteArgument() {
        var program = Parser.parse(HEAD + """
                fnc bad(): int {
                  val Cell q = new Cell();
                  return consumeRead(rt borrow q, q.bump());
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program),
                "later arguments cannot mutate a shared owner still lent to the call");
    }

    @Test
    void reorderedNamedMutableBorrowChecksInLexicalSourceOrder() {
        var accepted = Parser.parse(HEAD + """
                fnc good(): int {
                  val Cell q = new Cell();
                  return consume(value: q.value, owner: rt borrow mut q);
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(accepted));
        assertDoesNotThrow(() -> OwnershipChecker.check(accepted));

        var rejected = Parser.parse(HEAD + """
                fnc bad(): int {
                  val Cell q = new Cell();
                  return consume(owner: rt borrow mut q, value: q.value);
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(rejected),
                "an earlier exclusive named argument loan must block later owner reads");
    }

    @Test
    void anUnrelatedLaterArgumentRemainsLegal() {
        var program = Parser.parse(HEAD + """
                fnc good(): int {
                  val Cell q = new Cell();
                  return consume(rt borrow mut q, 42);
                }
                """);
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }
}
