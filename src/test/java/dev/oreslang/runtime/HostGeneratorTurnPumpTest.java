package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forced interleavings of physical turn exit, cancellation and host admission.
 * No sleep-based timing or arbitrary Truffle multi-thread admission override.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class HostGeneratorTurnPumpTest {

    @Test
    void rootSchedulerIdentityAloneDoesNotAuthorizeGuestEntry() {
        assertFalse(OresContext.generatorRootTurnAuthorized(false, false));
        assertFalse(OresContext.generatorRootTurnAuthorized(true, false),
                "bound scheduler without physical Truffle guest entry is not enough");
        assertFalse(OresContext.generatorRootTurnAuthorized(false, true),
                "guest entry without owning root scheduler is not enough");
        assertTrue(OresContext.generatorRootTurnAuthorized(true, true));
    }

    @Test
    void cannotEnterGuestFromForeignThreadEvenWithValidOwnerToken() throws Exception {
        Thread legitimateOwner = Thread.currentThread();
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> Thread.currentThread() == legitimateOwner);
        AtomicInteger starts = new AtomicInteger();
        OresFuture<Integer> pull = pump.request(pump.token(), () -> {
            starts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(7), OresFuture.completed(null));
        });
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<Throwable> denied = new AtomicReference<>();
        Thread foreign = Thread.ofPlatform().name("foreign-guest-entry").start(() -> {
            try {
                pump.dispatchOne();
            } catch (Throwable rejection) {
                denied.set(rejection);
            } finally {
                attempted.countDown();
            }
        });
        assertTrue(attempted.await(5, TimeUnit.SECONDS));
        foreign.join();
        assertInstanceOf(SecurityException.class, denied.get());
        assertEquals(0, starts.get(), "foreign thread may not start a guest producer");
        assertFalse(pull.isDone());
        assertTrue(pump.dispatchOne());
        assertEquals(7, pull.get(5, TimeUnit.SECONDS));
        assertEquals(1, starts.get());
        pump.close();
    }

    @Test
    void resultBeforePhysicalExitDoesNotAdmitAnotherTurn() throws Exception {
        AtomicInteger wakes = new AtomicInteger();
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true, wakes::incrementAndGet, 2);
        OresFuture<Integer> firstValue = new OresFuture<>();
        OresFuture<Void> firstPhysicalExit = new OresFuture<>();
        AtomicInteger secondStarts = new AtomicInteger();

        OresFuture<Integer> first = pump.request(pump.token(),
                () -> new HostGeneratorTurnPump.Turn<>(firstValue, firstPhysicalExit));
        OresFuture<Integer> second = pump.request(pump.token(), () -> {
            secondStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(22), OresFuture.completed(null));
        });

        AtomicReference<Boolean> publicationRetainedOwnership = new AtomicReference<>();
        first.whenCompleteRuntime((value, failure) ->
                publicationRetainedOwnership.set(!pump.dispatchOne()));
        assertTrue(pump.dispatchOne());
        firstValue.completeFromRuntime(11);
        assertFalse(first.isDone(),
                "public result cannot settle before the turn has physically left guest");
        assertFalse(pump.dispatchOne(), "a second producer must not overlap guest entry");
        assertEquals(0, secondStarts.get());
        assertThrows(IllegalStateException.class, pump::requireQuiescent);
        firstPhysicalExit.completeFromRuntime(null);
        assertEquals(11, first.get(5, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, publicationRetainedOwnership.get(),
                "the previous Future must publish before the next turn is eligible");
        assertTrue(wakes.get() >= 2, "owner must be notified of resumed capacity");
        assertTrue(pump.dispatchOne());
        assertEquals(22, second.get(5, TimeUnit.SECONDS));
        assertEquals(1, secondStarts.get());
        pump.close();
    }

    @Test
    void cancelledPullDoesNotFakePhysicalGuestExitOrAllowNewTurn() {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(UUID.randomUUID(), () -> true);
        OresFuture<Integer> producerValue = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        OresFuture<Integer> pull = pump.request(pump.token(),
                () -> new HostGeneratorTurnPump.Turn<>(producerValue, physicalExit));
        assertTrue(pump.dispatchOne());
        assertTrue(pull.cancel(false));
        assertTrue(producerValue.isCancelled(),
                "consumer cancellation must request producer cancellation");
        assertFalse(pump.isQuiescent(),
                "Future cancellation is not physical guest cleanup");
        pump.seal();
        assertThrows(IllegalStateException.class, pump::requireQuiescent);
        physicalExit.completeFromRuntime(null);
        assertDoesNotThrow(pump::requireQuiescent);
        assertThrows(RejectedExecutionException.class,
                () -> pump.request(pump.token(),
                        () -> new HostGeneratorTurnPump.Turn<>(
                                OresFuture.completed(1), OresFuture.completed(null))));
    }

    @Test
    void cancelledPhysicalExitReceiptCannotAuthorizeNextTurnOrRetirement() {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(UUID.randomUUID(), () -> true);
        OresFuture<Integer> producer = new OresFuture<>();
        OresFuture<Void> exit = new OresFuture<>();
        AtomicInteger secondStarts = new AtomicInteger();
        OresFuture<Integer> first = pump.request(pump.token(),
                () -> new HostGeneratorTurnPump.Turn<>(producer, exit));
        OresFuture<Integer> second = pump.request(pump.token(), () -> {
            secondStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(22), OresFuture.completed(null));
        });
        assertTrue(pump.dispatchOne());
        assertTrue(exit.cancel(false));
        assertTrue(producer.isCancelled(),
                "failed exit proof must request cancellation of the producer");
        assertTrue(second.isCancelled(), "a failed physical exit seals the pending queue");
        assertFalse(pump.dispatchOne());
        assertFalse(pump.isQuiescent());
        assertThrows(IllegalStateException.class, pump::close,
                "cancelled physical exit is not a DONE receipt");
        assertEquals(0, secondStarts.get());
        assertTrue(first.isDone());
    }

    @Test
    void exceptionalPhysicalExitReceiptCannotAuthorizeRetirement() {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(UUID.randomUUID(), () -> true);
        OresFuture<Integer> producer = OresFuture.completed(13);
        OresFuture<Void> exit = new OresFuture<>();
        OresFuture<Integer> pull = pump.request(pump.token(),
                () -> new HostGeneratorTurnPump.Turn<>(producer, exit));
        assertTrue(pump.dispatchOne());
        exit.failFromRuntime(new IllegalStateException("physical exit unknown"));
        assertTrue(pull.isDone());
        assertFalse(pump.isQuiescent(),
                "failure of DONE evidence must remain permanently quarantined");
        assertThrows(IllegalStateException.class, pump::close);
    }

    @Test
    void contextIncarnationAndBackpressureAreFailClosed() {
        AtomicInteger started = new AtomicInteger();
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true, () -> { }, 1);
        assertThrows(SecurityException.class,
                () -> pump.request(new HostGeneratorTurnPump.OwnerToken(
                        pump.token().contextId(), UUID.randomUUID()),
                        () -> {
                            started.incrementAndGet();
                            return new HostGeneratorTurnPump.Turn<>(
                                    OresFuture.completed(1), OresFuture.completed(null));
                        }));
        OresFuture<Integer> queued = pump.request(pump.token(), () -> {
            started.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(1), OresFuture.completed(null));
        });
        assertThrows(RejectedExecutionException.class,
                () -> pump.request(pump.token(), () -> {
                    started.incrementAndGet();
                    return new HostGeneratorTurnPump.Turn<>(
                            OresFuture.completed(2), OresFuture.completed(null));
                }));
        pump.seal();
        assertTrue(queued.isCancelled(), "sealed requests must be cancelled before dispatch");
        assertEquals(0, started.get());
        assertFalse(pump.dispatchOne());
        pump.close();
    }

    @Test
    void invalidExitBarrierAndFailedWakeDoNotCreateOrphanedProducer()
            throws Exception {
        HostGeneratorTurnPump invalid = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        OresFuture<Integer> pull = invalid.request(invalid.token(), () -> {
            OresFuture<Integer> shared = new OresFuture<>();
            return new HostGeneratorTurnPump.Turn<>(shared, shared);
        });
        assertTrue(invalid.dispatchOne());
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pull.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(invalid.isQuiescent(),
                "invalid starter exit receipt cannot certify physical quiescence");
        assertThrows(IllegalStateException.class, invalid::close,
                "invalid physical-exit receipt must permanently quarantine this incarnation");

        AtomicInteger producerStarts = new AtomicInteger();
        HostGeneratorTurnPump brokenNotifier = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true,
                () -> { throw new RejectedExecutionException("owner has retired"); }, 2);
        assertThrows(RejectedExecutionException.class, () ->
                brokenNotifier.request(brokenNotifier.token(), () -> {
                    producerStarts.incrementAndGet();
                    return new HostGeneratorTurnPump.Turn<>(
                            OresFuture.completed(5), OresFuture.completed(null));
                }));
        assertTrue(brokenNotifier.isQuiescent());
        assertEquals(0, producerStarts.get(),
                "failed owner wake may not leave dispatchable orphan work");
        assertThrows(RejectedExecutionException.class,
                () -> brokenNotifier.request(brokenNotifier.token(),
                        () -> new HostGeneratorTurnPump.Turn<>(
                                OresFuture.completed(6), OresFuture.completed(null))));
        brokenNotifier.close();
    }

    @Test
    void slowStarterCannotHoldCloseMonitorAndCancellationFencesItsExit() throws Exception {
        // Starter is a potentially slow user-supplied bridge callback. The
        // lifecycle owner must be able to seal and report incomplete cleanup
        // before the callback supplies its exit barrier.
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        OresFuture<Integer> guestResult = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        OresFuture<Integer> request = pump.request(pump.token(), () -> {
            starterEntered.countDown();
            try {
                if (!releaseStarter.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("starter did not receive release");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return new HostGeneratorTurnPump.Turn<>(guestResult, physicalExit);
        });
        Thread owner = Thread.ofPlatform().start(() -> {
            try { pump.dispatchOne(); }
            catch (Throwable failure) { ownerFailure.set(failure); }
        });
        try {
            assertTrue(starterEntered.await(5, TimeUnit.SECONDS));
            CountDownLatch closeFinished = new CountDownLatch(1);
            AtomicReference<Throwable> closeFailure = new AtomicReference<>();
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    pump.seal();
                    assertThrows(IllegalStateException.class, pump::requireQuiescent);
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                } finally {
                    closeFinished.countDown();
                }
            });
            assertTrue(closeFinished.await(3, TimeUnit.SECONDS),
                    "close blocked behind in-flight generator starter");
            closer.join(3000);
            assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
            assertTrue(request.isCancelled(),
                    "sealing a starting pull must cancel its host request");
            assertThrows(IllegalStateException.class, pump::requireQuiescent);
        } finally {
            releaseStarter.countDown();
            owner.join(5000);
        }
        assertFalse(owner.isAlive());
        assertNull(ownerFailure.get(), String.valueOf(ownerFailure.get()));
        assertTrue(guestResult.isCancelled(),
                "cancellation before starter return must reach its guest result");
        assertFalse(pump.isQuiescent(),
                "cancelled result cannot stand in for physical exit");
        physicalExit.completeFromRuntime(null);
        assertDoesNotThrow(pump::requireQuiescent);
    }

    @Test
    void reentrantStarterCanSealWithoutDeadlockAndWithoutEarlyRetirement() {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        OresFuture<Integer> guestResult = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        OresFuture<Integer> host = pump.request(pump.token(), () -> {
            pump.seal(); // permitted concurrently with starter execution
            assertThrows(IllegalStateException.class, pump::requireQuiescent);
            return new HostGeneratorTurnPump.Turn<>(guestResult, physicalExit);
        });
        assertTrue(pump.dispatchOne());
        assertTrue(host.isCancelled());
        assertTrue(guestResult.isCancelled());
        assertThrows(IllegalStateException.class, pump::requireQuiescent);
        physicalExit.completeFromRuntime(null);
        pump.close();
    }

    @Test
    void starterFailureAfterSealStillQuarantinesPhysicalExit() throws Exception {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        AtomicInteger subsequentStarts = new AtomicInteger();

        OresFuture<Integer> first = pump.request(pump.token(), () -> {
            starterEntered.countDown();
            try {
                if (!releaseStarter.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("starter release not observed");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            // Failure after host cancellation must not erase uncertainty about
            // an operation that may have started before returning DONE evidence.
            throw new IllegalStateException("physical producer exit unknown");
        });
        OresFuture<Integer> queued = pump.request(pump.token(), () -> {
            subsequentStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(2), OresFuture.completed(null));
        });
        Thread owner = Thread.ofPlatform().start(() -> {
            try { pump.dispatchOne(); }
            catch (Throwable failure) { ownerFailure.set(failure); }
        });
        try {
            assertTrue(starterEntered.await(5, TimeUnit.SECONDS));
            pump.seal();
            assertTrue(first.isCancelled(), "seal must cancel the starting public pull");
            assertTrue(queued.isCancelled(), "queued work must be rejected at shutdown");
            assertThrows(IllegalStateException.class, pump::requireQuiescent);
        } finally {
            releaseStarter.countDown();
            owner.join(5000);
        }
        assertFalse(owner.isAlive(), "the owner must exit after startup failure");
        assertNull(ownerFailure.get(), String.valueOf(ownerFailure.get()));
        assertEquals(0, subsequentStarts.get());
        assertFalse(pump.isQuiescent(),
                "after starter failure, cancellation is not evidence of physical DONE");
        assertThrows(IllegalStateException.class, pump::close,
                "a later close retry must retain permanent unknown-exit quarantine");
    }

    @Test
    void starterFailurePoisonsQueueBeforeAnyFollowingDispatch() throws Exception {
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true);
        AtomicInteger laterStarts = new AtomicInteger();
        OresFuture<Integer> broken = pump.request(pump.token(), () -> {
            throw new IllegalStateException("producer start failed after reservation");
        });
        OresFuture<Integer> later = pump.request(pump.token(), () -> {
            laterStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(4), OresFuture.completed(null));
        });
        assertTrue(pump.dispatchOne());
        assertInstanceOf(IllegalStateException.class,
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> broken.get(5, TimeUnit.SECONDS)).getCause());
        assertTrue(later.isCancelled(),
                "unknown partial startup cannot admit the next producer");
        assertEquals(0, laterStarts.get());
        assertFalse(pump.dispatchOne());
        assertFalse(pump.isQuiescent(),
                "a starter that threw without an exit receipt is quarantined");
        assertThrows(IllegalStateException.class, pump::close,
                "physical context retirement must remain blocked after unknown startup");
    }

    @Test
    void schedulerBackedHeapFrameCannotOverlapPhysicalStarts() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger resumes = new AtomicInteger();
            GeneratorRuntime.SchedulerBacked<Integer> frame =
                    GeneratorRuntime.schedulerBacked(root, pump.token(), input -> {
                        int value = resumes.incrementAndGet();
                        if (value == 1) {
                            firstEntered.countDown();
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("frame release timed out");
                            }
                        }
                        return OresScheduler.done(GeneratorRuntime.Step.yielded(value));
                    });

            HostGeneratorTurnPump.Turn<GeneratorRuntime.Step<Integer>> first =
                    frame.nextTurn().start();
            // The observer registers before the held guest carrier is released.
            // Runtime Future waiters are delivered in registration order; this
            // latch follows the frame's own physical-exit lease callback.
            CountDownLatch firstExitCallbacksDone = new CountDownLatch(1);
            first.physicalExit().whenCompleteRuntime(
                    (ignored, failure) -> firstExitCallbacksDone.countDown());
            try {
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class,
                        () -> frame.nextTurn().start(),
                        "even an internal bypass cannot resume one heap frame twice");
                assertEquals(1, resumes.get());
            } finally {
                releaseFirst.countDown();
            }
            assertEquals(1, first.result().get(5, TimeUnit.SECONDS).value());
            assertTrue(firstExitCallbacksDone.await(5, TimeUnit.SECONDS));

            HostGeneratorTurnPump.Turn<GeneratorRuntime.Step<Integer>> second =
                    frame.nextTurn().start();
            assertEquals(2, second.result().get(5, TimeUnit.SECONDS).value());
            second.physicalExit().get(5, TimeUnit.SECONDS);
            assertEquals(2, resumes.get());
            pump.close();
        }
    }

    @Test
    void typecheckedOresSourceYieldsThreeValuesAndCancelsFourthWithOneFinally()
            throws Exception {
        // This is source AST -> restricted generator frame -> certified
        // scheduler-owned turns. No handwritten host-side yield callback
        // and no AsyncRuntime/virtual-thread generator producer.
        String source = """
                async fnc pending(): int { return 44; }
                async generator fnc numbers(): int {
                  try {
                    yield 11;
                    yield 22;
                    yield 33;
                    yield await pending();
                  } catch (err) {
                  } finally {
                    val int cleanup_marker = 1;
                  }
                  return;
                }
                """;
        OresFuture<Integer> unresolved = new OresFuture<>();
        GeneratorRuntime.SourceLowering frame =
                GeneratorRuntime.SourceLowering.compile(
                        source, "numbers", java.util.Map.of("pending", unresolved));
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            GeneratorRuntime.SchedulerBacked<Object> lowered =
                    GeneratorRuntime.schedulerBacked(root, pump.token(), frame);
            for (int expected : new int[]{11, 22, 33}) {
                OresFuture<GeneratorRuntime.Step<Object>> next =
                        pump.requestCertified(pump.token(), lowered.nextTurn());
                assertTrue(pump.dispatchOne());
                var yielded = next.get(5, TimeUnit.SECONDS);
                assertFalse(yielded.done());
                assertEquals(expected, ((Number) yielded.value()).intValue());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!pump.isQuiescent() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(pump.isQuiescent(),
                        "physical DONE must settle before next source yield");
            }
            assertEquals(0, frame.cleanupRuns(),
                    "lexical finally must not run before fourth yield");
            OresFuture<GeneratorRuntime.Step<Object>> pending =
                    pump.requestCertified(pump.token(), lowered.nextTurn());
            assertTrue(pump.dispatchOne());
            long started = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!frame.awaitingSourceFuture() && System.nanoTime() < started) {
                Thread.onSpinWait();
            }
            assertTrue(frame.awaitingSourceFuture(), "fourth source yield must await");
            assertFalse(pending.isDone());
            assertTrue(pending.cancel(false));
            pump.seal();
            root.close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!pump.isQuiescent() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(pump.isQuiescent(),
                    "cancellation must wait for authenticated physical DONE");
            assertEquals(1, frame.cleanupRuns(), "source finally must run exactly once");
            assertEquals(1, ((Number) frame.cleanupBinding("cleanup_marker")).intValue());
            assertFalse(unresolved.isDone(), "do not cancel an externally owned Future");
            pump.close();
        }
    }

    @Test
    void restrictedOresSourceLoweringRejectsUnsupportedYieldBeforeGuestStart() {
        String source = """
                async generator fnc numbers(): int {
                  yield 1 + 2;
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> GeneratorRuntime.SourceLowering.compile(
                        source, "numbers", java.util.Map.of()));
    }

    @Test
    void restrictedLoweringRejectsCleanupCrossingLexicalYieldBoundary() {
        String after = """
                async generator fnc values(): int {
                  try { yield 1; } catch (err) { }
                  finally { val int marker = 1; }
                  yield 2;
                  return;
                }
                """;
        String before = """
                async generator fnc values(): int {
                  yield 1;
                  try { yield 2; } catch (err) { }
                  finally { val int marker = 1; }
                  return;
                }
                """;
        for (String source : new String[]{after, before}) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> GeneratorRuntime.SourceLowering.compile(
                            source, "values", java.util.Map.of()));
            assertTrue(denied.getMessage().contains("whole-body try"),
                    "unsupported cleanup scope must fail before guest execution");
        }
    }

    @Test
    void emptySourceCatchConsumesAwaitFailureAndRunsFinallyOnce() throws Exception {
        String source = """
                async fnc pending(): int { return 44; }
                async generator fnc values(): int {
                  try { yield await pending(); }
                  catch (err) { }
                  finally { val int marker = 1; }
                  return;
                }
                """;
        OresFuture<Integer> rejected = new OresFuture<>();
        GeneratorRuntime.SourceLowering frame =
                GeneratorRuntime.SourceLowering.compile(
                        source, "values", java.util.Map.of("pending", rejected));
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            GeneratorRuntime.SchedulerBacked<Object> lowered =
                    GeneratorRuntime.schedulerBacked(root, pump.token(), frame);
            OresFuture<GeneratorRuntime.Step<Object>> pull =
                    pump.requestCertified(pump.token(), lowered.nextTurn());
            assertTrue(pump.dispatchOne());
            rejected.failFromRuntime(new IllegalStateException("await rejection"));
            assertTrue(pull.get(5, TimeUnit.SECONDS).done(),
                    "empty catch must consume the rejected awaited future");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!pump.isQuiescent() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(pump.isQuiescent());
            assertEquals(1, frame.cleanupRuns());
            assertEquals(1, ((Number) frame.cleanupBinding("marker")).intValue());
            assertTrue(lowered.isTerminal());
            pump.close();
        }
    }


    @Test
    void stagedJavaNextStepAutoDispatchesThreeCompiledYieldsThroughOwnedScheduler()
            throws Exception {
        // A one-carrier stand-in for the context-owned root turn. This checks
        // that arbitrary Java host threads cannot run source code or dispatch
        // it themselves. Real Truffle Context.enter remains a separate P0 gate.
        try (OresScheduler root = new OresScheduler(1)) {
            AtomicReference<HostGeneratorTurnPump> owner = new AtomicReference<>();
            AtomicInteger ownerWakeups = new AtomicInteger();
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> OresScheduler.current() == root,
                    () -> root.startSync(() -> {
                        ownerWakeups.incrementAndGet();
                        // Owner wakeups are advisory. Multiple host submits
                        // can queue wake tasks before the first reservation;
                        // a later wake may legitimately find an active turn.
                        owner.get().dispatchOne();
                        return null;
                    }), 8, root);
            owner.set(pump);
            String source = """
                    async generator fnc values(): int {
                      yield 11;
                      yield 22;
                      yield 33;
                      return;
                    }
                    """;
            GeneratorRuntime.HostPullSession<Object> host =
                    GeneratorRuntime.stagedSourceHostPullSession(
                            pump, root, source, "values", java.util.Map.of());
            assertNull(OresScheduler.current(),
                    "Java host must not have scheduler or guest-entry rights");
            // Enqueue all four pulls without a guest entry on this host thread.
            var first = host.nextStep();
            var second = host.nextStep();
            var third = host.nextStep();
            var done = host.nextStep();
            assertNull(OresScheduler.current());
            assertEquals(11, ((Number) first.get(5, TimeUnit.SECONDS).value()).intValue());
            assertEquals(22, ((Number) second.get(5, TimeUnit.SECONDS).value()).intValue());
            assertEquals(33, ((Number) third.get(5, TimeUnit.SECONDS).value()).intValue());
            assertTrue(done.get(5, TimeUnit.SECONDS).done());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!host.isPhysicallyQuiescent() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(host.isPhysicallyQuiescent(), "all four pulls need physical DONE");
            assertTrue(host.isTerminal());
            assertTrue(ownerWakeups.get() >= 4, "root owner must dispatch every pull");
            host.close();
            assertThrows(java.util.concurrent.RejectedExecutionException.class, host::nextStep);
        }
    }

    @Test
    void stagedHostCloseCannotRetireYieldedSourceBeforeFinallyRuns() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            AtomicReference<HostGeneratorTurnPump> owner = new AtomicReference<>();
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> OresScheduler.current() == root,
                    () -> root.startSync(() -> { owner.get().dispatchOne(); return null; }),
                    4, root);
            owner.set(pump);
            String source = """
                    async generator fnc values(): int {
                      try { yield 1; yield 2; }
                      catch (err) { }
                      finally { val int cleanup = 1; }
                      return;
                    }
                    """;
            var host = GeneratorRuntime.stagedSourceHostPullSession(
                    pump, root, source, "values", java.util.Map.of());
            assertEquals(1,
                    ((Number) host.nextStep().get(5, TimeUnit.SECONDS).value()).intValue());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!host.isPhysicallyQuiescent() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(host.isPhysicallyQuiescent());
            assertFalse(host.isTerminal());
            assertThrows(IllegalStateException.class, host::close,
                    "physical exit of one yield does not prove source finally");
            assertThrows(IllegalStateException.class, host::close,
                    "a second close must not erase incomplete lexical cleanup");
            assertThrows(java.util.concurrent.RejectedExecutionException.class, host::nextStep);
        }
    }

    @Test
    void invalidSourceCannotCreateHostPullsOrWakeScheduler() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            AtomicInteger wakes = new AtomicInteger();
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> false, wakes::incrementAndGet, 2, root);
            assertThrows(IllegalArgumentException.class,
                    () -> GeneratorRuntime.stagedSourceHostPullSession(
                            pump, root, """
                                    async generator fnc values(): int {
                                      yield 1 + 2;
                                      return;
                                    }
                                    """, "values", java.util.Map.of()));
            assertEquals(0, wakes.get());
            assertTrue(pump.isQuiescent());
            pump.close();
        }
    }

    @Test
    void hostIteratorPreflightRejectsForeignRootWithoutQuarantiningOwner()
            throws Exception {
        try (OresScheduler owner = new OresScheduler(1);
             OresScheduler foreign = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, owner);
            AtomicInteger resumes = new AtomicInteger();
            GeneratorRuntime.SchedulerFrame<Integer> frame = input ->
                    OresScheduler.done(GeneratorRuntime.Step.yielded(
                            resumes.incrementAndGet()));

            assertThrows(SecurityException.class, () ->
                    GeneratorRuntime.certifiedHostIterator(pump, foreign, frame),
                    "a foreign root must be rejected before any physical task exists");
            assertThrows(SecurityException.class, () ->
                    GeneratorRuntime.certifiedHostIterator(pump, null, frame));
            assertTrue(pump.isQuiescent(),
                    "invalid construction must not poison the owning context");
            assertEquals(0, resumes.get());

            var legitimate = GeneratorRuntime.certifiedHostIterator(pump, owner, frame);
            var pull = legitimate.nextStep();
            assertFalse(pull.isDone());
            assertTrue(pump.dispatchOne());
            assertEquals(1, pull.get(5, TimeUnit.SECONDS).value());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!pump.isQuiescent() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(pump.isQuiescent());
            pump.close();
        }
    }

    @Test
    void hostIteratorRejectsClosedOwnerBeforeFirstPull() throws Exception {
        OresScheduler root = new OresScheduler(1);
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> true, root);
        root.close();
        assertThrows(RejectedExecutionException.class, () ->
                GeneratorRuntime.certifiedHostIterator(pump, root, input ->
                        OresScheduler.done(GeneratorRuntime.Step.yielded(5))));
        assertTrue(pump.isQuiescent(),
                "closed-root preflight cannot consume admission or poison the pump");
        pump.close();
    }

    @Test
    void hostNextStepStagesRealTypedSourceWithoutEnteringGuestOnHostThread()
            throws Exception {
        String source = """
                async generator fnc values(): int {
                  yield 11;
                  yield 22;
                  yield 33;
                  return;
                }
                """;
        GeneratorRuntime.SourceLowering frame =
                GeneratorRuntime.SourceLowering.compile(
                        source, "values", java.util.Map.of());
        try (OresScheduler root = new OresScheduler(1)) {
            Thread authorizedOwner = Thread.currentThread();
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(),
                    () -> Thread.currentThread() == authorizedOwner,
                    () -> { }, 4, root);
            var host = GeneratorRuntime.certifiedHostIterator(pump, root, frame);

            AtomicReference<OresFuture<GeneratorRuntime.Step<Object>>> submitted =
                    new AtomicReference<>();
            Thread foreign = Thread.ofPlatform().start(
                    () -> submitted.set(host.nextStep()));
            foreign.join();
            assertNotNull(submitted.get());
            assertFalse(submitted.get().isDone(),
                    "host admission must not manufacture physical guest entry");
            assertEquals(0, frame.cleanupRuns());
            assertThrows(SecurityException.class, () -> {
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread foreignDispatch = Thread.ofPlatform().start(() -> {
                    try { pump.dispatchOne(); }
                    catch (Throwable denied) { failure.set(denied); }
                });
                foreignDispatch.join();
                if (failure.get() instanceof SecurityException denied) throw denied;
            });
            for (int value : new int[]{11, 22, 33}) {
                OresFuture<GeneratorRuntime.Step<Object>> pull;
                if (value == 11) pull = submitted.get();
                else pull = host.nextStep();
                assertFalse(pull.isDone(), "pull must wait for authorized dispatch");
                assertTrue(pump.dispatchOne());
                assertEquals(value, ((Number) pull.get(5, TimeUnit.SECONDS).value()).intValue());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!pump.isQuiescent() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(pump.isQuiescent());
            }
            OresFuture<GeneratorRuntime.Step<Object>> last = host.nextStep();
            assertFalse(last.isDone());
            assertTrue(pump.dispatchOne());
            assertTrue(last.get(5, TimeUnit.SECONDS).done());
            assertTrue(host.isTerminal());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!pump.isQuiescent() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(pump.isQuiescent());
            pump.close();
        }
    }

    @Test
    void certifiedSchedulerFrameYieldsThreeTypedValuesAndFinishes() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            AtomicInteger next = new AtomicInteger();
            GeneratorRuntime.SchedulerBacked<Integer> frame =
                    GeneratorRuntime.schedulerBacked(root, pump.token(), resume -> {
                        assertTrue(resume.initial(), "each pull starts a new root task");
                        int value = next.incrementAndGet();
                        return OresScheduler.done(value <= 3
                                ? GeneratorRuntime.Step.yielded(value)
                                : GeneratorRuntime.Step.doneStep());
                    });

            for (int expected = 1; expected <= 3; expected++) {
                OresFuture<GeneratorRuntime.Step<Integer>> pull =
                        pump.requestCertified(pump.token(), frame.nextTurn());
                assertTrue(pump.dispatchOne(), "owner must dispatch requested pull");
                GeneratorRuntime.Step<Integer> step = pull.get(5, TimeUnit.SECONDS);
                assertFalse(step.done());
                assertEquals(expected, step.value());
                // A Future can become publicly visible slightly before the
                // pump finishes its post-publication bookkeeping. Wait for
                // the actual exit + reservation-release condition; never
                // weaken requireQuiescent() just to hide this race.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!pump.isQuiescent() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(pump.isQuiescent(),
                        "public publication requires physical guest DONE");
            }
            OresFuture<GeneratorRuntime.Step<Integer>> finalPull =
                    pump.requestCertified(pump.token(), frame.nextTurn());
            assertTrue(pump.dispatchOne());
            assertTrue(finalPull.get(5, TimeUnit.SECONDS).done());
            assertTrue(frame.isTerminal());
            long finalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!pump.isQuiescent() && System.nanoTime() < finalDeadline) {
                Thread.onSpinWait();
            }
            assertTrue(pump.isQuiescent(),
                    "completed host pull must eventually release physical ownership");
            pump.close();
            assertEquals(4, next.get());
        }
    }

    @Test
    void contextOwnedPumpCannotDowngradeToSyntheticPhysicalExit() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            AtomicInteger starts = new AtomicInteger();
            assertThrows(SecurityException.class,
                    () -> pump.request(pump.token(), () -> {
                        starts.incrementAndGet();
                        return new HostGeneratorTurnPump.Turn<>(
                                OresFuture.completed(1), OresFuture.completed(null));
                    }));
            assertEquals(0, starts.get(), "legacy guest producer must never start");
            assertTrue(pump.isQuiescent());
            pump.close();
        }
    }

    @Test
    void certifiedPullRejectsSyntheticOrForeignSchedulerDoneProof() throws Exception {
        try (OresScheduler root = new OresScheduler(1);
             OresScheduler foreign = new OresScheduler(1)) {
            HostGeneratorTurnPump synthetic = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            OresFuture<Integer> fake = synthetic.requestCertified(synthetic.token(),
                    () -> new HostGeneratorTurnPump.Turn<>(
                            OresFuture.completed(3), OresFuture.completed(null)));
            assertTrue(synthetic.dispatchOne());
            assertInstanceOf(SecurityException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> fake.get(5, TimeUnit.SECONDS)).getCause());
            assertThrows(IllegalStateException.class, synthetic::close,
                    "untrusted physical receipt must poison the incarnation");

            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            OresFuture<Integer> pulled = pump.requestCertified(pump.token(),
                    () -> foreign.startCertifiedGeneratorTurn(
                            pump.token(), input -> OresScheduler.done(7)));
            assertTrue(pump.dispatchOne());
            assertInstanceOf(SecurityException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> pulled.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(pump.isQuiescent(),
                    "a foreign scheduler receipt cannot authorize retirement");
            assertThrows(IllegalStateException.class, pump::close);
        }
    }

    @Test
    void certifiedPhysicalDoneReceiptCannotBeReplayedForAnotherPull()
            throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            AtomicReference<HostGeneratorTurnPump.Turn<Integer>> original =
                    new AtomicReference<>();
            OresFuture<Integer> first =
                    pump.requestCertified(pump.token(), () -> {
                        var issued = root.startCertifiedGeneratorTurn(
                                pump.token(), input -> OresScheduler.done(19));
                        original.set(issued);
                        return issued;
                    });
            assertTrue(pump.dispatchOne());
            assertEquals(19, first.get(5, TimeUnit.SECONDS));
            // Wait for the pump's post-publication ownership release; Future
            // completion itself is allowed to wake the host slightly earlier.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!pump.isQuiescent() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(pump.isQuiescent());
            OresFuture<Integer> replay =
                    pump.requestCertified(pump.token(), () -> original.get());
            assertTrue(pump.dispatchOne());
            assertInstanceOf(SecurityException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> replay.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(pump.isQuiescent(),
                    "replayed DONE must permanently quarantine physical ownership");
            assertThrows(IllegalStateException.class, pump::close);
        }
    }

    @Test
    void losingGuestEntryAuthorizationDuringStarterPoisonsCertifiedPull()
            throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            java.util.concurrent.atomic.AtomicBoolean authorized =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), authorized::get, root);
            OresFuture<Integer> host = pump.requestCertified(pump.token(), () -> {
                var started = root.startCertifiedGeneratorTurn(
                        pump.token(), input -> OresScheduler.done(31));
                // This models losing the real root entered guest-turn scope
                // while startup is still trying to hand back its receipt.
                authorized.set(false);
                return started;
            });
            assertTrue(pump.dispatchOne());
            assertInstanceOf(SecurityException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> host.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(pump.isQuiescent());
            assertThrows(IllegalStateException.class, pump::close);
        }
    }

    @Test
    void preIssuedDoneReceiptCannotBeLaunderedIntoLaterRequest() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            // Same context and scheduler, valid issued generation, successful
            // physical exit -- but NO active pump request when it was minted.
            var preIssued = root.startCertifiedGeneratorTurn(
                    pump.token(), input -> OresScheduler.done(41));
            assertEquals(41, preIssued.result().get(5, TimeUnit.SECONDS));
            OresFuture<Integer> attempt =
                    pump.requestCertified(pump.token(), () -> preIssued);
            assertTrue(pump.dispatchOne());
            assertInstanceOf(SecurityException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> attempt.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(pump.isQuiescent());
            assertThrows(IllegalStateException.class, pump::close,
                    "a pre-issued DONE cannot bypass request-scoped authorization");
        }
    }

    @Test
    void onePumpReservationCannotMintTwoCertifiedGuestTasks() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            OresFuture<Integer> host = pump.requestCertified(pump.token(), () -> {
                root.startCertifiedGeneratorTurn(
                        pump.token(), input -> OresScheduler.done(11));
                // No second task can be scheduled with the same issuance
                // lease, even if the first one completes immediately.
                return root.startCertifiedGeneratorTurn(
                        pump.token(), input -> OresScheduler.done(12));
            });
            assertTrue(pump.dispatchOne());
            assertInstanceOf(SecurityException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> host.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(pump.isQuiescent());
            assertThrows(IllegalStateException.class, pump::close);
        }
    }

    @Test
    void detachedStarterThreadCannotMintRedeemablePhysicalDone() throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            AtomicReference<HostGeneratorTurnPump.Turn<Integer>> leaked =
                    new AtomicReference<>();
            AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
            OresFuture<Integer> host = pump.requestCertified(pump.token(), () -> {
                Thread worker = Thread.ofPlatform().start(() -> {
                    try {
                        leaked.set(root.startCertifiedGeneratorTurn(
                                pump.token(), input -> OresScheduler.done(17)));
                    } catch (Throwable failure) {
                        backgroundFailure.set(failure);
                    }
                });
                try {
                    worker.join(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "starter interrupted waiting for unauthorized worker", interrupted);
                }
                assertFalse(worker.isAlive());
                assertNull(backgroundFailure.get(), String.valueOf(backgroundFailure.get()));
                return leaked.get();
            });
            assertTrue(pump.dispatchOne());
            assertInstanceOf(SecurityException.class,
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> host.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(pump.isQuiescent());
            assertThrows(IllegalStateException.class, pump::close);
        }
    }

    @Test
    void failedGuestExecutorExitCannotMintSuccessfulPhysicalDone() throws Exception {
        java.util.concurrent.ExecutorService carriers =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            OresScheduler root = OresScheduler.runtimeOwned(
                    "leave-error-root", 1, carriers, turn -> {
                        turn.run();
                        throw new IllegalStateException(
                                "simulated failure while leaving guest context");
                    });
            try {
                HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                        UUID.randomUUID(), () -> true, root);
                OresFuture<Integer> host = pump.requestCertified(pump.token(),
                        () -> root.startCertifiedGeneratorTurn(
                                pump.token(), input -> OresScheduler.done(23)));
                assertTrue(pump.dispatchOne());
                assertInstanceOf(IllegalStateException.class,
                        assertThrows(java.util.concurrent.ExecutionException.class,
                                () -> host.get(5, TimeUnit.SECONDS)).getCause());
                assertFalse(pump.isQuiescent(),
                        "even completed scheduler work cannot certify a failed guest leave");
                assertThrows(IllegalStateException.class, pump::close);
            } finally {
                root.close();
            }
        } finally {
            carriers.shutdownNow();
            assertTrue(carriers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelledCertifiedAwaitCannotStartAnotherPullBeforePhysicalDone()
            throws Exception {
        try (OresScheduler root = new OresScheduler(1)) {
            HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                    UUID.randomUUID(), () -> true, root);
            OresFuture<Integer> neverReady = new OresFuture<>();
            CountDownLatch frameEntered = new CountDownLatch(1);
            AtomicInteger secondStarts = new AtomicInteger();
            OresFuture<Integer> first = pump.requestCertified(pump.token(),
                    () -> root.startCertifiedGeneratorTurn(pump.token(), input -> {
                        frameEntered.countDown();
                        return OresScheduler.await(neverReady);
                    }));
            OresFuture<Integer> second = pump.requestCertified(pump.token(),
                    () -> root.startCertifiedGeneratorTurn(pump.token(), input -> {
                        secondStarts.incrementAndGet();
                        return OresScheduler.done(8);
                    }));
            assertTrue(pump.dispatchOne());
            assertTrue(frameEntered.await(5, TimeUnit.SECONDS));
            assertFalse(pump.dispatchOne(),
                    "the second pull cannot enter while the first owns its lease");
            assertTrue(first.cancel(false));
            pump.seal();
            assertTrue(second.isCancelled());
            assertEquals(0, secondStarts.get());
            // The scheduler owns cancellation. Only its terminal after-carrier
            // publication can release the certified physical exit receipt.
            CountDownLatch physicallyDone = new CountDownLatch(1);
            neverReady.whenCompleteRuntime((value, failure) -> physicallyDone.countDown());
            root.close();
            assertTrue(pump.isQuiescent(),
                    "scheduler shutdown has physically drained cancelled tasks");
            pump.close();
        }
    }

    @Test
    void cancellationAndContextShutdownRaceCannotStartQueuedTurn() throws Exception {
        AtomicReference<Thread> ownerThread = new AtomicReference<>();
        HostGeneratorTurnPump pump = new HostGeneratorTurnPump(
                UUID.randomUUID(), () -> Thread.currentThread() == ownerThread.get());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch resumeOwner = new CountDownLatch(1);
        OresFuture<Integer> producerValue = new OresFuture<>();
        OresFuture<Void> physicalExit = new OresFuture<>();
        AtomicInteger secondStarts = new AtomicInteger();
        OresFuture<Integer> first = pump.request(pump.token(), () -> {
            started.countDown();
            return new HostGeneratorTurnPump.Turn<>(producerValue, physicalExit);
        });
        OresFuture<Integer> second = pump.request(pump.token(), () -> {
            secondStarts.incrementAndGet();
            return new HostGeneratorTurnPump.Turn<>(
                    OresFuture.completed(2), OresFuture.completed(null));
        });

        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        Thread owner = Thread.ofPlatform().name("context-owned-pump-turn").start(() -> {
            ownerThread.set(Thread.currentThread());
            try {
                assertTrue(pump.dispatchOne());
                if (!resumeOwner.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("owner did not receive release barrier");
                }
                assertFalse(pump.dispatchOne(),
                        "retired pump must reject even an authorized owner");
            } catch (Throwable failure) {
                ownerFailure.set(failure);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(first.cancel(false));
        pump.seal();
        assertTrue(second.isCancelled());
        assertThrows(IllegalStateException.class, pump::requireQuiescent);
        resumeOwner.countDown();
        owner.join(5000);
        assertFalse(owner.isAlive(), "owner dispatch is not allowed to deadlock teardown");
        assertNull(ownerFailure.get(), String.valueOf(ownerFailure.get()));
        assertEquals(0, secondStarts.get());
        physicalExit.completeFromRuntime(null);
        assertDoesNotThrow(pump::requireQuiescent);
    }
}
