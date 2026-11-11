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
        assertThrows(IllegalStateException.class, () -> gate.commitInit(state(owner)));
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
    void closingBeforeInitializationIsTerminal() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.close();
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
    }

    @Test
    void closingDuringInitializationRejectsLateCommit() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.close();
        gate.failInit();
        assertEquals(ActorSourceLifecycleGate.Phase.CLOSED, gate.phase());
        assertThrows(IllegalStateException.class, () -> gate.commitInit(state(owner)));
    }

    @Test
    void retiringReadyActorFencesAllLaterMail() {
        var owner = ActorRuntime.ActorId.create();
        var gate = new ActorSourceLifecycleGate(owner, TYPE);
        gate.beginInit(owner, TYPE);
        gate.commitInit(state(owner));
        gate.close();
        assertEquals(ActorSourceLifecycleGate.Phase.CLOSED, gate.phase());
        assertThrows(IllegalStateException.class,
                () -> gate.requireReceive(owner, TYPE, state(owner), mail(owner)));
        assertThrows(IllegalStateException.class, () -> gate.beginInit(owner, TYPE));
    }

    @Test
    void blankActorTypeCannotCreateGate() {
        var owner = ActorRuntime.ActorId.create();
        assertThrows(IllegalArgumentException.class,
                () -> new ActorSourceLifecycleGate(owner, " "));
    }
}
