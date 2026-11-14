package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Java actor-cell lifecycle admission, not proof of executable out-of-line
 * declare actor syntax. Tests never synthesize user-accessible actor permits.
 */
final class ActorSourceLifecycleGateTest {
    private static final String TYPE = "Worker";

    private static ActorRuntime.ActorOwnedGuestState state(ActorRuntime.ActorId owner) {
        return () -> owner;
    }

    private static ActorRuntime.ActorInboxMail<String> mail(ActorRuntime.ActorId owner) {
        return new ActorRuntime.ActorInboxMail<>(owner, Optional.empty(), 0L, "hello");
    }

    @Test
    void ownedInitializationAllowsMailboxOnlyAfterCommit() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        assertEquals(ActorSourceLifecycleGate.Phase.NEW, gate.phase());
        gate.beginInit(owner, TYPE);
        assertEquals(ActorSourceLifecycleGate.Phase.INITIALIZING, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
        gate.commitInit(state(owner));
        assertEquals(ActorSourceLifecycleGate.Phase.READY, gate.phase());
        assertDoesNotThrow(() -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
    }

    @Test
    void noMailboxBeforeInitialization() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
    }

    @Test
    void initializationIsOneShotEvenBeforeCommit() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
        gate.commitInit(state(owner));
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
    }

    @Test
    void foreignOwnerCannotInitialize() {
        var owner = ActorRuntime.ActorId.create();
        var other = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        assertThrows(SecurityException.class, () -> gate.beginInit(other, TYPE));
        assertEquals(ActorSourceLifecycleGate.Phase.NEW, gate.phase());
    }

    @Test
    void wrongActorTypeCannotInitializeOrReceive() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        assertThrows(SecurityException.class, () -> gate.beginInit(owner, "Other"));
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        assertThrows(SecurityException.class,
                () -> gate.requireReceive(owner, "Other", state(owner), mail(owner)));
    }

    @Test
    void foreignStartupStateFailsTerminally() {
        var owner = ActorRuntime.ActorId.create();
        var foreign = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        assertThrows(SecurityException.class, () -> gate.commitInit(state(foreign)));
        assertEquals(ActorSourceLifecycleGate.Phase.FAILED, gate.phase());
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
    }

    @Test
    void nullStartupStateFailsTerminally() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        assertThrows(SecurityException.class, () -> gate.commitInit(null));
        assertEquals(ActorSourceLifecycleGate.Phase.FAILED, gate.phase());
    }

    @Test
    void startupExceptionCannotBeRetried() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.failInit();
        assertEquals(ActorSourceLifecycleGate.Phase.FAILED, gate.phase());
        assertThrows(ActorSourceLifecycleGate.InitializationStopped.class, () -> gate.commitInit(state(owner)));
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
    }

    @Test
    void foreignStateCannotBeUsedForMailboxTurn() {
        var owner = ActorRuntime.ActorId.create();
        var foreign = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        assertThrows(SecurityException.class,
                () -> gate.requireReceive(owner, TYPE, state(foreign), mail(owner)));
    }

    @Test
    void foreignMailboxRecipientCannotDispatch() {
        var owner = ActorRuntime.ActorId.create();
        var foreign = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        assertThrows(SecurityException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(foreign)));
    }

    @Test
    void foreignActorCannotUseCorrectStateAndMailbox() {
        var owner = ActorRuntime.ActorId.create();
        var foreign = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        assertThrows(SecurityException.class,
                () -> gate.requireReceive(foreign, TYPE, state(owner), mail(owner)));
    }

    @Test
    void closingBeforeInitializationRequiresStopSeal() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        assertThrows(IllegalStateException.class, gate::close,
                "physical reclamation requires STOP even when no guest work ran");
        gate.sealStop(false);
        gate.close();
        assertThrows(ActorSourceLifecycleGate.InitializationStopped.class,
                () -> gate.beginInit(owner, TYPE));
    }

    @Test
    void closingDuringInitializationRejectsLateCommit() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.sealStop(false);
        assertTrue(gate.initializationActive());
        assertThrows(IllegalStateException.class, gate::close,
                "STOP cannot retire an initializer still physically executing");
        gate.failInit();
        assertFalse(gate.initializationActive());
        gate.close();
        assertEquals(ActorSourceLifecycleGate.Phase.CLOSED, gate.phase());
        assertThrows(ActorSourceLifecycleGate.InitializationStopped.class, () -> gate.commitInit(state(owner)));
    }

    @Test
    void retiringReadyActorFencesAllLaterMail() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        assertThrows(IllegalStateException.class, gate::close);
        gate.sealStop(false);
        gate.close();
        assertEquals(ActorSourceLifecycleGate.Phase.CLOSED, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
    }

    @Test
    void authenticatedTeardownIsOnceOnlyAndSealsMailboxIngress() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        gate.sealStop(true);
        assertEquals(ActorSourceLifecycleGate.Phase.STOPPING_GRACEFUL, gate.phase());
        gate.beginTeardown(owner, TYPE, state(owner));
        assertEquals(ActorSourceLifecycleGate.Phase.TEARING_DOWN, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
        assertThrows(IllegalStateException.class,
                () -> gate.beginTeardown(owner, TYPE, state(owner)));
        assertThrows(IllegalStateException.class, gate::close,
                "active tear_down must pin physical retirement");
        gate.finishTeardown(owner, TYPE);
        assertThrows(IllegalStateException.class,
                () -> gate.finishTeardown(owner, TYPE));
        gate.close();
        assertEquals(ActorSourceLifecycleGate.Phase.CLOSED, gate.phase());
    }

    @Test
    void foreignTeardownCannotRevokeOwnedActor() {
        var owner = ActorRuntime.ActorId.create();
        var foreign = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        assertThrows(SecurityException.class,
                () -> gate.beginTeardown(owner, TYPE, state(foreign)));
        assertEquals(ActorSourceLifecycleGate.Phase.READY, gate.phase());
        assertThrows(SecurityException.class,
                () -> gate.beginTeardown(foreign, TYPE, state(owner)));
        assertDoesNotThrow(() -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
    }

    @Test
    void failedStartupCannotInvokeTeardown() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.failInit();
        assertThrows(IllegalStateException.class,
                () -> gate.beginTeardown(owner, TYPE, state(owner)));
    }


    @Test
    void stopDuringInitializationRejectsLateStateCommitAndAllReceive() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.sealStop(false);
        assertEquals(ActorSourceLifecycleGate.Phase.STOPPING_TERMINAL, gate.phase());
        assertTrue(gate.initializationActive());
        assertThrows(ActorSourceLifecycleGate.InitializationStopped.class, () -> gate.commitInit(state(owner)));
        assertThrows(IllegalStateException.class, gate::close);
        gate.failInit();
        assertFalse(gate.initializationActive());
        assertEquals(ActorSourceLifecycleGate.Phase.STOPPING_TERMINAL, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
        assertThrows(IllegalStateException.class,
                () -> gate.beginTeardown(owner, TYPE, state(owner)));
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
    }

    @Test
    void stopBeforeInitializationDoesNotAllocateAnActor() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.sealStop(true); // graceful cannot initialize a never-started actor
        assertEquals(ActorSourceLifecycleGate.Phase.STOPPING_TERMINAL, gate.phase());
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
        assertThrows(ActorSourceLifecycleGate.InitializationStopped.class, () -> gate.commitInit(state(owner)));
        gate.close();
        assertEquals(ActorSourceLifecycleGate.Phase.CLOSED, gate.phase());
    }

    @Test
    void gracefulStopImmediatelySealsMailboxAndGrantsOneTeardown() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        gate.sealStop(true);
        assertThrows(IllegalStateException.class, gate::close,
                "graceful STOP must run the approved teardown before closing the gate");
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
        assertThrows(ActorSourceLifecycleGate.InitializationStopped.class, () -> gate.commitInit(state(owner)));
        gate.beginTeardown(owner, TYPE, state(owner));
        assertThrows(IllegalStateException.class,
                () -> gate.beginTeardown(owner, TYPE, state(owner)));
        assertThrows(IllegalStateException.class, gate::close,
                "active tear_down must pin physical retirement");
        gate.finishTeardown(owner, TYPE);
        assertThrows(IllegalStateException.class,
                () -> gate.finishTeardown(owner, TYPE));
        gate.close();
        assertEquals(ActorSourceLifecycleGate.Phase.CLOSED, gate.phase());
    }

    @Test
    void panicCancelOrForceStopCannotStartCooperativeTeardown() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        gate.sealStop(false);
        assertEquals(ActorSourceLifecycleGate.Phase.STOPPING_TERMINAL, gate.phase());
        gate.sealStop(true); // retry cannot upgrade a terminal stop to graceful
        assertThrows(IllegalStateException.class,
                () -> gate.beginTeardown(owner, TYPE, state(owner)));
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
    }

    @Test
    void gracefulStopCanBeRevokedWithoutResurrectingMail() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        gate.sealStop(true);
        gate.sealStop(false);
        assertEquals(ActorSourceLifecycleGate.Phase.STOPPING_TERMINAL, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.beginTeardown(owner, TYPE, state(owner)));
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
    }

    @Test
    void concurrentStopWinsBeforeBlockedInitializerCommits() throws Exception {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        var began = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        var workerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                gate.beginInit(owner, TYPE);
                began.countDown();
                if (!proceed.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("initializer release timed out");
                }
                assertThrows(IllegalStateException.class,
                        () -> gate.commitInit(state(owner)));
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        });
        try {
            assertTrue(began.await(10, java.util.concurrent.TimeUnit.SECONDS));
            gate.sealStop(false);
        } finally {
            proceed.countDown();
        }
        worker.join(10_000);
        assertFalse(worker.isAlive(), "initializer must terminate");
        assertNull(workerFailure.get(), () -> "unexpected worker failure: " + workerFailure.get());
        assertEquals(ActorSourceLifecycleGate.Phase.STOPPING_TERMINAL, gate.phase());
    }


    @Test
    void unexpectedGuestStateAccessorFailureIsNotAStopReceipt() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        ActorRuntime.ActorOwnedGuestState malformed = () -> {
            throw new IllegalStateException("broken state owner accessor");
        };
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> gate.commitInit(malformed));
        assertEquals("broken state owner accessor", failure.getMessage());
        assertFalse(failure instanceof ActorSourceLifecycleGate.InitializationStopped,
                "an arbitrary guest failure must never masquerade as STOP");
        gate.failInit();
        assertEquals(ActorSourceLifecycleGate.Phase.FAILED, gate.phase());
    }

    @Test
    void normalGracefulStopAfterReadyIsNotAStoppedInitializer() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        gate.sealStop(true);
        IllegalStateException duplicateCommit = assertThrows(
                IllegalStateException.class, () -> gate.commitInit(state(owner)));
        assertFalse(duplicateCommit instanceof ActorSourceLifecycleGate.InitializationStopped,
                "duplicate READY commit is not an init-stop control signal");
    }

    @Test
    void blankActorTypeCannotCreateGate() {
        var owner = ActorRuntime.ActorId.create();
        assertThrows(IllegalArgumentException.class,
                () -> new ActorSourceLifecycleGate(owner, " "));
    }
}
