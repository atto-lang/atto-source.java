package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-feature ownership regression: Atto uses rt take and compiler-derived
 * asynchronous capture transfers, not a separate Rust-style move keyword.
 *
 * These tests run the real Java compiler/type checker. They are not execution
 * receipts for LLVM, Wasm, Forge or .NET, or for a live NATS broker.
 */
final class AttoOwnershipTransferNoMoveKeywordTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 1;
            end
            """;

    private static void admit(String program) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(BOX + program));
    }

    private static void rejectMoved(String program) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + program)));
        assertTrue(failure.getMessage().toLowerCase().contains("moved"),
                "expected semantic moved-value diagnostic, not syntax denial: "
                        + failure.getMessage());
    }

    @Test
    void explicitTakeTransfersIntoNewLocalWithoutMoveKeyword() {
        admit("""
                pub fnc main(): int {
                  val Box original = new Box();
                  val Box current = rt take original;
                  return current.value;
                }
                """);
    }

    @Test
    void takeCannotLeaveReadableOriginalOrBeTakenTwice() {
        rejectMoved("""
                fnc bad(): int {
                  val Box original = new Box();
                  val Box current = rt take original;
                  return original.value;
                }
                """);
        rejectMoved("""
                fnc bad(): void {
                  val Box original = new Box();
                  val Box first = rt take original;
                  val Box second = rt take original;
                  return;
                }
                """);
    }

    @Test
    void mutableRebindingAndMutableReferentAreDifferentCapabilities() {
        admit("""
                fnc ok(): int {
                  val Box fixed_mutable = new Box();
                  fixed_mutable.value = 4;
                  let Box rebindable_readonly = new Box();
                  rebindable_readonly = new Box();
                  let mut Box mutable_and_rebindable = new Box();
                  mutable_and_rebindable.value = 7;
                  return fixed_mutable.value;
                }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): void {
                          let Box readonly = new Box();
                          readonly.value = 3;
                          return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): void {
                          val Box fixed = new Box();
                          fixed = new Box();
                          return;
                        }
                        """)));
    }

    @Test
    void canonicalMutableBorrowIsRtMutBorrow() {
        admit("""
                fnc inspect(borrow mut int n): int { return n; }
                fnc ok(): int {
                  let mut int n = 7;
                  return inspect(rt mut borrow n);
                }
                """);
    }

    @Test
    void independentTaskMovesNonCopyOwnerAtSubmission() {
        rejectMoved("""
                pub async fnc bad(): int {
                  val Box owner = new Box();
                  let Future<int> child = task { return owner.value; };
                  stdio.println(owner.value);
                  return await child;
                }
                """);
    }

    @Test
    void independentTaskRejectsBorrowCapture() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        pub async fnc bad(): int {
                          val Box owner = new Box();
                          val borrow Box view = rt borrow owner;
                          let Future<int> child = task { return view.value; };
                          return await child;
                        }
                        """)));
        String detail = failure.getMessage().toLowerCase();
        assertTrue(detail.contains("borrow") || detail.contains("capture"),
                "expected lifetime/capture diagnostic: " + failure.getMessage());
    }

    @Test
    void deferredChannelCallbackMovesOwnedCaptureAtRegistration() {
        rejectMoved("""
                actor fnc bad(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  val Array<int> data = [7, 8];
                  nb cb writech output, 42 || -> {
                    stdio.println(data[0]);
                  };
                  stdio.println(data[0]);
                  return;
                }
                """);
    }

    @Test
    void deferredChannelCallbackAllowsCopyScalarCapture() {
        admit("""
                actor fnc good(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  val int value = 7;
                  nb cb writech output, 42 || -> {
                    stdio.println(value);
                  };
                  stdio.println(value);
                  return;
                }
                """);
    }

    @Test
    void channelsCannotTakeTheSameHandleTwice() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc consume(Channel<int> input): int {
                          return await (nb readch input);
                        }
                        fnc bad(): void {
                          val Channel<int> channel = Channel.new<int>(1);
                          val Future<int> one = consume(rt take channel);
                          val Future<int> two = consume(rt take channel);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().toLowerCase().contains("moved")
                || failure.getMessage().toLowerCase().contains("own"),
                failure.getMessage());
    }
}
