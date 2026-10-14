package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SourceAdmissionBudgetTest {
    @Test void acceptsBoundaryBytesAndDepth() {
        SourceAdmissionBudget budget = new SourceAdmissionBudget();
        assertDoesNotThrow(() -> budget.admit(16 * 1024 * 1024, SourceAdmissionBudget.MAX_DEPTH));
    }

    @Test void rejectsTotalImportByteBudgetWithoutIntegerOverflow() {
        SourceAdmissionBudget budget = new SourceAdmissionBudget();
        for (int i = 0; i < 4; i++) budget.admit(16 * 1024 * 1024, 0);
        assertThrows(IllegalArgumentException.class, () -> budget.admit(1, 0));
    }

    @Test void rejectsTooManySourceUnits() {
        SourceAdmissionBudget budget = new SourceAdmissionBudget();
        for (int i = 0; i < SourceAdmissionBudget.MAX_UNITS; i++) budget.admit(0, 0);
        assertThrows(IllegalArgumentException.class, () -> budget.admit(0, 0));
    }

    @Test void rejectsExcessiveImportDepthAndInvalidSourceLength() {
        SourceAdmissionBudget budget = new SourceAdmissionBudget();
        assertThrows(IllegalArgumentException.class,
                () -> budget.admit(1, SourceAdmissionBudget.MAX_DEPTH + 1));
        assertThrows(IllegalArgumentException.class, () -> budget.admit(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> budget.admit(1, -1));
    }
}
