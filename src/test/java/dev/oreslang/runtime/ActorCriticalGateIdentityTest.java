package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host actor-cell identity checks. These are not guest compiler evidence. */
@Timeout(20)
final class ActorCriticalGateIdentityTest {

    @Test void hostCannotMintAnActorCriticalGateWithoutAnAdmittedActorTurn() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class, runtime::currentActorCriticalGate);
            assertTrue(error.getMessage().contains("authenticated current actor turn"));
        }
    }

    @Test void eachActorOwnsOneGateAcrossMessagesDespiteSharingBehaviorCode()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Map<ActorRuntime.ActorId, CriticalGate> gates = new ConcurrentHashMap<>();
            CountDownLatch delivered = new CountDownLatch(4);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.Behavior<String> sameCode = (message, context) -> {
                try {
                    CriticalGate gate = runtime.currentActorCriticalGate();
                    CriticalGate original = gates.putIfAbsent(context.self().id(), gate);
                    if (original != null) assertSame(original, gate,
                            "actor gate changed across mailbox turns");
                    var ticket = gate.tryEnter().orElseThrow();
                    assertTrue(gate.holds(ticket));
                    assertTrue(gate.release(ticket).isEmpty());
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                } finally {
                    delivered.countDown();
                }
            };
            var privateActor = runtime.spawnPrivate(() -> sameCode);
            var sharedActor = runtime.spawnShared(() -> sameCode);
            privateActor.send("first");
            sharedActor.send("first");
            privateActor.send("second");
            sharedActor.send("second");

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            CriticalGate left = gates.get(privateActor.id());
            CriticalGate right = gates.get(sharedActor.id());
            assertNotNull(left);
            assertNotNull(right);
            assertNotSame(left, right, "shared immutable source/behavior cannot share actor gate");
            assertTrue(left.quiescent());
            assertTrue(right.quiescent());
            privateActor.stop();
            sharedActor.stop();
            privateActor.done().get(5, TimeUnit.SECONDS);
            sharedActor.done().get(5, TimeUnit.SECONDS);
            assertTrue(left.retired());
            assertTrue(right.retired());
            assertTrue(left.tryEnter().isEmpty());
            assertTrue(right.tryEnter().isEmpty());
        }
    }

    @Test void actorIncarnationGetsNewGateAndOldTicketCannotAuthorizeIt()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<CriticalGate> first = new AtomicReference<>();
            AtomicReference<CriticalGate> second = new AtomicReference<>();
            CountDownLatch delivered = new CountDownLatch(2);
            var a = runtime.<String>spawnPrivate(() -> (message, context) -> {
                first.set(runtime.currentActorCriticalGate());
                delivered.countDown();
            });
            a.send("open");
            assertTrue(firstMessage(delivered, first));
            a.stop();
            a.done().get(5, TimeUnit.SECONDS);
            assertTrue(first.get().retired());

            var b = runtime.<String>spawnPrivate(() -> (message, context) -> {
                second.set(runtime.currentActorCriticalGate());
                delivered.countDown();
            });
            b.send("open");
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertNotNull(second.get());
            assertNotSame(first.get(), second.get());
            assertThrows(IllegalArgumentException.class, () -> {
                CriticalGate.Ticket newOwner = second.get().request().ticket();
                // A ticket from the replacement cannot release a retired gate.
                try { first.get().release(newOwner); }
                finally { second.get().release(newOwner); }
            });
            b.stop();
            b.done().get(5, TimeUnit.SECONDS);
        }
    }

    private static boolean firstMessage(
            CountDownLatch delivered, AtomicReference<CriticalGate> gate)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gate.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        return gate.get() != null && delivered.getCount() == 1;
    }
}
