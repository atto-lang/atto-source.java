package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AuditCallReceiverLoanTest {
    private static final String BASE = """
            define class Counter as
              pub let int count = 0;
              pub bump(mut self)() -> int {
                self.count = self.count + 1;
                return self.count;
              }
              pub take_borrow(mut self)(borrow mut Counter other) -> int {
                return other.count;
              }
              pub inspect(borrow mut Counter other) -> int {
                return self.count + other.count;
              }
              pub read_value(int value) -> int {
                return self.count + value;
              }
              pub read_count() -> int {
                return self.count;
              }
              pub increment_by(mut self)(int value) -> int {
                self.count = self.count + value;
                return self.count;
              }
            end
            fnc take(borrow mut Counter p, int value) -> int {
              return value;
            }
            """;

    private static void mustReject(String program) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BASE + program)));
    }

    @Test
    void forwardedMutableParamCannotBeReadDuringLaterArgument() {
        mustReject("""
                fnc invalid(borrow mut Counter p) -> int {
                  return take(p, p.count);
                }
                """);
    }

    @Test
    void forwardedMutableParamCannotBeMutatedDuringLaterArgument() {
        mustReject("""
                fnc invalid(borrow mut Counter p) -> int {
                  return take(p, p.bump());
                }
                """);
    }

    @Test
    void mutableMethodReceiverCannotSimultaneouslyBeBorrowedAsArgument() {
        mustReject("""
                fnc invalid() -> int {
                  val Counter p = new Counter();
                  return p.take_borrow(rt mut borrow p);
                }
                """);
    }

    @Test
    void readOnlyMethodCannotBorrowSameReceiverMutably() {
        mustReject("""
                fnc invalid() -> int {
                  val Counter p = new Counter();
                  return p.inspect(rt mut borrow p);
                }
                """);
    }

    @Test
    void readOnlyMethodCannotWriteToReceiverInLaterArgument() {
        mustReject("""
                fnc invalid() -> int {
                  val Counter p = new Counter();
                  return p.read_value(p.bump());
                }
                """);
    }

    @Test
    void readOnlyMethodCanCopyFieldIntoLaterArgument() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BASE + """
                fnc valid() -> int {
                  val Counter p = new Counter();
                  return p.read_value(p.count);
                }
                """)));
    }

    @Test
    void readOnlyMethodCanReceiveIndependentMutableOwner() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BASE + """
                fnc valid() -> int {
                  val Counter p = new Counter();
                  val Counter q = new Counter();
                  return p.inspect(rt mut borrow q);
                }
                """)));
    }

    @Test
    void twoPhaseMutableReceiverAllowsNestedReadOnlyMethodArgument() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BASE + """
                fnc valid() -> int {
                  val Counter p = new Counter();
                  return p.increment_by(p.read_count());
                }
                """)));
    }

    @Test
    void distinctReceiverAndMutableArgumentAreFine() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BASE + """
                fnc valid() -> int {
                  val Counter p = new Counter();
                  val Counter q = new Counter();
                  return p.take_borrow(rt mut borrow q);
                }
                """)));
    }
}
