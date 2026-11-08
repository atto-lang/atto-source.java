package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Mainline CONTROL bootstrap regression for the two-worker default.
 *
 * <p>This counts only VM-owned CONTROL carriers. It does not claim a
 * two-thread JVM/OS process, lazy actor pthreads, or an inter-tenant
 * security boundary. Physical actor carriers are tested separately.</p>
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
@Timeout(25)
final class OresVMControlBudgetParityTest {
    private static final String CONTROL = "ores.runtime.control.parallelism";

    @Test
    void defaultStartsExactlyTwoControlWorkersBeforeAnyRootTurn()
            throws Exception {
        String previous = System.getProperty(CONTROL);
        System.clearProperty(CONTROL);
        try {
            try (OresVM first = OresVM.create(Runnable::run);
                    OresVM sibling = OresVM.create(Runnable::run)) {
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
        } finally {
            restore(previous);
        }
    }

    @Test
    void explicitControlOverrideIsCapturedByEachVmAtBootstrap()
            throws Exception {
        String previous = System.getProperty(CONTROL);
        try {
            System.setProperty(CONTROL, "1");
            try (OresVM first = OresVM.create(Runnable::run)) {
                assertEquals(1, first.controlCarrierCount());
                System.setProperty(CONTROL, "3");
                try (OresVM sibling = OresVM.create(Runnable::run)) {
                    assertEquals(1, first.controlCarrierCount(),
                            "changing a process property must not mutate a live VM");
                    assertEquals(3, sibling.controlCarrierCount());
                    first.close();
                    assertEquals(3, sibling.controlCarrierCount());
                    assertEquals(43, sibling.rootScheduler()
                            .startSync(() -> 43).get(5, TimeUnit.SECONDS));
                }
            }
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) System.clearProperty(CONTROL);
        else System.setProperty(CONTROL, previous);
    }
}
