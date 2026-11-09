package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level test fixture for registry-scoped membership grant epochs.
 * This has not executed until an exact-head JUnit run supplies real receipts.
 */
@Timeout(15)
final class ProcessGroupRegistryMembershipEpochTest {

    @Test
    void staleHandleCannotRevokeNewMembershipInSameLiveGroup() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 2)) {
            // Keep the group alive across the test actor's leave/rejoin.
            ActorRuntime.ActorRef<Integer> keeper =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });
            pg.join("local", "sticky", keeper);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            ProcessGroupRegistry.Membership<Integer> old =
                                    pg.joinCurrent("local", "sticky");
                            assertEquals(2, pg.memberCount("local", "sticky"));
                            // Explicit leave, rather than old.leave(), keeps the
                            // old handle unconsumed while it becomes stale.
                            pg.leaveCurrent("local", "sticky");

                            ProcessGroupRegistry.Membership<Integer> fresh =
                                    pg.joinCurrent("local", "sticky");
                            assertEquals(2, pg.memberCount("local", "sticky"));

                            old.leave();
                            assertTrue(old.left());
                            assertTrue(pg.contains("local", "sticky", context.self()),
                                    "stale handle must not remove fresh membership");

                            fresh.leave();
                            assertTrue(fresh.left());
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, pg.memberCount("local", "sticky"),
                    "keeper remains; actor's fresh membership has left");
            assertTrue(keeper.isAlive());
            assertTrue(actor.isAlive(),
                    "group cooperation never owns actor lifecycle");
            keeper.stop();
            actor.stop();
            assertTrue(keeper.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void repeatedJoinHandlesShareOneRevocationEpoch() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 2)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            ProcessGroupRegistry.Membership<Integer> first =
                                    pg.joinCurrent("local", "once");
                            ProcessGroupRegistry.Membership<Integer> duplicate =
                                    pg.joinCurrent("local", "once");
                            assertEquals(1, pg.memberCount("local", "once"));
                            first.leave();
                            assertDoesNotThrow(duplicate::leave,
                                    "two handles for the same idempotent join must share revocation");
                            assertTrue(duplicate.left());
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(0, pg.groupCount());
            assertTrue(actor.isAlive());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unauthorizedHostLeaveCannotConsumeValidActorsHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 2)) {
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch left = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ProcessGroupRegistry.Membership<Integer>> handle =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> {
                        try {
                            if (message == 1) {
                                handle.set(pg.joinCurrent("local", "authorization"));
                            } else {
                                handle.get().leave();
                            }
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            if (message == 1) joined.countDown();
                            else left.countDown();
                        }
                    });
            actor.send(1);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertThrows(SecurityException.class, () -> handle.get().leave(),
                    "host code cannot impersonate the registered actor");
            assertFalse(handle.get().left(),
                    "unauthorized leave attempt must not burn the actor handle");
            assertEquals(1, pg.memberCount("local", "authorization"));

            actor.send(2);
            assertTrue(left.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(handle.get().left());
            assertEquals(0, pg.groupCount());
            assertTrue(actor.isAlive());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
