package dev.oreslang.runtime;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Terminal producer failure is sticky until explicit iterator close. */
final class GeneratorTerminalFailureStickyTest {
    @Test
    void synchronousProducerFailureCannotBeSilentlyRecastAsNormalDone() {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            AtomicInteger effects = new AtomicInteger();
            try (AsyncRuntime runtime = new AsyncRuntime();
                 var generator = GeneratorRuntime.generator(runtime, emit -> {
                     effects.incrementAndGet();
                     emit.emit(5);
                     throw new IllegalStateException("producer failed");
                 })) {
                assertEquals(5, generator.nextStep().value());
                for (int i = 0; i < 3; i++) {
                    IllegalStateException failure = assertThrows(
                            IllegalStateException.class, generator::nextStep);
                    assertEquals("producer failed", failure.getMessage());
                }
                assertEquals(1, effects.get());
                generator.close();
                assertTrue(generator.nextStep().done());
            }
        });
    }

    @Test
    void asyncProducerFailureRemainsFailedFutureUntilExplicitClose() {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            AtomicInteger effects = new AtomicInteger();
            try (AsyncRuntime runtime = new AsyncRuntime();
                 var generator = GeneratorRuntime.asyncGenerator(runtime, emit -> {
                     effects.incrementAndGet();
                     emit.emit(9);
                     throw new IllegalArgumentException("producer rejected");
                 })) {
                assertEquals(9, generator.nextStep().get(2, TimeUnit.SECONDS).value());
                for (int i = 0; i < 3; i++) {
                    var pull = generator.nextStep();
                    ExecutionException failure = assertThrows(ExecutionException.class,
                            () -> pull.get(2, TimeUnit.SECONDS));
                    IllegalArgumentException original = assertInstanceOf(
                            IllegalArgumentException.class, failure.getCause());
                    assertEquals("producer rejected", original.getMessage());
                }
                assertEquals(1, effects.get());
                generator.close();
                assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
            }
        });
    }
}
