package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent-Task capture admission is a spawn-time ownership boundary,
 * even if the caller never awaits the returned Future.
 *
 * Scoped immutable READ capability is a separately proven feature; this
 * v2 branch must never accidentally admit ordinary borrowed captures.
 */
final class TaskBorrowBoundaryV2Test {
    private static final String BOX = """
            define class Box as
              pub let int value = 1;
            end
            """;

    private static void rejectsWith(String program, String... fragments) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(BOX + program));
        String message = failure.getMessage().toLowerCase();
        boolean matched = false;
        for (String fragment : fragments) {
            matched |= message.contains(fragment.toLowerCase());
        }
        assertTrue(matched, () -> "missing ownership diagnostic: " + failure.getMessage());
    }

    @Test
    void ordinaryImmutableBorrowCannotEscapeIntoEagerTask() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  val &Box loan = &owner;
                  let Future<int> child = task { return loan.value; };
                  await child;
                  return;
                }
                """, "borrow", "deferred task");
    }

    @Test
    void mutableBorrowCannotEscapeIntoCpuTask() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  val &mut Box loan = &mut owner;
                  let Future<int> child = cpu task { return loan.value; };
                  await child;
                  return;
                }
                """, "borrow", "mutable");
    }

    @Test
    void outstandingParentLoanCannotCrossAwait() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  val &Box loan = &owner;
                  let Future<int> child = task { return 42; };
                  await child;
                  stdio.stdout.write(loan.value);
                  return;
                }
                """, "borrow", "suspension");
    }

    @Test
    void sameAffineOwnerCannotBeCapturedTwice() {
        rejectsWith("""
                pub async fnc main(): void {
                  let Box owner = new Box();
                  let Future<int> first = cpu task { return owner.value; };
                  let Future<int> second = task { return owner.value; };
                  await first;
                  await second;
                  return;
                }
                """, "moved", "capture");
    }

    @Test
    void immutableCopyValueMayBeCapturedWithoutMovingParent() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc main(): void {
                  const int value = 41;
                  let Future<int> child = task { return value + 1; };
                  stdio.stdout.write(value);
                  stdio.stdout.write(await child);
                  return;
                }
                """));
    }
}
