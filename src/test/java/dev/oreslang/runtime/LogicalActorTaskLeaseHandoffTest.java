package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real SHARED compiled actor regression for generation-pin handoff into the
 * canonical TaskScope. A failure before scheduler admission must not strand
 * old executable code or allow an untracked continuation to become runnable.
 */
@Timeout(60)
final class LogicalActorTaskLeaseHandoffTest {
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

    private static String replacement() {
        return SOURCE.replace(
                "self.count = self.count + mail.value;",
                "self.count = self.count + mail.value + 1;");
    }

    @Test
    void cancelledCompilerScopeCannotLeaveRouterGenerationPinned()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             var scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("scope-rejected-v1.ores", SOURCE);
            var next = manager.stageActorGeneration("scope-rejected-v2.ores",
                    replacement());
            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var cancelled = OresTaskScope.compilerOwned()) {
                assertTrue(cancelled.cancel());
                long epoch = router.ref().routeEpoch();
                assertThrows(RejectedExecutionException.class,
                        () -> router.startCompilerTaskForUpgrade(
                                cancelled, scheduler,
                                resume -> OresScheduler.done(1), epoch));
                assertTrue(router.upgradeTo(next, WAIT),
                        "scope rejection must release the pre-acquired generation pin");
                assertEquals(epoch + 1L, router.ref().routeEpoch());
            }
        }
    }

    @Test
    void faultedCanonicalReservationReleasesPinnedGenerationAndAllowsUpgrade()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             var scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("scope-oom-v1.ores", SOURCE);
            var next = manager.stageActorGeneration("scope-oom-v2.ores",
                    replacement());
            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var scope = OresTaskScope.compilerOwned()) {
                var field = OresTaskScope.class.getDeclaredField("tasks");
                field.setAccessible(true);
                Object original = field.get(scope);
                field.set(scope, new HashSet<Object>() {
                    @Override public boolean add(Object slot) {
                        throw new OutOfMemoryError("injected Task owner allocation");
                    }
                });
                try {
                    assertThrows(OutOfMemoryError.class,
                            () -> router.startCompilerTaskForUpgrade(
                                    scope, scheduler,
                                    resume -> OresScheduler.done(2),
                                    router.ref().routeEpoch()));
                } finally {
                    field.set(scope, original);
                }
                assertTrue(scope.physicallyQuiescent());
                assertTrue(router.upgradeTo(next, WAIT),
                        "failed pre-admission reservation cannot orphan an old image pin");
                assertEquals(2L, router.ref().routeEpoch());
            }
        }
    }

    @Test
    void staleCapturedEpochRejectedBeforeSchedulerWorkAndWithoutPins()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             var scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("stale-scope-v1.ores", SOURCE);
            var next = manager.stageActorGeneration("stale-scope-v2.ores",
                    replacement());
            try (var router = LogicalSourceActorRouterV1.spawn(
                    first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var scope = OresTaskScope.compilerOwned()) {
                long stale = router.ref().routeEpoch();
                assertTrue(router.upgradeTo(next, WAIT));
                var frames = new java.util.concurrent.atomic.AtomicInteger();
                assertThrows(RejectedExecutionException.class,
                        () -> router.startCompilerTaskForUpgrade(
                                scope, scheduler,
                                resume -> {
                                    frames.incrementAndGet();
                                    return OresScheduler.done(1);
                                }, stale));
                assertEquals(0, frames.get());
                assertTrue(scope.physicallyQuiescent());
                assertEquals(stale + 1L, router.ref().routeEpoch());
            }
        }
    }
}
