package dev.oreslang;

import dev.oreslang.runtime.GuestCallDepthExceededException;
import dev.oreslang.runtime.GuestCallDepthGuard;
import dev.oreslang.runtime.TrapFailurePolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class GuestCallDepthGuardTest {
    @Test
    void rejectsOneCallBeforeExceedingTheBoundAndRestoresTheCarrier() {
        int reserved = 0;
        try {
            for (int i = 1; i <= GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS; i++) {
                assertEquals(i, GuestCallDepthGuard.enter());
                reserved++;
            }
            GuestCallDepthExceededException exhausted = assertThrows(
                    GuestCallDepthExceededException.class, GuestCallDepthGuard::enter);
            assertTrue(exhausted.getMessage().contains("max 64"));
            assertEquals(0, exhausted.getStackTrace().length);
        } finally {
            while (reserved > 0) GuestCallDepthGuard.leave(reserved--);
        }
        assertEquals(1, GuestCallDepthGuard.enter());
        GuestCallDepthGuard.leave(1);
    }

    @Test
    void independentCarriersHaveIndependentBudgets() throws Exception {
        int entry = GuestCallDepthGuard.enter();
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                int other = pool.submit(() -> {
                    int depth = GuestCallDepthGuard.enter();
                    try {
                        return depth;
                    } finally {
                        GuestCallDepthGuard.leave(depth);
                    }
                }).get(3, TimeUnit.SECONDS);
                assertEquals(1, other);
                assertEquals(2, GuestCallDepthGuard.enter());
                GuestCallDepthGuard.leave(2);
            } finally {
                GuestCallDepthGuard.leave(entry);
            }
        }
        assertEquals(1, GuestCallDepthGuard.enter());
        GuestCallDepthGuard.leave(1);
    }

    @Test
    void exhaustedCallBudgetCannotBeConsumedByGuestTrapOrCatch() {
        var exhausted = new GuestCallDepthExceededException(64);
        assertFalse(TrapFailurePolicy.isTrappable(exhausted, IllegalArgumentException.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(exhausted, IllegalArgumentException.class));
        assertFalse(TrapFailurePolicy.isGuestCatchable(
                new IllegalStateException("wrapper", exhausted),
                IllegalArgumentException.class));
    }

    @Test
    void failedNestedInvocationDoesNotPoisonTheNextTurn() {
        int entry = GuestCallDepthGuard.enter();
        try {
            throw new IllegalStateException("guest failure");
        } catch (IllegalStateException expected) {
            assertEquals("guest failure", expected.getMessage());
        } finally {
            GuestCallDepthGuard.leave(entry);
        }
        assertEquals(1, GuestCallDepthGuard.enter());
        GuestCallDepthGuard.leave(1);
    }
}
