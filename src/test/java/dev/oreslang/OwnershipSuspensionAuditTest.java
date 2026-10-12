package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OwnershipSuspensionAuditTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 7;
            end
            """;
    private static void check(String code) { OwnershipChecker.check(Parser.parse(code)); }
    private static void reject(String code, String message) {
        var error = assertThrows(IllegalArgumentException.class, () -> check(code));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }
    @Test void suspendingCallChecksItsTemporaryArgumentLoan() {
        reject(BOX + """
                suspends fnc read(borrow Box box): int { return box.value; }
                suspends fnc bad(): int {
                  val Box box = new Box();
                  return read(rt borrow box);
                }
                """, "borrow");
    }
    @Test void qualifiedStaticAndInstanceCallsCheckInvocationLoans() {
        reject(BOX + """
                define module worker as
                  pub suspends fnc read(borrow Box box): int { return box.value; }
                end
                suspends fnc bad(): int {
                  val Box box = new Box();
                  return worker.read(rt borrow box);
                }
                """, "borrow");
        reject(BOX + """
                define class Reader as
                  pub static suspends fnc read(borrow Box box): int { return box.value; }
                  pub suspends readInstance(borrow Box box): int { return box.value; }
                end
                suspends fnc bad(): int {
                  val Box box = new Box();
                  return Reader.read(rt borrow box);
                }
                """, "borrow");
        reject(BOX + """
                define class Reader as
                  pub suspends read(borrow Box box): int { return box.value; }
                end
                suspends fnc bad(): int {
                  val Box box = new Box();
                  val Reader reader = new Reader();
                  return reader.read(rt borrow box);
                }
                """, "borrow");
    }

    @Test void laterArgumentCannotSuspendWhileEarlierLoanIsLive() {
        reject(BOX + """
                fnc read(borrow Box box, int n): int { return box.value + n; }
                suspends fnc pause(): int { rt cooperate; return 1; }
                suspends fnc bad(): int {
                  val Box box = new Box();
                  return read(rt borrow box, pause());
                }
                """, "borrow");
    }
    @Test void newlyCreatedYieldBorrowCannotEscape() {
        reject(BOX + """
                gen* fnc bad(): Iterator<borrow Box> {
                  val Box box = new Box();
                  yield rt borrow box;
                  return;
                }
                """, "borrow");
    }
    @Test void explicitProjectedTakeCannotDuplicateMoveOnlyData() {
        reject(BOX + """
                fnc bad(): void {
                  val holder = struct{child: Box}{child: new Box()};
                  val first = rt take holder.child;
                  val second = rt take holder.child;
                  return;
                }
                """, "projected");
    }
    @Test void collectionGetCannotTurnAnAliasedElementIntoAnOwner() {
        reject(BOX + "fnc bad(): void { val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); val extracted = rt take boxes.get(0); return; }", "projected");
    }

    @Test void castsAndConditionalsCannotHideProjectedTake() {
        for (String projection : new String[]{"holder.child as Box", "true ? holder.child : holder.child"}) {
            reject(BOX + "fnc bad(): void { val holder = struct{child: Box}{child: new Box()}; val first = rt take (" + projection + "); return; }", "projected");
        }
    }

    @Test void owningOperationsCannotUpgradeBorrowedData() {
        for (String operation : new String[]{"copy", "share", "take"}) {
            reject(BOX + "fnc bad(): void { val Box box = new Box(); val view = rt borrow box; val result = rt " + operation + " view; return; }", "rt " + operation);
        }
    }
    @Test void castsCannotLaunderBorrowedViewIntoOwnedBinding() {
        reject(BOX + """
                fnc bad(): void {
                  let Box target = new Box();
                  val Box apparentOwner = (rt borrow target) as Box;
                  val Box moved = rt take apparentOwner;
                  return;
                }
                """, "borrowed value cannot be bound as an owned type");
        reject(BOX + """
                fnc bad(): void {
                  let Box target = new Box();
                  val apparentOwner = (rt borrow target) as Box;
                  val Box moved = rt take apparentOwner;
                  return;
                }
                """, "borrowed value cannot be bound as an owned type");
        reject(BOX + """
                fnc bad(): void {
                  let Box target = new Box();
                  val apparentOwner = (rt borrow target) as? Box;
                  return;
                }
                """, "optional cast of borrowed value");
    }

    @Test void conditionalCannotJoinOwnedAndBorrowedBranches() {
        reject(BOX + """
                fnc bad(): void {
                  let Box target = new Box();
                  val apparentOwner = true ? (rt borrow target) : target;
                  return;
                }
                """, "conditional cannot merge borrowed values");
        reject(BOX + """
                fnc bad(): void {
                  let Box target = new Box();
                  val apparentOwner = true ? target : (rt borrow target);
                  return;
                }
                """, "conditional cannot merge borrowed values");
    }

    @Test void genuineOwnedAndCopyableCastsStillWork() {
        check(BOX + """
                fnc good(): void {
                  val Box owner = new Box();
                  val Box retainedOwner = owner as Box;
                  val int number = 9;
                  val copied = rt copy number;
                  val shared = rt share number;
                  val optional = number as? int;
                  return;
                }
                """);
    }

    @Test void delegatedGeneratorCannotExportBorrowedElement() {
        reject(BOX + """
                fnc fabricate(): Iterator<borrow Box> { return null; }
                gen* fnc bad(): Iterator<borrow Box> {
                  yield* fabricate();
                  return;
                }
                """, "cannot yield borrowed elements");
    }

    @Test void delegatedGeneratorCannotHideBorrowInNestedElement() {
        reject(BOX + """
                fnc fabricate(): Iterator<Option<borrow Box>> { return null; }
                gen* fnc bad(): Iterator<Option<borrow Box>> {
                  yield* fabricate();
                  return;
                }
                """, "cannot yield borrowed elements");
    }

    @Test void delegatedOwnedScalarElementRemainsValid() {
        check("""
                fnc fabricate(): Iterator<int> { return null; }
                gen* fnc good(): Iterator<int> {
                  yield* fabricate();
                  return;
                }
                """);
    }

    @Test void endedLoansPermitSuspensionAndOwnerTransfer() {
        check(BOX + """
                fnc read(borrow Box box): int { return box.value; }
                suspends fnc pause(): int { rt cooperate; return 1; }
                suspends fnc good(): void {
                  val Box box = new Box();
                  val n = read(rt borrow box);
                  pause();
                  val moved = rt take box;
                  return;
                }
                """);
    }
}
