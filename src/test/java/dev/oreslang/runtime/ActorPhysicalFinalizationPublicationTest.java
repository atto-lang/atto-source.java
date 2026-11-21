package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime termination linearization regression. ActorRef absence
 * from the runtime registry must never be mistaken for completed physical
 * finalization while shutdown observers are still executing.
 */
@Timeout(30)
final class ActorPhysicalFinalizationPublicationTest {
    @Test
    @SuppressWarnings("unchecked")
    void temporaryRegistryAbsenceIsNotProofOfPhysicalFinalization()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var registry = (java.util.Map<Object, Object>) actorsField.get(runtime);
            Object cell = registry.get(actor.id());
            assertNotNull(cell);
            // Inject precisely the registry-absence window that used to
            // certify physical termination without checking a durable ref
            // witness. Restore it before asking the runtime to stop.
            assertSame(cell, registry.remove(actor.id()));
            try {
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor),
                        "missing ActorCell cannot release a live generation");
                assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS),
                        "unknown registry entry is not a terminal barrier");
            } finally {
                assertNull(registry.putIfAbsent(actor.id(), cell));
            }
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void terminationWaitSurvivesRegistryRemovalWithoutSpuriousTimeout()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var field = ActorRuntime.class.getDeclaredField("actors");
            field.setAccessible(true);
            var actors = (java.util.Map<Object, Object>) field.get(runtime);
            Object cell = actors.remove(actor.id());
            assertNotNull(cell);

            var started = new CountDownLatch(1);
            var completed = new CountDownLatch(1);
            var observedFinalization = new AtomicBoolean();
            var error = new AtomicReference<Throwable>();
            Thread waiter = Thread.ofPlatform().start(() -> {
                started.countDown();
                try {
                    observedFinalization.set(
                            actor.awaitTermination(5, TimeUnit.SECONDS));
                } catch (Throwable failure) {
                    error.set(failure);
                } finally {
                    completed.countDown();
                }
            });
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertFalse(completed.await(40, TimeUnit.MILLISECONDS),
                        "temporary absence of ActorCell must not shorten a "
                        + "five-second physical finalization wait");
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            } finally {
                // A missing actor is a simulated registry linearization
                // window, not permission to discard its live executor.
                assertNull(actors.putIfAbsent(actor.id(), cell));
                actor.stop();
                waiter.join(5000);
            }
            assertFalse(waiter.isAlive());
            assertNull(error.get());
            assertTrue(observedFinalization.get(),
                    "surviving ActorRef wait must observe actual finalization");
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<Boolean> booleanCarrier(
            Class<?> runtimeType, String field) throws Exception {
        var declared = runtimeType.getDeclaredField(field);
        declared.setAccessible(true);
        return (ThreadLocal<Boolean>) declared.get(null);
    }

    @Test
    void orphanedCarrierMarkersCannotBlockOnActorFinalization()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS),
                    "an ordinary host may observe an unfinished actor");

            // No active CURRENT actor or scheduler Task is present. Each
            // carrier marker alone must be sufficient to deny a blocking
            // physical join that could stall its executor indefinitely.
            record Marker(Class<?> owner, String field) { }
            var carriers = java.util.List.of(
                    new Marker(ActorRuntime.class, "ACTOR_CARRIER"),
                    new Marker(OresScheduler.class, "SCHEDULER_CARRIER"),
                    new Marker(AsyncRuntime.class, "ASYNC_CARRIER"));
            for (var carrier : carriers) {
                var local = booleanCarrier(carrier.owner(), carrier.field());
                Boolean before = local.get();
                try {
                    local.set(Boolean.TRUE);
                    assertFalse(ActorRuntime.inActorExecution());
                    assertThrows(SecurityException.class,
                            () -> actor.awaitTermination(5, TimeUnit.SECONDS),
                            "carrier-only thread may not join actor: " + carrier.field());
                } finally {
                    if (before == null) local.remove();
                    else local.set(before);
                }
            }
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void realSchedulerTurnCannotJoinPhysicalActorFinalization()
            throws Exception {
        try (var runtime = new ActorRuntime();
             var scheduler = new OresScheduler(1)) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            var denied = scheduler.start(resume -> {
                assertThrows(SecurityException.class,
                        () -> actor.awaitTermination(5, TimeUnit.SECONDS),
                        "a real scheduler carrier must not block on actor finalization");
                return OresScheduler.done(1);
            });
            assertEquals(1, denied.get(5, TimeUnit.SECONDS));
            assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor),
                    "rejected scheduler join must not finalize the actor");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "real host remains authorized to join finalized actor");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void physicalRetirementCannotPrecedeExactReceiptFailureCallbacks()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            // Inject an exact receipt reservation into the live idle ActorCell.
            // This isolates the terminal-settlement ordering without racing
            // the scheduler's normal user-message processing.
            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var actors = (java.util.Map<Object, Object>) actorsField.get(runtime);
            Object cell = actors.get(actor.id());
            assertNotNull(cell);
            var ledgerField = cell.getClass().getDeclaredField(
                    "pendingPhysicalReceipts");
            ledgerField.setAccessible(true);
            var receipts = (java.util.Map<Long, ActorRuntime.PhysicalMailReceipt>)
                    ledgerField.get(cell);
            var receiptConstructor = ActorRuntime.PhysicalMailReceipt.class
                    .getDeclaredConstructor(ActorRuntime.ActorRef.class,
                            long.class, OresFuture.class);
            receiptConstructor.setAccessible(true);
            var pending = new OresFuture<Void>(() -> false, () -> { });
            var receipt = receiptConstructor.newInstance(actor, 77L, pending);
            assertNull(receipts.putIfAbsent(77L, receipt));

            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var deniedSelfJoin = new AtomicBoolean();
            var callbackError = new AtomicReference<Throwable>();
            receipt.terminal().whenCompleteRuntime((ignored, failure) -> {
                try {
                    assertNotNull(failure, "stopped mail must fail, not PROCESSED");
                    assertThrows(IllegalStateException.class,
                            () -> actor.awaitTermination(5, TimeUnit.SECONDS),
                            "settling a receipt cannot join its own actor stop");
                    deniedSelfJoin.set(true);
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("receipt callback not released");
                } catch (Throwable error) {
                    callbackError.compareAndSet(null, error);
                    entered.countDown();
                }
            });

            var stopError = new AtomicReference<Throwable>();
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopError.set(failure);
                }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "stop never published the exact receipt terminal");
                assertNull(callbackError.get());
                assertTrue(deniedSelfJoin.get());
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor),
                        "generation retirement must wait for terminal receipt observer");
                assertFalse(actor.awaitTermination(30, TimeUnit.MILLISECONDS),
                        "external host waiter cannot pass an unfinished observer");
            } finally {
                release.countDown();
                stopper.join(5000);
            }
            assertFalse(stopper.isAlive());
            assertNull(stopError.get());
            assertNull(callbackError.get());
            assertEquals(ActorRuntime.PhysicalMailDisposition.STOPPED,
                    receipt.terminalDisposition());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedReceiptNotificationResumesExactBatchWithoutDoubleRefund()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var actors = (java.util.Map<Object, Object>) actorsField.get(runtime);
            Object cell = actors.get(actor.id());
            assertNotNull(cell);
            var ledgerField = cell.getClass().getDeclaredField(
                    "pendingPhysicalReceipts");
            ledgerField.setAccessible(true);
            var receipts = (java.util.Map<Long, ActorRuntime.PhysicalMailReceipt>)
                    ledgerField.get(cell);
            var ctor = ActorRuntime.PhysicalMailReceipt.class.getDeclaredConstructor(
                    ActorRuntime.ActorRef.class, long.class, OresFuture.class);
            ctor.setAccessible(true);
            var firstNotifications = new java.util.concurrent.atomic.AtomicInteger();
            var secondAttempts = new java.util.concurrent.atomic.AtomicInteger();

            var first = new OresFuture<Void>(
                    () -> false, () -> {}, firstNotifications::incrementAndGet);
            var second = new OresFuture<Void>(
                    () -> false, () -> {}, () -> {
                        if (secondAttempts.getAndIncrement() == 0)
                            throw new IllegalStateException(
                                    "injected one-time terminal notification release failure");
                    });
            var firstReceipt = ctor.newInstance(actor, 81L, first);
            var secondReceipt = ctor.newInstance(actor, 82L, second);
            receipts.put(81L, firstReceipt);
            receipts.put(82L, secondReceipt);

            // A post-terminal notification owner can fail after the Future
            // CAS. A retry must NOT issue a second receipt transition, drop
            // the exact batch, or mark physical code retirement complete.
            var firstStop = assertThrows(
                    IllegalStateException.class, actor::stop);
            assertTrue(firstStop.getMessage().contains(
                    "injected one-time terminal notification release failure"));
            assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
            assertEquals(ActorRuntime.PhysicalMailDisposition.STOPPED,
                    firstReceipt.terminalDisposition());
            assertEquals(ActorRuntime.PhysicalMailDisposition.STOPPED,
                    secondReceipt.terminalDisposition());

            // Each batch member is rechecked for terminal notification
            // drainage. Completed releases must not run again; the failed
            // release is retried using the same pending batch.
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertEquals(1, firstNotifications.get());
            assertEquals(2, secondAttempts.get());
        }
    }

    @Test
    void recordedPhysicalFinalizerCannotSynchronouslyWaitForItself()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);
            // Emulate only the terminal callback's owning thread marker.
            // The separate blocked-observer test exercises real completion
            // callbacks and retirement publication across threads.
            var marker = actor.getClass().getDeclaredField(
                    "physicalFinalizationOwnerThread");
            marker.setAccessible(true);
            marker.set(actor, Thread.currentThread());
            try {
                IllegalStateException denied = assertThrows(
                        IllegalStateException.class,
                        () -> actor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(denied.getMessage().contains(
                        "cannot join its own physical finalization"));
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            } finally {
                marker.set(actor, null);
            }
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
        }
    }

    @Test
    void blockedTerminalObserverCannotPublishEarlyCodeImageRetirement()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var entered = new CountDownLatch(1);
            var releaseObserver = new CountDownLatch(1);
            actor.getDone().whenCompleteRuntime((ignored, failure) -> {
                entered.countDown();
                try {
                    if (!releaseObserver.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("terminal observer not released");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("terminal observer interrupted", interrupted);
                }
            });

            var stopFailure = new AtomicReference<Throwable>();
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });
            Thread waiter = null;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS),
                        "actor termination never entered terminal observer");

                // A missing ActorCell after unregisterActor is not itself a
                // physical-completion proof. The on-done callback deliberately
                // blocks this actor's finalization, not a guest Task carrier.
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor),
                        "an incomplete terminal observer must retain old-code pin");

                var waiterEntered = new CountDownLatch(1);
                var waiterFinished = new CountDownLatch(1);
                var terminationObserved = new AtomicBoolean();
                var waiterFailure = new AtomicReference<Throwable>();
                waiter = Thread.ofPlatform().start(() -> {
                    waiterEntered.countDown();
                    try {
                        terminationObserved.set(
                                actor.awaitTermination(5, TimeUnit.SECONDS));
                    } catch (Throwable failure) {
                        waiterFailure.set(failure);
                    } finally {
                        waiterFinished.countDown();
                    }
                });
                assertTrue(waiterEntered.await(5, TimeUnit.SECONDS));
                assertFalse(waiterFinished.await(35, TimeUnit.MILLISECONDS),
                        "awaitTermination cannot pass while completion observers run");

                releaseObserver.countDown();
                assertTrue(waiterFinished.await(5, TimeUnit.SECONDS));
                assertNull(waiterFailure.get());
                assertTrue(terminationObserved.get(),
                        "physical finalization must eventually become observable");
            } finally {
                releaseObserver.countDown();
                stopper.join(5000);
                if (waiter != null) waiter.join(5000);
            }

            assertFalse(stopper.isAlive(), "stopper did not exit");
            assertNull(stopFailure.get(), "physical stop raised an exception");
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertTrue(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedPreSettlementTeardownRetainsBatchForSupervisorRetry()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            var registry = (java.util.Map<Object, Object>)
                    actorsField.get(runtime);
            Object cell = registry.get(actor.id());
            assertNotNull(cell);

            var hookField = cell.getClass().getDeclaredField(
                    "terminalTeardownFaultForTesting");
            hookField.setAccessible(true);
            var attempts = new AtomicInteger();
            hookField.set(cell, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected failure after terminal settlement publication");
                }
            });

            var batchField = cell.getClass().getDeclaredField(
                    "terminalSettlementBatch");
            var activeField = cell.getClass().getDeclaredField(
                    "terminalSettlementActive");
            batchField.setAccessible(true);
            activeField.setAccessible(true);

            var failure = assertThrows(OutOfMemoryError.class, actor::stop);
            assertTrue(failure.getMessage().contains("injected failure"));
            Object retainedOwner = batchField.get(cell);
            assertNotNull(retainedOwner,
                    "the already-sealed actor must retain exact terminal ownership");
            assertFalse(activeField.getBoolean(cell),
                    "failure must release exclusive settlement claim for retry");
            assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS));

            actor.stop();
            assertEquals(2, attempts.get(),
                    "the same batch must resume teardown preparation");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertNull(batchField.get(cell),
                    "a successful retry must consume its terminal settlement owner");
            assertFalse(activeField.getBoolean(cell));
            // A settled actor must not replay teardown on subsequent stops.
            actor.stop();
            assertEquals(2, attempts.get());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectorFreeCompletedFirstChannelCloseCanResumeTeardown()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var registryField = ActorRuntime.class.getDeclaredField("actors");
            registryField.setAccessible(true);
            var registry = (java.util.Map<Object, Object>)
                    registryField.get(runtime);
            Object cell = registry.get(actor.id());
            assertNotNull(cell);
            var faultField = cell.getClass().getDeclaredField(
                    "terminalTeardownAfterFirstCloseFaultForTesting");
            faultField.setAccessible(true);
            faultField.set(cell, (Runnable) () -> {
                throw new OutOfMemoryError(
                        "injected after proven selector-free mailbox close");
            });
            var batchField = cell.getClass().getDeclaredField(
                    "terminalSettlementBatch");
            batchField.setAccessible(true);
            var mailboxField = cell.getClass().getDeclaredField("mailbox");
            mailboxField.setAccessible(true);
            var mailbox = (ChannelRuntime.Channel<?>) mailboxField.get(cell);

            assertThrows(OutOfMemoryError.class, actor::stop);
            Object retained = batchField.get(cell);
            assertNotNull(retained);
            assertTrue(mailbox.closeSettledWithoutSelectorsForTeardown(),
                    "the original close returned normally without pending select arms");
            assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
            // A second injection is unrelated to replay safety. Remove it
            // without touching the batch's authoritative ambiguity marker.
            faultField.set(cell, null);
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertNull(batchField.get(cell),
                    "verified retry consumes the same durable settlement owner");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void channelCloseWithPendingSelectNeverForgesCompletionProof()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var registryField = ActorRuntime.class.getDeclaredField("actors");
            registryField.setAccessible(true);
            var registry = (java.util.Map<Object, Object>)
                    registryField.get(runtime);
            Object cell = registry.get(actor.id());
            assertNotNull(cell);
            var mailboxField = cell.getClass().getDeclaredField("mailbox");
            mailboxField.setAccessible(true);
            var mailbox = (ChannelRuntime.Channel<?>) mailboxField.get(cell);
            var registeredRead = mailbox.readAsync();
            assertFalse(registeredRead.isDone());
            assertEquals(1, mailbox.pendingRegistrations());

            var faultField = cell.getClass().getDeclaredField(
                    "terminalTeardownAfterFirstCloseFaultForTesting");
            faultField.setAccessible(true);
            faultField.set(cell, (Runnable) () -> {
                throw new OutOfMemoryError("injected after close with pending read");
            });
            var batchField = cell.getClass().getDeclaredField(
                    "terminalSettlementBatch");
            batchField.setAccessible(true);
            try {
                assertThrows(OutOfMemoryError.class, actor::stop);
                Object batch = batchField.get(cell);
                assertNotNull(batch);
                assertFalse(mailbox.closeSettledWithoutSelectorsForTeardown(),
                        "registration removal after close cannot forge pre-close proof");
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
                faultField.set(cell, null);
                var denial = assertThrows(IllegalStateException.class, actor::stop);
                assertTrue(denial.getMessage().contains("independent reconciliation"));
                assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
                assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));

                // No general completion-replay receipt exists for this
                // fixture. Test-only manual repair occurs only after checking
                // its registered read reached a terminal Future result and
                // all channel registrations have detached. Not product API.
                assertTrue(registeredRead.isDone());
                assertEquals(0, mailbox.pendingRegistrations());
                var ambiguousField = batch.getClass().getDeclaredField(
                        "physicalTeardownAmbiguous");
                ambiguousField.setAccessible(true);
                ambiguousField.setBoolean(batch, false);
                actor.stop();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            } finally {
                faultField.set(cell, null);
            }
        }
    }
    @Test
    @SuppressWarnings("unchecked")
    void finalizationFaultBeforeUnregisterRemainsRetryable() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var registryField = ActorRuntime.class.getDeclaredField("actors");
            registryField.setAccessible(true);
            var registry = (java.util.Map<Object, Object>)
                    registryField.get(runtime);
            Object cell = registry.get(actor.id());
            assertNotNull(cell);

            var faultField = cell.getClass().getDeclaredField(
                    "finalizationFaultForTesting");
            var activeField = cell.getClass().getDeclaredField(
                    "finalizationActive");
            var finalizedField = cell.getClass().getDeclaredField("finalized");
            faultField.setAccessible(true);
            activeField.setAccessible(true);
            finalizedField.setAccessible(true);

            var attempts = new AtomicInteger();
            faultField.set(cell, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected before actor unregister publication");
                }
            });

            var failure = assertThrows(OutOfMemoryError.class, actor::stop);
            assertTrue(failure.getMessage().contains("injected before actor unregister"));
            assertSame(cell, registry.get(actor.id()),
                    "failed finalization must keep the exact physical owner discoverable");
            assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
            assertFalse(activeField.getBoolean(cell),
                    "failed finalization must release its exclusive retry claim");
            assertFalse(finalizedField.getBoolean(cell),
                    "internal finalized state cannot publish before teardown completes");

            actor.stop();
            assertEquals(2, attempts.get(),
                    "retry must re-enter the same physical-finalization boundary");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertTrue(finalizedField.getBoolean(cell));
            assertFalse(activeField.getBoolean(cell));
            assertNull(registry.get(actor.id()));
        }
    }


    @Test
    @SuppressWarnings("unchecked")
    void finalizationFaultAfterUnregisterRetainsRetryOwnerAndExitHookExactlyOnce()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var exitHookCalls = new AtomicInteger();
            runtime.setActorExitHook(ignored -> exitHookCalls.incrementAndGet());

            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var registryField = ActorRuntime.class.getDeclaredField("actors");
            registryField.setAccessible(true);
            var registry = (java.util.Map<Object, Object>)
                    registryField.get(runtime);
            Object cell = registry.get(actor.id());
            assertNotNull(cell);

            var afterUnregisterFault = cell.getClass().getDeclaredField(
                    "finalizationAfterUnregisterFaultForTesting");
            afterUnregisterFault.setAccessible(true);
            var recoveryField = actor.getClass().getDeclaredField(
                    "physicalFinalizationRecoveryCell");
            recoveryField.setAccessible(true);

            var attempts = new AtomicInteger();
            afterUnregisterFault.set(cell, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected after actor unregister publication");
                }
            });

            var failure = assertThrows(OutOfMemoryError.class, actor::stop);
            assertTrue(failure.getMessage().contains(
                    "injected after actor unregister publication"));
            assertNull(registry.get(actor.id()),
                    "fault is deliberately after primary-registry removal");
            assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor),
                    "registry absence must not certify physical retirement");
            assertFalse(actor.awaitTermination(0, TimeUnit.NANOSECONDS));
            assertSame(cell, recoveryField.get(actor),
                    "ActorRef must retain the exact cell for supervisor retry");
            assertEquals(1, exitHookCalls.get(),
                    "first finalization attempt invokes actor exit hook once");

            actor.stop();

            assertEquals(2, attempts.get(),
                    "retry must cross the post-unregister boundary again");
            assertEquals(1, exitHookCalls.get(),
                    "retry must not duplicate the documented exactly-once exit hook");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertNull(recoveryField.get(actor),
                    "successful physical finalization must release recovery ownership");
        }
    }


    @Test
    @SuppressWarnings("unchecked")
    void concurrentPostUnregisterRetriesPublishOnePhysicalWitness() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var exitHookCalls = new AtomicInteger();
            runtime.setActorExitHook(ignored -> exitHookCalls.incrementAndGet());

            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var registryField = ActorRuntime.class.getDeclaredField("actors");
            registryField.setAccessible(true);
            var registry = (java.util.Map<Object, Object>) registryField.get(runtime);
            Object cell = registry.get(actor.id());
            assertNotNull(cell);

            var faultField = cell.getClass().getDeclaredField(
                    "finalizationAfterUnregisterFaultForTesting");
            faultField.setAccessible(true);
            var attempts = new AtomicInteger();
            faultField.set(cell, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "inject one post-unregister recovery race");
                }
            });

            assertThrows(OutOfMemoryError.class, actor::stop);
            assertNull(registry.get(actor.id()));
            assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor));
            assertEquals(1, exitHookCalls.get());

            int workerCount = 12;
            var start = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread[] workers = new Thread[workerCount];
            for (int i = 0; i < workerCount; i++) {
                workers[i] = Thread.ofPlatform().unstarted(() -> {
                    try {
                        start.await();
                        actor.stop();
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    }
                });
                workers[i].start();
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join(5000);
                assertFalse(worker.isAlive(), "concurrent stop retry stalled");
            }

            assertNull(failure.get(),
                    "competing supervisors must converge on one finalization witness");
            assertEquals(2, attempts.get(),
                    "only the failed attempt and one recovery finalizer may cross the boundary");
            assertEquals(1, exitHookCalls.get(),
                    "concurrent retry must preserve exactly-once exit-hook invocation");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));

            var countField = ActorRuntime.class.getDeclaredField("actorCount");
            countField.setAccessible(true);
            var count = (AtomicInteger) countField.get(runtime);
            assertEquals(0, count.get(),
                    "concurrent retry cannot double-debit the physical actor slot");

            var recoveryField = actor.getClass().getDeclaredField(
                    "physicalFinalizationRecoveryCell");
            recoveryField.setAccessible(true);
            assertNull(recoveryField.get(actor),
                    "the emergency retry owner must clear after the one physical witness");
        }
    }

    @Test
    void terminalObserverStopCannotSelfJoinPhysicalFinalization() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var callbackEntered = new CountDownLatch(1);
            var nestedStopFailure = new AtomicReference<Throwable>();
            actor.getDone().whenCompleteRuntime((ignored, failure) -> {
                callbackEntered.countDown();
                try {
                    actor.stop();
                    nestedStopFailure.set(new AssertionError(
                            "terminal observer unexpectedly joined its own finalization"));
                } catch (Throwable rejected) {
                    nestedStopFailure.set(rejected);
                }
            });

            actor.stop();
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            Throwable rejected = nestedStopFailure.get();
            assertInstanceOf(IllegalStateException.class, rejected);
            assertTrue(rejected.getMessage().contains(
                    "cannot join its own physical finalization"));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
        }
    }




    @Test
    @SuppressWarnings("unchecked")
    void registryRemovalAfterMutationCompletesFinalizationAndDebitsSlotOnce()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(
                    () -> (message, context) -> { });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var registryField = ActorRuntime.class.getDeclaredField("actors");
            registryField.setAccessible(true);
            var original = (java.util.Map<Object, Object>) registryField.get(runtime);
            var faultedRemoval = new AtomicBoolean();
            var faulty = new java.util.concurrent.ConcurrentHashMap<Object, Object>() {
                @Override public boolean remove(Object key, Object value) {
                    boolean removed = super.remove(key, value);
                    if (removed && faultedRemoval.compareAndSet(false, true)) {
                        throw new OutOfMemoryError(
                                "injected physical unregister after removal");
                    }
                    return removed;
                }
            };
            faulty.putAll(original);
            registryField.set(runtime, faulty);
            try {
                actor.stop();
                assertTrue(faultedRemoval.get(),
                        "fixture must inject a failure after exact registry deletion");
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                        "a verified after-removal error cannot strand physical completion");
                assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
                assertTrue(faulty.isEmpty(),
                        "physical actor identity must be absent after finalization");
                var slotField = ActorRuntime.class.getDeclaredField("actorCount");
                slotField.setAccessible(true);
                var count = (AtomicInteger) slotField.get(runtime);
                assertEquals(0, count.get(),
                        "the physical actor's slot is debited exactly once");
                actor.stop();
                assertEquals(0, count.get(),
                        "repeated host stop cannot double-debit the actor slot");
            } finally {
                // Preserve all surviving registry identities if the injected
                // fixture fails an assertion; do not restore a stale copy.
                original.clear();
                original.putAll(faulty);
                registryField.set(runtime, original);
            }
        }
    }

    @Test
    void sealedPrivateMemorySliceResumesCleanupBeforePhysicalFinalization()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
            var blockRef = new AtomicReference<ActorRuntime.PrivateMemoryBlock>();

            var actor = runtime.<Integer>spawnPrivateTrusted(context -> {
                var slice = context.privateMemory().orElseThrow();
                var block = slice.allocatePrivateBytes(64);
                block.writeLong(0, 0x0102030405060708L);
                sliceRef.set(slice);
                blockRef.set(block);
                return (message, actorContext) -> { };
            });
            actor.getReady().get(5, TimeUnit.SECONDS);

            var slice = sliceRef.get();
            var block = blockRef.get();
            assertNotNull(slice);
            assertNotNull(block);
            assertEquals(64L, slice.usedBytes());
            long baselinePrivateBytes =
                    runtime.privateMemoryBytes() - slice.usedBytes();
            assertTrue(baselinePrivateBytes >= 0L);

            var memoryField = block.getClass().getDeclaredField("memory");
            memoryField.setAccessible(true);
            var originalBacking =
                    (java.nio.ByteBuffer) memoryField.get(block);
            assertNotNull(originalBacking);

            var faultField = slice.getClass().getDeclaredField(
                    "closeAfterSealFaultForTesting");
            faultField.setAccessible(true);
            var attempts = new AtomicInteger();
            faultField.set(slice, (Runnable) () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new OutOfMemoryError(
                            "injected after private-memory slice seal");
                }
            });

            var failure = assertThrows(OutOfMemoryError.class, actor::stop);
            assertTrue(failure.getMessage().contains(
                    "injected after private-memory slice seal"));
            assertTrue(slice.closed(),
                    "allocation must stay sealed after failed teardown");
            assertEquals(64L, slice.usedBytes(),
                    "failed cleanup must retain exact quota refund authority");
            assertEquals(baselinePrivateBytes + 64L,
                    runtime.privateMemoryBytes(),
                    "runtime debit cannot publish before slice cleanup succeeds");
            assertThrows(IllegalStateException.class, () -> block.readLong(0),
                    "sealed slice must deny guest access while cleanup is pending");
            assertFalse(runtime.physicallyFinalizedForHotUpgrade(actor),
                    "sealed-but-unclean private memory cannot certify retirement");

            actor.stop();

            assertEquals(2, attempts.get(),
                    "physical finalization must retry the same slice cleanup");
            assertTrue(block.closed());
            assertNull(memoryField.get(block),
                    "successful retry must detach the direct backing memory");
            var zeroed = originalBacking.duplicate();
            zeroed.clear();
            while (zeroed.hasRemaining()) {
                assertEquals((byte) 0, zeroed.get(),
                        "private direct memory must be zeroed before retirement");
            }
            assertEquals(0L, slice.usedBytes());
            assertEquals(baselinePrivateBytes, runtime.privateMemoryBytes(),
                    "successful retry must refund private runtime quota exactly once");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(runtime.physicallyFinalizedForHotUpgrade(actor));
        }
    }

}
