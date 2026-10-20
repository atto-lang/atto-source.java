package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-runtime proof of the A requests / S supervises / B works split.
 *
 * This is NOT a guest-source or restart/CONTROL proof. The host launch below
 * stands in for the future supervisor CONTROL-lane start_child operation.
 */
@Timeout(15)
final class ProximateSupervisorSeparationTest {
    @Test
    void actorARequestDoesNotMakeAASupervisorAndSCanOwnB() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorSupervisor root = runtime.rootSupervisor();
            ActorRuntime.ActorSupervisor s = runtime.createChildSupervisor(root);
            ActorRuntime.ActorGroup sWorkers = runtime.createActorGroup(s);

            CountDownLatch aRequestedB = new CountDownLatch(1);
            AtomicReference<Throwable> privilegeFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<String> a = runtime.spawnPrivateTrusted(factory ->
                    (message, context) -> {
                        if (!"launch-B".equals(message)) return;
                        // Ordinary A must not gain S's CONTROL authority by
                        // knowing S's host object or requesting a new child.
                        try {
                            context.runtime().createActorGroup(s);
                            privilegeFailure.set(new AssertionError(
                                    "actor A acquired supervisor authority"));
                        } catch (SecurityException expected) {
                            // Correct: only authenticated supervisor CONTROL may bind S.
                        } catch (Throwable unexpected) {
                            privilegeFailure.set(unexpected);
                        }
                        try {
                            s.startChildHost(sWorkers,
                                    ActorRuntime.ActorKind.PRIVATE,
                                    IsolatePolicy.developer(),
                                    worker -> (value, workerContext) -> { });
                            privilegeFailure.set(new AssertionError(
                                    "actor A invoked S's privileged startChildHost"));
                        } catch (SecurityException expected) {
                            // Knowing S's object is not possession of CONTROL authority.
                        } catch (Throwable unexpected) {
                            privilegeFailure.set(unexpected);
                        } finally {
                            aRequestedB.countDown();
                        }
                    });

            a.getReady().get(5, TimeUnit.SECONDS);
            a.send("launch-B");
            assertTrue(aRequestedB.await(5, TimeUnit.SECONDS));
            assertNull(privilegeFailure.get(), "actor A must not be supervisor-authorized");
            assertEquals(0, sWorkers.workerCount(),
                    "A's denied CONTROL calls must not create an orphaned B");

            // Today's executable host bridge: S's already-bound lifecycle group
            // launches B. A has no ownership/cancellation authority over B.
            // Future source lowering must route this through S's CONTROL lane.
            ActorRuntime.ActorRef<String> b = s.startChildHost(
                    sWorkers,
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    factory -> (message, context) -> { });
            b.getReady().get(5, TimeUnit.SECONDS);

            assertEquals(Set.of(s.id()), root.childSupervisorIds());
            assertEquals(Set.of(), root.advisedGroupIds());
            assertEquals(Set.of(sWorkers.id()), s.advisedGroupIds());
            assertEquals(s.id(), sWorkers.supervisorId());
            assertEquals(1, sWorkers.workerCount());
            assertEquals(0, sWorkers.memberCount(),
                    "supervision must not imply event-bus membership");

            a.stop();
            a.done().get(5, TimeUnit.SECONDS);
            assertTrue(b.isAlive(),
                    "B's lifecycle is owned by S's group, not its requester A");

            sWorkers.close();
            assertTrue(b.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(Set.of(), s.advisedGroupIds());
            assertEquals(s.id(), sWorkers.supervisorId(),
                    "closure must not silently reparent S's workers");
        }
    }

    @Test
    void failedChildInitializationDoesNotRemainManaged() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var s = runtime.createSupervisor();
            var group = runtime.createActorGroup(s);
            ActorRuntime.ActorRef<String> failed = s.startChildHost(
                    group,
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    factory -> { throw new IllegalStateException("child initialization failed"); });
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> failed.getReady().get(5, TimeUnit.SECONDS));
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, group.workerCount(),
                    "failed child must leave no retained managed worker");
            group.close();
        }
    }

    @Test
    void aCannotMintChildSupervisorsAndForeignSIsRejected() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime other = new ActorRuntime()) {
            ActorRuntime.ActorSupervisor s =
                    runtime.createChildSupervisor(runtime.rootSupervisor());
            ActorRuntime.ActorSupervisor foreign =
                    other.createChildSupervisor(other.rootSupervisor());

            CountDownLatch attempted = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            ActorRuntime.ActorRef<String> a = runtime.spawnPrivateTrusted(factory ->
                    (message, context) -> {
                        try {
                            context.runtime().createChildSupervisor(s);
                            unexpected.set(new AssertionError(
                                    "ordinary actor minted a child supervisor"));
                        } catch (SecurityException expected) {
                            // Authenticated host/supervisor CONTROL only.
                        } catch (Throwable error) {
                            unexpected.set(error);
                        } finally {
                            attempted.countDown();
                        }
                    });

            a.getReady().get(5, TimeUnit.SECONDS);
            a.send("try-to-promote");
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertNull(unexpected.get());

            assertThrows(IllegalArgumentException.class,
                    () -> runtime.createActorGroup(foreign));
            assertEquals(Set.of(), s.childSupervisorIds());
            assertEquals(Set.of(), s.advisedGroupIds());
            ActorRuntime.ActorGroup unrelated = runtime.createActorGroup();
            assertThrows(IllegalArgumentException.class, () ->
                    s.startChildHost(unrelated,
                            ActorRuntime.ActorKind.PRIVATE,
                            IsolatePolicy.developer(),
                            factory -> (message, context) -> { }));
            unrelated.close();
            assertThrows(IllegalStateException.class, () ->
                    s.startChildHost(unrelated,
                            ActorRuntime.ActorKind.PRIVATE,
                            IsolatePolicy.developer(),
                            factory -> (message, context) -> { }),
                    "closed group must not accept a late child");
            a.stop();
            a.done().get(5, TimeUnit.SECONDS);
        }
    }
}
