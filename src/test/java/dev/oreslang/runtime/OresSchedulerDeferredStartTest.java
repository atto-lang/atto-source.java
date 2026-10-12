package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source task registration must not imply physical CONTROL admission.
 * The host bridge owns exactly one later dispatch or abandonment.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class OresSchedulerDeferredStartTest {
    @Test
    void registeredSourceCannotEnterUntilHostReturnDispatch() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var bodies = new AtomicInteger();
            var deferred = scheduler.prepareDeferred(resume -> {
                bodies.incrementAndGet();
                return OresScheduler.done(41);
            });
            assertEquals(0, bodies.get(),
                    "preparing a guest source task must not start a carrier");
            assertFalse(deferred.future().isDone());
            deferred.dispatchAfterHostReturn();
            assertEquals(41, deferred.future().get(5, TimeUnit.SECONDS));
            assertEquals(1, bodies.get());
            assertThrows(IllegalStateException.class, deferred::dispatchAfterHostReturn);
        }
    }

    @Test
    void rejectedHostReturnNeverExecutesAnUnstartedGuestFrame() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var bodies = new AtomicInteger();
            var deferred = scheduler.prepareDeferred(resume -> {
                bodies.incrementAndGet();
                return OresScheduler.done(5);
            });
            deferred.abandonBeforeDispatch();
            assertThrows(CancellationException.class,
                    () -> deferred.future().get(5, TimeUnit.SECONDS));
            assertEquals(0, bodies.get());
            deferred.abandonBeforeDispatch();
            assertThrows(IllegalStateException.class, deferred::dispatchAfterHostReturn);
        }
    }

    @Test
    void externalCancellationBeforeHostReturnIsNeverResurrected() throws Exception {
        try (var scheduler = new OresScheduler(1)) {
            var bodies = new AtomicInteger();
            var deferred = scheduler.prepareDeferred(resume -> {
                bodies.incrementAndGet();
                return OresScheduler.done(7);
            });
            assertTrue(deferred.future().cancel(false));
            deferred.dispatchAfterHostReturn();
            assertEquals(0, bodies.get());
            assertThrows(CancellationException.class,
                    () -> deferred.future().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void schedulerShutdownBeforeHostReturnCannotCreateGuestWork() throws Exception {
        var scheduler = new OresScheduler(1);
        var bodies = new AtomicInteger();
        var deferred = scheduler.prepareDeferred(resume -> {
            bodies.incrementAndGet();
            return OresScheduler.done(19);
        });
        scheduler.close();
        deferred.dispatchAfterHostReturn();
        assertEquals(0, bodies.get());
        assertTrue(deferred.future().isDone());
    }

    @Test
    void multipleDeferredSourceStartsRemainIndependent() throws Exception {
        try (var scheduler = new OresScheduler(2)) {
            var started = new AtomicInteger();
            var first = scheduler.prepareDeferred(resume -> {
                started.incrementAndGet();
                return OresScheduler.done(1);
            });
            var second = scheduler.prepareDeferred(resume -> {
                started.incrementAndGet();
                return OresScheduler.done(2);
            });
            first.abandonBeforeDispatch();
            second.dispatchAfterHostReturn();
            assertEquals(2, second.future().get(5, TimeUnit.SECONDS));
            assertThrows(CancellationException.class,
                    () -> first.future().get(5, TimeUnit.SECONDS));
            assertEquals(1, started.get());
        }
    }
}
