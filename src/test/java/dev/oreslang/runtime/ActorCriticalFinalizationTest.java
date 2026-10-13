package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-actor per-incarnation admission revocation. A STOP request must not
 * revoke a LIVE guest critical writer; finalization then permanently seals
 * the state gate before memory/identity is reclaimed.
 */
final class ActorCriticalFinalizationTest {
    private static final class VerifierState implements ActorRuntime.ActorOwnedGuestState {
        private final ActorRuntime.ActorId owner;
        private final CriticalRwGate gate = new CriticalRwGate();
        private final AtomicInteger revocations;

        private VerifierState(ActorRuntime.ActorId owner, AtomicInteger revocations) {
            this.owner = owner;
            this.revocations = revocations;
        }

        @Override public ActorRuntime.ActorId ownerActorId() { return owner; }

        @Override public void revokeRuntimeAdmissions() {
            assertEquals(0, revocations.getAndIncrement(),
                    "actor-owned admission state must be revoked exactly once");
            gate.close();
        }
    }

    @Test
    void stopDefersGateRevocationUntilActiveActorCarrierUnwinds() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finishGuest = new CountDownLatch(1);
        AtomicReference<VerifierState> stored = new AtomicReference<>();
        AtomicReference<CriticalRwGate.Ticket> currentWriter = new AtomicReference<>();
        AtomicInteger revocations = new AtomicInteger();
        Ast.Program program = Parser.parse("pub fnc main(): void { return; }");
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime()) {
            SharedCodeImageStore.CodeImage image =
                    images.publish("critical-stop-audit", program);
            ActorRuntime.ActorCodeExecutor source = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    VerifierState state = new VerifierState(context.self().id(), revocations);
                    stored.set(state);
                    return state;
                }

                @Override public void receiveActor(
                        String type,
                        ActorRuntime.ActorOwnedGuestState raw,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) throws Exception {
                    VerifierState state = (VerifierState) raw;
                    var writer = state.gate.newCriticalTicket();
                    currentWriter.set(writer);
                    writer.enterAsync();
                    entered.countDown();
                    try {
                        if (!finishGuest.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("guest turn release not signaled");
                        }
                        assertTrue(writer.isHeld(),
                                "actor gate must not revoke while guest turn is executing");
                    } finally {
                        writer.close();
                    }
                }
            };
            ActorRuntime.ActorRef<Object> actor =
                    runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED, source, "CriticalVerifier");
            try {
                actor.send("work");
                assertTrue(entered.await(5, TimeUnit.SECONDS), "actor turn did not begin");
                VerifierState state = stored.get();
                assertNotNull(state);
                var queuedReader = state.gate.newTicket(CriticalRwGate.Mode.READ);
                queuedReader.enterAsync();
                assertFalse(queuedReader.isHeld());
                // Host STOP is deliberately synchronous: it must not return
                // before finalization. Drive it on a separate host thread while
                // this test still owns the latch that holds guest execution.
                CountDownLatch stopStarted = new CountDownLatch(1);
                AtomicReference<Throwable> stopFailure = new AtomicReference<>();
                Thread stopCaller = Thread.ofPlatform().name("critical-stop-host").start(() -> {
                    stopStarted.countDown();
                    try {
                        actor.stop();
                    } catch (Throwable failure) {
                        stopFailure.set(failure);
                    }
                });
                try {
                    assertTrue(stopStarted.await(5, TimeUnit.SECONDS));
                    long requestedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (actor.isAlive() && stopCaller.isAlive()
                            && System.nanoTime() < requestedBy) Thread.yield();
                    assertFalse(actor.isAlive(), "host STOP must publish the actor stop fence");
                    assertEquals(0, revocations.get(),
                            "STOP revoked a permit before the active guest turn unwound");
                    assertTrue(currentWriter.get().isHeld());
                    assertFalse(queuedReader.isHeld());
                } finally {
                    // Never deadlock this host test even if a STOP assertion fails.
                    finishGuest.countDown();
                }
                stopCaller.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(stopCaller.isAlive(), "host STOP failed to finish after guest unwind");
                assertNull(stopFailure.get(), "host STOP should finalize after guest unwind");
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(1, revocations.get(), "gate must revoke exactly once at finalization");
                assertFalse(queuedReader.isHeld(), "stale reader admitted after actor terminated");
                assertThrows(IllegalStateException.class,
                        () -> state.gate.newCriticalTicket(),
                        "a stale actor instance must reject all future critical admissions");
                queuedReader.close();
            } finally {
                finishGuest.countDown();
            }
        }
    }

    @Test
    void foreignTicketCloseWaitsForActorFinalizationBeforeReleasingWriter()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exitGuest = new CountDownLatch(1);
        AtomicReference<VerifierState> stateRef = new AtomicReference<>();
        AtomicReference<CriticalRwGate.Ticket> writerRef = new AtomicReference<>();
        AtomicInteger revocations = new AtomicInteger();
        Ast.Program program = Parser.parse("pub fnc main(): void { return; }");
        try (SharedCodeImageStore images = new SharedCodeImageStore();
             ActorRuntime runtime = new ActorRuntime()) {
            SharedCodeImageStore.CodeImage image =
                    images.publish("foreign-critical-close-audit", program);
            ActorRuntime.ActorCodeExecutor source = new ActorRuntime.ActorCodeExecutor() {
                @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }

                @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                        String type, ActorRuntime.ActorContext<Object> context) {
                    var state = new VerifierState(context.self().id(), revocations);
                    stateRef.set(state);
                    return state;
                }

                @Override public void receiveActor(
                        String type,
                        ActorRuntime.ActorOwnedGuestState raw,
                        ActorRuntime.ActorInboxMail<Object> mail,
                        ActorRuntime.ActorContext<Object> context) throws Exception {
                    var state = (VerifierState) raw;
                    var writer = state.gate.newCriticalTicket();
                    writerRef.set(writer);
                    writer.enterAsync();
                    entered.countDown();
                    try {
                        if (!exitGuest.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("actor writer guest not released");
                        }
                        assertTrue(writer.isHeld(),
                                "foreign close prematurely revoked the actor's WRITE permit");
                    } finally {
                        writer.close();
                    }
                }
            };
            ActorRuntime.ActorRef<Object> actor =
                    runtime.spawnCodeActor(ActorRuntime.ActorKind.SHARED,
                            source, "ForeignCloseVerifier");
            try {
                actor.send("test");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var state = stateRef.get();
                assertNotNull(state);
                var writer = writerRef.get();
                var reader = state.gate.newTicket(CriticalRwGate.Mode.READ);
                reader.enterAsync();
                assertFalse(reader.isHeld());

                // The caller has no actor execution domain. Direct close must
                // initiate actor shutdown and defer actual release.
                writer.close();
                writer.close();
                assertTrue(writer.isHeld());
                assertFalse(reader.isHeld());
                assertEquals(0, revocations.get());

                exitGuest.countDown();
                assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
                assertEquals(1, revocations.get());
                assertFalse(reader.isHeld(),
                        "a stale reader must not be admitted after actor retirement");
                assertFalse(writer.isHeld());
                assertThrows(IllegalStateException.class, state.gate::newCriticalTicket);
                reader.close();
            } finally {
                exitGuest.countDown();
            }
        }
    }

    @Test
    void sourceActorStateRevokesOnlyInstanceGate() throws Exception {
        Class<?> objectClass = null;
        Class<?> sourceStateClass = null;
        for (Class<?> nested : dev.oreslang.nodes.OresEvalRootNode.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("OresObject")) objectClass = nested;
            if (nested.getSimpleName().equals("SourceActorState")) sourceStateClass = nested;
        }
        assertNotNull(objectClass);
        assertNotNull(sourceStateClass);
        Constructor<?> objectConstructor = objectClass.getDeclaredConstructors()[0];
        objectConstructor.setAccessible(true);
        Object guestObject = objectConstructor.newInstance(null, null, Map.of());
        Field gateField = objectClass.getDeclaredField("criticalGate");
        gateField.setAccessible(true);
        CriticalRwGate domain = (CriticalRwGate) gateField.get(guestObject);
        var writer = domain.newCriticalTicket();
        writer.enterAsync();

        Constructor<?> stateConstructor = sourceStateClass.getDeclaredConstructors()[0];
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance(
                ActorRuntime.ActorId.create(), "CriticalVerifier", guestObject);
        Method revoke = sourceStateClass.getDeclaredMethod("revokeRuntimeAdmissions");
        revoke.setAccessible(true);
        revoke.invoke(state);
        assertFalse(writer.isHeld(), "retired actor instance retained writer permit");
        assertThrows(IllegalStateException.class, domain::newCriticalTicket);
        writer.close();
    }
}
