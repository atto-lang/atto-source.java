package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end SHARED source-compiled code-image and mailbox observation proof.
 * This is a local supervisor witness, not a remote authenticated code receipt.
 */
@Timeout(60)
final class LogicalSourceActorImagePublicationTest {
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
        var step = bridge.nextOutput(WAIT);
        assertFalse(step.done());
        return assertInstanceOf(Long.class, step.value().value());
    }

    @Test
    void concurrentPublicationNeverCombinesOldImageWithNewProxyEpoch()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("image-v1.ores", V1);
            var second = manager.stageActorGeneration("image-v2.ores", V2);
            var firstCompiler = first.linkActorGeneration("Counter").executor();
            var secondCompiler = second.linkActorGeneration("Counter").executor();
            String firstCode = firstCompiler.actorCodeSha256("Counter");
            String secondCode = secondCompiler.actorCodeSha256("Counter");
            String stateLayout = firstCompiler.actorStateSchemaSha256("Counter");
            String messageAbi = firstCompiler.actorMessageAbiSha256("Counter");
            assertNotEquals(firstCode, secondCode);
            assertEquals(stateLayout,
                    secondCompiler.actorStateSchemaSha256("Counter"));
            assertEquals(messageAbi,
                    secondCompiler.actorMessageAbiSha256("Counter"));
            try (var bridge = LogicalSourceActorProxyBridgeV1.spawnShared(
                    first, "Counter", WAIT, 4, 8, 32)) {
                var before = bridge.publication();
                assertEquals(1L, before.address().routeEpoch());
                assertEquals(first.id(), before.generationId());
                assertEquals(first.sha256(), before.codeSha256());
                assertEquals(firstCode, before.linkedCodeSha256());
                assertEquals(stateLayout, before.stateSchemaSha256());
                assertEquals(messageAbi, before.messageAbiSha256());
                assertEquals(1L, before.mailbox().routeEpoch());

                bridge.admitLong(41L);
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(42L, next(bridge));
                bridge.admitLong(-1L);

                var go = new CountDownLatch(1);
                var seen = new AtomicInteger();
                var running = new java.util.concurrent.atomic.AtomicBoolean(true);
                var error = new AtomicReference<Throwable>();
                var observer = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                        while (running.get()) {
                            var published = bridge.publication();
                            assertEquals(published.address().routeEpoch(),
                                    published.mailbox().routeEpoch());
                            if (published.address().routeEpoch() == 1L) {
                                assertEquals(first.id(), published.generationId());
                                assertEquals(first.sha256(), published.codeSha256());
                                assertEquals(firstCode, published.linkedCodeSha256());
                            } else if (published.address().routeEpoch() == 2L) {
                                assertEquals(second.id(), published.generationId());
                                assertEquals(second.sha256(), published.codeSha256());
                                assertEquals(secondCode, published.linkedCodeSha256());
                            } else {
                                throw new AssertionError("unexpected source image epoch");
                            }
                            assertEquals(stateLayout, published.stateSchemaSha256());
                            assertEquals(messageAbi, published.messageAbiSha256());
                            seen.incrementAndGet();
                            Thread.onSpinWait();
                        }
                    } catch (Throwable failure) {
                        error.compareAndSet(null, failure);
                    }
                });
                try {
                    go.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (seen.get() == 0 && System.nanoTime() < deadline) {
                        Thread.sleep(2);
                    }
                    assertTrue(seen.get() > 0);
                    assertTrue(bridge.upgradeTo(second, WAIT));
                } finally {
                    running.set(false);
                    go.countDown();
                    observer.join(8000);
                }
                assertFalse(observer.isAlive());
                assertNull(error.get());
                var after = bridge.publication();
                assertEquals(before.address().actorId(), after.address().actorId());
                assertEquals(before.address().incarnation(),
                        after.address().incarnation());
                assertEquals(2L, after.address().routeEpoch());
                assertEquals(2L, after.mailbox().routeEpoch());
                assertNotEquals(before.generationId(), after.generationId());
                assertNotEquals(before.codeSha256(), after.codeSha256());
                assertEquals(second.id(), after.generationId());
                assertEquals(second.sha256(), after.codeSha256());
                assertEquals(secondCode, after.linkedCodeSha256());
                assertEquals(stateLayout, after.stateSchemaSha256());
                assertEquals(messageAbi, after.messageAbiSha256());
                assertEquals(1, after.mailbox().queuedMessages());
                assertTrue(bridge.dispatchOne(WAIT));
                assertEquals(43L, next(bridge));
                assertEquals(0, bridge.publication().mailbox().retainedMessages());
            }
        }
    }

    @Test
    void foreignMailboxCannotObserveCodeOrPhysicalSupervisorWitness()
            throws Exception {
        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var first = manager.stageActorGeneration("foreign-image.ores", V1);
            try (var router = LogicalSourceActorRouterV1.spawn(
                         first, ActorRuntime.ActorKind.SHARED, "Counter", WAIT);
                 var mailbox = new LogicalActorProxyMailboxV1(2, 8, 16);
                 var foreign = new LogicalActorProxyMailboxV1(2, 8, 16)) {
                router.bindProxyMailboxForSupervisor(mailbox);
                var witness = router.supervisorPublication(mailbox);
                assertEquals(1L, witness.address().routeEpoch());
                assertEquals(witness.address().routeEpoch(),
                        witness.mailbox().routeEpoch());
                assertEquals(first.id(), witness.generationId());
                assertEquals(first.sha256(), witness.codeSha256());
                assertEquals(
                        first.linkActorGeneration("Counter").executor()
                                .actorCodeSha256("Counter"), witness.linkedCodeSha256());
                assertEquals(
                        first.linkActorGeneration("Counter").executor()
                                .actorStateSchemaSha256("Counter"), witness.stateSchemaSha256());
                assertEquals(
                        first.linkActorGeneration("Counter").executor()
                                .actorMessageAbiSha256("Counter"), witness.messageAbiSha256());
                assertEquals(router.physicalAddressForTesting(),
                        witness.physicalAddress());
                assertThrows(SecurityException.class,
                        () -> router.supervisorPublication(foreign));
            }
        }
    }
}
