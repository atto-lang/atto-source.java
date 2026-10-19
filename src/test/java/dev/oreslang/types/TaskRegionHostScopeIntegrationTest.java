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
    private static final ThreadSafetyTraits.NominalProof CORE =
            new ThreadSafetyTraits.NominalProof() {
                @Override
                public boolean has(
                        Types.Named type,
                        ThreadSafetyTraits.Trait trait) {
                    return false;
                }
            };

    private static TaskAccessVerifier.Admission admit(
            TaskAccessVerifier verifier,
            TaskAccessVerifier.Claim claim) {
        return verifier.admitScopedTyped(
                List.of(new TaskAccessVerifier.TypedClaim(
                        claim, Types.Primitive.INT)),
                CORE,
                place -> Types.Primitive.INT);
    }

    @Test void twoHostTasksFinishBeforeReleasingStaticLoans() throws Exception {
        var ledger = new TaskAccessVerifier();
        var column = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root(
                        "PROCESS", "process-main", 198, 1, true))
                .field("position", true);
        var leftClaim = new TaskAccessVerifier.Claim(
                column.range(0, 256, true), TaskAccessVerifier.Mode.WRITE);
        var rightClaim = new TaskAccessVerifier.Claim(
                column.range(256, 512, true), TaskAccessVerifier.Mode.WRITE);
        var leftAdmission = admit(ledger, leftClaim);
        var rightAdmission = admit(ledger, rightClaim);
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
                        new TaskAccessVerifier.Claim(
                                column, TaskAccessVerifier.Mode.WRITE)));
                assertThrows(IllegalStateException.class, ledger::finishScope);
            } finally {
                finish.countDown();
            }

            // getDone, not result settlement, is the host physical barrier.
            assertNull(AsyncRuntime.await(left.getDone()));
            leftAdmission.completion().releaseAfterPhysicalQuiescence();
            assertNull(AsyncRuntime.await(right.getDone()));
            rightAdmission.completion().releaseAfterPhysicalQuiescence();

            assertEquals(0, ledger.activeChildren());
            ledger.seal();
            ledger.finishScope();
            for (int i = 0; i < values.length; ++i) {
                assertEquals(i < 256 ? 10 : 20, values[i]);
            }
        }
    }
}
