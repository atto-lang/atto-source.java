package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real actor/runtime integration coverage (not just a model mailbox).
 * These source actors execute on SHARED Java/Graal contexts; this does not
 * prove per-actor protected address spaces or LLVM image loading.
 */
@Timeout(60)
final class LogicalSourceActorProxyBridgeV1Test {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private static final String V1 = """
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

    private static final String V2 = """
            define actor Counter as
              let int count;
              on_start(): void { self.count = 999; return; }
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
            pub routine main(): void { return; }
            """;

    private static long next(LogicalSourceActorProxyBridgeV1 bridge)
            throws Exception {
        var step = bridge.nextOutput(WAIT);
        assertFalse(step.done(), "actor output must not terminate");
        return assertInstanceOf(Long.class, step.value().value());
    }

    private static final String TEXT_ACTOR = """
            define actor TextSink as
              let string last;
              on_start(): void { self.last = ""; return; }
              receive(ActorMail<string> mail): void {
                self.last = mail.value;
                self.send(self.last);
                return;
              }
            end
            pub routine main(): void { return; }
            """;

    @Test
    void compilerMustProveExactActorMailIntBeforeBindingScalarTransport()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var text = manager.stageActorGeneration(
                    "non-scalar-receiver.ores", TEXT_ACTOR);
            var textCompiler = text.linkActorGeneration("TextSink").executor();
            assertFalse(textCompiler.actorReceivesProxyLong("TextSink"));
            assertThrows(SecurityException.class,
                    () -> LogicalSourceActorProxyBridgeV1.spawnSharedAbiBoundLong(
                            text, "TextSink", WAIT, 4,
                            SourceActorScalarWireV1.BYTE_LENGTH,
                            SourceActorScalarWireV1.BYTE_LENGTH * 4L),
                    "matching self-declared ABI hash cannot authorize a non-int receive");
            // Binding failed before publishing a proxy or accepting physical
            // mail. The staged generation remains reusable and retireable.
            manager.retire(text.id());
            assertTrue(text.closed());

            var intActor = manager.stageActorGeneration(
                    "scalar-receiver.ores", V1);
            assertTrue(intActor.linkActorGeneration("Counter").executor()
                    .actorReceivesProxyLong("Counter"));
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedAbiBoundLong(
                    intActor, "Counter", WAIT, 2,
                    SourceActorScalarWireV1.BYTE_LENGTH,
                    SourceActorScalarWireV1.BYTE_LENGTH * 2L)) {
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
            }
        }
    }

    @Test
    void scalarUpgradeRequiresCandidateCompilerProofBeforePhysicalHandoff()
            throws Exception {
        String wrongReceive = """
                define actor Counter as
                  let int count;
                  on_start(): void { self.count = 999; return; }
                  receive(ActorMail<string> mail): void {
                    self.send(self.count);
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("proof-v1.ores", V1);
            var incompatible = manager.stageActorGeneration(
                    "wrong-receive-v2.ores", wrongReceive);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedAbiBoundLong(
                    first, "Counter", WAIT, 2,
                    SourceActorScalarWireV1.BYTE_LENGTH,
                    SourceActorScalarWireV1.BYTE_LENGTH * 2L)) {
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                var old = bridge.publication();
                assertFalse(old.failSealed());
                assertTrue(old.authoritativeForDispatch());
                assertThrows(SecurityException.class,
                        () -> bridge.upgradeTo(incompatible, WAIT),
                        "candidate compiler must prove ActorMail<int> before old ingress freeze");
                assertTrue(bridge.failSealed(),
                        "invalid upgrade requires supervised reconciliation");
                var quarantined = bridge.publication();
                assertTrue(quarantined.failSealed(),
                        "coherent diagnostic must disclose terminal quarantine");
                assertFalse(quarantined.authoritativeForDispatch(),
                        "matching code and route digests are not a healthy certificate");
                assertEquals(old.address().routeEpoch(),
                        quarantined.address().routeEpoch(),
                        "denied compiler type cannot publish new route");
                assertEquals(old.generationId(), quarantined.generationId());
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void compilerAbiBoundScalarMessagesSurviveSourceUpgradeWithQueuedMail()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("typed-scalar-v1.ores", V1);
            var second = manager.stageActorGeneration("typed-scalar-v2.ores", V2);
            assertThrows(IllegalArgumentException.class,
                    () -> LogicalSourceActorProxyBridgeV1.spawnSharedAbiBoundLong(
                            first, "Counter", WAIT, 4, 8, 288),
                    "reject a too-small wire budget before creating a physical actor");
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedAbiBoundLong(
                    first, "Counter", WAIT, 4,
                    SourceActorScalarWireV1.BYTE_LENGTH,
                    SourceActorScalarWireV1.BYTE_LENGTH * 4L)) {
                var logical = bridge.address();
                var initialPublication = bridge.publication();
                assertEquals(first.id(), initialPublication.generationId());
                assertEquals(first.sha256(), initialPublication.codeSha256());
                assertEquals(
                        first.linkActorGeneration("Counter").executor()
                                .actorCodeSha256("Counter"),
                        initialPublication.linkedCodeSha256());
                var firstMessage = bridge.admitLong(41L);
                assertEquals(SourceActorScalarWireV1.BYTE_LENGTH,
                        firstMessage.payload().length);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));

                // Queued immutable old-generation bytes must not be rebound
                // to a new actor identity or mutable code/state pointer.
                var queuedAcrossUpgrade = bridge.admitLong(-1L);
                assertEquals(1, bridge.snapshot().queuedMessages());
                assertTrue(bridge.upgradeTo(second, WAIT));
                var successorPublication = bridge.publication();
                assertFalse(successorPublication.failSealed());
                assertTrue(successorPublication.authoritativeForDispatch());
                assertEquals(2L, successorPublication.address().routeEpoch());
                assertEquals(2L, successorPublication.mailbox().routeEpoch());
                assertEquals(second.id(), successorPublication.generationId());
                assertEquals(second.sha256(), successorPublication.codeSha256());
                assertEquals(
                        second.linkActorGeneration("Counter").executor()
                                .actorCodeSha256("Counter"),
                        successorPublication.linkedCodeSha256());
                assertEquals(initialPublication.stateSchemaSha256(),
                        successorPublication.stateSchemaSha256());
                assertEquals(initialPublication.messageAbiSha256(),
                        successorPublication.messageAbiSha256());
                assertEquals(logical.actorId(), bridge.address().actorId());
                assertEquals(logical.incarnation(), bridge.address().incarnation());
                assertEquals(SourceActorScalarWireV1.BYTE_LENGTH,
                        queuedAcrossUpgrade.payload().length);

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                bridge.admitLong(-2L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(45L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(3L, bridge.snapshot().completionWatermark());
            }
        }
    }

    @Test
    void abiBoundRouteRejectsTamperedAndLegacyMailBeforePhysicalSubmission()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration("typed-reject.ores", V1);
            try (var router = LogicalSourceActorRouterV1.spawn(
                          generation, ActorRuntime.ActorKind.SHARED,
                          "Counter", WAIT);
                 var proxy = new LogicalActorProxyMailboxV1(
                         4, SourceActorScalarWireV1.BYTE_LENGTH,
                         SourceActorScalarWireV1.BYTE_LENGTH * 4L)) {
                router.bindProxyMailboxForSupervisor(proxy, true);
                var binding = router.scalarWireBindingForProxy(proxy);
                byte[] invalid = SourceActorScalarWireV1.encodeLong(binding, 41L);
                invalid[63] ^= 1; // Compiler ABI digest, not message value.
                var attempted = proxy.admit(invalid);
                assertSame(attempted, proxy.pollDispatch());
                assertThrows(SecurityException.class,
                        () -> router.sendAndDrainForProxy(
                                proxy, attempted, proxy.snapshot().routeEpoch()));
                // The malformed frame was refused BEFORE reserving a physical
                // send, so a trusted supervisor may requeue it without
                // erasing uncertain physical delivery.
                proxy.requeueProvenUnsent(attempted, proxy.snapshot().routeEpoch());
                assertEquals(1, proxy.snapshot().queuedMessages());
                assertEquals(0, proxy.snapshot().outstandingMessages());
                assertEquals(1, proxy.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void compiledActorDrainReceiptRejectsForgedEnvelopeStaleEpochAndReplay()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("receipt-v1.ores", V1);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var proxy = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                router.bindProxyMailboxForSupervisor(proxy);
                assertTrue(router.proxyRouteEpochAlignedForTesting(proxy));
                assertThrows(SecurityException.class,
                        () -> router.ref().send(41L),
                        "raw host send must not bypass bound proxy credits");
                byte[] wire = new byte[Long.BYTES];
                wire[Long.BYTES - 1] = 41;
                var admitted = proxy.admit(wire);
                var dispatched = proxy.pollDispatch();
                assertSame(admitted, dispatched);

                // The wrong epoch is rejected BEFORE physical send. Credits
                // remain assigned to this outstanding logical delivery.
                assertThrows(SecurityException.class,
                        () -> router.sendAndDrainForProxy(
                                proxy, dispatched, proxy.snapshot().routeEpoch() + 1));
                assertEquals(1, proxy.snapshot().outstandingMessages());

                try (var foreignMailbox = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                    byte[] copy = dispatched.payload();
                    foreignMailbox.admit(copy);
                    foreignMailbox.pollDispatch();
                    assertThrows(SecurityException.class,
                            () -> router.sendAndDrainForProxy(
                                    foreignMailbox, dispatched,
                                    foreignMailbox.snapshot().routeEpoch()),
                            "same sequence and epoch cannot borrow another mailbox's envelope");
                }

                var receipt = router.sendAndDrainForProxy(
                        proxy, dispatched, proxy.snapshot().routeEpoch());
                assertThrows(IllegalStateException.class,
                        () -> router.sendAndDrainForProxy(
                                proxy, dispatched, proxy.snapshot().routeEpoch()),
                        "one accepted physical send cannot be submitted twice "
                                + "before its drain acknowledgment");
                assertEquals(1, proxy.snapshot().retainedMessages());
                // The physical actor may already have accepted the message,
                // even if its cumulative drain has not been consumed.
                var attempted = proxy.snapshot();
                assertThrows(IllegalStateException.class,
                        () -> proxy.requeueProvenUnsent(dispatched, proxy.snapshot().routeEpoch()),
                        "sequence-only requeue must never erase attempted physical delivery");
                assertEquals(attempted, proxy.snapshot());
                assertThrows(IllegalStateException.class,
                        () -> router.sendAndDrainForProxy(
                                proxy, dispatched, proxy.snapshot().routeEpoch()),
                        "rejected requeue must not reopen physical send admission");
                var forged = new LogicalActorProxyMailboxV1.Envelope(
                        dispatched.messageSequence(), dispatched.payload());
                assertThrows(SecurityException.class,
                        () -> receipt.awaitAndComplete(forged, WAIT),
                        "equal bytes and sequence are not a physical receipt");
                assertEquals(1, proxy.snapshot().retainedMessages());
                assertEquals(0L, proxy.snapshot().completionWatermark());

                receipt.awaitAndComplete(dispatched, WAIT);
                assertEquals(0, proxy.snapshot().retainedMessages());
                assertEquals(1L, proxy.snapshot().completionWatermark());
                assertThrows(IllegalStateException.class,
                        () -> receipt.awaitAndComplete(dispatched, WAIT),
                        "a cumulative physical drain cannot refund twice");
                assertEquals(0, proxy.snapshot().retainedMessages());
                var output = router.ref().nextOutput(WAIT);
                assertFalse(output.done());
                long observed = assertInstanceOf(
                        Long.class, output.value().value());
                assertEquals(42L, observed);
            }
        }
    }

    @Test
    void physicalReceiptTimeoutIrrevocablyConsumesCompletionClaim()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("timeout-receipt-v1.ores", V1);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var proxy = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                router.bindProxyMailboxForSupervisor(proxy);
                byte[] wire = new byte[Long.BYTES];
                wire[Long.BYTES - 1] = 41;
                var envelope = proxy.admit(wire);
                assertSame(envelope, proxy.pollDispatch());
                var receipt = router.sendAndDrainForProxy(
                        proxy, envelope, proxy.snapshot().routeEpoch());

                // Deliberately replace only the receipt's test observation
                // Future with a never-settling one. The real physical send
                // already happened; this reproduces an ambiguous terminal
                // acknowledgment without relying on scheduler timing.
                var physicalField = receipt.getClass()
                        .getDeclaredField("physicalReceipt");
                physicalField.setAccessible(true);
                Object physical = physicalField.get(receipt);
                var terminalField = physical.getClass().getDeclaredField("terminal");
                terminalField.setAccessible(true);
                var pending = new OresFuture<Void>(() -> false, () -> { });
                terminalField.set(physical, pending);

                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> receipt.awaitAndComplete(
                                envelope, Duration.ofMillis(1)));
                assertEquals(1, proxy.snapshot().outstandingMessages());
                assertEquals(1, proxy.snapshot().retainedMessages());
                assertEquals(0L, proxy.snapshot().completionWatermark());
                assertThrows(IllegalStateException.class,
                        () -> receipt.awaitAndComplete(envelope, WAIT),
                        "a timed-out receipt must never be retried to refund credits");
                pending.completeFromRuntime(null);
                assertThrows(IllegalStateException.class,
                        () -> receipt.awaitAndComplete(envelope, WAIT),
                        "even a late successful terminal cannot revive consumed receipt");
                assertThrows(IllegalStateException.class,
                        () -> router.sendAndDrainForProxy(
                                proxy, envelope, proxy.snapshot().routeEpoch()),
                        "ambiguous physical send remains recorded and cannot replay");
                assertEquals(1, proxy.snapshot().retainedMessages(),
                        "failed receipt must preserve credits for supervisor quarantine");
            }
        }
    }

    @Test
    void racingExactPhysicalReceiptAwaitersCannotRefundCreditsTwice()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("race-receipt-v1.ores", V1);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var proxy = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                router.bindProxyMailboxForSupervisor(proxy);
                var envelope = proxy.admit(
                        new byte[] {0, 0, 0, 0, 0, 0, 0, 41});
                assertSame(envelope, proxy.pollDispatch());
                var receipt = router.sendAndDrainForProxy(
                        proxy, envelope, proxy.snapshot().routeEpoch());

                // Let the ACTUAL runtime complete its physical message before
                // controlling just the host completion observation. Test-only
                // reflection prevents scheduling jitter from deciding the race.
                var physicalField = receipt.getClass()
                        .getDeclaredField("physicalReceipt");
                physicalField.setAccessible(true);
                Object physical = physicalField.get(receipt);
                var terminalField = physical.getClass().getDeclaredField("terminal");
                terminalField.setAccessible(true);
                @SuppressWarnings("unchecked")
                OresFuture<Void> realTerminal =
                        (OresFuture<Void>) terminalField.get(physical);
                realTerminal.get(5, java.util.concurrent.TimeUnit.SECONDS);
                var testTerminal = new OresFuture<Void>(() -> false, () -> { });
                terminalField.set(physical, testTerminal);

                var gate = new java.util.concurrent.CountDownLatch(1);
                var success = new java.util.concurrent.atomic.AtomicInteger();
                var refused = new java.util.concurrent.atomic.AtomicInteger();
                var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread[] contenders = new Thread[16];
                for (int i = 0; i < contenders.length; ++i) {
                    contenders[i] = Thread.ofPlatform().start(() -> {
                        try {
                            if (!gate.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("receipt race start timeout");
                            receipt.awaitAndComplete(envelope, WAIT);
                            success.incrementAndGet();
                        } catch (IllegalStateException duplicateClaim) {
                            refused.incrementAndGet();
                        } catch (Throwable failure) {
                            unexpected.compareAndSet(null, failure);
                        }
                    });
                }
                gate.countDown();
                testTerminal.completeFromRuntime(null);
                for (var contender : contenders) {
                    contender.join(5000);
                    assertFalse(contender.isAlive(), "receipt contender did not exit");
                }
                assertNull(unexpected.get());
                assertEquals(1, success.get(),
                        "one physical receipt may settle transport credits once");
                assertEquals(15, refused.get());
                assertEquals(0, proxy.snapshot().retainedMessages());
                assertEquals(1L, proxy.snapshot().completionWatermark());
                var output = router.ref().nextOutput(WAIT);
                assertFalse(output.done());
                assertEquals(42L, assertInstanceOf(Long.class, output.value().value()));
            }
        }
    }

    @Test
    void realCompiledSharedActorUpgradeWaitsForSuspendedPinnedTask()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit());
             OresScheduler scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("task-pinned-v1.ores", V1);
            var candidate = manager.stageActorGeneration("task-pinned-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                var identity = bridge.address();
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));

                var release = new OresFuture<Integer>();
                var suspended = new java.util.concurrent.CountDownLatch(1);
                var turns = new java.util.concurrent.atomic.AtomicInteger();
                var task = bridge.startHostPinnedTaskForUpgrade(
                        scheduler, resume -> {
                            turns.incrementAndGet();
                            if (resume.initial()) {
                                suspended.countDown();
                                return OresScheduler.await(release);
                            }
                            assertNull(resume.failure());
                            return OresScheduler.done((Integer) resume.value());
                        });
                assertTrue(suspended.await(5, java.util.concurrent.TimeUnit.SECONDS));

                // The real actor is idle, but the old compiled source context
                // still has a generation-pinned suspended Task. A checkpoint
                // must NOT confuse idle mailbox with physical quiescence.
                assertFalse(bridge.upgradeTo(candidate, WAIT));
                assertEquals(identity, bridge.address());
                assertFalse(bridge.failSealed(),
                        "busy old generation is a retryable refusal, not corruption");

                // Old source actor remains authoritative after rollback.
                bridge.admitLong(1L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));

                release.completeFromRuntime(7);
                assertEquals(7, task.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(2, turns.get(),
                        "resume must retain original Task pin, not admit a new one");

                // Result Future settlement can beat the scheduler's final
                // physical-exit callback. Poll the authoritative ingress
                // freeze rather than assuming get() certifies quiescence.
                boolean published = false;
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!published && System.nanoTime() < deadline) {
                    published = bridge.upgradeTo(candidate, WAIT);
                    if (!published) Thread.sleep(2L);
                }
                assertTrue(published,
                        "source-image publication requires physical Task exit");
                assertEquals(bridge.address().routeEpoch(),
                        bridge.publication().mailbox().routeEpoch());
                assertEquals(identity.actorId(), bridge.address().actorId());
                assertEquals(identity.incarnation(), bridge.address().incarnation());
                assertEquals(identity.routeEpoch() + 1, bridge.address().routeEpoch());
                bridge.admitLong(-2L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(45L, next(bridge));
            }
        }
    }

    @Test
    void nestedTaskOwnershipKeepsUpgradeFrozenUntilLastPhysicalChildExits()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             OresScheduler scheduler = new OresScheduler(2)) {
            var first = manager.stageActorGeneration("scope-nested-v1.ores", V1);
            var second = manager.stageActorGeneration("scope-nested-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                var nested = bridge.childHostTaskScopeForUpgrade().childScope();
                var one = new OresFuture<Integer>();
                var two = new OresFuture<Integer>();
                var started = new java.util.concurrent.CountDownLatch(2);
                var top = bridge.startHostPinnedTaskForUpgrade(
                        scheduler, resume -> {
                            if (resume.initial()) {
                                started.countDown();
                                return OresScheduler.await(one);
                            }
                            return OresScheduler.done((Integer) resume.value());
                        });
                var child = nested.start(scheduler, resume -> {
                    if (resume.initial()) {
                        started.countDown();
                        return OresScheduler.await(two);
                    }
                    return OresScheduler.done((Integer) resume.value());
                });
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(bridge.upgradeTo(second, WAIT),
                        "both child scopes hold physical old-generation pins");
                one.completeFromRuntime(1);
                assertEquals(1, top.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(bridge.upgradeTo(second, WAIT),
                        "one settled result cannot retire another suspended child");
                two.completeFromRuntime(2);
                assertEquals(2, child.get(5, java.util.concurrent.TimeUnit.SECONDS));
                boolean committed = false;
                long until = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!committed && System.nanoTime() < until) {
                    committed = bridge.upgradeTo(second, WAIT);
                    if (!committed) Thread.sleep(2);
                }
                assertTrue(committed);
                assertEquals(2L, bridge.publication().address().routeEpoch());
            }
        }
    }

    @Test
    void staleTaskScopeCannotRebindCapturedFrameToSuccessorActorGeneration()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             OresScheduler scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("epoch-scope-v1.ores", V1);
            var candidate = manager.stageActorGeneration("epoch-scope-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16)) {
                var old = bridge.childHostTaskScopeForUpgrade();
                var oldGrandchild = old.childScope();
                assertEquals(1L, bridge.address().routeEpoch());
                assertTrue(bridge.upgradeTo(candidate, WAIT));
                assertEquals(2L, bridge.address().routeEpoch());

                var incorrectlyExecuted =
                        new java.util.concurrent.atomic.AtomicBoolean();
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> old.start(scheduler, resume -> {
                            incorrectlyExecuted.set(true);
                            return OresScheduler.done(1);
                        }));
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> oldGrandchild.start(scheduler, resume -> {
                            incorrectlyExecuted.set(true);
                            return OresScheduler.done(2);
                        }));
                assertFalse(incorrectlyExecuted.get(),
                        "old TaskScope must not bind captured code to new actor");

                // The logical supervisor is stable across publication and may
                // mint a fresh child scope authenticated to the new epoch.
                var next = bridge.childHostTaskScopeForUpgrade();
                var result = next.start(scheduler,
                        resume -> OresScheduler.done(3));
                assertEquals(3, result.get(
                        5, java.util.concurrent.TimeUnit.SECONDS));
                next.close();
                old.close();
                assertTrue(old.physicallyQuiescent());
                assertTrue(next.physicallyQuiescent());
            }
        }
    }

    @Test
    void deeplyNestedIdleTaskScopesDoNotOverflowHostStack() throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var generation = manager.stageActorGeneration(
                    "deep-scope-tree.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    generation, "Counter", WAIT, 2, 8, 16)) {
                var root = bridge.childHostTaskScopeForUpgrade();
                var leaf = root;
                // Deep enough to overflow the former recursive tree probe
                // on ordinary host thread stacks. Close is also iterative.
                for (int i = 0; i < 12000; i++) {
                    leaf = leaf.childScope();
                }
                assertTrue(root.physicallyQuiescent());
                assertEquals(1, root.retainedChildScopesForTesting());
                root.close();
                assertTrue(root.physicallyQuiescent());
                assertTrue(leaf.physicallyQuiescent());
                assertEquals(0, root.retainedChildScopesForTesting(),
                        "fully closed deep trees must not leak strong child refs");
            }
        }
    }

    @Test
    void nestedScopeCloseJoinsPhysicalChildrenAndReconcilesOwnerSlots()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             OresScheduler scheduler = new OresScheduler(2)) {
            var generation = manager.stageActorGeneration(
                    "scope-physical-retirement.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    generation, "Counter", WAIT, 2, 8, 16)) {
                var root = bridge.childHostTaskScopeForUpgrade();
                var child = root.childScope();
                var grandchild = child.childScope();
                var entered = new java.util.concurrent.CountDownLatch(2);
                var releaseCarriers = new java.util.concurrent.atomic.AtomicBoolean();
                var closeError =
                        new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var first = child.start(scheduler, resume -> {
                    entered.countDown();
                    while (!releaseCarriers.get()) Thread.onSpinWait();
                    return OresScheduler.done(1);
                });
                var second = grandchild.start(scheduler, resume -> {
                    entered.countDown();
                    while (!releaseCarriers.get()) Thread.onSpinWait();
                    return OresScheduler.done(2);
                });
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                Thread closer = Thread.ofPlatform().start(() -> {
                    try {
                        root.close();
                    } catch (Throwable failure) {
                        closeError.set(failure);
                    }
                });
                try {
                    long until = System.nanoTime()
                            + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                    while ((!first.isDone() || !second.isDone())
                            && System.nanoTime() < until) {
                        Thread.sleep(2);
                    }
                    assertTrue(first.isCancelled());
                    assertTrue(second.isCancelled());
                    assertFalse(root.physicallyQuiescent(),
                            "cancelled result Futures cannot retire running carriers");
                    assertTrue(closer.isAlive(),
                            "parent must join all physical child/grandchild exits");
                } finally {
                    releaseCarriers.set(true);
                    closer.join(7000);
                }
                assertFalse(closer.isAlive(), "bounded close must finish");
                assertNull(closeError.get());
                assertTrue(root.physicallyQuiescent(),
                        "close return must reconcile owned slot records");
                assertTrue(child.physicallyQuiescent());
                assertTrue(grandchild.physicallyQuiescent());
                assertEquals(0, root.retainedChildScopesForTesting(),
                        "physically joined children must not remain retained");
                assertEquals(0, child.retainedChildScopesForTesting());
                assertThrows(java.util.concurrent.RejectedExecutionException.class,
                        () -> grandchild.start(scheduler,
                                resume -> OresScheduler.done(3)));
                assertTrue(bridge.upgradeTo(
                        manager.stageActorGeneration(
                                "scope-retired-next.ores", V2), WAIT),
                        "released child generation pins must allow migration");
            }
        }
    }

    @Test
    void physicalTaskOwnerCloseWaitsForExecutingCancelledCarrier()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             OresScheduler scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("scope-close.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var releaseCarrier = new java.util.concurrent.atomic.AtomicBoolean();
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            var closeError =
                    new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var task = bridge.startHostPinnedTaskForUpgrade(
                    scheduler, resume -> {
                        entered.countDown();
                        while (!releaseCarrier.get()) Thread.onSpinWait();
                        return OresScheduler.done(1);
                    });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    bridge.close();
                    closed.set(true);
                } catch (Throwable problem) {
                    closeError.set(problem);
                }
            });
            try {
                long until = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while (!task.isDone() && System.nanoTime() < until)
                    Thread.sleep(2);
                assertTrue(task.isDone(),
                        "scope close requests cancellation of the owned Task");
                assertFalse(closed.get(),
                        "cancelled Future must not retire an executing carrier");
                assertTrue(closer.isAlive(),
                        "scope close must wait for physical Task exit");
            } finally {
                releaseCarrier.set(true);
                closer.join(7000);
            }
            assertFalse(closer.isAlive(), "bounded host close should finish");
            assertNull(closeError.get(), "physical retirement must not fail");
            assertTrue(closed.get());
            assertThrows(IllegalStateException.class,
                    () -> bridge.startHostPinnedTaskForUpgrade(
                            scheduler, resume -> OresScheduler.done(2)));
        }
    }

    @Test
    void publicationSnapshotNeverExposesMixedRouteAndMailboxEpoch()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("coherent-v1.ores", V1);
            var next = manager.stageActorGeneration("coherent-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                var seen = new java.util.concurrent.atomic.AtomicInteger();
                var failure =
                        new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var stop = new java.util.concurrent.atomic.AtomicBoolean();
                var start = new java.util.concurrent.CountDownLatch(1);
                Thread observer = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        while (!stop.get()) {
                            var publication = bridge.publication();
                            if (publication.address().routeEpoch()
                                    != publication.mailbox().routeEpoch()) {
                                throw new AssertionError(
                                        "physical and proxy route epochs torn");
                            }
                            seen.incrementAndGet();
                        }
                    } catch (Throwable problem) {
                        failure.compareAndSet(null, problem);
                    }
                });
                try {
                    start.countDown();
                    long observedBy = System.nanoTime()
                            + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                    while (seen.get() == 0 && System.nanoTime() < observedBy)
                        Thread.sleep(2);
                    assertTrue(seen.get() > 0, "observer must start before publication");
                    assertTrue(bridge.upgradeTo(next, WAIT));
                    var publication = bridge.publication();
                    assertEquals(2L, publication.address().routeEpoch());
                    assertEquals(2L, publication.mailbox().routeEpoch());
                    bridge.admitLong(41L);
                    assertTrue(bridge.dispatchOne(WAIT));
                    assertEquals(42L, next(bridge));
                } finally {
                    stop.set(true);
                    observer.join(7000);
                }
                assertFalse(observer.isAlive());
                assertNull(failure.get());
                assertTrue(seen.get() > 0,
                        "observer must exercise epoch snapshots during upgrade");
            }
        }
    }

    @Test
    void realSourceActorDrainsProxyDeliveryBeforeRefundingCredits()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 2, 8, 16)) {
                var one = bridge.admitLong(41L);
                var two = bridge.admitLong(1L);
                assertEquals(1L, one.messageSequence());
                assertEquals(2L, two.messageSequence());
                assertEquals(2, bridge.snapshot().retainedMessages());
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(3L),
                        "queued message credits are bounded before execution");

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertEquals(1, bridge.snapshot().retainedMessages());
                assertEquals(1L, bridge.snapshot().completionWatermark());

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(2L, bridge.snapshot().completionWatermark());
                assertFalse(bridge.dispatchOne(WAIT));
                assertFalse(bridge.failSealed());
            }
        }
    }

    @Test
    void queuedMessagesCrossRealSourceUpgradeWithStableStateAndRouteIdentity()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            var candidate = manager.stageActorGeneration("bridge-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 4, 8, 32)) {
                var before = bridge.address();
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));

                // Retained immutable wire bytes are admitted but not yet
                // sent physically; route switching must preserve their FIFO.
                bridge.admitLong(-1L);
                bridge.admitLong(-2L);
                assertEquals(2, bridge.snapshot().queuedMessages());
                assertTrue(bridge.upgradeTo(candidate, WAIT));

                var after = bridge.address();
                assertEquals(before.actorId(), after.actorId());
                assertEquals(before.incarnation(), after.incarnation());
                assertEquals(before.routeEpoch() + 1L, after.routeEpoch());
                assertEquals(after.routeEpoch(), bridge.snapshot().routeEpoch());
                assertEquals(2, bridge.snapshot().queuedMessages());

                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(45L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(3L, bridge.snapshot().completionWatermark());
                assertFalse(bridge.failSealed());
            }
        }
    }

    @Test
    void queuedBytesSurviveAQuiescentUpgrade()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var initial = manager.stageActorGeneration("bridge-v1.ores", V1);
            var candidate = manager.stageActorGeneration("bridge-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    initial, "Counter", WAIT, 2, 8, 16)) {
                // Queued-but-not-dispatched bytes do not block a code switch;
                // they will be delivered to the new authoritative generation.
                bridge.admitLong(41L);
                assertTrue(bridge.upgradeTo(candidate, WAIT));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertEquals(1L, bridge.snapshot().completionWatermark());
            }
        }
    }

    @Test
    void admissionRacingSourceUpgradeStaysQueuedForAuthoritativeNewRoute()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("race-v1.ores", V1);
            var second = manager.stageActorGeneration("race-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));

                var start = new java.util.concurrent.CountDownLatch(1);
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread ingress = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        bridge.admitLong(-1L);
                    } catch (Throwable unexpected) {
                        failure.set(unexpected);
                    }
                });
                start.countDown();
                assertTrue(bridge.upgradeTo(second, WAIT));
                ingress.join(5000);
                assertFalse(ingress.isAlive(), "ingress did not finish");
                assertNull(failure.get());
                assertEquals(2L, bridge.address().routeEpoch());
                assertEquals(1, bridge.snapshot().queuedMessages());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertEquals(2L, bridge.snapshot().completionWatermark());
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void incompatiblePhysicalUpgradeFailsSealedInsteadOfReplayingMail()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("same-v1.ores", V1);
            var sameCode = manager.stageActorGeneration("same-code.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16)) {
                assertThrows(IllegalArgumentException.class,
                        () -> bridge.upgradeTo(sameCode, WAIT),
                        "source actor checkpoint refuses identical code generation");
                assertTrue(bridge.failSealed());
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(1L));
                assertThrows(IllegalStateException.class,
                        () -> bridge.dispatchOne(WAIT));
                assertEquals(1L, bridge.address().routeEpoch());
            }
        }
    }

    @Test
    void terminalUpgradeFailureCannotPublishPoisonAheadOfAnAdmissionLockHolder()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("poison-lock-v1.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16)) {
                var field = LogicalSourceActorProxyBridgeV1.class
                        .getDeclaredField("admissionLock");
                field.setAccessible(true);
                Object admission = field.get(bridge);
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread upgrader;
                synchronized (admission) {
                    upgrader = Thread.ofPlatform().start(() -> {
                        try {
                            // Exact current generation is a forbidden
                            // replacement. The failure must seal ingress.
                            bridge.upgradeTo(first, WAIT);
                            failure.set(new AssertionError(
                                    "same-generation upgrade should reject"));
                        } catch (IllegalArgumentException expected) {
                            failure.set(expected);
                        } catch (Throwable unexpected) {
                            failure.set(unexpected);
                        }
                    });
                    long deadline = System.nanoTime()
                            + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while (upgrader.getState() != Thread.State.BLOCKED
                            && System.nanoTime() < deadline) {
                        Thread.sleep(2);
                    }
                    assertEquals(Thread.State.BLOCKED, upgrader.getState(),
                            "upgrade must await the authoritative admission-lock seal");
                    assertFalse(bridge.failSealed(),
                            "poison cannot become observable ahead of admission fence");
                }
                upgrader.join(10000);
                assertFalse(upgrader.isAlive());
                assertInstanceOf(IllegalArgumentException.class, failure.get());
                assertTrue(bridge.failSealed());
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(1L),
                        "no envelope may enter after terminal poisoning");
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void asyncCarrierCannotBlockOnProxyReadOrWriteMonitors() throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             AsyncRuntime async = new AsyncRuntime()) {
            var first = manager.stageActorGeneration("async-guard-v1.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16)) {
                var task = async.submit(() -> {
                    assertTrue(AsyncRuntime.isAsyncCarrierThread());
                    assertThrows(SecurityException.class, bridge::address);
                    assertThrows(SecurityException.class, bridge::snapshot);
                    assertThrows(SecurityException.class, bridge::publication);
                    assertThrows(SecurityException.class,
                            () -> bridge.admitLong(1L));
                    return 1;
                });
                assertEquals(1, task.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(bridge.failSealed());
                assertEquals(1L, bridge.publication().address().routeEpoch());
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void schedulerTerminalCarrierCannotBlockOnProxyEpochOrAdmissionMonitors()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
             OresScheduler scheduler = new OresScheduler(1)) {
            var first = manager.stageActorGeneration("terminal-guard-v1.ores", V1);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16)) {
                var exited = new java.util.concurrent.CountDownLatch(1);
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var task = scheduler.start(
                        resume -> OresScheduler.done(1),
                        () -> {
                            try {
                                assertNull(OresScheduler.current(),
                                        "the terminal hook is not a guest Task turn");
                                assertTrue(OresScheduler.isSchedulerCarrierThread(),
                                        "the callback still runs on a guest carrier");
                                assertThrows(SecurityException.class, bridge::address);
                                assertThrows(SecurityException.class, bridge::snapshot);
                                assertThrows(SecurityException.class, bridge::publication);
                                assertThrows(SecurityException.class,
                                        bridge::routeEpochsAlignedForTesting);
                                assertThrows(SecurityException.class,
                                        () -> bridge.admitLong(1L));
                            } catch (Throwable unexpected) {
                                failure.set(unexpected);
                            } finally {
                                exited.countDown();
                            }
                        });
                assertEquals(1, task.get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(exited.await(5, java.util.concurrent.TimeUnit.SECONDS));
                assertNull(failure.get(), "terminal carrier must fail before any proxy locks");
                assertFalse(bridge.failSealed());
                assertEquals(1L, bridge.publication().address().routeEpoch(),
                        "ordinary host observations remain usable");
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void closingCannotSilentlyDropAcceptedQueuedActorMail() throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("queued-close.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16);
            try {
                bridge.admitLong(41L);
                var before = bridge.snapshot();
                assertThrows(IllegalStateException.class, bridge::close,
                        "proxy admission is not permission to discard accepted mail");
                assertEquals(before, bridge.snapshot());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertDoesNotThrow(bridge::close);
                assertThrows(IllegalStateException.class,
                        () -> bridge.admitLong(1L));
            } finally {
                // If a test assertion fails with queued credits, retain the
                // live generation rather than silently treating mail as done.
                if (bridge.snapshot().retainedMessages() == 0) {
                    bridge.close();
                }
            }
        }
    }

    @Test
    void admissionRacingCloseIsEitherRejectedOrRetainedUntilRealDelivery()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("close-race.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 2, 8, 16);
            var start = new java.util.concurrent.CountDownLatch(1);
            var received = new java.util.concurrent.atomic.AtomicBoolean();
            var ingressFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var closeFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread sender = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    bridge.admitLong(41L);
                    received.set(true);
                } catch (IllegalStateException closedFirst) {
                    // The close linearized before admission.
                } catch (Throwable unexpected) {
                    ingressFailure.set(unexpected);
                }
            });
            Thread closer = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    bridge.close();
                } catch (IllegalStateException mailOutstanding) {
                    // The sender admitted before close: it must not be lost.
                } catch (Throwable unexpected) {
                    closeFailure.set(unexpected);
                }
            });
            start.countDown();
            sender.join(10000);
            closer.join(10000);
            assertFalse(sender.isAlive() || closer.isAlive());
            assertNull(ingressFailure.get());
            assertNull(closeFailure.get());
            if (received.get()) {
                assertEquals(1, bridge.snapshot().retainedMessages());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
            } else {
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
            assertDoesNotThrow(bridge::close);
            assertDoesNotThrow(bridge::close,
                    "completed physical close must be idempotent");
            assertDoesNotThrow(first::close,
                    "finalized bridge must release its actor generation");
        }
    }

    @Test
    void closedBridgeForbidsLateMailboxAdmission()
            throws Exception {
        try (HotReloadManager manager =
                     new HotReloadManager(IsolatePolicy.developer(),
                             ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("closed.ores", V1);
            var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 1, 8, 8);
            bridge.close();
            assertDoesNotThrow(bridge::close);
            assertThrows(IllegalStateException.class,
                    () -> bridge.admitLong(1L));
            assertThrows(IllegalStateException.class,
                    () -> bridge.dispatchOne(WAIT));
            assertEquals(0, bridge.snapshot().retainedMessages());
        }
    }
    @Test
    void physicalAndProxyEpochsPublishTogetherUnderConcurrentObservation()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("atomic-v1.ores", V1);
            var second = manager.stageActorGeneration("atomic-v2.ores", V2);
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 6, 8, 48)) {
                var initial = bridge.address();
                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                bridge.admitLong(-1L);
                bridge.admitLong(-2L);

                var start = new java.util.concurrent.CountDownLatch(1);
                var started = new java.util.concurrent.CountDownLatch(1);
                var running = new java.util.concurrent.atomic.AtomicBoolean(true);
                var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread observer = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                        started.countDown();
                        while (running.get()) {
                            if (!bridge.routeEpochsAlignedForTesting()) {
                                throw new AssertionError(
                                        "observable split between physical and proxy route epochs");
                            }
                            Thread.onSpinWait();
                        }
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                });
                try {
                    start.countDown();
                    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(bridge.upgradeTo(second, WAIT));
                } finally {
                    running.set(false);
                    start.countDown();
                    observer.join(10000);
                }
                assertFalse(observer.isAlive());
                assertNull(unexpected.get());
                assertTrue(bridge.routeEpochsAlignedForTesting());
                assertEquals(initial.actorId(), bridge.address().actorId());
                assertEquals(initial.incarnation(), bridge.address().incarnation());
                assertEquals(2L, bridge.address().routeEpoch());
                assertEquals(2L, bridge.snapshot().routeEpoch());
                assertEquals(2, bridge.snapshot().queuedMessages());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(45L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void rejectedSupervisorUpgradeNeverLoadsAnUnauthorizedCandidate()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("preflight-v1.ores", V1);
            // Well-formed source with no "Counter" actor. Linking it as
            // Counter would fail; the security gate must reject first,
            // without invoking the compiler's actor linking path.
            var invalid = manager.stageActorGeneration(
                    "preflight-no-counter.ores",
                    V2.replace("actor Counter as", "actor OtherCounter as"));
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16);
                 var foreign = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                var valid = mailbox.freezeDispatchForUpgrade();
                var foreignTicket = foreign.freezeDispatchForUpgrade();
                try {
                    assertThrows(SecurityException.class,
                            () -> router.upgradeTo(invalid, WAIT),
                            "unproxied requests must fail before candidate linking");
                    assertThrows(SecurityException.class,
                            () -> router.upgradeToForProxy(
                                    invalid, WAIT, foreign, foreignTicket),
                            "foreign mailbox must fail before candidate linking");
                    assertThrows(IllegalStateException.class,
                            () -> router.upgradeToForProxy(
                                    invalid, WAIT, mailbox, foreignTicket),
                            "foreign ticket must fail before candidate linking");
                    assertEquals(1L, router.ref().routeEpoch());
                    assertEquals(1L, mailbox.snapshot().routeEpoch());
                    assertTrue(mailbox.snapshot().dispatchFrozen(),
                            "refused calls must leave the true freeze untouched");
                } finally {
                    foreignTicket.rollback();
                    valid.rollback();
                }
                // Unauthorized requests neither pin nor retain the candidate.
                manager.retire(invalid.id());
                assertTrue(invalid.closed());
            }
        }
    }

    @Test
    void boundRouterRejectsForeignFreezeAndUnproxiedUpgrade()
            throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("authority-v1.ores", V1);
            var second = manager.stageActorGeneration("authority-v2.ores", V2);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var proxy = new LogicalActorProxyMailboxV1(4, 8, 32);
                 var foreign = new LogicalActorProxyMailboxV1(4, 8, 32)) {
                router.bindProxyMailboxForSupervisor(proxy);
                assertThrows(IllegalStateException.class,
                        () -> router.bindProxyMailboxForSupervisor(foreign));
                assertThrows(SecurityException.class,
                        () -> router.upgradeTo(second, WAIT));
                assertTrue(router.proxyRouteEpochAlignedForTesting(proxy));
                var ownedTicket = proxy.freezeDispatchForUpgrade();
                var foreignTicket = foreign.freezeDispatchForUpgrade();
                assertThrows(SecurityException.class,
                        () -> router.upgradeToForProxy(
                                second, WAIT, foreign, foreignTicket));
                assertThrows(IllegalStateException.class,
                        () -> router.upgradeToForProxy(
                                second, WAIT, proxy, foreignTicket));
                assertEquals(1L, router.ref().routeEpoch());
                assertTrue(proxy.snapshot().dispatchFrozen());
                assertTrue(router.proxyRouteEpochAlignedForTesting(proxy));
                foreignTicket.rollback();
                // The refused attempts must not consume the trusted ticket.
                assertTrue(router.upgradeToForProxy(
                        second, WAIT, proxy, ownedTicket));
                assertTrue(router.proxyRouteEpochAlignedForTesting(proxy));
                assertEquals(2L, router.ref().routeEpoch());
                assertEquals(2L, proxy.snapshot().routeEpoch());
                assertThrows(IllegalStateException.class, ownedTicket::commit);
            }
        }
    }

    @Test
    void lateProxyBindingCannotInheritPriorRawDeliveries() throws Exception {
        try (HotReloadManager manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("late-bind-v1.ores", V1);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var proxy = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                // Prior raw admission would poison a later cumulative
                // physical drain proof, even if the raw send had thrown.
                router.ref().send(41L);
                assertThrows(IllegalStateException.class,
                        () -> router.bindProxyMailboxForSupervisor(proxy));
                assertEquals(1L, router.ref().routeEpoch());
                assertEquals(1L, proxy.snapshot().routeEpoch());
                assertEquals(0, proxy.snapshot().retainedMessages());
                var output = router.ref().nextOutput(WAIT);
                assertFalse(output.done());
                assertEquals(42L, assertInstanceOf(Long.class, output.value().value()));
            }
        }
    }

}
