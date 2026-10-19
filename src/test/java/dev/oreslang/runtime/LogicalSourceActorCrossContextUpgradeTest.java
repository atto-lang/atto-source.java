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
                assertThrows(IllegalStateException.class,
                        () -> manager.retire(first.id()),
                        "active logical route must pin its generation");
                assertThrows(IllegalStateException.class, first::close,
                        "direct generation close must not invalidate a live actor route");
                assertThrows(IllegalStateException.class, manager::close,
                        "manager shutdown must not invalidate a live logical actor route");
                var logicalBefore = ref.address();
                var physicalBefore = router.physicalAddressForTesting();

                ref.send(41L);
                assertEquals(42L, next(ref));

                assertTrue(router.upgradeTo(second, WAIT));
                var logicalAfter = ref.address();
                var physicalAfter = router.physicalAddressForTesting();

                assertEquals(logicalBefore.actorId(), logicalAfter.actorId());
                assertEquals(1L, logicalBefore.incarnation());
                assertEquals(1L, logicalAfter.incarnation(),
                        "transparent hot replacement preserves actor incarnation");
                assertEquals(1L, logicalBefore.routeEpoch());
                assertEquals(2L, logicalAfter.routeEpoch(),
                        "physical route publication advances route epoch");
                assertNotEquals(physicalBefore.runtimeId(), physicalAfter.runtimeId());
                assertNotEquals(physicalBefore.actorId(), physicalAfter.actorId());
                assertSame(second, router.generation());
                assertThrows(IllegalStateException.class,
                        () -> manager.retire(second.id()),
                        "new active generation must remain route-pinned");
                assertThrows(IllegalStateException.class, second::start,
                        "generic init/main may not run behind an active actor route");
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
    void unreadOldOutputBlocksMigrationUntilParentConsumesIt() throws Exception {
        String v1 = """
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
        String v2 = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 999; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { val version = 2; return; }
                """;

        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("out-v1.ores", v1);
            var second = manager.stageActorGeneration("out-v2.ores", v2);
            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first,
                                 ActorRuntime.ActorKind.SHARED,
                                 "Counter",
                                 WAIT)) {
                var ref = router.ref();
                ref.send(41L);
                router.physicalRefForTesting().drain().get(5, java.util.concurrent.TimeUnit.SECONDS);

                assertThrows(IllegalStateException.class,
                        () -> router.upgradeTo(second, WAIT),
                        "v1 must not strand unread outputs in the retired physical stream");
                assertEquals(1L, ref.incarnation());
                assertEquals(1L, ref.routeEpoch(),
                        "failed migration must not advance the route epoch");
                assertEquals(42L, next(ref));

                assertTrue(router.upgradeTo(second, WAIT));
                ref.send(1L);
                assertEquals(43L, next(ref));
            }
        }
    }

    @Test
    void actorGroupMembershipBlocksCrossContextMigrationUntilReleased() throws Exception {
        String v1 = """
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
        String v2 = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 999; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { val version = 2; return; }
                """;

        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("group-v1.ores", v1);
            var second = manager.stageActorGeneration("group-v2.ores", v2);
            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first,
                                 ActorRuntime.ActorKind.SHARED,
                                 "Counter",
                                 WAIT)) {
                ActorRuntime runtime =
                        first.linkActorGeneration("Counter").runtime();
                var group = runtime.createActorGroup();
                group.join(
                        router.physicalRefForTesting(),
                        group.joinCapability());
                assertTrue(group.contains(router.physicalRefForTesting()));

                assertThrows(IllegalStateException.class,
                        () -> router.upgradeTo(second, WAIT),
                        "v1 must not silently discard ActorGroup membership");
                assertEquals(1L, router.ref().incarnation());
                assertEquals(1L, router.ref().routeEpoch());

                // Closing an ordinary cooperation group removes membership but
                // does not stop unrelated members; migration can then proceed.
                group.close();
                assertTrue(router.upgradeTo(second, WAIT));
                router.ref().send(1L);
                assertEquals(2L, next(router.ref()));
            }
        }
    }

    @Test
    void protectedActorKindsCannotDowngradeToSameJvmHotReload() throws Exception {
        String source = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 1; return; }
                  receive(ActorMail<int> mail): void { return; }
                end
                pub routine main(): void { return; }
                """;
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration("protected-v1.ores", source);
            for (var kind : java.util.List.of(
                    ActorRuntime.ActorKind.PRIVATE,
                    ActorRuntime.ActorKind.UNTRUSTED)) {
                var failure = assertThrows(SecurityException.class,
                        () -> LogicalSourceActorRouterV1.spawn(
                                generation, kind, "Counter", WAIT));
                assertTrue(failure.getMessage().contains("protected worker/isolate"),
                        failure::getMessage);
            }
            assertEquals(1, manager.liveGenerations(),
                    "refused physical isolation must not stage extra generations");
            assertDoesNotThrow(generation::close,
                    "refused routes must not leave a generation lifetime pin");
        }
    }

    @Test
    void interruptedLogicalOutputReadDoesNotPinOldCodeImage() throws Exception {
        String firstSource = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 2; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        String secondSource = firstSource.replace(
                "self.count = 2; return;", "self.count = 999; return;");
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("interrupt-v1.ores", firstSource);
            var second = manager.stageActorGeneration("interrupt-v2.ores", secondSource);
            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first, ActorRuntime.ActorKind.SHARED,
                                 "Counter", WAIT)) {
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread reader = Thread.ofPlatform().start(() -> {
                    Thread.currentThread().interrupt();
                    try {
                        router.ref().nextOutput(WAIT);
                        failure.set(new AssertionError("interrupted read unexpectedly succeeded"));
                    } catch (InterruptedException expected) {
                        // The router must cancel the pending output subscription.
                    } catch (Throwable unexpected) {
                        failure.set(unexpected);
                    }
                });
                reader.join(5000);
                assertFalse(reader.isAlive(), "interrupted logical reader did not stop");
                assertNull(failure.get());
                assertTrue(router.upgradeTo(second, WAIT));
                router.ref().send(40L);
                assertEquals(42L, next(router.ref()));
            }
        }
    }

    @Test
    void timedOutLogicalOutputReadDoesNotStrandHotUpgrade() throws Exception {
        String v1 = """
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
        String v2 = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 900; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("timeout-v1.ores", v1);
            var second = manager.stageActorGeneration("timeout-v2.ores", v2);
            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first, ActorRuntime.ActorKind.SHARED,
                                 "Counter", WAIT)) {
                var ref = router.ref();
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> ref.nextOutput(Duration.ofMillis(20)));
                assertTrue(router.upgradeTo(second, WAIT),
                        "timed-out output waiter must be cancelled before upgrading");
                ref.send(41L);
                assertEquals(42L, next(ref),
                        "migrated actor must preserve state after output timeout");
            }
        }
    }

    @Test
    void pendingOldOutputReaderBlocksMigrationUntilCancelled() throws Exception {
        String v1 = """
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
        String v2 = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 999; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { val version = 2; return; }
                """;

        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("reader-v1.ores", v1);
            var second = manager.stageActorGeneration("reader-v2.ores", v2);
            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first,
                                 ActorRuntime.ActorKind.SHARED,
                                 "Counter",
                                 WAIT)) {
                var pending = router.physicalRefForTesting().outputs().next();
                assertFalse(pending.isDone());
                assertThrows(IllegalStateException.class,
                        () -> router.upgradeTo(second, WAIT),
                        "v1 must not strand a pending reader on the retired output channel");
                assertEquals(1L, router.ref().incarnation());
                assertEquals(1L, router.ref().routeEpoch());
                assertTrue(pending.cancel(false));
                assertTrue(router.upgradeTo(second, WAIT));
                router.ref().send(1L);
                assertEquals(2L, next(router.ref()));
            }
        }
    }


    @Test
    void logicalOutputReaderRegistrationIsLinearizedBeforeMigration() throws Exception {
        String v1 = """
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
        String v2 = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 999; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { val version = 2; return; }
                """;

        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("logical-reader-v1.ores", v1);
            var second = manager.stageActorGeneration("logical-reader-v2.ores", v2);
            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first,
                                 ActorRuntime.ActorKind.SHARED,
                                 "Counter",
                                 WAIT)) {
                var ref = router.ref();
                var result = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread reader = Thread.ofPlatform().start(() -> {
                    try {
                        var step = ref.nextOutput(WAIT);
                        if (!step.done()) {
                            result.set(assertInstanceOf(Long.class, step.value().value()));
                        }
                    } catch (Throwable thrown) {
                        failure.set(thrown);
                    }
                });

                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (reader.getState() != Thread.State.WAITING
                        && reader.getState() != Thread.State.TIMED_WAITING) {
                    if (!reader.isAlive()) break;
                    if (System.nanoTime() >= deadline)
                        fail("logical output reader did not reach blocking wait");
                    Thread.onSpinWait();
                }
                assertTrue(reader.isAlive(),
                        () -> "logical reader failed before migration: " + failure.get());

                assertThrows(IllegalStateException.class,
                        () -> router.upgradeTo(second, WAIT),
                        "migration must observe a logical reader registered before route-lock release");
                assertEquals(1L, ref.routeEpoch());

                ref.send(41L);
                reader.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
                assertFalse(reader.isAlive(), "logical reader did not complete");
                assertNull(failure.get());
                assertEquals(42L, result.get());

                assertTrue(router.upgradeTo(second, WAIT));
                assertEquals(1L, ref.incarnation());
                assertEquals(2L, ref.routeEpoch());
            }
        }
    }

    @Test
    void candidateFromForeignHotReloadManagerIsRejectedBeforeMigration() throws Exception {
        String v1 = """
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
        String v2 = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 999; return; }
                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { val version = 2; return; }
                """;

        try (HotReloadManager left =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit());
             HotReloadManager right =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = left.stageActorGeneration("left-v1.ores", v1);
            var foreign = right.stageActorGeneration("right-v2.ores", v2);
            try (LogicalSourceActorRouterV1 router =
                         LogicalSourceActorRouterV1.spawn(
                                 first,
                                 ActorRuntime.ActorKind.SHARED,
                                 "Counter",
                                 WAIT)) {
                var before = router.physicalAddressForTesting();
                assertThrows(SecurityException.class,
                        () -> router.upgradeTo(foreign, WAIT));
                assertEquals(1L, router.ref().incarnation());
                assertEquals(1L, router.ref().routeEpoch());
                assertEquals(before, router.physicalAddressForTesting());
                router.ref().send(1L);
                assertEquals(2L, next(router.ref()));
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
                assertEquals(1L, ref.routeEpoch());
                assertEquals(physical, router.physicalAddressForTesting());
                assertSame(first, router.generation());

                ref.send(1L);
                assertEquals(8L, next(ref),
                        "rollback must reopen the exact old physical route");
            }
        }
    }

    @Test
    void concurrentHostCloseDoesNotDoubleReleaseActiveGenerationLease()
            throws Exception {
        String source = """
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
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration("double-close.ores", source);
            var router = LogicalSourceActorRouterV1.spawn(
                    generation, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
            var ref = router.ref();
            ref.send(41L);
            assertEquals(42L, next(ref));

            final int closers = 8;
            var start = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread[] workers = new Thread[closers];
            for (int i = 0; i < closers; i++) {
                workers[i] = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        router.close();
                    } catch (Throwable thrown) {
                        failure.compareAndSet(null, thrown);
                    }
                });
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join(10000);
                assertFalse(worker.isAlive(), "concurrent close must terminate");
            }
            assertNull(failure.get(),
                    "racing cleanup must not double-release generation leases");
            assertDoesNotThrow(router::close);
            assertThrows(IllegalStateException.class, ref::routeEpoch);
            assertDoesNotThrow(generation::close,
                    "generation must not remain pinned after physical retirement");
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
