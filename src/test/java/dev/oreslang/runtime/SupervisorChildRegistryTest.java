package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Identity bookkeeping only; actor finalization and respawn are host duties. */
final class SupervisorChildRegistryTest {
    @Test
    void logicalIdentitySurvivesReplacementWhileActorIdentityAdvances() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var firstActor = ActorRuntime.ActorId.create();
        var first = registry.register(firstActor);
        assertEquals(1, first.incarnation());
        assertEquals(SupervisorChildRegistry.Phase.ACTIVE, first.phase());

        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(first.child(), firstActor, 1),
                "cannot reserve replacement before physical-finalization admission");
        registry.finalized(first.child(), firstActor, 1);
        var reservation = registry.beginRestart(first.child(), firstActor, 1);
        assertEquals(SupervisorChildRegistry.Phase.RESTARTING,
                registry.snapshot(first.child()).phase());
        assertThrows(IllegalStateException.class,
                () -> registry.beginRestart(first.child(), firstActor, 1),
                "a competing restart must not be allowed to spawn");
        assertThrows(IllegalStateException.class,
                () -> registry.stop(first.child(), firstActor, 1),
                "STOP cannot overtake an active restart reservation");
        var nextActor = ActorRuntime.ActorId.create();
        assertThrows(IllegalStateException.class, () -> registry.replace(reservation, nextActor));
        registry.stageReplacement(reservation, nextActor);
        assertThrows(IllegalStateException.class, () -> registry.replace(reservation, nextActor));
        assertThrows(IllegalStateException.class, () ->
                registry.acknowledgeReplacementAdmission(reservation, nextActor, () -> false));
        registry.acknowledgeReplacementAdmission(reservation, nextActor, () -> true);
        var next = registry.replace(reservation, nextActor);
        assertThrows(IllegalStateException.class,
                () -> registry.replace(reservation, ActorRuntime.ActorId.create()),
                "committed reservation cannot replay");
        assertEquals(first.child(), next.child(), "logical identity must remain stable");
        assertNotEquals(first.actor(), next.actor(), "replacement needs a fresh ActorId");
        assertEquals(2, next.incarnation());
        assertEquals(SupervisorChildRegistry.Phase.ACTIVE, next.phase());
        assertThrows(IllegalStateException.class,
                () -> registry.finalized(first.child(), firstActor, 1),
                "a stale finalizer must not finalize the replacement");
        assertThrows(IllegalStateException.class, () -> registry.stop(
                first.child(), firstActor, 1),
                "an old failure cannot stop the new actor incarnation");

        registry.finalized(next.child(), nextActor, 2);
        registry.stop(next.child(), nextActor, 2);
        assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                registry.snapshot(next.child()).phase());
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(next.child(), nextActor, 2));
        registry.retireStopped(next.child());
        assertEquals(0, registry.trackedChildren());
        assertThrows(IllegalStateException.class,
                () -> registry.snapshot(next.child()),
                "retired child IDs may never reactivate");
    }

    @Test
    void rejectsCrossSupervisorIdentityAndDuplicateActiveActor() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        var registry = new SupervisorChildRegistry(supervisor, group);
        var foreign = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var first = registry.register(actor);
        assertThrows(IllegalStateException.class, () -> registry.register(actor));
        assertThrows(SecurityException.class,
                () -> foreign.snapshot(first.child()));
        // Two registry instances for one owner still mint disjoint UUIDs;
        // old child keys cannot address a newly created registry (ABA).
        var replacementRegistry = new SupervisorChildRegistry(supervisor, group);
        var otherInstance = replacementRegistry.register(ActorRuntime.ActorId.create());
        assertNotEquals(first.child(), otherInstance.child());
        assertThrows(IllegalStateException.class,
                () -> registry.snapshot(otherInstance.child()));
        assertThrows(IllegalStateException.class,
                () -> replacementRegistry.snapshot(first.child()));
        assertThrows(IllegalStateException.class,
                () -> registry.finalized(first.child(), ActorRuntime.ActorId.create(), 1));

        registry.finalized(first.child(), actor, 1);
        var permit = registry.beginRestart(first.child(), actor, 1);
        assertThrows(IllegalStateException.class,
                () -> registry.replace(permit, actor),
                "same ActorId must not masquerade as replacement");
        var other = registry.register(ActorRuntime.ActorId.create());
        assertThrows(IllegalStateException.class,
                () -> registry.replace(permit, other.actor()),
                "cannot steal an active actor belonging to another logical child");
        registry.abortRestart(permit);
        assertThrows(IllegalStateException.class,
                () -> registry.replace(permit, ActorRuntime.ActorId.create()),
                "aborted reservation must never reactivate");
    }

    @Test
    void failedRestartAbortRestoresFinalizedButFencesOldPermit() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var oldActor = ActorRuntime.ActorId.create();
        var first = registry.register(oldActor);
        registry.finalized(first.child(), oldActor, 1);
        var previous = registry.beginRestart(first.child(), oldActor, 1);
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(first.child(), oldActor, 1));
        registry.abortRestart(previous);
        assertEquals(SupervisorChildRegistry.Phase.FINALIZED,
                registry.snapshot(first.child()).phase());
        var current = registry.beginRestart(first.child(), oldActor, 1);
        assertNotEquals(previous.nonce(), current.nonce());
        assertThrows(IllegalStateException.class, () ->
                registry.abortRestart(previous));
        assertThrows(IllegalStateException.class, () ->
                registry.replace(previous, ActorRuntime.ActorId.create()));
        var replacement = ActorRuntime.ActorId.create();
        registry.stageReplacement(current, replacement);
        registry.acknowledgeReplacementAdmission(current, replacement, () -> true);
        registry.replace(current, replacement);
        assertEquals(2, registry.snapshot(first.child()).incarnation());
        registry.finalized(first.child(), replacement, 2);
        var reuse = registry.beginRestart(first.child(), replacement, 2);
        assertThrows(IllegalStateException.class,
                () -> registry.replace(reuse, oldActor),
                "A -> B -> A actor ABA must not be accepted");
        registry.abortRestart(reuse);
        assertEquals(2, registry.trackedActorIdentities());
    }

    @Test
    void admissionRollbackDoesNotRecycleRetiredActorIdentity() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var admitted = registry.register(actor);
        assertThrows(IllegalStateException.class,
                () -> registry.detachActive(admitted.child(), ActorRuntime.ActorId.create(), 1));
        registry.detachActive(admitted.child(), actor, 1);
        assertEquals(0, registry.trackedChildren());
        assertEquals(1, registry.trackedActorIdentities(),
                "rollback must preserve actor-identity replay fence");
        assertThrows(IllegalStateException.class, () -> registry.register(actor));
        assertThrows(IllegalStateException.class,
                () -> registry.finalized(admitted.child(), actor, 1));
        assertThrows(IllegalStateException.class,
                () -> registry.detachActive(admitted.child(), actor, 1));
    }

    @Test
    void reservedStopExcludesRestartAndStaleAcknowledgments() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var first = registry.register(actor);
        registry.finalized(first.child(), actor, 1);

        var stop = registry.beginStop(first.child(), actor, 1, 17);
        assertEquals(SupervisorChildRegistry.Phase.STOPPING,
                registry.snapshot(first.child()).phase());
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(first.child(), actor, 1));
        assertThrows(IllegalStateException.class, () ->
                registry.beginStop(first.child(), actor, 1, 18));
        assertThrows(IllegalStateException.class, () ->
                registry.stop(first.child(), actor, 1));
        var wrong = new SupervisorChildRegistry.StopReservation(
                first.child(), actor, 1, 17, java.util.UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> registry.commitStop(wrong));
        assertEquals(SupervisorChildRegistry.Phase.STOPPING,
                registry.snapshot(first.child()).phase(),
                "a forged acknowledgement must not release the reservation");

        assertThrows(IllegalStateException.class, () -> registry.markStopApplied(stop),
                "an uninvoked host effect cannot assert APPLIED");
        assertThrows(IllegalStateException.class, () -> registry.acknowledgeHostStop(stop),
                "a host acknowledgment cannot skip the original invocation");
        registry.claimHostStopInvocation(stop);
        registry.markStopApplied(stop);
        assertThrows(IllegalStateException.class, () -> registry.markStopUncertain(stop),
                "a verified host effect must never be downgraded to uncertain");
        registry.commitStop(stop);
        assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                registry.snapshot(first.child()).phase());
        assertThrows(IllegalStateException.class, () -> registry.commitStop(stop));
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(first.child(), actor, 1));
    }

    @Test
    void concurrentStopAndRestartReservationsHaveExactlyOneWinner() throws Exception {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var first = registry.register(actor);
        registry.finalized(first.child(), actor, 1);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var stopTask = pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("race timed out");
                    registry.beginStop(first.child(), actor, 1, 19);
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            var restartTask = pool.submit(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("race timed out");
                    registry.beginRestart(first.child(), actor, 1);
                    successes.incrementAndGet();
                } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            stopTask.get(5, java.util.concurrent.TimeUnit.SECONDS);
            restartTask.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, successes.get(), "STOP and RESTART must never both own a turn");
        assertEquals(1, failures.get());
        assertTrue(registry.snapshot(first.child()).phase()
                == SupervisorChildRegistry.Phase.STOPPING
                || registry.snapshot(first.child()).phase()
                == SupervisorChildRegistry.Phase.RESTARTING);
    }

    @Test
    void concurrentReplacementOfSameGenerationHasSingleWinner() throws Exception {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var first = registry.register(actor);
        registry.finalized(first.child(), actor, 1);

        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var success = new java.util.concurrent.atomic.AtomicInteger();
        var failed = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i ->
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("race barrier timed out");
                            var permit = registry.beginRestart(first.child(), actor, 1);
                            var newActor = ActorRuntime.ActorId.create();
                            registry.stageReplacement(permit, newActor);
                            registry.acknowledgeReplacementAdmission(permit, newActor, () -> true);
                            registry.replace(permit, newActor);
                            success.incrementAndGet();
                        } catch (IllegalStateException expected) {
                            failed.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    })).toList();
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, success.get(), "only one replacement can win generation 1");
        assertEquals(1, failed.get());
        assertEquals(2, registry.snapshot(first.child()).incarnation());
    }
    @Test
    void reconciledStopCannotReactivateChild() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var child = registry.register(actor);
        registry.finalized(child.child(), actor, 1);
        var permit = registry.beginStop(child.child(), actor, 1, 42);
        registry.markStopUncertain(permit);
        assertEquals(1, registry.unresolvedStops().size());
        registry.reconcileVerifiedStop(permit);
        assertTrue(registry.unresolvedStops().isEmpty());
        assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                registry.reconcileVerifiedStop(permit).phase());
        assertThrows(IllegalStateException.class, () -> registry.beginRestart(child.child(), actor, 1));
    }

    @Test
    void concurrentReconciliationCannotBeDowngradedByLateUncertainty() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 64; i++) {
                var registry = new SupervisorChildRegistry(
                        ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
                var actor = ActorRuntime.ActorId.create();
                var child = registry.register(actor);
                registry.finalized(child.child(), actor, 1);
                var permit = registry.beginStop(child.child(), actor, 1, i + 1L);
                var start = new java.util.concurrent.CountDownLatch(1);
                var reconcile = pool.submit(() -> {
                    start.await();
                    return registry.reconcileVerifiedStop(permit);
                });
                var uncertain = pool.submit(() -> {
                    start.await();
                    try { registry.markStopUncertain(permit); }
                    catch (IllegalStateException expectedAfterReconcile) { }
                    return null;
                });
                start.countDown();
                assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                        reconcile.get(5, java.util.concurrent.TimeUnit.SECONDS).phase());
                uncertain.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(SupervisorChildRegistry.StopReceiptStatus.ACKNOWLEDGED,
                        registry.stopReceipt(child.child()).status());
                assertTrue(registry.unresolvedStops().isEmpty());
            }
        }
    }

    @Test
    void originalHostAcknowledgmentAndReconcilerHaveOneTerminalDecision()
            throws Exception {
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 64; i++) {
                var registry = new SupervisorChildRegistry(
                        ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
                var actor = ActorRuntime.ActorId.create();
                var child = registry.register(actor);
                registry.finalized(child.child(), actor, 1);
                var ticket = registry.beginStop(child.child(), actor, 1, i + 100L);
                // Simulate the already-entered host callback before racing its
                // returned success against an independent authoritative receipt.
                registry.claimHostStopInvocation(ticket);
                var start = new java.util.concurrent.CountDownLatch(1);
                var original = workers.submit(() -> {
                    start.await();
                    return registry.acknowledgeHostStop(ticket);
                });
                var reconciler = workers.submit(() -> {
                    start.await();
                    return registry.reconcileVerifiedStop(ticket);
                });
                start.countDown();
                assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                        original.get(5, java.util.concurrent.TimeUnit.SECONDS).phase());
                assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                        reconciler.get(5, java.util.concurrent.TimeUnit.SECONDS).phase());
                assertEquals(SupervisorChildRegistry.StopReceiptStatus.ACKNOWLEDGED,
                        registry.stopReceipt(child.child()).status());
                assertTrue(registry.unresolvedStops().isEmpty());
                assertThrows(IllegalStateException.class, () ->
                        registry.beginRestart(child.child(), actor, 1));
            }
        }
    }


    @Test
    void restartAdmissionAndCleanupArePhysicallyFenced() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var failed = ActorRuntime.ActorId.create();
        var child = registry.register(failed);
        registry.finalized(child.child(), failed, 1);
        var permit = registry.beginRestart(child.child(), failed, 1);
        var candidate = ActorRuntime.ActorId.create();
        registry.stageReplacement(permit, candidate);
        assertThrows(IllegalStateException.class, () -> registry.abortRestart(permit));
        assertThrows(IllegalStateException.class, () -> registry.replace(permit, candidate));
        assertThrows(IllegalStateException.class, () ->
                registry.acknowledgeReplacementCleanup(permit, candidate, () -> false));
        assertEquals(SupervisorChildRegistry.Phase.RESTARTING,
                registry.snapshot(child.child()).phase());
        registry.acknowledgeReplacementAdmission(permit, candidate, () -> true);
        assertThrows(IllegalStateException.class, () -> registry.abortRestart(permit));
        registry.acknowledgeReplacementCleanup(permit, candidate, () -> true);
        assertThrows(IllegalStateException.class, () -> registry.replace(permit, candidate));
        registry.abortRestart(permit);
        assertEquals(SupervisorChildRegistry.Phase.FINALIZED,
                registry.snapshot(child.child()).phase());
        assertThrows(IllegalStateException.class, () -> registry.replace(permit, candidate));
    }

    @Test
    void closedSupervisorRejectsLateReplacementPublication() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var failed = ActorRuntime.ActorId.create();
        var child = registry.register(failed);
        registry.finalized(child.child(), failed, 1);
        var permit = registry.beginRestart(child.child(), failed, 1);
        var candidate = ActorRuntime.ActorId.create();
        registry.stageReplacement(permit, candidate);
        registry.acknowledgeReplacementAdmission(permit, candidate, () -> true);
        registry.closeRestartAdmission();
        assertThrows(IllegalStateException.class, () -> registry.replace(permit, candidate));
        assertThrows(IllegalStateException.class, () -> registry.abortRestart(permit));
        registry.acknowledgeReplacementCleanup(permit, candidate, () -> true);
        registry.abortRestart(permit);
        assertThrows(IllegalStateException.class, () ->
                registry.beginRestart(child.child(), failed, 1));
    }


    @Test
    void restartAdmissionVerifierCannotPublishAfterConcurrentGroupClose() throws Exception {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var dying = ActorRuntime.ActorId.create();
        var child = registry.register(dying);
        registry.finalized(child.child(), dying, 1);
        var claim = registry.beginRestart(child.child(), dying, 1);
        var candidate = ActorRuntime.ActorId.create();
        registry.stageReplacement(claim, candidate);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try {
                registry.acknowledgeReplacementAdmission(claim, candidate, () -> {
                    // This callback must execute WITHOUT the registry monitor.
                    assertEquals(SupervisorChildRegistry.Phase.RESTARTING,
                            registry.snapshot(child.child()).phase());
                    entered.countDown();
                    try {
                        if (!finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            throw new IllegalStateException("proof gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                    return true;
                });
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
        worker.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            registry.closeRestartAdmission();
        } finally {
            finish.countDown();
        }
        worker.join(5000);
        assertFalse(worker.isAlive(), "admission verifier deadlocked the registry");
        assertInstanceOf(IllegalStateException.class, result.get());
        assertThrows(IllegalStateException.class, () -> registry.replace(claim, candidate));
        assertThrows(IllegalStateException.class, () -> registry.abortRestart(claim));
        registry.acknowledgeReplacementCleanup(claim, candidate, () -> true);
        registry.abortRestart(claim);
    }


    @Test
    void matchesSharedVerifiedRestartRefinementTrace() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var trace = new java.util.ArrayList<String>();
        var old = ActorRuntime.ActorId.create();
        var child = registry.register(old);
        trace.add("ACTIVE");
        registry.finalized(child.child(), old, 1);
        trace.add("FINALIZED");
        var first = registry.beginRestart(child.child(), old, 1);
        trace.add("RESTARTING");
        var next = ActorRuntime.ActorId.create();
        registry.stageReplacement(first, next);
        trace.add("SPAWNED");
        assertThrows(IllegalStateException.class, () -> registry.replace(first, next));
        registry.acknowledgeReplacementAdmission(first, next, () -> true);
        trace.add("ADMITTED");
        var activated = registry.replace(first, next);
        assertEquals(2, activated.incarnation());
        trace.add("ACTIVE");
        registry.finalized(activated.child(), next, 2);
        trace.add("FINALIZED");
        var retry = registry.beginRestart(activated.child(), next, 2);
        trace.add("RESTARTING");
        var candidate = ActorRuntime.ActorId.create();
        registry.stageReplacement(retry, candidate);
        trace.add("SPAWNED");
        assertThrows(IllegalStateException.class, () -> registry.abortRestart(retry));
        registry.acknowledgeReplacementCleanup(retry, candidate, () -> true);
        trace.add("CLEANED");
        registry.abortRestart(retry);
        trace.add("FINALIZED");
        var expected = "ACTIVE>FINALIZED>RESTARTING>SPAWNED>ADMITTED>"
                + "ACTIVE>FINALIZED>RESTARTING>SPAWNED>CLEANED>FINALIZED";
        assertEquals(expected, String.join(">", trace));
        System.out.println("SUPERVISOR_RESTART_TRACE_V1=" + expected);
    }


    @Test
    void onePhysicalReplacementCannotBeStagedForTwoLogicalChildren() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actorA = ActorRuntime.ActorId.create();
        var actorB = ActorRuntime.ActorId.create();
        var a = registry.register(actorA);
        var b = registry.register(actorB);
        registry.finalized(a.child(), actorA, 1);
        registry.finalized(b.child(), actorB, 1);
        var ticketA = registry.beginRestart(a.child(), actorA, 1);
        var ticketB = registry.beginRestart(b.child(), actorB, 1);
        var candidate = ActorRuntime.ActorId.create();
        registry.stageReplacement(ticketA, candidate);
        assertThrows(IllegalStateException.class, () ->
                registry.stageReplacement(ticketB, candidate));
        assertThrows(IllegalStateException.class, () -> registry.register(candidate));
        assertThrows(IllegalStateException.class, () -> registry.replace(ticketA, candidate));
        registry.acknowledgeReplacementAdmission(ticketA, candidate, () -> true);
        registry.replace(ticketA, candidate);
        assertThrows(IllegalStateException.class, () ->
                registry.stageReplacement(ticketB, candidate));
        registry.abortRestart(ticketB);
    }


    @Test
    void physicallySpawnedButAbortedActorIdIsPermanentlyRetired() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var old = ActorRuntime.ActorId.create();
        var child = registry.register(old);
        registry.finalized(child.child(), old, 1);
        var claim = registry.beginRestart(child.child(), old, 1);
        var spawned = ActorRuntime.ActorId.create();
        registry.stageReplacement(claim, spawned);
        registry.acknowledgeReplacementCleanup(claim, spawned, () -> true);
        registry.abortRestart(claim);
        assertThrows(IllegalStateException.class, () -> registry.register(spawned),
                "an aborted physical candidate must not become a new child");
        var retry = registry.beginRestart(child.child(), old, 1);
        assertThrows(IllegalStateException.class, () ->
                registry.stageReplacement(retry, spawned),
                "a stale physical identity must not be staged again");
        registry.abortRestart(retry);
    }


    @Test
    void actualRegistryTransitionsAreOrderedAndExcludeRejectedActions() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var initialActor = ActorRuntime.ActorId.create();
        var child = registry.register(initialActor);
        registry.finalized(child.child(), initialActor, 1);
        var claim = registry.beginRestart(child.child(), initialActor, 1);
        var staged = ActorRuntime.ActorId.create();
        registry.stageReplacement(claim, staged);
        assertThrows(IllegalStateException.class, () -> registry.replace(claim, staged));
        registry.acknowledgeReplacementAdmission(claim, staged, () -> true);
        var replacement = registry.replace(claim, staged);
        registry.finalized(replacement.child(), staged, 2);
        var retry = registry.beginRestart(replacement.child(), staged, 2);
        var doomed = ActorRuntime.ActorId.create();
        registry.stageReplacement(retry, doomed);
        assertThrows(IllegalStateException.class, () -> registry.abortRestart(retry));
        registry.acknowledgeReplacementCleanup(retry, doomed, () -> true);
        registry.abortRestart(retry);
        var entries = registry.committedTransitions();
        var operations = entries.stream().map(
                SupervisorChildRegistry.Transition::operation).toList();
        var expected = java.util.List.of("register", "finalize-asserted", "restart.reserve",
                "restart.spawn-asserted", "restart.admit-verified", "restart.commit",
                "finalize-asserted", "restart.reserve", "restart.spawn-asserted",
                "restart.cleanup-verified", "restart.abort");
        assertEquals(expected, operations,
                "only actual committed registry mutations belong to the journal");
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(i + 1L, entries.get(i).sequence());
            assertEquals(child.child(), entries.get(i).state().child());
        }
        assertEquals(claim.nonce(), entries.get(2).restartPermit());
        assertNull(entries.get(2).stopPermit(),
                "a restart reservation cannot masquerade as a STOP ticket");
        assertEquals(claim.nonce(), entries.get(5).restartPermit());
        assertEquals(retry.nonce(), entries.get(7).restartPermit());
        assertEquals(retry.nonce(), entries.get(10).restartPermit());
        assertNotEquals(claim.nonce(), retry.nonce());
        assertEquals(staged, entries.get(5).state().actor());
        assertEquals(2, entries.get(5).state().incarnation());
        assertTrue(registry.auditTraceComplete());
        assertThrows(UnsupportedOperationException.class, () -> entries.clear());
        System.out.println("SUPERVISOR_REGISTRY_TRANSITIONS_V1="
                + String.join(">", operations));
    }

    @Test
    void uncertainStopJournalNeverFakesInvocationOrExternalProof() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var child = registry.register(actor);
        registry.finalized(child.child(), actor, 1);
        var permit = registry.beginStop(child.child(), actor, 1, 43);
        registry.markStopUncertain(permit);
        assertThrows(IllegalStateException.class, () -> registry.commitStop(permit));
        registry.markStopUncertain(permit); // idempotent, not a new transition
        assertEquals(java.util.List.of("register", "finalize-asserted", "stop.reserve",
                "stop.uncertain"), registry.committedTransitions().stream()
                .map(SupervisorChildRegistry.Transition::operation).toList());
        assertEquals(43, registry.committedTransitions().get(3).stopTurn());
        assertNull(registry.committedTransitions().get(2).restartPermit(),
                "a STOP nonce must not masquerade as a restart capability");
        assertEquals(permit.nonce(), registry.committedTransitions().get(2).stopPermit(),
                "STOP reservation must retain its exact independent nonce");
        assertNull(registry.committedTransitions().get(3).restartPermit(),
                "uncertain STOP must not expose a restart permit");
        assertEquals(permit.nonce(), registry.committedTransitions().get(3).stopPermit(),
                "uncertain STOP must preserve the original nonce");
        assertEquals(SupervisorChildRegistry.Phase.STOPPING,
                registry.snapshot(child.child()).phase());
        registry.reconcileVerifiedStop(permit); // host proof is external
        assertEquals("stop.ack-reconciled",
                registry.committedTransitions().get(4).operation());
        assertEquals(5, registry.committedTransitions().size());
        assertNull(registry.committedTransitions().get(4).restartPermit());
        assertEquals(permit.nonce(), registry.committedTransitions().get(4).stopPermit(),
                "reconciled STOP must use the same authorized receipt identity");
    }

    @Test
    void registryJournalAndRestartPermitTypesAreNotPublicGuestApis() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isPublic(
                SupervisorChildRegistry.Transition.class.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(SupervisorChildRegistry.class
                .getDeclaredMethod("committedTransitions").getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isPublic(SupervisorChildRegistry.class
                .getDeclaredMethod("auditTraceComplete").getModifiers()));
    }

    @Test
    void closeFencesReplacementAndJournalRecordsOnlySuccessfulCleanup() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var child = registry.register(actor);
        registry.finalized(child.child(), actor, 1);
        var permit = registry.beginRestart(child.child(), actor, 1);
        var candidate = ActorRuntime.ActorId.create();
        registry.stageReplacement(permit, candidate);
        registry.acknowledgeReplacementAdmission(permit, candidate, () -> true);
        registry.closeRestartAdmission();
        assertThrows(IllegalStateException.class, () -> registry.replace(permit, candidate));
        assertThrows(IllegalStateException.class, () -> registry.abortRestart(permit));
        registry.acknowledgeReplacementCleanup(permit, candidate, () -> true);
        registry.abortRestart(permit);
        assertEquals(java.util.List.of("register", "finalize-asserted",
                "restart.reserve", "restart.spawn-asserted", "restart.admit-verified",
                "restart.close", "restart.cleanup-verified", "restart.abort"),
                registry.committedTransitions().stream()
                        .map(SupervisorChildRegistry.Transition::operation).toList());
    }
    @Test
    void hostAcknowledgmentRequiresClaimButIndependentReconciliationStillWorks() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var failed = ActorRuntime.ActorId.create();
        var child = registry.register(failed);
        registry.finalized(child.child(), failed, 1);
        var receipt = registry.beginStop(child.child(), failed, 1, 82);
        assertThrows(IllegalStateException.class,
                () -> registry.acknowledgeHostStop(receipt));
        assertThrows(IllegalStateException.class,
                () -> registry.markStopApplied(receipt));
        assertEquals(SupervisorChildRegistry.StopReceiptStatus.PENDING,
                registry.stopReceipt(child.child()).status());
        assertEquals(SupervisorChildRegistry.Phase.STOPPING,
                registry.snapshot(child.child()).phase());
        registry.claimHostStopInvocation(receipt);
        assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                registry.acknowledgeHostStop(receipt).phase());
        assertEquals(SupervisorChildRegistry.StopReceiptStatus.ACKNOWLEDGED,
                registry.stopReceipt(child.child()).status());

        var separate = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var physical = ActorRuntime.ActorId.create();
        var logical = separate.register(physical);
        separate.finalized(logical.child(), physical, 1);
        var pending = separate.beginStop(logical.child(), physical, 1, 13);
        separate.markStopUncertain(pending);
        // A crash may prevent recording the invocation itself. Only an
        // independently verified effect (outside this registry) may resolve it.
        assertEquals(SupervisorChildRegistry.Phase.STOPPED,
                separate.reconcileVerifiedStop(pending).phase());
        assertEquals(SupervisorChildRegistry.StopReceiptStatus.ACKNOWLEDGED,
                separate.stopReceipt(logical.child()).status());
    }

    @Test
    void stopHostInvocationMustBeClaimedOnlyOnceForExactTurn() {
        var registry = new SupervisorChildRegistry(
                ActorRuntime.SupervisorId.create(), ActorRuntime.ActorGroupId.create());
        var actor = ActorRuntime.ActorId.create();
        var child = registry.register(actor);
        registry.finalized(child.child(), actor, 1);
        var permit = registry.beginStop(child.child(), actor, 1, 91);
        var foreign = new SupervisorChildRegistry.StopReservation(
                permit.child(), actor, 1, 91, java.util.UUID.randomUUID());
        assertThrows(IllegalStateException.class,
                () -> registry.claimHostStopInvocation(foreign));
        registry.claimHostStopInvocation(permit);
        assertThrows(IllegalStateException.class,
                () -> registry.claimHostStopInvocation(permit));
        registry.markStopUncertain(permit);
        assertThrows(IllegalStateException.class,
                () -> registry.claimHostStopInvocation(permit));
        var trace = registry.committedTransitions();
        assertEquals(java.util.List.of("register", "finalize-asserted",
                "stop.reserve", "stop.invoke-asserted", "stop.uncertain"),
                trace.stream().map(SupervisorChildRegistry.Transition::operation).toList());
        assertEquals(permit.nonce(), trace.get(3).stopPermit());
        assertEquals(permit.turnGeneration(), trace.get(3).stopTurn());
        assertEquals(1L, trace.stream()
                .filter(t -> t.operation().equals("stop.invoke-asserted")).count());
        assertEquals(SupervisorChildRegistry.Phase.STOPPING,
                registry.snapshot(child.child()).phase());
    }

}
