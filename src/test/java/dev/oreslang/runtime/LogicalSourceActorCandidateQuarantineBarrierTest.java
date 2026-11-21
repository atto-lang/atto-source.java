package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quarantine cannot clear merely because old-route retirement has drained.
 * A physically live, unpublished candidate still owns executable code.
 */
@Timeout(45)
final class LogicalSourceActorCandidateQuarantineBarrierTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String SOURCE = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 1; return; }
              receive(ActorMail<int> mail): void {
                self.count = self.count + mail.value;
                self.send(self.count);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    private static Field field(String name) throws Exception {
        Field field = LogicalSourceActorRouterV1.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void unretiredCandidatePreventsQuarantineClearEvenWithoutRollbackHandle()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var old = manager.stageActorGeneration("old-candidate-barrier.ores", SOURCE);
            var next = manager.stageActorGeneration("new-candidate-barrier.ores",
                    SOURCE.replace("self.count + mail.value;",
                            "self.count + mail.value + 1;"));
            var image = next.linkActorGeneration("Counter");
            var candidateLease = next.pinActorRoute();
            var candidate = image.runtime().spawnCodeActor(
                    ActorRuntime.ActorKind.SHARED, image.policy(),
                    image.executor(), "Counter");
            candidate.getReady().get(5, TimeUnit.SECONDS);

            try (var router = LogicalSourceActorRouterV1.spawn(
                    old, ActorRuntime.ActorKind.SHARED, "Counter", WAIT)) {
                Class<?> type = Class.forName(
                        "dev.oreslang.runtime.LogicalSourceActorRouterV1$CandidateRetirement");
                Constructor<?> constructor = type.getDeclaredConstructor(
                        HotReloadManager.Generation.class,
                        HotReloadManager.Generation.ActorRouteLease.class,
                        HotReloadManager.ActorGenerationView.class,
                        long.class, long.class,
                        LogicalSourceActorRouterV1.ImageReceipts.class);
                constructor.setAccessible(true);
                Object owner = constructor.newInstance(next, candidateLease, image,
                        1L, 1L, LogicalSourceActorRouterV1.ImageReceipts.from(
                                image, "Counter"));
                Field physical = type.getDeclaredField("physical");
                physical.setAccessible(true);
                physical.set(owner, candidate);
                Field eligible = type.getDeclaredField("retirementEligible");
                eligible.setAccessible(true);

                @SuppressWarnings("unchecked")
                ArrayList<Object> ledger =
                        (ArrayList<Object>) field("retiredCandidates").get(router);
                Field quarantined = field("retirementQuarantined");
                Field pins = HotReloadManager.Generation.class
                        .getDeclaredField("actorRoutePins");
                pins.setAccessible(true);
                assertEquals(1, pins.getInt(next),
                        "unpublished candidate has exactly one generation pin");
                synchronized (field("routeLock").get(router)) {
                    ledger.add(owner);
                    quarantined.setBoolean(router, true);
                }

                try {
                    assertEquals(0, router.reapRetired());
                    assertTrue(quarantined.getBoolean(router),
                            "a live unpublished candidate must block reopening");
                    assertEquals(1, pins.getInt(next),
                            "a live candidate must retain its executable image");
                    assertThrows(IllegalStateException.class,
                            () -> router.ref().send(41L),
                            "quarantined route must reject physical mail admission");

                    eligible.setBoolean(owner, true);
                    assertEquals(0, router.reapRetired(),
                            "retirement eligibility is not physical finalization");
                    assertTrue(quarantined.getBoolean(router));
                } finally {
                    candidate.stop();
                    assertTrue(candidate.awaitTermination(5, TimeUnit.SECONDS));
                }

                assertEquals(1, router.reapRetired(),
                        "candidate code pin is retired only after physical finalization");
                assertTrue(ledger.isEmpty());
                assertEquals(0, pins.getInt(next),
                        "final physical retirement must debit exactly one image pin");
                assertFalse(quarantined.getBoolean(router),
                        "all physical owners retired: route can reopen");
                assertEquals(0, router.reapRetired(),
                        "retirement owner cannot refund the code pin twice");

                router.ref().send(41L);
                var output = router.ref().nextOutput(WAIT);
                assertFalse(output.done());
                assertEquals(42L,
                        assertInstanceOf(Long.class, output.value().value()));
            }
        }
    }
}
