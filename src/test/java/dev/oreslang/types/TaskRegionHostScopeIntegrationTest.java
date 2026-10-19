package dev.oreslang.types;

import dev.oreslang.runtime.AsyncRuntime;
import dev.oreslang.runtime.OresTaskScope;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Host TaskScope and compiler loan verifier integration; guest syntax stays gated. */
final class TaskRegionHostScopeIntegrationTest {
    @Test void twoHostTasksFinishBeforeReleasingStaticLoans() throws Exception {
        var ledger = new TaskAccessVerifier();
        var column = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 198, 1, true))
                .field("position", true);
        var leftClaim = new TaskAccessVerifier.Claim(
                column.range(0, 256, true), TaskAccessVerifier.Mode.WRITE);
        var rightClaim = new TaskAccessVerifier.Claim(
                column.range(256, 512, true), TaskAccessVerifier.Mode.WRITE);
        var leftTicket = ledger.admit(List.of(leftClaim));
        var rightTicket = ledger.admit(List.of(rightClaim));
        int[] values = new int[512];
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            var left = scope.submit(() -> {
                for (int i = 0; i < 256; ++i) values[i] = 10;
                entered.countDown();
                finish.await();
                return 256;
            });
            var right = scope.submit(() -> {
                for (int i = 256; i < 512; ++i) values[i] = 20;
                entered.countDown();
                finish.await();
                return 256;
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(2, ledger.activeChildren());
                assertFalse(ledger.parentMayAccess(
                        new TaskAccessVerifier.Claim(column, TaskAccessVerifier.Mode.WRITE)));
                assertThrows(IllegalStateException.class, ledger::finishScope);
            } finally {
                finish.countDown();
            }
            assertNull(AsyncRuntime.await(left.getDone()));
            ledger.quiescent(leftTicket);
            assertNull(AsyncRuntime.await(right.getDone()));
            ledger.quiescent(rightTicket);
            assertEquals(0, ledger.activeChildren());
            ledger.finishScope();
            for (int i = 0; i < values.length; ++i)
                assertEquals(i < 256 ? 10 : 20, values[i]);
        }
    }
}
