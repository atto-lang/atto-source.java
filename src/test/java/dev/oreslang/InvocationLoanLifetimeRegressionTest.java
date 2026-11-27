package dev.oreslang;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InvocationLoanLifetimeRegressionTest {
    private static final String BOX = "define class Box as pub let int value = 7; end\n";
    private static void reject(String program, String code) {
        var e = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(BOX + program)));
        assertTrue(e.getMessage().contains(code), e::getMessage);
    }
    @Test void directSuspendingArgumentLoanRejected() {
        reject("suspends fnc read(borrow Box b): int { return b.value; } "
                + "suspends fnc bad(): int { val b = new Box(); return read(borrow! b); }", "E_LOAN_SUSPEND");
    }
    @Test void moduleSuspendingArgumentLoanRejected() {
        reject("define module worker as pub suspends fnc read(borrow Box b): int { return b.value; } end "
                + "suspends fnc bad(): int { val b = new Box(); return worker.read(borrow! b); }", "E_LOAN_SUSPEND");
    }
    @Test void collectionGetCannotCreateOwnerThroughTake() {
        reject("fnc bad(): void { val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); "
                + "val x = take! boxes.get(0); return; }", "E_MOVE_UNPROVEN");
    }
    @Test void wrappedCollectionGetCannotCreateOwner() {
        for (String expr : new String[]{"boxes.get(0) as Box", "true ? boxes.get(0) : boxes.get(0)"}) {
            reject("fnc bad(): void { val Array<Box> boxes = new Array<Box>(); boxes.add(new Box()); "
                    + "val x = take! (" + expr + "); return; }", "E_MOVE_UNPROVEN");
        }
    }
    @Test void completedNonSuspendingCallReleasesLoan() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(BOX
                + "fnc read(borrow Box b): int { return b.value; } "
                + "suspends fnc pause(): int { rt cooperate; return 1; } "
                + "suspends fnc good(): int { val b = new Box(); val n = read(borrow! b); "
                + "pause(); val moved = take! b; return moved.value + n; }")));
    }
}
