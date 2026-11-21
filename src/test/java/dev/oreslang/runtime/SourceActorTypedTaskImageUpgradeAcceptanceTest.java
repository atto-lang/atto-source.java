package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Combined real-product SHARED hot-upgrade acceptance scenario:
 * compiler-verified ORM2 INT -> bounded immutable logical mailbox ->
 * runtime-minted physical completion -> canonical TaskScope generation
 * pin -> checkpoint/state migration -> one coherent image/epoch witness ->
 * physical finalization and old-generation retirement.
 *
 * This remains trusted-host SHARED execution, NOT PRIVATE/UNTRUSTED/LLVM
 * isolation or authenticated remote actor routing.
 */
@Timeout(80)
final class SourceActorTypedTaskImageUpgradeAcceptanceTest {
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

    private static final String V2 = V1.replace(
            "self.count = self.count + mail.value;",
            "if mail.value < 0; do\n"
            + "  self.count = self.count - mail.value;\n"
            + "else\n"
            + "  self.count = self.count + mail.value;\n"
            + "fi");

    private static long next(LogicalSourceActorProxyBridgeV1 bridge)
            throws Exception {
        var output = bridge.nextOutput(WAIT);
        assertFalse(output.done(), "source actor output unexpectedly ended");
        return assertInstanceOf(Long.class, output.value().value());
    }

    @Test
    void orm2QueuedMailSuspendedTaskAndStatePublishAsOneCoherentUpgrade()
            throws Exception {
        try (var manager = new HotReloadManager(
                     IsolatePolicy.developer(), ExecutionProfile.serverJit());
             var scheduler = new OresScheduler(2)) {
            var first = manager.stageActorGeneration("accept-v1.ores", V1);
            var second = manager.stageActorGeneration("accept-v2.ores", V2);
            String messageAbi = first.linkActorGeneration("Counter")
                    .executor().actorMessageAbiSha256("Counter");
            assertEquals(messageAbi, second.linkActorGeneration("Counter")
                    .executor().actorMessageAbiSha256("Counter"));
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnSharedTypedPrimitive(
                    first, "Counter", WAIT, 4, 128, 512);
                 var oldScope = bridge.childHostTaskScopeForUpgrade()) {
                var before = bridge.publication();
                var identity = before.address();
                assertEquals(first.id(), before.generationId());
                assertEquals(messageAbi, before.messageAbiSha256());
                assertEquals(1L, identity.routeEpoch());
                assertEquals(1L, before.mailbox().routeEpoch());

                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT),
                        "only the physical PROCESSED receipt may refund ORM2 credit");
                assertEquals(42L, next(bridge));

                // Real scheduler frame enters the old code-generation lease,
                // then awaits a Future without occupying a carrier thread.
                var release = new OresFuture<Integer>();
                var entered = new CountDownLatch(1);
                var resumptions = new AtomicInteger();
                var task = oldScope.start(scheduler, resumed -> {
                    resumptions.incrementAndGet();
                    if (resumed.initial()) {
                        entered.countDown();
                        return OresScheduler.await(release);
                    }
                    assertNull(resumed.failure());
                    return OresScheduler.done((Integer) resumed.value());
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(oldScope.physicallyQuiescent());

                // Immutable mail is admitted while V1 is still authoritative,
                // but stays queued across the freeze and eventual code switch.
                bridge.admitLong(-1L);
                assertFalse(bridge.upgradeTo(second, WAIT),
                        "idle actor inbox must not disguise suspended Task pin");
                var refused = bridge.publication();
                assertEquals(before.generationId(), refused.generationId());
                assertEquals(identity, refused.address());
                assertEquals(identity.routeEpoch(), refused.mailbox().routeEpoch());
                assertFalse(bridge.failSealed(),
                        "a busy-generation refusal is safely retryable");
                bridge.admitLong(-2L);
                assertEquals(2, bridge.snapshot().queuedMessages());

                release.completeFromRuntime(7);
                assertEquals(7, task.get(5, TimeUnit.SECONDS));
                assertEquals(2, resumptions.get());
                // A task result is NOT its physical quiescence barrier.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!oldScope.physicallyQuiescent()
                        && System.nanoTime() < deadline) {
                    Thread.sleep(2);
                }
                assertTrue(oldScope.physicallyQuiescent(),
                        "old generation pinned frame must physically exit");
                oldScope.close();

                boolean committed = false;
                while (!committed && System.nanoTime() < deadline) {
                    committed = bridge.upgradeTo(second, WAIT);
                    if (!committed) Thread.sleep(2);
                }
                assertTrue(committed, "quiescent ORM2 SHARED actor must be upgradeable");
                var after = bridge.publication();
                assertEquals(identity.actorId(), after.address().actorId());
                assertEquals(identity.incarnation(), after.address().incarnation());
                assertEquals(identity.routeEpoch() + 1L, after.address().routeEpoch());
                assertEquals(after.address().routeEpoch(), after.mailbox().routeEpoch());
                assertEquals(second.id(), after.generationId());
                assertEquals(second.sha256(), after.codeSha256());
                assertNotEquals(before.linkedCodeSha256(), after.linkedCodeSha256());
                assertEquals(before.stateSchemaSha256(), after.stateSchemaSha256());
                assertEquals(messageAbi, after.messageAbiSha256());
                assertEquals(2, after.mailbox().queuedMessages());

                // V2 reverses negative values. The output 43/45 proves both
                // that V1's count=42 checkpoint persisted and the V2 code ran.
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(45L, next(bridge));
                assertEquals(0, bridge.snapshot().retainedMessages());
                assertEquals(3L, bridge.snapshot().completionWatermark());

                // Old executable image is no longer owned by a live actor,
                // queued message, or physically executing Task. Retirement is
                // now legal even while V2 continues serving logical mail.
                manager.retire(first.id());
                assertTrue(first.closed());
                bridge.admitLong(-3L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(48L, next(bridge));
                assertEquals(4L, bridge.snapshot().completionWatermark());
            }
        }
    }
}
