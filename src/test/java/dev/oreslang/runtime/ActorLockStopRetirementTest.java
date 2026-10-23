package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real actor TurnExecutor / STOP / physical-receipt lifecycle regressions. */
@Timeout(25)
final class ActorLockStopRetirementTest {
    @Test
    void directActorGuardIsRevokedAndDirtySharedPayloadPoisonedBeforeDone()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var mutex = OresMutex.shared(new int[] {1});
            AtomicReference<OresMutex.Guard<int[]>> held = new AtomicReference<>();
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                var guard = mutex.tryLock().orElseThrow();
                guard.value()[0] = 5;
                held.set(guard); // deliberately skip lexical release
                context.self().stop();
            });
            ref.send("hold");
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.getDone().isDone());
            assertNotNull(held.get());
            assertTrue(held.get().released(), "STOP published before actor permit retirement");
            assertTrue(mutex.isPoisoned(), "abandoned actor writer must fail closed");
            assertThrows(IllegalStateException.class, held.get()::value);
            assertThrows(OresMutex.PoisonedMutexException.class, mutex::lock);
            mutex.recover(value -> { value[0] = 0; return null; });
            var guard = mutex.tryLock().orElseThrow();
            assertEquals(0, guard.value()[0]);
            guard.release();
        }
    }

    @Test
    void blockedActorExitHookDoesNotHoldLifecycleMonitorOrPublishDone()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch unblock = new CountDownLatch(1);
            AtomicBoolean hookOwnsMonitor = new AtomicBoolean(true);
            AtomicBoolean hookRanAsActor = new AtomicBoolean(true);
            AtomicReference<Object> cellRef = new AtomicReference<>();
            runtime.setActorExitHook(domain -> {
                hookRanAsActor.set(ActorRuntime.inActorExecution());
                try {
                    Object cell = cellRef.get();
                    assertNotNull(cell);
                    Field lock = cell.getClass().getDeclaredField("lifecycleLock");
                    lock.setAccessible(true);
                    hookOwnsMonitor.set(Thread.holdsLock(lock.get(cell)));
                    entered.countDown();
                    if (!unblock.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("hook blocker exceeded");
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            var ref = runtime.<String>spawnShared(() -> (message, context) ->
                    context.self().stop());
            Field actors = ActorRuntime.class.getDeclaredField("actors");
            actors.setAccessible(true);
            cellRef.set(((Map<?, ?>) actors.get(runtime)).get(ref.id()));
            try {
                ref.send("stop");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(ref.getDone().isDone(),
                        "observer DONE preceded actor exit receipt");
                assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                        "terminal registry removed before cleanup completed");
                assertFalse(hookOwnsMonitor.get(),
                        "trusted exit hook called while holding lifecycleLock");
                assertFalse(hookRanAsActor.get(),
                        "exit hook inherited guest actor authority");
            } finally {
                unblock.countDown();
            }
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.getDone().isDone());
            assertTrue(ref.failure().isEmpty());
        }
    }

    @Test
    void exitHookFailureQuarantinesActorAndWithholdsSuccessfulDone() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            CountDownLatch entered = new CountDownLatch(1);
            runtime.setActorExitHook(domain -> {
                entered.countDown();
                throw new IllegalStateException("injected lock/arena cleanup failure");
            });
            var ref = runtime.<String>spawnShared(() -> (message, context) ->
                    context.self().stop());
            ref.send("stop");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (ref.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(ref.failure().isPresent(), "cleanup failure lost");
            assertFalse(ref.getDone().isDone(),
                    "unsafe actor teardown forged a DONE receipt");
            assertFalse(ref.awaitTermination(20, TimeUnit.MILLISECONDS),
                    "failed exit hook released quarantined actor");
        } finally {
            // Quarantined actors intentionally prevent clean runtime shutdown.
            // A failed cleanup receipt is not a successful join.
            assertThrows(IllegalStateException.class, runtime::close);
        }
    }
}
