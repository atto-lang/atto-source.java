package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class GeneratorRuntimeTest {
    @Test
    void asyncPullsUseOresFutureIncludingTerminalPulls() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.asyncGenerator(runtime, emitter -> emitter.emit(42))) {
            var first = generator.nextStep();
            assertEquals(OresFuture.class, first.getClass());
            assertEquals(42, first.get(2, TimeUnit.SECONDS).value());
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
            generator.close();
            var terminal = generator.nextStep();
            assertEquals(OresFuture.class, terminal.getClass());
            assertTrue(terminal.get(2, TimeUnit.SECONDS).done());
        }
    }

    @Test
    void closeUnblocksAnInFlightPullAndInterruptsProducer() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.asyncGenerator(runtime, emitter -> {
                 started.countDown();
                 try {
                     new CountDownLatch(1).await();
                 } catch (InterruptedException expected) {
                     interrupted.countDown();
                     throw expected;
                 }
             })) {
            var pull = generator.nextStep();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            generator.close();
            assertTrue(pull.get(2, TimeUnit.SECONDS).done());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeAtYieldDoesNotResumeGuestBody() throws Exception {
        CountDownLatch resumed = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.asyncGenerator(runtime, emitter -> {
                 emitter.emit(1);
                 resumed.countDown();
             })) {
            assertEquals(1, generator.nextStep().get(2, TimeUnit.SECONDS).value());
            generator.close();
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
            assertFalse(resumed.await(100, TimeUnit.MILLISECONDS));
        }
    }
}
