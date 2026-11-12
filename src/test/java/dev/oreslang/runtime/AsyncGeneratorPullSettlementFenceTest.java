package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An engine step returning is not the same as its public OresFuture settling:
 * TurnExecutor may still be leaving its host/context turn.
 */
final class AsyncGeneratorPullSettlementFenceTest {
    @Test
    void engineExitWithoutFutureSettlementDoesNotAdmitAnotherPull() throws Exception {
        CountDownLatch consumerBodyReturned = new CountDownLatch(1);
        CountDownLatch allowConsumerTurnExit = new CountDownLatch(1);
        AtomicBoolean firstTurn = new AtomicBoolean(true);
        AtomicInteger producerActivations = new AtomicInteger();

        AsyncRuntime runtime = new AsyncRuntime(turn -> {
            boolean holdConsumerExit = firstTurn.compareAndSet(true, false);
            turn.run();
            if (holdConsumerExit) {
                consumerBodyReturned.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        allowConsumerTurnExit.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        });
        var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emit -> {
            producerActivations.incrementAndGet();
            emit.emit(11);
            emit.emit(22);
        });

        try {
            OresFuture<GeneratorRuntime.Step<Integer>> first = generator.nextStep();
            assertTrue(consumerBodyReturned.await(2, TimeUnit.SECONDS),
                    "engine step never returned to the turn adapter");
            assertFalse(first.isDone(),
                    "Future must remain pending while turn/context exit is held");

            // Before the fix the engine's finally cleared the single-flight
            // flag here, admitting extra scheduler tasks while first was pending.
            for (int attempt = 0; attempt < 16; attempt++) {
                OresFuture<GeneratorRuntime.Step<Integer>> overlapping = generator.nextStep();
                ExecutionException rejected = assertThrows(ExecutionException.class,
                        () -> overlapping.get(2, TimeUnit.SECONDS));
                IllegalStateException concurrent = assertInstanceOf(
                        IllegalStateException.class, rejected.getCause());
                assertTrue(concurrent.getMessage().contains("concurrent AsyncIterator.next()"));
            }
            assertEquals(1, producerActivations.get(),
                    "overlapping pulls must not run or duplicate the producer");

            allowConsumerTurnExit.countDown();
            assertEquals(11, first.get(2, TimeUnit.SECONDS).value());
            assertEquals(22, generator.nextStep().get(2, TimeUnit.SECONDS).value());
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
            assertEquals(1, producerActivations.get());
        } finally {
            allowConsumerTurnExit.countDown();
            try {
                generator.close();
            } finally {
                runtime.close();
            }
        }
    }
}
