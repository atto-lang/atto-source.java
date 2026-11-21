package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorAsyncSendBackpressureTest {
    private static IsolatePolicy mailboxPolicy(int messages) {
        IsolatePolicy base = IsolatePolicy.developer();
        return new IsolatePolicy(
                base.capabilities(),
                base.maxHeapBytes(),
                messages,
                base.maxWallTime(),
                base.adversarial());
    }

    private static IsolatePolicy oneMessagePolicy() {
        return mailboxPolicy(1);
    }

    @Test
    void fullMailboxSuspendsProducerUntilARealSlotIsTransferred() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn timed out");
                    }
                }
                processed.add(message);
            });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2); // exactly fills the one-message USER queue

            OresFuture<Void> third = ref.sendAsync(3);
            assertFalse(third.isDone(), "full mailbox must retain a bounded waiter, not fake admission");
            assertThrows(ActorRuntime.ActorMailboxFullException.class, () -> ref.send(4));

            releaseFirst.countDown();
            third.get(5, TimeUnit.SECONDS);
            ref.drain().get(5, TimeUnit.SECONDS);

            assertEquals(List.of(1, 2, 3), processed);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void cancellationDetachesPendingProducerAndReturnsNoPhantomMail() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn timed out");
                    }
                }
                processed.add(message);
            });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            OresFuture<Void> cancelled = ref.sendAsync(3);
            assertFalse(cancelled.isDone());
            assertTrue(cancelled.cancel(false));

            releaseFirst.countDown();
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), processed);

            // Cancellation must not leak the slot or retained payload budget.
            ref.send(4);
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2, 4), processed);

            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void pendingProducerRegistrationsAreBoundedIndependentlyOfMailboxDepth() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn timed out");
                    }
                }
            });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);

            // Producer registration is deliberately independent of queue depth.
            // The one-message mailbox can still retain a finite cohort of
            // suspended producers, but never more than the runtime cap.
            List<OresFuture<Void>> waiters = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                OresFuture<Void> waiter = ref.sendAsync(3 + i);
                assertFalse(waiter.isDone(), "waiter " + i + " should remain bounded/pending");
                waiters.add(waiter);
            }

            OresFuture<Void> overflow = ref.sendAsync(10_000);
            ExecutionException failed = assertThrows(
                    ExecutionException.class,
                    () -> overflow.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorSendBackpressureException.class, failed.getCause());
            assertEquals(
                    ActorRuntime.SendError.Reason.RESOURCE_EXHAUSTED,
                    ((ActorRuntime.ActorSendBackpressureException) failed.getCause()).reason());

            for (OresFuture<Void> waiter : waiters) {
                assertTrue(waiter.cancel(false));
            }
            releaseFirst.countDown();
            ref.drain().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }


    @Test
    void gracefulStopFailsUnadmittedProducerWithoutWaitingForItsPayload() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn timed out");
                    }
                }
            });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            OresFuture<Void> pending = ref.sendAsync(3);
            assertFalse(pending.isDone());

            OresFuture<Void> done = ref.gracefulStop();
            ExecutionException rejected = assertThrows(
                    ExecutionException.class,
                    () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorSealedException.class, rejected.getCause());
            assertFalse(done.isDone(), "graceful stop still owes already-admitted mail");

            releaseFirst.countDown();
            done.get(5, TimeUnit.SECONDS);
            assertFalse(ref.isAlive());
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void multiplePendingProducersReceiveCreditsInRegistrationOrder() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(mailboxPolicy(3), () -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn timed out");
                    }
                }
                processed.add(message);
            });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            ref.send(3);
            ref.send(4); // three queued USER credits are now full

            OresFuture<Void> fifth = ref.sendAsync(5);
            OresFuture<Void> sixth = ref.sendAsync(6);
            OresFuture<Void> seventh = ref.sendAsync(7);
            assertFalse(fifth.isDone());
            assertFalse(sixth.isDone());
            assertFalse(seventh.isDone());

            releaseFirst.countDown();
            fifth.get(5, TimeUnit.SECONDS);
            sixth.get(5, TimeUnit.SECONDS);
            seventh.get(5, TimeUnit.SECONDS);
            ref.drain().get(5, TimeUnit.SECONDS);

            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), processed);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void pendingSendHoldsPhysicalAdmissionLoanUntilItIsRetired() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn timed out");
                    }
                }
            });
            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);

            OresFuture<Void> pending = ref.sendAsync(3);
            assertFalse(pending.isDone());

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var actors = (java.util.Map<?, ?>) actorsField.get(runtime);
            Object cell = actors.get(ref.id());
            assertNotNull(cell);
            var loanField = cell.getClass().getDeclaredField("activeAsyncSendAdmissions");
            loanField.setAccessible(true);
            assertEquals(1, loanField.getInt(cell),
                    "pending payload/credit must fence physical actor finalization");

            assertTrue(pending.cancel(false));
            assertEquals(0, loanField.getInt(cell),
                    "cancellation before transfer must retire the physical admission loan");

            releaseFirst.countDown();
            ref.drain().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void cancellationAfterCreditTransferCannotRollBackAdmission() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch creditTransferred = new CountDownLatch(1);
        CountDownLatch publishTransferredCredit = new CountDownLatch(1);
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.asyncSendCreditTransferHookForTesting = () -> {
                creditTransferred.countDown();
                try {
                    if (!publishTransferredCredit.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("credit-transfer publication hook timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                            "credit-transfer publication hook interrupted",
                            interrupted);
                }
            };

            var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn timed out");
                    }
                }
                processed.add(message);
            });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            OresFuture<Void> third = ref.sendAsync(3);
            assertFalse(third.isDone());

            releaseFirst.countDown();
            assertTrue(creditTransferred.await(5, TimeUnit.SECONDS),
                    "producer credit never reached the transferred state");

            assertFalse(third.cancel(false),
                    "cancellation must lose once mailbox credit has transferred");
            assertFalse(third.isCancelled());

            publishTransferredCredit.countDown();
            third.get(5, TimeUnit.SECONDS);
            ref.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2, 3), processed);

            runtime.asyncSendCreditTransferHookForTesting = null;
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            publishTransferredCredit.countDown();
            releaseFirst.countDown();
        }
    }

    @Test
    void releaseRacingWaiterRegistrationCannotLoseTheWakeup() throws Exception {
        for (int round = 0; round < 32; round++) {
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            try (ActorRuntime runtime = new ActorRuntime()) {
                var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                    if (message == 1) {
                        firstEntered.countDown();
                        if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("held actor turn timed out");
                        }
                    }
                });

                ref.send(1);
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                ref.send(2);

                AtomicReference<OresFuture<Void>> pending = new AtomicReference<>();
                AtomicReference<Throwable> producerFailure = new AtomicReference<>();
                CountDownLatch producerStarted = new CountDownLatch(1);
                Thread producer = new Thread(() -> {
                    producerStarted.countDown();
                    try {
                        pending.set(ref.sendAsync(3));
                    } catch (Throwable failure) {
                        producerFailure.set(failure);
                    }
                }, "async-send-registration-race-" + round);
                producer.start();
                assertTrue(producerStarted.await(5, TimeUnit.SECONDS));

                // Intentionally race freeing the only USER slot against the
                // producer's full-check/snapshot/registration path.
                releaseFirst.countDown();
                producer.join(5_000);
                assertFalse(producer.isAlive());
                assertNull(producerFailure.get());

                OresFuture<Void> admitted = pending.get();
                assertNotNull(admitted);
                admitted.get(5, TimeUnit.SECONDS);
                ref.drain().get(5, TimeUnit.SECONDS);
                ref.stop();
                ref.getDone().get(5, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    @Test
    void pendingSendObserversNeverRunUnderActorAdmissionLocks() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch callbackObserved = new CountDownLatch(1);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message == 1) {
                    firstEntered.countDown();
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held actor turn timed out");
                    }
                }
            });
            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var actors = (java.util.Map<?, ?>) actorsField.get(runtime);
            Object cell = actors.get(ref.id());
            assertNotNull(cell);
            var lifecycleField = cell.getClass().getDeclaredField("lifecycleLock");
            lifecycleField.setAccessible(true);
            Object lifecycleLock = lifecycleField.get(cell);
            var accountingField = cell.getClass().getDeclaredField("mailboxAccountingLock");
            accountingField.setAccessible(true);
            Object accountingLock = accountingField.get(cell);

            OresFuture<Void> pending = ref.sendAsync(3);
            assertFalse(pending.isDone());
            pending.whenCompleteRuntime((ignored, failure) -> {
                try {
                    if (failure != null) throw new AssertionError("unexpected async-send failure", failure);
                    assertFalse(Thread.holdsLock(lifecycleLock),
                            "async-send observer ran under actor lifecycleLock");
                    assertFalse(Thread.holdsLock(accountingLock),
                            "async-send observer ran under mailboxAccountingLock");
                } catch (Throwable assertion) {
                    callbackFailure.set(assertion);
                } finally {
                    callbackObserved.countDown();
                }
            });

            releaseFirst.countDown();
            assertTrue(callbackObserved.await(5, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            ref.drain().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void serialActorCannotAwaitCapacityThatOnlyItsOwnTurnCanRelease() throws Exception {
        CountDownLatch diagnosed = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message != 1) return;

                context.self().send(2); // fill the only queued USER slot
                OresFuture<Void> impossible = context.self().sendAsync(3);
                try {
                    impossible.get(0, TimeUnit.NANOSECONDS);
                    unexpected.set(new AssertionError("self-send unexpectedly admitted"));
                } catch (ExecutionException failure) {
                    if (!(failure.getCause()
                            instanceof ActorRuntime.ActorSendWouldDeadlockException deadlock)) {
                        unexpected.set(failure);
                    } else if (deadlock.reason()
                            != ActorRuntime.SendError.Reason.WOULD_DEADLOCK) {
                        unexpected.set(new AssertionError("wrong deadlock reason"));
                    }
                }
                diagnosed.countDown();
            });

            ref.send(1);
            assertTrue(diagnosed.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get());
            ref.drain().get(5, TimeUnit.SECONDS);
            ref.stop();
            ref.getDone().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void producerActorShutdownWithdrawsUntransferredSend() throws Exception {
        CountDownLatch targetEntered = new CountDownLatch(1);
        CountDownLatch releaseTarget = new CountDownLatch(1);
        CountDownLatch producerReturned = new CountDownLatch(1);
        AtomicReference<OresFuture<Void>> producerSend = new AtomicReference<>();
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        try (ActorRuntime runtime = new ActorRuntime()) {
            var target = runtime.<Integer>spawnShared(oneMessagePolicy(), () -> (message, context) -> {
                if (message == 1) {
                    targetEntered.countDown();
                    if (!releaseTarget.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("held target turn timed out");
                    }
                }
                processed.add(message);
            });

            target.send(1);
            assertTrue(targetEntered.await(5, TimeUnit.SECONDS));
            target.send(2);

            var producer = runtime.<String>spawnSharedTrusted(
                    producerContext -> (message, context) -> {
                        producerSend.set(target.sendAsync(3));
                        producerReturned.countDown();
                    });

            producer.send("send");
            assertTrue(producerReturned.await(5, TimeUnit.SECONDS));
            OresFuture<Void> pending = producerSend.get();
            assertNotNull(pending);
            assertFalse(pending.isDone());

            producer.stop();
            producer.getDone().get(5, TimeUnit.SECONDS);
            assertTrue(
                    pending.isCancelled(),
                    "producer shutdown must withdraw a not-yet-transferred send");

            releaseTarget.countDown();
            target.drain().get(5, TimeUnit.SECONDS);
            assertEquals(
                    List.of(1, 2),
                    processed,
                    "a dead producer must not deliver a still-pending async send");

            target.send(4);
            target.drain().get(5, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2, 4), processed);
            target.stop();
            target.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseTarget.countDown();
        }
    }


    @Test
    void gracefulStopIncludesCreditTransferredButNotYetPublishedSend()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch creditTransferred = new CountDownLatch(1);
        CountDownLatch publishTransferredCredit = new CountDownLatch(1);
        List<Integer> processed = Collections.synchronizedList(new ArrayList<>());

        try (ActorRuntime runtime = new ActorRuntime()) {
            runtime.asyncSendCreditTransferHookForTesting = () -> {
                creditTransferred.countDown();
                try {
                    if (!publishTransferredCredit.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "credit-transfer publication hook timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            };

            var ref = runtime.<Integer>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (message == 1) {
                            firstEntered.countDown();
                            if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError(
                                        "held actor turn timed out");
                            }
                        }
                        processed.add(message);
                    });

            ref.send(1);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            ref.send(2);
            OresFuture<Void> third = ref.sendAsync(3);
            assertFalse(third.isDone());

            releaseFirst.countDown();
            assertTrue(
                    creditTransferred.await(5, TimeUnit.SECONDS),
                    "producer credit never transferred");

            OresFuture<Void> done = ref.gracefulStop();
            assertFalse(
                    done.isDone(),
                    "graceful stop must wait for the transferred pre-publication loan");
            assertFalse(
                    third.isDone(),
                    "transferred send is still deliberately held before publication");

            publishTransferredCredit.countDown();

            third.get(5, TimeUnit.SECONDS);
            done.get(5, TimeUnit.SECONDS);
            assertEquals(
                    List.of(1, 2, 3),
                    processed,
                    "graceful watermark must include the irrevocably transferred send");
            assertFalse(ref.isAlive());
        } finally {
            publishTransferredCredit.countDown();
            releaseFirst.countDown();
        }
    }


    @Test
    void schedulerAwaitOnPendingSendAsyncReleasesSharedCarrier() throws Exception {
        CountDownLatch targetEntered = new CountDownLatch(1);
        CountDownLatch releaseTarget = new CountDownLatch(1);
        CountDownLatch producerAwaitRegistered = new CountDownLatch(1);
        CountDownLatch producerResumed = new CountDownLatch(1);
        CountDownLatch observerRan = new CountDownLatch(1);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();

        // Two shared workers are intentional: one is physically occupied by
        // the held target turn. The other must become reusable after the
        // producer SourceTask returns OresScheduler.await(sendAsyncFuture).
        // If await parks that carrier, the observer has no worker and fails.
        var config = new ActorRuntime.DispatcherConfig(1, 2, 1, 32);
        try (ActorRuntime runtime = new ActorRuntime(oneMessagePolicy(), config)) {
            var target = runtime.<Integer>spawnShared(
                    oneMessagePolicy(),
                    () -> (message, context) -> {
                        if (message == 1) {
                            targetEntered.countDown();
                            if (!releaseTarget.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("held target turn timed out");
                            }
                        }
                    });

            target.send(1);
            assertTrue(targetEntered.await(5, TimeUnit.SECONDS));
            target.send(2); // fill the only queued USER slot

            var producer = runtime.<Integer>spawnShared(() -> (message, context) -> {
                context.runtime().startActorTask(new OresScheduler.Task<Void>() {
                    private boolean waiting;

                    @Override
                    public OresScheduler.Step<Void> resume(
                            OresScheduler.Resume resume) {
                        if (!waiting) {
                            waiting = true;
                            OresFuture<Void> admission = target.sendAsync(3);
                            assertFalse(admission.isDone(),
                                    "target mailbox must be saturated for this proof");
                            producerAwaitRegistered.countDown();
                            return OresScheduler.await(admission);
                        }

                        if (resume.failure() != null) {
                            producerFailure.set(resume.failure());
                            return OresScheduler.done(null);
                        }
                        producerResumed.countDown();
                        return OresScheduler.done(null);
                    }
                });
            });

            var observer = runtime.<Integer>spawnShared(
                    () -> (message, context) -> observerRan.countDown());

            producer.send(1);
            assertTrue(
                    producerAwaitRegistered.await(5, TimeUnit.SECONDS),
                    "producer SourceTask never registered pending mailbox admission");

            observer.send(1);
            assertTrue(
                    observerRan.await(2, TimeUnit.SECONDS),
                    "pending send_async await parked the only free shared carrier");

            releaseTarget.countDown();
            assertTrue(
                    producerResumed.await(5, TimeUnit.SECONDS),
                    "producer continuation did not resume after mailbox credit release");
            assertNull(producerFailure.get());

            target.drain().get(5, TimeUnit.SECONDS);
            producer.stop();
            observer.stop();
            target.stop();
            producer.getDone().get(5, TimeUnit.SECONDS);
            observer.getDone().get(5, TimeUnit.SECONDS);
            target.getDone().get(5, TimeUnit.SECONDS);
        } finally {
            releaseTarget.countDown();
        }
    }

}
