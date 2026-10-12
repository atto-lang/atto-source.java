package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorSelectFairnessIsolationTest {

    @Test
    void sameSharedCodeSiteHasIndependentCommitDrivenFairnessPerActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Object immutableCodeSite = new Object();
            CountDownLatch delivered = new CountDownLatch(4);
            Map<ActorRuntime.ActorId, CopyOnWriteArrayList<Integer>> selections =
                    new ConcurrentHashMap<>();

            ActorRuntime.Behavior<String> behavior = (message, context) -> {
                ChannelRuntime.FairCursor cursor =
                        runtime.actorLocalSelectCursor(immutableCodeSite);
                ChannelRuntime.Channel<Integer> a = new ChannelRuntime.Channel<>(1);
                ChannelRuntime.Channel<Integer> b = new ChannelRuntime.Channel<>(1);

                // Multiple DEFAULT selections must not consume any FAIR credit.
                for (int i = 0; i < 2; i++) {
                    int index = new ChannelRuntime.SelectSet(
                            List.of(ChannelRuntime.read(a),
                                    ChannelRuntime.read(b),
                                    ChannelRuntime.defaultCase()),
                            cursor).selectAsync(ChannelRuntime.SelectPolicy.FAIR)
                            .join().index();
                    assertEquals(2, index);
                }

                assertTrue(a.tryWrite(10));
                assertTrue(b.tryWrite(20));
                int selected = new ChannelRuntime.SelectSet(
                        List.of(ChannelRuntime.read(a), ChannelRuntime.read(b)),
                        cursor).selectAsync(ChannelRuntime.SelectPolicy.FAIR)
                        .join().index();

                selections.computeIfAbsent(
                                context.self().id(),
                                ignored -> new CopyOnWriteArrayList<>())
                        .add(selected);
                delivered.countDown();
            };

            ActorRuntime.ActorRef<String> shared = runtime.spawnShared(() -> behavior);
            ActorRuntime.ActorRef<String> isolated = runtime.spawnPrivate(() -> behavior);
            shared.send("a");
            isolated.send("a");
            shared.send("b");
            isolated.send("b");

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(0, 1), selections.get(shared.id()));
            assertEquals(List.of(0, 1), selections.get(isolated.id()));
            shared.stop();
            isolated.stop();
            shared.done().get(5, TimeUnit.SECONDS);
            isolated.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void hostCannotAcquireActorLocalFairnessCursor() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.actorLocalSelectCursor(new Object()));
            assertTrue(failure.getMessage().contains("actor turn"),
                    failure.getMessage());
        }
    }
}
