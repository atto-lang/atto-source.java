package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Adversarial and concurrent admission regressions for named actor routing. */
final class ActorAddressBusHardeningTest {
    @Test
    void rejectsMalformedAndOversizedAddressNames() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorAddressBus bus = runtime.createActorGroup().addresses();
            for (String invalid : List.of(
                    "", " leading", ".hidden", "a b", "a?b",
                    "a\nb", "a".repeat(129), "name\u0000hidden")) {
                assertThrows(IllegalArgumentException.class,
                        () -> bus.defineAddress(invalid, String.class), invalid);
            }
            assertThrows(NullPointerException.class,
                    () -> bus.defineAddress(null, String.class));
            assertThrows(IllegalArgumentException.class,
                    () -> bus.send("bad name", "payload"));
        }
    }

    @Test
    void rejectsUnsafeAddressPayloadDeclarations() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorAddressBus bus = runtime.createActorGroup().addresses();
            assertThrows(NullPointerException.class,
                    () -> bus.defineAddress("null-type", null));
            assertThrows(IllegalArgumentException.class,
                    () -> bus.defineAddress("primitive", int.class));
            assertThrows(IllegalArgumentException.class,
                    () -> bus.defineAddress("array", String[].class));
            assertThrows(IllegalArgumentException.class,
                    () -> bus.defineAddress("void", Void.class));
            bus.defineAddress("typed", String.class);
            bus.defineAddress("typed", String.class);
            assertThrows(IllegalStateException.class,
                    () -> bus.defineAddress("typed", Object.class));
        }
    }

    @Test
    void emptyAddressesReturnNoRecipientWithoutQueuingMessages() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorAddressBus bus = runtime.createActorGroup().addresses();
            bus.defineAddress("no.members", Integer.class);
            assertEquals(Optional.empty(), bus.send("no.members", 7));
            assertEquals(new ActorAddressBus.BroadcastResult(0, 0, 0),
                    bus.publish("no.members", 8));
            assertEquals(List.of(), bus.members("no.members"));
            assertThrows(IllegalArgumentException.class,
                    () -> bus.send("missing", 9));
            assertThrows(IllegalArgumentException.class,
                    () -> bus.publish("missing", 9));
        }
    }

    @Test
    void selfLeaveAndRejoinAreIdempotentAndRestoreRouting() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorAddressBus bus = group.addresses();
            bus.defineAddress("cycle", Integer.class);
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch left = new CountDownLatch(1);
            CountDownLatch rejoined = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        if (message == 0) {
                            capability.join();
                            bus.joinCurrent("cycle", context.self());
                            joined.countDown();
                        } else if (message == 1) {
                            bus.leaveCurrent("cycle");
                            bus.leaveCurrent("cycle");
                            left.countDown();
                        } else if (message == 2) {
                            bus.joinCurrent("cycle", context.self());
                            rejoined.countDown();
                        }
                    });
            actor.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(actor.id()), bus.members("cycle"));
            actor.send(1);
            assertTrue(left.await(5, TimeUnit.SECONDS));
            assertTrue(bus.members("cycle").isEmpty());
            assertEquals(Optional.empty(), bus.send("cycle", 44));
            actor.send(2);
            assertTrue(rejoined.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(actor.id()), bus.members("cycle"));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(bus.members("cycle").isEmpty());
        }
    }

    @Test
    void leavingActorGroupRevokesAddressUntilExplicitRejoin() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorAddressBus bus = group.addresses();
            bus.defineAddress("group-leave", Integer.class);
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch left = new CountDownLatch(1);
            CountDownLatch rejoined = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        if (message == 0) {
                            capability.join();
                            bus.joinCurrent("group-leave", context.self());
                            joined.countDown();
                        } else if (message == 1) {
                            group.leaveCurrent();
                            left.countDown();
                        } else if (message == 2) {
                            capability.join();
                            bus.joinCurrent("group-leave", context.self());
                            rejoined.countDown();
                        }
                    });
            actor.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            actor.send(1);
            assertTrue(left.await(5, TimeUnit.SECONDS));
            assertFalse(group.contains(actor));
            assertEquals(List.of(), bus.members("group-leave"));
            actor.send(2);
            assertTrue(rejoined.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(actor.id()), bus.members("group-leave"));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void groupMemberCannotDefineOrRetypeManagerAddresses() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorAddressBus bus = group.addresses();
            bus.defineAddress("manager-only", String.class);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        capability.join();
                        try {
                            bus.defineAddress("forged", String.class);
                        } catch (Throwable e) {
                            failure.set(e);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(failure.get() instanceof SecurityException,
                    "member must not acquire manager authority");
            assertThrows(IllegalArgumentException.class,
                    () -> bus.members("forged"));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sameRuntimeOutsiderCannotDiscoverOrPublishToGroup() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorAddressBus bus = group.addresses();
            bus.defineAddress("secret", String.class);
            CountDownLatch done = new CountDownLatch(1);
            AtomicInteger denied = new AtomicInteger();
            ActorRuntime.ActorRef<Integer> outsider = runtime.spawnShared(
                    () -> (message, context) -> {
                        try { bus.members("secret"); }
                        catch (SecurityException e) { denied.incrementAndGet(); }
                        try { bus.send("secret", "read"); }
                        catch (SecurityException e) { denied.incrementAndGet(); }
                        try { bus.publish("secret", "read"); }
                        catch (SecurityException e) { denied.incrementAndGet(); }
                        done.countDown();
                    });
            outsider.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(3, denied.get());
            assertEquals(List.of(), bus.members("secret"));
            outsider.stop();
            assertTrue(outsider.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void simultaneousSendersPreserveSingleRecipientPerAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorAddressBus bus = group.addresses();
            bus.defineAddress("parallel-send", Integer.class);
            CountDownLatch joined = new CountDownLatch(3);
            CountDownLatch delivered = new CountDownLatch(48);
            List<ActorRuntime.ActorRef<Integer>> actors = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                        () -> (message, context) -> {
                            if (message == 0) {
                                capability.join();
                                bus.joinCurrent("parallel-send", context.self());
                                joined.countDown();
                            } else {
                                delivered.countDown();
                            }
                        });
                actors.add(actor);
                actor.send(0);
                assertTrue(joined.getCount() >= 0);
            }
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> futures = new ArrayList<>();
                Set<ActorRuntime.ActorId> recipients = Set.copyOf(bus.members("parallel-send"));
                assertEquals(3, recipients.size());
                for (int i = 0; i < 4; i++) {
                    futures.add(workers.submit(() -> {
                        for (int n = 0; n < 12; n++) {
                            assertTrue(recipients.contains(bus.send("parallel-send", n + 1).orElseThrow()));
                        }
                    }));
                }
                for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
                assertTrue(delivered.await(10, TimeUnit.SECONDS));
            } finally {
                workers.shutdownNow();
                for (ActorRuntime.ActorRef<Integer> actor : actors) actor.stop();
                for (ActorRuntime.ActorRef<Integer> actor : actors)
                    assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void parallelPublishAccountsForAllRecipientsWithoutDoubleAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability capability = group.joinCapability();
            ActorAddressBus bus = group.addresses();
            bus.defineAddress("parallel-publish", Integer.class);
            CountDownLatch joined = new CountDownLatch(2);
            CountDownLatch delivered = new CountDownLatch(32);
            List<ActorRuntime.ActorRef<Integer>> actors = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ActorRuntime.ActorRef<Integer> actor = runtime.spawnShared(
                        () -> (message, context) -> {
                            if (message == 0) {
                                capability.join();
                                bus.joinCurrent("parallel-publish", context.self());
                                joined.countDown();
                            } else {
                                delivered.countDown();
                            }
                        });
                actors.add(actor);
                actor.send(0);
            }
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(workers.submit(() -> {
                        for (int n = 0; n < 8; n++) {
                            assertEquals(new ActorAddressBus.BroadcastResult(2, 2, 0),
                                    bus.publish("parallel-publish", n + 1));
                        }
                    }));
                }
                for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
                assertTrue(delivered.await(10, TimeUnit.SECONDS));
            } finally {
                workers.shutdownNow();
                for (ActorRuntime.ActorRef<Integer> actor : actors) actor.stop();
                for (ActorRuntime.ActorRef<Integer> actor : actors)
                    assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }
}
