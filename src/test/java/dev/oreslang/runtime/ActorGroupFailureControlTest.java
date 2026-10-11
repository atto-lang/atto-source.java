package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
final class ActorGroupFailureControlTest {
    @Test
    void failurePreemptsNormalGroupTrafficAndSurvivesMemberTeardown() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var join = group.joinCapability();
            // The group Mailman is optional: its output/data channel must not
            // be required for a supervisor event to make progress.
            OresFuture<ActorRuntime.ActorFailureNotice> awaiting = group.nextMemberFailure();
            assertFalse(awaiting.cancel(false));
            var healthy = runtime.<String>spawnShared(
                    () -> (message, actor) -> { });
            var failed = runtime.<String>spawnShared(
                    () -> (message, actor) -> { throw new OresPanic("member failed"); });
            group.join(healthy, join);
            group.join(failed, join);

            failed.send("boom");
            var notice = awaiting.get(5, TimeUnit.SECONDS);
            assertEquals(failed.id(), notice.actorId());
            assertEquals(ActorRuntime.ActorFailureKind.PANIC, notice.kind());
            assertEquals("member failed", notice.summary());
            assertThrows(ExecutionException.class,
                    () -> failed.done().get(5, TimeUnit.SECONDS));
            assertFalse(group.contains(failed));
            assertTrue(healthy.isAlive(),
                    "member failure must not destroy a sibling or host process");
            healthy.stop();
            group.close();
        }
    }

    @Test
    void normalStopDoesNotSynthesizeFailureAndConcurrentReadersAreRejected() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var capability = group.joinCapability();
            var normal = runtime.<String>spawnShared(() -> (message, actor) -> { });
            group.join(normal, capability);
            var awaiting = group.nextMemberFailure();
            assertThrows(IllegalStateException.class, group::nextMemberFailure);
            normal.stop();
            normal.done().get(5, TimeUnit.SECONDS);
            assertFalse(awaiting.isDone(), "stopping a member is not a panic");
            var failed = runtime.<String>spawnShared(
                    () -> (message, actor) -> { throw new OresRaised("escalated"); });
            group.join(failed, capability);
            failed.send("explode");
            assertEquals(ActorRuntime.ActorFailureKind.RAISE,
                    awaiting.get(5, TimeUnit.SECONDS).kind());
            group.close();
        }
    }

    @Test
    void noUnrelatedActorCanConsumeAGroupSupervisorNotice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            AtomicReference<Throwable> denied = new AtomicReference<>();
            CountDownLatch complete = new CountDownLatch(1);
            var outsider = runtime.<String>spawnShared(
                    () -> (message, actor) -> {
                        try {
                            group.nextMemberFailure();
                        } catch (Throwable failure) {
                            denied.set(failure);
                        } finally {
                            complete.countDown();
                        }
                    });
            outsider.send("attempt");
            assertTrue(complete.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, denied.get());
            assertTrue(outsider.isAlive());
            outsider.stop();
            group.close();
        }
    }

    @Test
    void waitingObserverIsFailedOnGroupClose() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var waiter = group.nextMemberFailure();
            group.close();
            ExecutionException closed = assertThrows(ExecutionException.class,
                    () -> waiter.get(5, TimeUnit.SECONDS));
            assertTrue(closed.getCause().getMessage().contains("ActorGroup closed"));
        }
    }

    @Test
    void failureFloodBecomesVisibleOverflowInsteadOfSilentDrop() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 1, 2))) {
            var group = runtime.createActorGroup();
            var capability = group.joinCapability();
            // Membership slots are reusable, but the unconsumed failure
            // channel may retain at most memberLimit (2) terminal records.
            for (int i = 0; i < 3; i++) {
                var actor = runtime.<String>spawnShared(
                        () -> (message, context) -> { throw new OresPanic("flood"); });
                group.join(actor, capability);
                actor.send("die");
                assertThrows(ExecutionException.class,
                        () -> actor.done().get(5, TimeUnit.SECONDS));
            }
            var failed = group.nextMemberFailure();
            ExecutionException overflow = assertThrows(ExecutionException.class,
                    () -> failed.get(5, TimeUnit.SECONDS));
            assertTrue(overflow.getCause().getMessage().contains("CONTROL overflow"));
            group.close();
        }
    }
}
