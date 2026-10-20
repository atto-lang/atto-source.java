package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ProcessGroupRegistryTest {
    @Test
    void duplicateJoinIsIdempotentAndExplicitLeaveReclaimsName() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 4)) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> actor = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            ProcessGroupRegistry.Membership<Integer> first =
                                    pg.joinCurrent("local", "workers");
                            ProcessGroupRegistry.Membership<Integer> duplicate =
                                    pg.joinCurrent("local", "workers");
                            assertEquals(1, pg.memberCount("local", "workers"));
                            first.leave();
                            assertEquals(0, pg.memberCount("local", "workers"));
                            assertDoesNotThrow(duplicate::leave);
                            assertTrue(duplicate.left());
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            done.countDown();
                        }
                    });

            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(0, pg.groupCount(), "empty named groups must be reclaimed");
            assertTrue(actor.isAlive(), "leaving pg must not stop the actor");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void namedGroupOutlivesFirstMemberAndNeverOwnsMemberLifecycle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 4)) {
            CountDownLatch joined = new CountDownLatch(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            ActorRuntime.BehaviorFactory<Integer> behavior =
                    factory -> (message, context) -> {
                        try {
                            pg.joinCurrent("local", "chat");
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            joined.countDown();
                        }
                    };

            ActorRuntime.ActorRef<Integer> first =
                    runtime.spawnSharedTrusted(behavior);
            ActorRuntime.ActorRef<Integer> second =
                    runtime.spawnSharedTrusted(behavior);

            first.send(1);
            second.send(1);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(2, pg.memberCount("local", "chat"));

            first.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(1, pg.memberCount("local", "chat"));
            List<ActorRuntime.ActorRef<Integer>> members =
                    pg.members("local", "chat");
            assertEquals(List.of(second.id()), members.stream()
                    .map(ActorRuntime.ActorRef::id)
                    .toList());
            assertTrue(second.isAlive(),
                    "first member retirement must not close a runtime-owned pg group");

            pg.delete("local", "chat");
            assertTrue(second.isAlive(),
                    "deleting a pg group must not cancel ordinary members");
            assertEquals(0, pg.groupCount());

            second.stop();
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void groupNameCardinalityIsBoundedAndReclaimed() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 1)) {
            ActorRuntime.ActorRef<Integer> a =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });
            ActorRuntime.ActorRef<Integer> b =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });

            pg.join("local", "a", a);
            assertEquals(1, pg.groupCount());
            assertThrows(
                    IllegalStateException.class,
                    () -> pg.join("local", "b", b));

            pg.delete("local", "a");
            assertEquals(0, pg.groupCount());
            pg.join("local", "b", b);
            assertEquals(1, pg.groupCount());

            a.stop();
            b.stop();
            assertTrue(a.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, pg.groupCount(),
                    "actor retirement must make an empty name reclaimable");
        }
    }

    @Test
    void actorCannotDeleteOrCloseRegistryAndCannotCrossRuntimeBoundary() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime other = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 4)) {
            ActorRuntime.ActorRef<Integer> member =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });
            pg.join("local", "managed", member);

            CountDownLatch localDone = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> localAttacker = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertThrows(
                                    SecurityException.class,
                                    () -> pg.delete("local", "managed"));
                            assertThrows(SecurityException.class, pg::close);
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            localDone.countDown();
                        }
                    });
            localAttacker.send(1);
            assertTrue(localDone.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1, pg.memberCount("local", "managed"));

            CountDownLatch remoteDone = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> remoteAttacker = other.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class, pg::groupCount);
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            remoteDone.countDown();
                        }
                    });
            remoteAttacker.send(1);
            assertTrue(remoteDone.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            pg.delete("local", "managed");
            member.stop();
            localAttacker.stop();
            remoteAttacker.stop();
            assertTrue(member.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(localAttacker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(remoteAttacker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void actorCannotEnumerateGroupItDidNotJoin() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ProcessGroupRegistry pg = new ProcessGroupRegistry(runtime, 4)) {
            ActorRuntime.ActorRef<Integer> member =
                    runtime.spawnSharedTrusted(factory -> (message, context) -> { });
            pg.join("local", "members-only", member);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> outsider = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertThrows(
                                    SecurityException.class,
                                    () -> pg.members("local", "members-only"));
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            done.countDown();
                        }
                    });

            outsider.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());

            member.stop();
            outsider.stop();
            assertTrue(member.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(outsider.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
