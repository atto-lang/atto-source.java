package dev.oreslang.runtime;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Executed JVM actor-turn tests for the physical region substrate only.
 * These do not compile `state {}` or execute a source-language guest.
 */
final class SealedActorStateRegionTest {
    @Test
    void untrustedHostWithoutActorTurnCannotAllocateState() {
        assertThrows(SecurityException.class,
                () -> SealedActorStateRegion.allocate(Map.of("count", 0L)));
    }

    @Test
    void actorRegionSurvivesMailboxTurnsButRejectsCrossActorAndEscapedViews()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<SealedActorStateRegion> state = new AtomicReference<>();
            AtomicReference<SealedActorStateRegion.LoanView> escaped = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch crossActorChecked = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(1);

            var owner = runtime.<Integer>spawnShared(() -> {
                var region = SealedActorStateRegion.allocate(
                        Map.of("count", 0L, "ready", false));
                state.set(region);
                return (message, context) -> {
                    try {
                        if (message == 1) {
                            region.write(view -> {
                                assertEquals(0, view.readInt("count"));
                                view.writeInt("count", 1);
                                view.writeBool("ready", true);
                                escaped.set(view);
                                return null;
                            });
                            assertEquals(1, region.read(view -> view.readInt("count")));
                            written.countDown();
                        } else if (message == 2) {
                            assertThrows(IllegalStateException.class,
                                    () -> escaped.get().readInt("count"),
                                    "callback-scoped state view must not survive a turn");
                            assertEquals(1, region.read(view -> view.readInt("count")));
                            assertTrue(region.read(view -> view.readBool("ready")));
                            assertThrows(SecurityException.class,
                                    () -> region.read(view -> {
                                        view.writeInt("count", 4);
                                        return null;
                                    }));
                            region.close();
                            assertTrue(region.isReclaimed());
                            assertThrows(IllegalStateException.class,
                                    () -> region.read(view -> view.readInt("count")));
                            context.self().stop();
                            completed.countDown();
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        context.self().stop();
                        written.countDown();
                        completed.countDown();
                    }
                };
            });

            owner.send(1);
            assertTrue(written.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "state admission/write failed");

            var intruder = runtime.<Integer>spawnShared(() -> (message, context) -> {
                try {
                    assertThrows(SecurityException.class,
                            () -> state.get().read(view -> view.readInt("count")));
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    crossActorChecked.countDown();
                    context.self().stop();
                }
            });
            intruder.send(1);
            assertTrue(crossActorChecked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "cross-actor state access not denied");
            assertTrue(intruder.awaitTermination(5, TimeUnit.SECONDS));

            owner.send(2);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "owner state read/teardown failed");
        }
    }

    @Test
    void stopFencesLiveViewAndDefersReclamationUntilFinally() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch checked = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnShared(() -> {
                var region = SealedActorStateRegion.allocate(Map.of("count", 0L));
                return (message, context) -> {
                    try {
                        region.write(view -> {
                            view.writeInt("count", 6);
                            region.close();
                            assertFalse(region.isReclaimed(),
                                    "active lexical loan physically pins the region");
                            assertThrows(IllegalStateException.class,
                                    () -> view.readInt("count"),
                                    "STOP must fence previously admitted views immediately");
                            return null;
                        });
                        assertTrue(region.isReclaimed(),
                                "last finally release must reclaim after STOP");
                        assertThrows(IllegalStateException.class,
                                () -> region.write(view -> null));
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        checked.countDown();
                        context.self().stop();
                    }
                };
            });
            actor.send(1);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "state reclamation violated lifetime rule");
        }
    }

    @Test
    void nestedLoansAndInvalidFieldTypesFailClosedWithoutWeakeningOwnership()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch checked = new CountDownLatch(1);
            var actor = runtime.<Integer>spawnShared(() -> {
                var region = SealedActorStateRegion.allocate(
                        Map.of("count", 7L, "open", true));
                return (message, context) -> {
                    try {
                        assertThrows(IllegalStateException.class,
                                () -> region.write(view ->
                                        region.write(nested -> null)),
                                "reentrant critical loan must be rejected");
                        assertEquals(7, region.read(view -> view.readInt("count")));
                        assertThrows(IllegalArgumentException.class,
                                () -> region.read(view -> view.readInt("open")));
                        assertThrows(IllegalArgumentException.class,
                                () -> region.read(view -> view.readBool("unknown")));
                        assertThrows(IllegalStateException.class,
                                () -> region.write(view -> OresFuture.completed("async")),
                                "critical callbacks cannot return an async Future");
                        assertEquals(0, regionStateLoansForTest(region),
                                "callback cleanup must release every loan");
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        region.close();
                        checked.countDown();
                        context.self().stop();
                    }
                };
            });
            actor.send(1);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertNull(failure.get(), "negative state loan checks failed");
        }
    }

    // Observes only the arbiter's count; never exposes region data/authority.
    private static int regionStateLoansForTest(SealedActorStateRegion region)
            throws Exception {
        var field = SealedActorStateRegion.class.getDeclaredField("loans");
        field.setAccessible(true);
        return ((ActorStateLoans) field.get(region)).activeCount();
    }
}
