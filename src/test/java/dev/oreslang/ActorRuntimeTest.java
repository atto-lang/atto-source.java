package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeTest {
    @Test
    void freezesMessagesBeforeDelivery() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<List<?>> observed = new AtomicReference<>();
            var ref = runtime.<List<Integer>>spawn(() -> (message, context) -> {
                observed.set(message);
                received.countDown();
            });

            ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
            ref.send(mutable);
            mutable.add(3);

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), observed.get());
            assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) observed.get()).add(9));
        }
    }

    @Test
    void rejectsUnknownMutableHostObjects() {
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(new StringBuilder("mutable")));
    }

    @Test
    void actorCarriesItsOwnStricterPolicy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<IsolatePolicy> observed = new AtomicReference<>();
            IsolatePolicy strict = IsolatePolicy.strictFaas();

            var ref = runtime.<String>spawn(strict, () -> (message, context) -> {
                observed.set(context.policy());
                received.countDown();
            });
            ref.send("ping");

            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(strict.capabilities(), observed.get().capabilities());
            assertEquals(strict.maxMailboxMessages(), observed.get().maxMailboxMessages());
        }
    }

    @Test
    void childActorCannotEscalatePastRuntimePolicyCeiling() {
        IsolatePolicy ceiling = IsolatePolicy.strictFaas();
        try (ActorRuntime runtime = new ActorRuntime(ceiling)) {
            IsolatePolicy escalated = ceiling.withCapabilities(IsolatePolicy.Capability.PROCESS_INFO);
            assertThrows(SecurityException.class, () ->
                    runtime.<String>spawn(escalated, () -> (message, context) -> { }));
        }
    }

    @Test
    void readonlySharingDeepFreezesContainers() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var shared = runtime.shareReadonly(Map.of("items", List.of(1, 2, 3)));
            assertNotNull(shared.value());
        }
    }

    @Test
    void actorGcTargetsOnlyTheCurrentActor() throws Exception {
        AtomicReference<ActorRuntime.ActorId> collectedActor = new AtomicReference<>();
        AtomicInteger processCollections = new AtomicInteger();
        ActorRuntime.GarbageCollector collector = new ActorRuntime.GarbageCollector() {
            @Override public void collectActor(ActorRuntime.ActorId actorId) {
                collectedActor.set(actorId);
            }

            @Override public void collectProcess() {
                processCollections.incrementAndGet();
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), collector)) {
            CountDownLatch collected = new CountDownLatch(1);
            var ref = runtime.<String>spawn(() -> (message, context) -> {
                context.gc();
                collected.countDown();
            });

            ref.send("collect");

            assertTrue(collected.await(2, TimeUnit.SECONDS));
            assertEquals(ref.id(), collectedActor.get());
            assertEquals(0, processCollections.get());
            assertEquals(1, runtime.gcStats().actorRequests());
            assertEquals(0, runtime.gcStats().processRequests());
        }
    }

    @Test
    void actorGcIsRejectedWithoutACurrentActor() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, runtime::gcCurrentActor);
            assertTrue(failure.getMessage().contains("current actor"));
        }
    }

    @Test
    void processGcUsesOnlyTheProcessCollectorPath() {
        AtomicInteger actorCollections = new AtomicInteger();
        AtomicInteger processCollections = new AtomicInteger();
        ActorRuntime.GarbageCollector collector = new ActorRuntime.GarbageCollector() {
            @Override public void collectActor(ActorRuntime.ActorId actorId) {
                actorCollections.incrementAndGet();
            }

            @Override public void collectProcess() {
                processCollections.incrementAndGet();
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), collector)) {
            runtime.gcProcess();

            assertEquals(0, actorCollections.get());
            assertEquals(1, processCollections.get());
            assertEquals(0, runtime.gcStats().actorRequests());
            assertEquals(1, runtime.gcStats().processRequests());
        }
    }

    @Test
    void concurrentProcessGcRequestsAreCoalesced() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger processCollections = new AtomicInteger();

        ActorRuntime.GarbageCollector collector = new ActorRuntime.GarbageCollector() {
            @Override public void collectActor(ActorRuntime.ActorId actorId) { }

            @Override public void collectProcess() {
                processCollections.incrementAndGet();
                entered.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                }
            }
        };

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), collector)) {
            Thread first = Thread.ofVirtual().start(runtime::gcProcess);
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            Thread second = Thread.ofVirtual().start(runtime::gcProcess);
            second.join();
            release.countDown();
            first.join();

            assertEquals(1, processCollections.get());
            assertEquals(2, runtime.gcStats().processRequests());
        }
    }

    @Test
    void freezeRejectsCyclesNullAndPathologicalDepth() {
        ArrayList<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(cyclic));
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(null));

        Object nested = "leaf";
        for (int i = 0; i < 300; i++) nested = List.of(nested);
        Object tooDeep = nested;
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(tooDeep));
    }

    @Test
    void sendableRepresentationsAreRefrozenAndCannotReturnSelf() {
        ArrayList<Integer> mutable = new ArrayList<>(List.of(1, 2));
        ActorRuntime.Sendable safe = () -> mutable;
        Object frozen = ActorRuntime.freeze(safe);

        assertInstanceOf(List.class, frozen);
        assertEquals(List.of(1, 2), frozen);
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) frozen).add(3));

        ActorRuntime.Sendable recursive = new ActorRuntime.Sendable() {
            @Override public Object freezeForSend() { return this; }
        };
        assertThrows(IllegalArgumentException.class, () -> ActorRuntime.freeze(recursive));
    }

    @Test
    void currentActorUsesItsStricterEffectivePolicy() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer())) {
            assertTrue(runtime.effectivePolicy().allows(IsolatePolicy.Capability.PROCESS_GC));

            CountDownLatch observed = new CountDownLatch(1);
            AtomicReference<IsolatePolicy> policy = new AtomicReference<>();
            var actor = runtime.<String>spawn(IsolatePolicy.strictFaas(), () -> (message, context) -> {
                policy.set(context.runtime().effectivePolicy());
                observed.countDown();
            });

            actor.send("check");

            assertTrue(observed.await(2, TimeUnit.SECONDS));
            assertNotNull(policy.get());
            assertFalse(policy.get().allows(IsolatePolicy.Capability.PROCESS_GC));
            assertEquals(IsolatePolicy.strictFaas().capabilities(), policy.get().capabilities());
        }
    }

    @Test
    void actorRefsCannotCrossRuntimeBoundariesByJavaReference() {
        try (ActorRuntime left = new ActorRuntime(); ActorRuntime right = new ActorRuntime()) {
            var leftRef = left.<String>spawn(() -> (message, context) -> { });
            var rightRef = right.<Object>spawn(() -> (message, context) -> { });

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> rightRef.send(leftRef));
            assertTrue(failure.getMessage().contains("different actor runtime"));
        }
    }

    @Test
    void actorRefsRemainSendableInsideOneRuntime() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch observed = new CountDownLatch(1);
            var target = runtime.<String>spawn(() -> (message, context) -> { });
            var receiver = runtime.<Object>spawn(() -> (message, context) -> {
                assertSame(target, message);
                observed.countDown();
            });

            receiver.send(target);
            assertTrue(observed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void gcRequestsAreRejectedAfterRuntimeClose() {
        ActorRuntime runtime = new ActorRuntime();
        runtime.close();

        assertThrows(IllegalStateException.class, runtime::gcProcess);
        assertThrows(IllegalStateException.class, runtime::gcCurrentActor);
    }
}
