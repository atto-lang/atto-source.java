package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorAddressBusTest {
    @Test
    void pgMembershipAndVertxSendPublishUseExistingActorMailboxes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorAddressBus addresses = group.addresses();
            addresses.defineAddress("orders.created", Integer.class);
            addresses.defineAddress("orders.created", Integer.class);

            CountDownLatch joined = new CountDownLatch(2);
            CountDownLatch delivered = new CountDownLatch(4);
            ConcurrentLinkedQueue<Integer> got = new ConcurrentLinkedQueue<>();

            ActorRuntime.ActorRef<Integer> first = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    capability.join();
                    addresses.joinCurrent("orders.created", context.self());
                    addresses.joinCurrent("orders.created", context.self()); // duplicate is idempotent
                    joined.countDown();
                } else {
                    got.add(message);
                    delivered.countDown();
                }
            });
            ActorRuntime.ActorRef<Integer> second = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    capability.join();
                    addresses.joinCurrent("orders.created", context.self());
                    joined.countDown();
                } else {
                    got.add(message);
                    delivered.countDown();
                }
            });

            first.send(0);
            second.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            assertEquals(Set.of(first.id(), second.id()), Set.copyOf(addresses.members("orders.created")));
            assertEquals(first.id(), addresses.send("orders.created", 101).orElseThrow());
            assertEquals(second.id(), addresses.send("orders.created", 102).orElseThrow());
            ActorAddressBus.BroadcastResult result = addresses.publish("orders.created", 103);
            assertEquals(new ActorAddressBus.BroadcastResult(2, 2, 0), result);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(4, got.size());
            assertEquals(1, got.stream().filter(n -> n == 101).count());
            assertEquals(1, got.stream().filter(n -> n == 102).count());
            assertEquals(2, got.stream().filter(n -> n == 103).count());

            first.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(List.of(second.id()), addresses.members("orders.created"));
            second.stop();
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(addresses.members("orders.created").isEmpty());
            assertEquals(Optional.empty(), addresses.send("orders.created", 1));
        }
    }

    @Test
    void addressTypesInvalidDefinitionsAndClosedLifecycleFailClosed() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorAddressBus addresses = group.addresses();
            addresses.defineAddress("pg.workers", String.class);
            assertThrows(IllegalStateException.class,
                    () -> addresses.defineAddress("pg.workers", Integer.class));
            assertThrows(IllegalArgumentException.class,
                    () -> addresses.defineAddress("bad name", String.class));
            assertThrows(IllegalArgumentException.class,
                    () -> addresses.defineAddress("invalid", int.class));
            assertThrows(IllegalArgumentException.class,
                    () -> addresses.send("pg.workers", 42));
            assertThrows(IllegalArgumentException.class,
                    () -> addresses.publish("pg.workers", null));
            assertThrows(IllegalArgumentException.class,
                    () -> addresses.send("undefined", "value"));
            assertThrows(SecurityException.class,
                    () -> addresses.joinCurrent("pg.workers",
                            runtime.spawnShared(() -> (message, context) -> { })));
            assertEquals(new ActorAddressBus.BroadcastResult(0, 0, 0),
                    addresses.publish("pg.workers", "empty"));
            group.close();
            assertThrows(IllegalStateException.class,
                    () -> addresses.send("pg.workers", "value"));
            assertThrows(IllegalStateException.class,
                    () -> addresses.publish("pg.workers", "value"));
            assertThrows(IllegalStateException.class,
                    () -> addresses.defineAddress("pg.new", String.class));
        }
    }

    @Test
    void actorCannotRegisterAnotherActorOrUseForeignRuntimeGroup() throws Exception {
        try (ActorRuntime owner = new ActorRuntime();
             ActorRuntime foreign = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = owner.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorAddressBus addresses = group.addresses();
            addresses.defineAddress("rpc.worker", String.class);
            ActorRuntime.ActorRef<String> target =
                    owner.spawnShared(() -> (message, context) -> { });
            group.join(target, capability);
            CountDownLatch tested = new CountDownLatch(1);
            AtomicReference<Throwable> spoof = new AtomicReference<>();
            ActorRuntime.ActorRef<String> tester = owner.spawnShared(() -> (message, context) -> {
                capability.join();
                try {
                    addresses.joinCurrent("rpc.worker", target);
                } catch (Throwable t) {
                    spoof.set(t);
                } finally {
                    tested.countDown();
                }
            });
            tester.send("test");
            assertTrue(tested.await(5, TimeUnit.SECONDS));
            assertTrue(spoof.get() instanceof SecurityException);
            assertTrue(addresses.members("rpc.worker").isEmpty());

            CountDownLatch foreignTested = new CountDownLatch(1);
            AtomicReference<Throwable> foreignFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> outsider = foreign.spawnShared(() -> (message, context) -> {
                try {
                    addresses.send("rpc.worker", "unauthorized");
                } catch (Throwable t) {
                    foreignFailure.set(t);
                } finally {
                    foreignTested.countDown();
                }
            });
            outsider.send(1);
            assertTrue(foreignTested.await(5, TimeUnit.SECONDS));
            assertTrue(foreignFailure.get() instanceof SecurityException);
            assertFalse(group.closed());
        }
    }

    @Test
    void mailboxBackpressureRemainsBoundedForAddressSendAndPublish() throws Exception {
        IsolatePolicy base = IsolatePolicy.developer();
        IsolatePolicy bounded = new IsolatePolicy(
                base.capabilities(), base.maxHeapBytes(), 1, base.maxWallTime());
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorAddressBus addresses = group.addresses();
            addresses.defineAddress("bounded", Integer.class);
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch busy = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> sink = runtime.spawnShared(bounded,
                    () -> (message, context) -> {
                        if (message == 0) {
                            capability.join();
                            addresses.joinCurrent("bounded", context.self());
                            joined.countDown();
                        } else if (message == 1) {
                            busy.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        }
                    });

            sink.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            addresses.send("bounded", 1);
            assertTrue(busy.await(5, TimeUnit.SECONDS));
            addresses.send("bounded", 2);
            assertThrows(IllegalStateException.class,
                    () -> addresses.send("bounded", 3));
            assertEquals(new ActorAddressBus.BroadcastResult(1, 0, 1),
                    addresses.publish("bounded", 4));
            release.countDown();
            sink.stop();
            assertTrue(sink.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(addresses.members("bounded").isEmpty());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }
}
