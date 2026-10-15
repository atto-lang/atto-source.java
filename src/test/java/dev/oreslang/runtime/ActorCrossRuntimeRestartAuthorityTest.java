package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A child handle created by another ActorRuntime is never restart authority,
 * even while a valid local direct-child terminal notice is active.
 */
@Timeout(15)
final class ActorCrossRuntimeRestartAuthorityTest {
    @Test
    void foreignHandleCannotConsumeAnActiveRestartNotice() throws Exception {
        CountDownLatch foreignSpawned = new CountDownLatch(1);
        CountDownLatch parentHandled = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ActorHandle<String>> foreignChild =
                new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorHandle<String>> localChild =
                new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        try (ActorRuntime foreign = new ActorRuntime();
                ActorRuntime owner = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> foreignParent = foreign.spawnShared(
                    () -> (mail, context) -> {
                        foreignChild.set(foreign.spawnChildShared(
                                unused -> (value, childContext) -> {}));
                        foreignSpawned.countDown();
                    });
            foreignParent.send("spawn");
            assertTrue(foreignSpawned.await(3, TimeUnit.SECONDS));
            assertNotNull(foreignChild.get());

            ActorRuntime.ActorRef<String> supervisor = owner.spawnShared(
                    () -> new ActorRuntime.ChildFailureHandler<String>() {
                        @Override
                        public void onMessage(
                                String message,
                                ActorRuntime.ActorContext<String> context) {
                            ActorRuntime.ActorHandle<String> child =
                                    owner.spawnChildShared(unused -> (value, ctx) -> {
                                        throw GuestActorEffectSignal.raise();
                                    });
                            localChild.set(child);
                            child.send("fail");
                        }

                        @Override
                        public void onChildFailure(
                                ActorRuntime.ActorFailureNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            try {
                                SecurityException failure = assertThrows(
                                        SecurityException.class,
                                        () -> owner.restartCurrentChildOneForOne(
                                                notice, foreignChild.get(),
                                                unused -> (value, ctx) -> {}));
                                assertTrue(failure.getMessage().contains(
                                        "owned by this ActorRuntime"));
                                // Rejected foreign capabilities must not spend the
                                // active notice; a real local restart still works.
                                ActorRuntime.ActorHandle<String> replacement =
                                        owner.restartCurrentChildOneForOne(
                                                notice, localChild.get(),
                                                unused -> (value, ctx) -> {});
                                assertNotEquals(localChild.get().id(), replacement.id());
                                replacement.send("valid");
                            } catch (Throwable failed) {
                                callbackFailure.set(failed);
                            } finally {
                                parentHandled.countDown();
                            }
                        }
                    });
            supervisor.send("spawn");
            assertTrue(parentHandled.await(5, TimeUnit.SECONDS),
                    "supervisor CONTROL callback did not execute");
            assertNull(callbackFailure.get(),
                    () -> "foreign-child restart authority was accepted: "
                            + callbackFailure.get());
            supervisor.stop();
            foreignParent.stop();
            assertTrue(supervisor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(foreignParent.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
