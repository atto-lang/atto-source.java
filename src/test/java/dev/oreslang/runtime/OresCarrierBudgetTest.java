package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class OresCarrierBudgetTest {

    private static OresCarrierBudget parse(String configured) {
        String previous = System.getProperty(OresCarrierBudget.PROPERTY);
        try {
            if (configured == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, configured);
            return OresCarrierBudget.configured();
        } finally {
            if (previous == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, previous);
        }
    }

    @Test void absentPropertyDoesNotChangeLegacyTopology() {
        assertNull(parse(null));
    }

    @Test void oneControlSlotRemainsReservedAtEverySupportedBudgetSize() {
        for (int cap : new int[] {4, 5, 6, 7, 10, 16, 64, 255, 256}) {
            OresCarrierBudget budget = parse(Integer.toString(cap));
            assertEquals(cap, budget.maximum());
            assertEquals(1, budget.controlReserve());
            assertTrue(budget.privateMaximum() >= 1);
            assertEquals(budget.privateMaximum(), budget.sharedMaximum());
            assertEquals(budget.privateMaximum(), budget.untrustedMaximum());
            assertTrue(budget.allocatedCeiling() <= cap,
                    "all actor and CONTROL carrier ceilings must fit within VM maximum");
            assertTrue(cap - budget.allocatedCeiling() <= 2,
                    "static partition may leave at most two unborrowed permits");
        }
    }

    @Test void invalidBudgetFailsClosedRatherThanDisablingTheGuard() {
        for (String bad : new String[] {"", " ", "0", "-1", "3", "257",
                "99999999999999999", "1.5", "8 ", "wat"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(bad), bad);
        }
    }

    @Test void forgedBudgetCannotOvercommitOrEraseControlReserve() {
        assertThrows(IllegalArgumentException.class,
                () -> new OresCarrierBudget(4, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OresCarrierBudget(4, 1, 2, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OresCarrierBudget(4, 1, 0, 1, 1));
    }
}
