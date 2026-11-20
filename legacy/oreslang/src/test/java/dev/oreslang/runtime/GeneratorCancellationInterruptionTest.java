package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Expected close-induced producer interruption is not a cleanup failure.
 * These checks use the real AsyncRuntime and GeneratorRuntime, not a model.
 */
public class GeneratorCancellationInterruptionTest {
    @Test
    void asyncCloseInterruptsBlockedProducerWithoutFailingCleanupReceipt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> {
                 entered.countDown();
                 try {
                     new CountDownLatch(1).await();
                 } catch (InterruptedException expected) {
                     interrupted.countDown();
                     throw expected;
                 }
             })) {
            OresFuture<GeneratorRuntime.Step<Integer>> pull = generator.nextStep();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            OresFuture<Void> close = generator.closeAsync();
            assertSame(close, generator.closeAsync(), "close must be single-flight");
            assertNull(close.get(3, TimeUnit.SECONDS));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(pull.get(3, TimeUnit.SECONDS).done());
            assertTrue(generator.nextStep().get(3, TimeUnit.SECONDS).done());
        }
    }

    @Test
    void synchronousCloseDoesNotMistakeExpectedInterruptForCleanupFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.<Integer>generator(runtime, emitter -> {
                 entered.countDown();
                 try {
                     new CountDownLatch(1).await();
                 } catch (InterruptedException expected) {
                     interrupted.countDown();
                     throw expected;
                 }
             })) {
            FutureTask<GeneratorRuntime.Step<Integer>> pull =
                    new FutureTask<>(generator::nextStep);
            Thread consumer = Thread.ofVirtual().start(pull);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertDoesNotThrow(generator::close);
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(pull.get(3, TimeUnit.SECONDS).done());
            consumer.join(3_000);
            assertFalse(consumer.isAlive());
        }
    }

    @Test
    void unsolicitedInterruptedProducerRemainsStickyFailure() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> {
                 throw new InterruptedException("producer interruption without close");
             })) {
            ExecutionException first = assertThrows(ExecutionException.class,
                    () -> generator.nextStep().get(3, TimeUnit.SECONDS));
            Throwable originalCause = first.getCause();
            Throwable firstRoot = originalCause instanceof RuntimeException
                    && originalCause.getCause() instanceof InterruptedException
                    ? originalCause.getCause() : originalCause;
            InterruptedException interrupted = assertInstanceOf(InterruptedException.class, firstRoot);
            assertEquals("producer interruption without close", interrupted.getMessage());
            ExecutionException second = assertThrows(ExecutionException.class,
                    () -> generator.nextStep().get(3, TimeUnit.SECONDS));
            Throwable laterCause = second.getCause();
            Throwable laterRoot = laterCause instanceof RuntimeException
                    && laterCause.getCause() instanceof InterruptedException
                    ? laterCause.getCause() : laterCause;
            assertSame(interrupted, laterRoot,
                    "terminal pulls retain the same underlying producer failure");
        }
    }

    @Test
    void cancellingAdmittedAsyncPullRevokesProducerAndFencesPhysicalCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch allowCleanupExit = new CountDownLatch(1);
        AsyncRuntime runtime = new AsyncRuntime();
        var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } finally {
                cleanupStarted.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        allowCleanupExit.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        });
        try {
            OresFuture<GeneratorRuntime.Step<Integer>> pull = generator.nextStep();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(pull.cancel(true));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> pull.get(3, TimeUnit.SECONDS));
            assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS));

            OresFuture<Void> close = generator.closeAsync();
            assertSame(close, generator.closeAsync());
            assertFalse(close.isDone(),
                    "logical pull cancellation must not fake physical cleanup");
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> close.get(100, TimeUnit.MILLISECONDS));
            allowCleanupExit.countDown();
            assertNull(close.get(3, TimeUnit.SECONDS));
            assertNull(runtime.taskExitReceipt(pull).get(3, TimeUnit.SECONDS));
            assertTrue(generator.nextStep().get(3, TimeUnit.SECONDS).done(),
                    "cancelled admitted pull must not leave an orphan activation");
        } finally {
            allowCleanupExit.countDown();
            generator.close();
            runtime.close();
        }
    }

    @Test
    void lateCancellationAfterYieldButBeforeFutureSettlementRevokesProducer() throws Exception {
        CountDownLatch pullTurnReturned = new CountDownLatch(1);
        CountDownLatch releasePullTurn = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> pullCarrier =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean resumedGuestBody =
                new java.util.concurrent.atomic.AtomicBoolean();
        AsyncRuntime runtime = new AsyncRuntime(turn -> {
            Thread current = Thread.currentThread();
            pullCarrier.compareAndSet(null, current);
            try {
                turn.run();
            } finally {
                if (current == pullCarrier.get()) {
                    pullTurnReturned.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try {
                            releasePullTurn.await();
                            break;
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        });
        var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> {
            emitter.emit(1);
            resumedGuestBody.set(true);
        });
        try {
            OresFuture<GeneratorRuntime.Step<Integer>> pull = generator.nextStep();
            assertTrue(pullTurnReturned.await(3, TimeUnit.SECONDS),
                    "pull turn must finish engine.next before cancellation");
            assertFalse(pull.isDone(),
                    "backing turn is held after yield and before Future settlement");
            var beforeSettlement = generator.nextStep();
            ExecutionException premature = assertThrows(ExecutionException.class,
                    () -> beforeSettlement.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, premature.getCause(),
                    "a yielded value is not claimed until its Future settles");

            assertTrue(pull.cancel(true));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> pull.get(3, TimeUnit.SECONDS));
            var beforePhysicalExit = generator.nextStep();
            ExecutionException overlapping = assertThrows(ExecutionException.class,
                    () -> beforePhysicalExit.get(3, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, overlapping.getCause(),
                    "cancelling an admitted pull must retain its gate through physical exit");

            OresFuture<Void> close = generator.closeAsync();
            assertNull(close.get(3, TimeUnit.SECONDS));
            assertFalse(resumedGuestBody.get(),
                    "a cancelled, unobserved yield must not resume guest execution");
            releasePullTurn.countDown();
            assertNull(runtime.taskExitReceipt(pull).get(3, TimeUnit.SECONDS));
            assertTrue(generator.nextStep().get(3, TimeUnit.SECONDS).done());
        } finally {
            releasePullTurn.countDown();
            generator.close();
            runtime.close();
        }
    }
}
