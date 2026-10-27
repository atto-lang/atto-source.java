package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class GeneratorRuntimeTest {
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
    void producerSubmissionRejectionIsStickyAndNeverRunsGuestBody() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            java.util.concurrent.atomic.AtomicInteger guestEffects =
                    new java.util.concurrent.atomic.AtomicInteger();
            AsyncRuntime runtime = new AsyncRuntime();
            var generator = GeneratorRuntime.generator(runtime, emitter -> {
                guestEffects.incrementAndGet();
                emitter.emit(1);
            });
            runtime.close(); // Submission is rejected synchronously.
            try (generator) {
                for (int i = 0; i < 3; i++) {
                    assertThrows(java.util.concurrent.RejectedExecutionException.class,
                            generator::nextStep);
                }
                assertEquals(0, guestEffects.get());
            }
        });
    }

    @Test
    void asyncPullOnClosedSchedulerReturnsFailedOresFuture() throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> emitter.emit(1));
        runtime.close();
        try (generator) {
            OresFuture<GeneratorRuntime.Step<Integer>> pull = generator.nextStep();
            assertEquals(OresFuture.class, pull.getClass());
            java.util.concurrent.ExecutionException rejected = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> pull.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                    rejected.getCause());
        }
    }

    @Test
    void terminalPullRemainsCompletedAfterSchedulerShutdown() throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        try (var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> emitter.emit(42))) {
            assertEquals(42, generator.nextStep().get(2, TimeUnit.SECONDS).value());
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
            runtime.close();
            for (int i = 0; i < 3; i++) {
                OresFuture<GeneratorRuntime.Step<Integer>> terminal = generator.nextStep();
                assertEquals(OresFuture.class, terminal.getClass());
                assertTrue(terminal.get(2, TimeUnit.SECONDS).done());
            }
        } finally {
            runtime.close();
        }
    }

    @Test
    void asyncPullDuringPendingProducerExitReturnsPromptly() throws Exception {
        CountDownLatch leaving = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        var producerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        AsyncRuntime runtime = new AsyncRuntime(turn -> {
            try { turn.run(); }
            finally {
                // Pull tasks use the same context adapter. Only hold the
                // producer's physical exit, never the first pull's completion.
                if (Thread.currentThread() == producerThread.get()) {
                    leaving.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try { allowExit.await(); break; }
                        catch (InterruptedException expected) { interrupted = true; }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        });
        var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> {
            producerThread.set(Thread.currentThread());
            emitter.emit(7);
        });
        try {
            assertEquals(7, generator.nextStep().get(2, TimeUnit.SECONDS).value());
            java.util.concurrent.FutureTask<Void> closing = new java.util.concurrent.FutureTask<>(
                    () -> { generator.close(); return null; });
            Thread closer = Thread.ofVirtual().start(closing);
            try {
                assertTrue(leaving.await(2, TimeUnit.SECONDS));
                var pull = assertTimeoutPreemptively(java.time.Duration.ofMillis(500),
                        generator::nextStep);
                assertEquals(OresFuture.class, pull.getClass());
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> pull.get(80, TimeUnit.MILLISECONDS));
            } finally {
                allowExit.countDown();
            }
            closing.get(2, TimeUnit.SECONDS);
            closer.join();
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
        } finally {
            // Release before closing even if the first pull/assertion fails.
            allowExit.countDown();
            generator.close();
            runtime.close();
        }
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
    void concurrentSyncPullIsRejectedInsteadOfQueueingAnotherConsumer() throws Exception {
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch releaseProducer = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.<Integer>generator(runtime, emitter -> {
                 producerStarted.countDown();
                 releaseProducer.await();
                 emitter.emit(42);
             })) {
            java.util.concurrent.FutureTask<GeneratorRuntime.Step<Integer>> first =
                    new java.util.concurrent.FutureTask<>(generator::nextStep);
            Thread consumer = Thread.ofVirtual().start(first);
            try {
                assertTrue(producerStarted.await(2, TimeUnit.SECONDS));
                IllegalStateException concurrent =
                        assertThrows(IllegalStateException.class, generator::nextStep);
                assertTrue(concurrent.getMessage().contains("concurrent Iterator.next()"));

                releaseProducer.countDown();
                assertEquals(42, first.get(2, TimeUnit.SECONDS).value());
                assertTrue(generator.nextStep().done());
            } finally {
                releaseProducer.countDown();
                consumer.join();
            }
        }
    }


    @Test
    void cancelledAsyncPullBeforeEngineAdmissionReleasesGateImmediately() throws Exception {
        CountDownLatch firstTurnEntered = new CountDownLatch(1);
        CountDownLatch releaseCancelledCarrier = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean blockFirstTurn =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        AsyncRuntime runtime = new AsyncRuntime(turn -> {
            if (blockFirstTurn.compareAndSet(true, false)) {
                firstTurnEntered.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        releaseCancelledCarrier.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            turn.run();
        });
        var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> emitter.emit(42));
        try {
            OresFuture<GeneratorRuntime.Step<Integer>> cancelled = generator.nextStep();
            assertTrue(firstTurnEntered.await(2, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel(true));

            // The cancelled task has not entered engine.next(), so it owns no
            // generator activation state. A replacement pull may proceed even
            // while that cancelled carrier is still leaving TurnExecutor.
            OresFuture<GeneratorRuntime.Step<Integer>> replacement = generator.nextStep();
            assertEquals(42, replacement.get(2, TimeUnit.SECONDS).value());
        } finally {
            releaseCancelledCarrier.countDown();
            generator.close();
            runtime.close();
        }
    }


    @Test
    void cancelledAsyncPullKeepsSingleFlightGateUntilEnginePullUnwinds() throws Exception {
        CountDownLatch producerLeaving = new CountDownLatch(1);
        CountDownLatch allowProducerExit = new CountDownLatch(1);
        var producerThread = new java.util.concurrent.atomic.AtomicReference<Thread>();

        AsyncRuntime runtime = new AsyncRuntime(turn -> {
            try {
                turn.run();
            } finally {
                if (Thread.currentThread() == producerThread.get()) {
                    producerLeaving.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try {
                            allowProducerExit.await();
                            break;
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        });
        var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter ->
                producerThread.set(Thread.currentThread()));
        try {
            OresFuture<GeneratorRuntime.Step<Integer>> first = generator.nextStep();
            assertTrue(producerLeaving.await(2, TimeUnit.SECONDS));
            assertTrue(first.cancel(true));

            OresFuture<GeneratorRuntime.Step<Integer>> overlapping = generator.nextStep();
            java.util.concurrent.ExecutionException rejected = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> overlapping.get(2, TimeUnit.SECONDS));
            IllegalStateException concurrent =
                    assertInstanceOf(IllegalStateException.class, rejected.getCause());
            assertTrue(concurrent.getMessage().contains("concurrent AsyncIterator.next()"));
        } finally {
            allowProducerExit.countDown();
            generator.close();
            runtime.close();
        }
    }


    @Test
    void asyncCloseIsMemoizedAndWaitsOffCallerForCleanup() throws Exception {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> {
                 try {
                     emitter.emit(1);
                 } finally {
                     cleanupStarted.countDown();
                     boolean interrupted = false;
                     for (;;) {
                         try { releaseCleanup.await(); break; }
                         catch (InterruptedException ignored) { interrupted = true; }
                     }
                     if (interrupted) Thread.currentThread().interrupt();
                 }
             })) {
            assertEquals(1, generator.nextStep().get(2, TimeUnit.SECONDS).value());

            OresFuture<Void> firstClose = generator.closeAsync();
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS));
            OresFuture<Void> repeatedClose = generator.closeAsync();
            assertSame(firstClose, repeatedClose);
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> firstClose.get(100, TimeUnit.MILLISECONDS));

            releaseCleanup.countDown();
            assertNull(firstClose.get(2, TimeUnit.SECONDS));
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
        } finally {
            releaseCleanup.countDown();
        }
    }


    @Test
    void concurrentAsyncPullIsRejectedBeforeAnotherPullTaskIsAdmitted() throws Exception {
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch releaseProducer = new CountDownLatch(1);
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> {
                 producerStarted.countDown();
                 releaseProducer.await();
                 emitter.emit(42);
             })) {
            OresFuture<GeneratorRuntime.Step<Integer>> first = generator.nextStep();
            assertTrue(producerStarted.await(2, TimeUnit.SECONDS));

            OresFuture<GeneratorRuntime.Step<Integer>> overlapping = generator.nextStep();
            java.util.concurrent.ExecutionException rejected = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> overlapping.get(2, TimeUnit.SECONDS));
            IllegalStateException concurrent =
                    assertInstanceOf(IllegalStateException.class, rejected.getCause());
            assertTrue(concurrent.getMessage().contains("concurrent AsyncIterator.next()"));

            releaseProducer.countDown();
            assertEquals(42, first.get(2, TimeUnit.SECONDS).value());
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
        } finally {
            releaseProducer.countDown();
        }
    }


    @Test
    void syncClosePreservesCleanupFailureAfterPhysicalExit() {
        AsyncRuntime runtime = new AsyncRuntime();
        var generator = GeneratorRuntime.<Integer>generator(runtime, emitter -> {
            try {
                emitter.emit(1);
            } finally {
                throw new IllegalStateException("generator cleanup failed");
            }
        });
        try {
            assertEquals(1, generator.nextStep().value());
            IllegalStateException first =
                    assertThrows(IllegalStateException.class, generator::close);
            assertEquals("generator cleanup failed", first.getMessage());

            IllegalStateException repeated =
                    assertThrows(IllegalStateException.class, generator::close);
            assertEquals("generator cleanup failed", repeated.getMessage());
            assertTrue(generator.nextStep().done());
        } finally {
            runtime.close();
        }
    }

    @Test
    void asyncClosePreservesCleanupFailureAfterPhysicalExit() throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        var generator = GeneratorRuntime.<Integer>asyncGenerator(runtime, emitter -> {
            try {
                emitter.emit(1);
            } finally {
                throw new IllegalStateException("async generator cleanup failed");
            }
        });
        try {
            assertEquals(1, generator.nextStep().get(2, TimeUnit.SECONDS).value());
            IllegalStateException first =
                    assertThrows(IllegalStateException.class, generator::close);
            assertEquals("async generator cleanup failed", first.getMessage());

            IllegalStateException repeated =
                    assertThrows(IllegalStateException.class, generator::close);
            assertEquals("async generator cleanup failed", repeated.getMessage());
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
        } finally {
            runtime.close();
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

    @Test
    void asyncCloseFutureWaitsForPhysicalProducerExit() throws Exception {
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
        }); var generator = GeneratorRuntime.asyncGenerator(runtime, emitter -> emitter.emit(1))) {
            assertEquals(1, generator.nextStep().get(2, TimeUnit.SECONDS).value());
            OresFuture<Void> closing = generator.closeAsync();
            try {
                assertTrue(leaving.await(2, TimeUnit.SECONDS));
                assertFalse(closing.isDone(),
                        "AsyncIterator.close completed before physical producer exit");
            } finally {
                allowExit.countDown();
            }
            closing.get(2, TimeUnit.SECONDS);
            assertTrue(generator.nextStep().get(2, TimeUnit.SECONDS).done());
        }
    }

    @Test
    void syncGeneratorReturnsOneStableIteratorView() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.generator(runtime, emitter -> {
                 emitter.emit(1);
                 emitter.emit(2);
             })) {
            var first = generator.iterator();
            var second = generator.iterator();
            assertSame(first, second);
            assertEquals(1, first.next());
            assertEquals(2, second.next());
            assertFalse(first.hasNext());
        }
    }

    @Test
    void closeInvalidatesPrefetchedSyncYield() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             var generator = GeneratorRuntime.generator(runtime, emitter -> {
                 emitter.emit(1);
                 emitter.emit(2);
             })) {
            var iterator = generator.iterator();
            assertTrue(iterator.hasNext());
            generator.close();
            assertFalse(iterator.hasNext());
            assertThrows(java.util.NoSuchElementException.class, iterator::next);
        }
    }
}
