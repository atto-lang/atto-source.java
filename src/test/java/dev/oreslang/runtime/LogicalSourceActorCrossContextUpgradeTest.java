package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(45)
final class LogicalSourceActorCrossContextUpgradeTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static long next(LogicalSourceActorRouterV1.LogicalActorRef ref)
            throws Exception {
        var step = ref.nextOutput(WAIT);
        assertFalse(step.done(), "logical actor output ended unexpectedly");
        return assertInstanceOf(Long.class, step.value().value());
    }

    @Test
    void realSourceActorMovesAcrossOresContextsWithStableLogicalIdentity()
            throws Exception {
        String v1 = """
                define actor Counter as
                  let int count;
                  on_start(): void {
                    self.count = 1;
                    return;
                  }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void {
                  val should_not_run_for_actor_route = 101;
                  return;
                }
                """;
        String v2 = """
                define actor Counter as
                  let int count;
                  on_start(): void {
                    self.count = 999;
                    return;
                  }
                  receive(ActorMail<int> mail): void {
                    if mail.value < 0; do
                      self.count = self.count - mail.value;
                    else
                      self.count = self.count + mail.value;
                    fi
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void {
                  val should_not_run_for_actor_route = 202;
                  return;
                }
                """;

        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("counter-v1.ores", v1);
            var second = manager.stageActorGeneration("counter-v2.ores", v2);
            var firstView = first.linkActorGeneration("Counter");
            var secondView = second.linkActorGeneration("Counter");
            assertNotEquals(firstView.context().contextId(), secondView.context().contextId());
            assertNotSame(firstView.runtime(), secondView.runtime());
            assertFalse(first.started(), "actor-only link must not execute main");
            assertFalse(second.started(), "candidate actor-only link must not execute main");

            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first,
                                 ActorRuntime.ActorKind.SHARED,
                                 "Counter",
                                 WAIT)) {
                var ref = router.ref();
                UUIDAssertions.requireStable(ref.id());
                var logicalBefore = ref.address();
                var physicalBefore = router.physicalAddressForTesting();

                ref.send(41L);
                assertEquals(42L, next(ref));

                assertTrue(router.upgradeTo(second, WAIT));
                var logicalAfter = ref.address();
                var physicalAfter = router.physicalAddressForTesting();

                assertEquals(logicalBefore.actorId(), logicalAfter.actorId());
                assertEquals(1L, logicalBefore.incarnation());
                assertEquals(2L, logicalAfter.incarnation());
                assertNotEquals(physicalBefore.runtimeId(), physicalAfter.runtimeId());
                assertNotEquals(physicalBefore.actorId(), physicalAfter.actorId());
                assertSame(second, router.generation());
                assertFalse(second.started(),
                        "cross-context actor restore must not run generation main");

                // Candidate on_start must not run: restored 42 becomes 43/45,
                // never 1000+.
                ref.send(-1L);
                assertEquals(43L, next(ref));
                ref.send(-2L);
                assertEquals(45L, next(ref));

                // The old physical actor has been terminated after commit, so
                // its dedicated generation can now be retired without harming
                // the stable logical route.
                manager.retire(first.id());
                assertTrue(first.closed());
                ref.send(1L);
                assertEquals(46L, next(ref));
            }
        }
    }

    @Test
    void incompatibleCrossContextCandidateRollsBackAndOldRouteKeepsWorking()
            throws Exception {
        String base = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 7; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        String badSchema = """
                define actor Counter as
                  let string count;
                  on_start(): void { self.count = "bad"; return; }
                  receive(ActorMail<int> mail): void { return; }
                end
                pub routine main(): void { return; }
                """;

        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("base.ores", base);
            var bad = manager.stageActorGeneration("bad.ores", badSchema);
            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first,
                                 ActorRuntime.ActorKind.SHARED,
                                 "Counter",
                                 WAIT)) {
                var ref = router.ref();
                var physical = router.physicalAddressForTesting();
                assertThrows(SecurityException.class,
                        () -> router.upgradeTo(bad, WAIT));
                assertEquals(1L, ref.incarnation());
                assertEquals(physical, router.physicalAddressForTesting());
                assertSame(first, router.generation());

                ref.send(1L);
                assertEquals(8L, next(ref),
                        "rollback must reopen the exact old physical route");
            }
        }
    }

    /**
     * Keep UUID assertions independent of string rendering and accidental
     * physical address equality.
     */
    private static final class UUIDAssertions {
        private UUIDAssertions() { }
        static void requireStable(java.util.UUID id) {
            assertNotNull(id);
            assertNotEquals(new java.util.UUID(0L, 0L), id);
        }
    }
}
