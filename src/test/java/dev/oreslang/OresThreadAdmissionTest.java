package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.AsyncRuntime;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TruffleLanguage.isThreadAccessAllowed(target, singleThreaded) can be called
 * while CURRENT thread != target. Authorize the exact target identity.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class OresThreadAdmissionTest {

    @Test
    void requestedSchedulerCarrierIsAllowedFromHostButArbitraryThreadIsNot()
            throws Exception {
        OresLanguage language = new OresLanguage();
        Thread unrelated = Thread.ofPlatform().unstarted(() -> {});
        AtomicReference<Thread> carrier = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);

        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> task = scheduler.start(resume -> {
                carrier.set(Thread.currentThread());
                entered.countDown();
                assertTrue(language.isThreadAccessAllowed(Thread.currentThread(), false));
                assertFalse(language.isThreadAccessAllowed(unrelated, false),
                        "caller being trusted must not admit an unrelated target");
                assertTrue(leave.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(null);
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                // This call executes on main, querying a DIFFERENT thread.
                assertTrue(language.isThreadAccessAllowed(carrier.get(), false));
                assertFalse(language.isThreadAccessAllowed(unrelated, false));
            } finally {
                leave.countDown();
            }
            task.get(5, TimeUnit.SECONDS);
        }
        assertFalse(language.isThreadAccessAllowed(carrier.get(), false),
                "closed scheduler's physical worker must lose admission");
    }

    @Test
    void requestedAsyncCarrierIsAllowedFromHostAndRevokedOnExit()
            throws Exception {
        OresLanguage language = new OresLanguage();
        AtomicReference<Thread> carrier = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresFuture<Void> task = runtime.submit(() -> {
                carrier.set(Thread.currentThread());
                entered.countDown();
                assertTrue(leave.await(5, TimeUnit.SECONDS));
                return null;
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(language.isThreadAccessAllowed(carrier.get(), false));
            } finally {
                leave.countDown();
            }
            task.get(5, TimeUnit.SECONDS);
        }
        assertFalse(language.isThreadAccessAllowed(carrier.get(), false),
                "completed async producer must not retain thread authority");
    }

    @Test
    void requestedActorCarrierIsAllowedFromHostAndRevokedAfterTurn()
            throws Exception {
        OresLanguage language = new OresLanguage();
        AtomicReference<Thread> carrier = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                carrier.set(Thread.currentThread());
                entered.countDown();
                assertTrue(leave.await(5, TimeUnit.SECONDS));
                context.self().stop();
            });
            try {
                actor.send("run");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(language.isThreadAccessAllowed(carrier.get(), false));
            } finally {
                leave.countDown();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
        }
        assertFalse(language.isThreadAccessAllowed(carrier.get(), false),
                "completed actor turn must lose carrier identity");
    }
}
