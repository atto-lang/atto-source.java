package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Source-authored only until an exact-head JUnit runner executes the methods. */
@Timeout(90)
final class ActorEventBusMembershipChurnTest {
    @Test
    void thirtyTwoLeaveRejoinCyclesReclaimSubscriptionCreditExactlyOnce()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var grant = group.joinCapability();
            var bus = group.events();
            bus.defineTopic("cycle", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            var old = new AtomicReference<ActorEventBus.Subscription<Integer>>();
            var allOld = new ArrayList<ActorEventBus.Subscription<Integer>>();
            var gate = new AtomicReference<CountDownLatch>();
            var failure = new AtomicReference<Throwable>();
            var actor = runtime.<String>spawnSharedTrusted(factory -> (msg, context) -> {
                try {
                    old.set(bus.<Integer>subscribe("cycle"));
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                } finally {
                    gate.get().countDown();
                }
            });
            for (int iteration = 0; iteration < 32; iteration++) {
                group.join(actor, grant);
                gate.set(new CountDownLatch(1));
                actor.send("subscribe");
                assertTrue(gate.get().await(10, TimeUnit.SECONDS));
                assertNull(failure.get(), () -> String.valueOf(failure.get()));
                var subscription = old.get();
                assertNotNull(subscription);
                assertEquals(1, creditFor(bus, actor.id()));
                bus.publishSystem("cycle", iteration);
                group.leave(actor);
                assertTrue(subscription.closed(), "leave must fence old subscription immediately");
                assertEquals(0, creditFor(bus, actor.id()),
                        "all reliable buffers and subscription credits drain on leave");
                allOld.add(subscription);
            }
            // No old incarnation's subscription can reanimate when the
            // same live actor joins again with the same group capability.
            group.join(actor, grant);
            gate.set(new CountDownLatch(1));
            actor.send("subscribe");
            assertTrue(gate.get().await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, creditFor(bus, actor.id()));
            for (var stale : allOld) assertTrue(stale.closed());
            assertNotSame(allOld.getLast(), old.get());
            group.close();
            assertEquals(0, creditFor(bus, actor.id()));
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void queuedReliableWriteSettlesOnRevocationWithoutCreditUnderflow()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var grant = group.joinCapability();
            var bus = group.events();
            bus.defineTopic("pending", ActorEventBus.DeliveryPolicy.RELIABLE, 1);
            CountDownLatch registered = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorEventBus.Subscription<Integer>> old = new AtomicReference<>();
            var actor = runtime.<String>spawnSharedTrusted(factory -> (msg, context) -> {
                try {
                    old.set(bus.<Integer>subscribe("pending"));
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    registered.countDown();
                }
            });
            group.join(actor, grant);
            actor.send("register");
            assertTrue(registered.await(10, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            var admitted = bus.publishSystem("pending", 1);
            var pending = bus.publishSystem("pending", 2);
            assertFalse(pending.completion().isDone(),
                    "a full bounded channel must defer the second reliable write");
            group.leave(actor);
            assertTrue(old.get().closed());
            assertEquals(0, creditFor(bus, actor.id()));
            assertTrue(pending.completion().isDone(),
                    "revocation must release pending reliable admission");
            assertThrows(ExecutionException.class,
                    () -> pending.completion().get(10, TimeUnit.SECONDS));
            // Already buffered first delivery can be admitted, then reclaimed
            // during close; no leaked publication retainers are permitted.
            assertTrue(admitted.completion().isDone());
            group.join(actor, grant);
            assertEquals(0, creditFor(bus, actor.id()),
                    "rejoin must not inherit old admission credits");
            group.close();
            actor.stop();
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static int creditFor(
            ActorEventBus bus, ActorRuntime.ActorId actor) throws Exception {
        Field lockField = ActorEventBus.class.getDeclaredField("subscriptionCreditLock");
        Field creditsField = ActorEventBus.class.getDeclaredField("subscriptionCredits");
        lockField.setAccessible(true);
        creditsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<ActorRuntime.ActorId, Integer> credits =
                (Map<ActorRuntime.ActorId, Integer>) creditsField.get(bus);
        synchronized (lockField.get(bus)) {
            return credits.getOrDefault(actor, 0);
        }
    }
}
