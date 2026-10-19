package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An irreversible route commit must retain old code retirement ownership even
 * when the subsequent generation-lease debit fails. The supervisor can retry
 * only if the retired physical route is still tracked.
 */
@Timeout(60)
final class LogicalSourceActorRetiredLeaseRecoveryTest {
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

    @Test
    void postPublicationOldGenerationReleaseFaultRetainsRetryableRetirement()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var oldGeneration = manager.stageActorGeneration(
                    "retired-pin-old.ores", SOURCE);
            var newGeneration = manager.stageActorGeneration(
                    "retired-pin-new.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            try (var router = LogicalSourceActorRouterV1.spawn(
                    oldGeneration, ActorRuntime.ActorKind.SHARED,
                    "Counter", WAIT)) {
                Field pins = HotReloadManager.Generation.class
                        .getDeclaredField("actorRoutePins");
                pins.setAccessible(true);
                assertEquals(1, pins.getInt(oldGeneration),
                        "the physical old route must own one code-generation pin");

                // Force a trusted lease-accounting failure AFTER route commit.
                // The old physical actor must remain in the retirement
                // registry instead of disappearing on a failed pin debit.
                pins.setInt(oldGeneration, 0);
                try {
                    IllegalStateException failure =
                            assertThrows(IllegalStateException.class,
                                    () -> router.upgradeTo(newGeneration, WAIT));
                    assertTrue(failure.getMessage().contains("route-pin underflow"),
                            "inject the known post-publication release fault");
                } finally {
                    // Restore the damaged lease accounting before invoking
                    // any route observation that may opportunistically reap.
                    pins.setInt(oldGeneration, 1);
                }

                assertEquals(1, router.reapRetired(),
                        "published old route must remain discoverable and retryable");
                assertEquals(0, pins.getInt(oldGeneration),
                        "the recovered retirement must debit the old pin once");
                assertEquals(2L, router.ref().routeEpoch(),
                        "postcommit release failure may not roll back routing");
                assertEquals(0, router.reapRetired(),
                        "a successful lease release must not replay");
            }
        }
    }
    @Test
    void retirementReservationOomeCannotPublishRouteOrMailbox()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var oldGeneration = manager.stageActorGeneration(
                    "retire-reserve-old.ores", SOURCE);
            var newGeneration = manager.stageActorGeneration(
                    "retire-reserve-new.ores",
                    SOURCE.replace(
                            "self.count = self.count + mail.value;",
                            "self.count = self.count + mail.value + 1;"));
            try (var router = LogicalSourceActorRouterV1.spawn(
                    oldGeneration, ActorRuntime.ActorKind.SHARED,
                    "Counter", WAIT)) {
                var retirement = LogicalSourceActorRouterV1.class
                        .getDeclaredField("retiredRoutes");
                retirement.setAccessible(true);
                Object original = retirement.get(router);
                retirement.set(router, new java.util.ArrayList<Object>() {
                    @Override public boolean add(Object value) {
                        throw new OutOfMemoryError(
                                "injected retirement record allocation before commit");
                    }
                });
                try {
                    assertThrows(OutOfMemoryError.class,
                            () -> router.upgradeTo(newGeneration, WAIT));
                } finally {
                    retirement.set(router, original);
                }
                assertEquals(1L, router.ref().routeEpoch(),
                        "failed retirement ownership reservation cannot publish candidate");
                assertEquals(0, router.reapRetired());
                assertTrue(router.upgradeTo(newGeneration, WAIT),
                        "failed precommit retirement record allocation must allow safe retry");
                assertEquals(2L, router.ref().routeEpoch());
            }
        }
    }

}
