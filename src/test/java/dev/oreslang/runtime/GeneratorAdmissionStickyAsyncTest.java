package dev.oreslang.runtime;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A rejected producer guest turn must never become successful terminal DONE. */
final class GeneratorAdmissionStickyAsyncTest {
    @Test
    void repeatedAsyncPullsPreserveRejectedProducerTurnAsFailedFutures() {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            AtomicInteger effects = new AtomicInteger();
            try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
                     throw new SecurityException("guest turn denied");
                 });
                 var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emit -> {
                     effects.incrementAndGet();
                     emit.emit(99);
                 })) {
                for (int attempt = 0; attempt < 3; attempt++) {
                    OresFuture<GeneratorRuntime.Step<Integer>> result = generator.nextStep();
                    ExecutionException rejected = assertThrows(
                            ExecutionException.class,
                            () -> result.get(2, TimeUnit.SECONDS));
                    SecurityException cause = assertInstanceOf(
                            SecurityException.class, rejected.getCause());
                    assertEquals("guest turn denied", cause.getMessage());
                }
                assertEquals(0, effects.get());
            }
        });
    }
}
