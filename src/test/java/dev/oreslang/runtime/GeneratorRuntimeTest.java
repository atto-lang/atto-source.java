package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class GeneratorRuntimeTest {
    @Test
    void structuredGeneratorScopeWaitsForPhysicalProducerExit() throws Exception {
        CountDownLatch leavingGuestTurn = new CountDownLatch(1);
        CountDownLatch allowPhysicalExit = new CountDownLatch(1);
        TaskScope scope = new TaskScope();

        try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
            try {
                turn.run();
            } finally {
                leavingGuestTurn.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        allowPhysicalExit.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        });
             var generator = GeneratorRuntime.generator(
                     runtime,
                     (emitter, registration) -> emitter.emit(1),
                     scope,
                     null)) {
            assertEquals(1, generator.nextStep().value());

            OresFuture<Void> drained = scope.close();
            assertTrue(leavingGuestTurn.await(2, TimeUnit.SECONDS));
            assertFalse(
                    drained.isDone(),
                    "terminal producer Future must not forge physical quiescence");

            allowPhysicalExit.countDown();
            drained.get(2, TimeUnit.SECONDS);
            assertTrue(generator.nextStep().done());
        }
    }

    @Test
    void explicitStructuredGeneratorCloseIsSuccessfulScopeRetirement()
            throws Exception {
        TaskScope scope = new TaskScope();
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.generator(
                     runtime,
                     (emitter, registration) -> emitter.emit(1),
                     scope,
                     null)) {
            assertEquals(1, generator.nextStep().value());
            generator.close();

            assertTrue(scope.close());
            assertNull(scope.failure(),
                    "normal iterator close must not become a scoped child failure");
            assertTrue(generator.nextStep().done());
        }
    }

    @Test
    void structuredGeneratorCancellationInterruptsProducerAndDrainsAfterExit()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger cleanup =
                new java.util.concurrent.atomic.AtomicInteger();
        TaskScope scope = new TaskScope();

        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.asyncGenerator(
                     runtime,
                     (emitter, registration) -> {
                         started.countDown();
                         try {
                             new CountDownLatch(1).await();
                         } catch (InterruptedException expected) {
                             interrupted.countDown();
                             throw expected;
                         } finally {
                             cleanup.incrementAndGet();
                         }
                     },
                     scope,
                     null)) {
            OresFuture<GeneratorRuntime.Step<Object>> pull = generator.nextStep();
            assertTrue(started.await(2, TimeUnit.SECONDS));

            OresFuture<Void> drained = scope.cancelAndDrain();
            assertTrue(pull.get(2, TimeUnit.SECONDS).done());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> drained.get(2, TimeUnit.SECONDS));
            assertEquals(1, cleanup.get(), "generator cleanup must run exactly once");
        }
    }

    @Test
    void closeAtYieldWaitsForTheProducerToLeaveItsGuestTurn() throws Exception {
        CountDownLatch leaving = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
            try { turn.run(); }
            finally {
                leaving.countDown();
                boolean interrupted = false;
                for (;;) {
                    try { allowExit.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }); var generator = GeneratorRuntime.generator(runtime, emitter -> emitter.emit(1))) {
            assertEquals(1, generator.nextStep().value());
            java.util.concurrent.FutureTask<Void> close =
                    new java.util.concurrent.FutureTask<>(() -> { generator.close(); return null; });
            Thread closer = Thread.ofVirtual().start(close);
            try {
                assertTrue(leaving.await(2, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> close.get(100, TimeUnit.MILLISECONDS));
            } finally { allowExit.countDown(); }
            close.get(2, TimeUnit.SECONDS);
            closer.join();
            assertTrue(generator.nextStep().done());
        }
    }

    @Test
    void terminalPullWaitsForTheProducerToLeaveItsGuestTurn() throws Exception {
        CountDownLatch leaving = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
            turn.run();
            leaving.countDown();
            try { allowExit.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }); var generator = GeneratorRuntime.generator(runtime, emitter -> {})) {
            java.util.concurrent.FutureTask<GeneratorRuntime.Step<Object>> pull =
                    new java.util.concurrent.FutureTask<>(generator::nextStep);
            Thread consumer = Thread.ofVirtual().start(pull);
            try {
                assertTrue(leaving.await(2, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> pull.get(100, TimeUnit.MILLISECONDS));
            } finally { allowExit.countDown(); }
            assertTrue(pull.get(2, TimeUnit.SECONDS).done());
            consumer.join();
        }
    }
    @Test
    void rejectedBackingGuestTurnFailsThePullInsteadOfParkingForever() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            try (AsyncRuntime runtime = new AsyncRuntime(turn -> {
                throw new SecurityException("generator guest turn denied");
            }); var generator = GeneratorRuntime.generator(runtime, emitter -> emitter.emit(1))) {
                SecurityException denied = assertThrows(SecurityException.class, generator::nextStep);
                assertEquals("generator guest turn denied", denied.getMessage());
            }
        });
    }
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
