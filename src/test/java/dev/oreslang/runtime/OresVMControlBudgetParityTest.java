package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Mainline CONTROL bootstrap regression for the two-worker default.
 *
 * <p>No global System properties are changed: these tests can run alongside
 * other suites safely. This counts only VM-owned CONTROL carriers, not all
 * JVM/OS threads, lazy actor pthreads, or tenant security boundaries.</p>
 */
@Timeout(25)
final class OresVMControlBudgetParityTest {

    @Test
    void defaultStartsExactlyTwoControlWorkersBeforeAnyRootTurn()
            throws Exception {
        assertEquals(2, OresVM.configuredControlParallelism(null),
                "missing override must resolve to two CONTROL workers");
        try (OresVM first = OresVM.createConfigured(Runnable::run, null, null);
                OresVM sibling = OresVM.createConfigured(Runnable::run, null, null)) {
            assertTrue(first.started());
            assertTrue(sibling.started());
            assertEquals(2, first.controlCarrierCount(),
                    "default is two CONTROL workers, regardless of CPU count");
            assertEquals(2, sibling.controlCarrierCount());
            assertEquals(2, sibling.rootScheduler().parallelism());

            first.close();

            AtomicInteger delivered = new AtomicInteger();
            sibling.executeControl(delivered::incrementAndGet)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1, delivered.get(),
                    "closing a sibling VM must not retire live CONTROL work");
            assertEquals(2, sibling.controlCarrierCount());
        }
    }

    @Test
    void explicitControlConfigurationIsBoundToEachVmAtBootstrap()
            throws Exception {
        assertEquals(1, OresVM.configuredControlParallelism("1"));
        assertEquals(3, OresVM.configuredControlParallelism("3"));
        try (OresVM first = OresVM.createConfigured(Runnable::run, null, "1");
                OresVM sibling = OresVM.createConfigured(Runnable::run, null, "3")) {
            assertEquals(1, first.controlCarrierCount());
            assertEquals(3, sibling.controlCarrierCount());
            first.close();
            assertEquals(3, sibling.controlCarrierCount(),
                    "closing one VM must not retire sibling CONTROL carriers");
            assertEquals(Integer.valueOf(43), sibling.rootScheduler()
                    .startSync(() -> 43).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidOverridesFailClosedWithoutStartingAnyControlThreads() {
        for (String bad : new String[] {
                "", " ", "abc", "1.0", "0", "-1", "129",
                "2147483648", "0x2"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresVM.configuredControlParallelism(bad),
                    () -> "invalid CONTROL override unexpectedly accepted: " + bad);
        }
        assertEquals(128, OresVM.configuredControlParallelism("128"));
        // The production VM factory must reject the same malformed inputs
        // before it allocates any CONTROL or physical STOP executors.
        for (String bad : new String[] { "0", "-1", "129", "abc" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresVM.createConfigured(Runnable::run, null, bad),
                    () -> "production CONTROL factory accepted: " + bad);
        }
    }
}
