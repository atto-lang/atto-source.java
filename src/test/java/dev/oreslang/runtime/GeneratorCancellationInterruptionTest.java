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
            RuntimeException original = assertInstanceOf(RuntimeException.class, first.getCause());
            InterruptedException interrupted =
                    assertInstanceOf(InterruptedException.class, original.getCause());
            assertEquals("producer interruption without close", interrupted.getMessage());
            ExecutionException second = assertThrows(ExecutionException.class,
                    () -> generator.nextStep().get(3, TimeUnit.SECONDS));
            RuntimeException repeated = assertInstanceOf(RuntimeException.class, second.getCause());
            assertSame(interrupted, repeated.getCause(),
                    "the original terminal interruption must remain sticky");
        }
    }
}
