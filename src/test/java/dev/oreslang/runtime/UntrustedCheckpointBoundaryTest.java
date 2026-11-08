package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Deterministic security and performance regressions for injected safepoints. */
final class UntrustedCheckpointBoundaryTest {
    @Test
    void wallDeadlineExpiresAtEqualityNotOneTickLater() {
        AtomicLong wall = new AtomicLong(100L);
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10L, 3L, 100L, 0L, wall::get, () -> 0L);
        quota.enter();
        quota.check();
        quota.leave();
        wall.set(109L);
        quota.enter();
        quota.check();
        quota.leave();
        assertEquals(1L, quota.remainingWallNanos());

        wall.set(110L);
        assertEquals(0L, quota.remainingWallNanos(),
                "admission and in-carrier checks must share the same exclusive deadline");
        quota.enter();
        try {
            var expired = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class, quota::check);
            assertEquals(ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                    expired.resource());
            var repeated = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class, quota::chargeSafepoint);
            assertEquals(ActorRuntime.UntrustedActorQuotaExceededException.Resource.WALL_TIME,
                    repeated.resource());
        } finally {
            quota.leave();
        }
    }

    @Test
    void cpuBudgetExpiresAtEqualityAcrossMigratedCarrierSegments() {
        AtomicLong wall = new AtomicLong();
        AtomicLong cpu = new AtomicLong(100L);
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10L, 3L, 0L, 0L, wall::get, cpu::get);
        quota.enter();
        cpu.set(106L);
        quota.check();
        quota.leave(); // Six CPU units from carrier A.
        cpu.set(1_000L); // Unrelated carrier: preserve debt, not absolute timestamp.
        quota.enter();
        try {
            cpu.set(1_003L);
            quota.check(); // Total 9.
            cpu.set(1_004L);
            var exceeded = assertThrows(
                    ActorRuntime.UntrustedActorQuotaExceededException.class, quota::check);
            assertEquals(ActorRuntime.UntrustedActorQuotaExceededException.Resource.CPU_TIME,
                    exceeded.resource());
        } finally {
            quota.leave();
        }
    }

    @Test
    void exhaustedFuelNeverRefillsAcrossResumesOrRepeatedFailures() {
        AtomicLong clock = new AtomicLong();
        ActorRuntime.UntrustedLogicalQuota quota = new ActorRuntime.UntrustedLogicalQuota(
                10_000L, 2L, 0L, 0L, clock::get, clock::get);
        for (int i = 0; i < 2; ++i) {
            quota.enter();
            quota.chargeSafepoint();
            quota.leave();
        }
        for (int i = 0; i < 32; ++i) {
            quota.enter();
            try {
                var exceeded = assertThrows(
                        ActorRuntime.UntrustedActorQuotaExceededException.class,
                        quota::chargeSafepoint);
                assertEquals(ActorRuntime.UntrustedActorQuotaExceededException.Resource.FUEL,
                        exceeded.resource());
            } finally {
                quota.leave();
            }
        }
    }

    @Test
    void frozenClockCannotEvadeReductionCapOrRearmBatchedChecks() {
        AtomicInteger reads = new AtomicInteger();
        SourceCooperationQuantum q = new SourceCooperationQuantum(() -> {
            reads.incrementAndGet();
            return 0L;
        });
        q.beginDispatch();
        assertEquals(2, reads.get());
        for (int n = 1; n <= 4096; ++n) {
            boolean due = q.completedBackedgeNeedsCheck();
            assertEquals(n >= SourceCooperationQuantum.MAX_BACKEDGES_PER_DISPATCH
                            || n % SourceCooperationQuantum.LOOP_CHECK_INTERVAL == 0,
                    due, "missed checkpoint at completed backedge " + n);
            if (due) {
                assertEquals(n >= SourceCooperationQuantum.MAX_BACKEDGES_PER_DISPATCH,
                        q.shouldCooperateAtCheck(),
                        "overdue no-yield region cannot reset reduction debt");
            }
        }
        // Two initial clock reads plus probes at 64,128,...,448.
        assertEquals(9, reads.get());
        assertEquals(4096L, q.completedBackedgesThisDispatch());
        q.beginDispatch();
        assertFalse(q.completedBackedgeNeedsCheck());
        assertEquals(1L, q.completedBackedgesThisDispatch());
    }

    @Test
    void softQuantumSamplesClockOnlyAtBatchedTrustedSafepoints() {
        AtomicLong nanos = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        SourceCooperationQuantum q = new SourceCooperationQuantum(() -> {
            reads.incrementAndGet();
            return nanos.get();
        });
        q.beginDispatch();
        for (int i = 0; i < 63; ++i) {
            assertFalse(q.completedBackedgeNeedsCheck());
        }
        nanos.set(SourceCooperationQuantum.SOFT_DISPATCH_QUANTUM_NANOS);
        assertEquals(2, reads.get(), "fast path must not read the clock");
        assertTrue(q.completedBackedgeNeedsCheck());
        assertTrue(q.shouldCooperateAtCheck());
        assertEquals(3, reads.get());
    }
}
