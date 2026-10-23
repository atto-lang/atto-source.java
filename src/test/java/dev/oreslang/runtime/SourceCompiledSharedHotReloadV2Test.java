package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end SHARED source actor hot-reload vertical slice:
 * source v1 -> real actor state -> actor-lane checkpoint -> source v2 restore
 * -> one logical route publication -> queued DATA executes on v2.
 */
@Timeout(45)
final class SourceCompiledSharedHotReloadV2Test {
    private static final String V1 = """
            define actor Counter as
              let int count;

              on_start(): void {
                self.count = 5;
                return;
              }

              receive(ActorMail<int> mail): void {
                self.count = self.count + mail.value;
                self.send(self.count);
                return;
              }
            end

            pub routine main(): void {
              val generic_main_must_not_run = 1;
              return;
            }
            """;

    private static final String V2 = """
            define actor Counter as
              let int count;

              on_start(): void {
                self.count = 999;
                return;
              }

              receive(ActorMail<int> mail): void {
                self.count = self.count + mail.value;
                self.count = self.count + 100;
                self.send(self.count);
                return;
              }
            end

            pub routine main(): void {
              val generic_main_must_not_run = 2;
              return;
            }
            """;

    private static byte[] encodeI64(long value) {
        return ByteBuffer.allocate(Long.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(value)
                .array();
    }

    private static Object decodeI64(byte[] bytes) {
        if (bytes.length != Long.BYTES)
            throw new IllegalArgumentException("expected canonical int64 mail");
        return ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong();
    }

    private static void awaitTrue(BooleanSupplier condition, String message)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail(message);
            Thread.sleep(2);
        }
    }

    private static long nextOutput(ActorRuntime.ActorRef<Object> ref)
            throws Exception {
        var step = ref.outputs().next().get(8, TimeUnit.SECONDS);
        assertFalse(step.done(), "actor output stream ended unexpectedly");
        Object value = step.value().value();
        assertInstanceOf(Long.class, value);
        return (Long) value;
    }

    private static ActorUpgradeEpochGate.Image image(
            HotReloadManager.ActorGenerationView view) {
        return new ActorUpgradeEpochGate.Image(
                view.executor().actorCodeSha256(view.actorTypeName()),
                view.executor().actorStateSchemaSha256(view.actorTypeName()),
                view.executor().actorMessageAbiSha256(view.actorTypeName()),
                "0".repeat(64));
    }

    @Test
    void sourceCompiledSharedActorKeepsStateAndSwitchesCodeAtOneLogicalCutover()
            throws Exception {
        try (var manager = new HotReloadManager(
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit());
             var completionExecutor = Executors.newSingleThreadExecutor()) {

            var oldGeneration =
                    manager.stageActorGeneration("counter-v1.ores", V1);
            var oldView = oldGeneration.linkActorGeneration("Counter");
            var candidateGeneration =
                    manager.stageActorGeneration("counter-v2.ores", V2);
            var candidateView =
                    candidateGeneration.linkActorGeneration("Counter");

            var oldImage = image(oldView);
            var candidateImage = image(candidateView);
            assertEquals(oldImage.stateSchemaSha256(),
                    candidateImage.stateSchemaSha256());
            assertEquals(oldImage.messageAbiSha256(),
                    candidateImage.messageAbiSha256());
            assertNotEquals(oldImage.codeSha256(),
                    candidateImage.codeSha256());

            try (var oldGenerationPin = oldGeneration.pinActorRoute();
                 var candidateGenerationPin = candidateGeneration.pinActorRoute()) {
                ActorRuntime.ActorRef<Object> oldRef =
                        oldView.runtime().spawnCodeActor(
                                ActorRuntime.ActorKind.SHARED,
                                oldView.policy(),
                                oldView.executor(),
                                oldView.actorTypeName());
                oldRef.getReady().get(8, TimeUnit.SECONDS);

                final byte[] initialCheckpoint;
                try (var initialFreeze =
                             oldView.runtime().beginSharedSourceActorUpgrade(oldRef)) {
                    initialCheckpoint =
                            initialFreeze.snapshot(8, TimeUnit.SECONDS);
                }

                var initialState = new ActorRoutePublicationV1.StateSnapshot(
                        oldImage.stateSchemaSha256(), initialCheckpoint);
                var mailbox = new LogicalActorProxyMailboxV1(8, 64, 1024);
                var gate = new ActorUpgradeEpochGate(9001, oldImage);
                try (var authority =
                             new ActorRoutePublicationV1<>(
                                     gate,
                                     mailbox,
                                     initialState,
                                     new SharedSourceActorHotReloadTransportV2.PhysicalRoute(
                                             oldView.runtime(), oldRef))) {
                    var transport = new SharedSourceActorHotReloadTransportV2(
                            completionExecutor,
                            SourceCompiledSharedHotReloadV2Test::decodeI64,
                            authority);

                    assertEquals(1L,
                            transport.admit(encodeI64(3)).messageSequence());
                    assertTrue(transport.pumpOne());
                    assertEquals(8L, nextOutput(oldRef),
                            "v1 on_start(5) + first mail(3)");
                    awaitTrue(
                            () -> mailbox.snapshot().completionWatermark() == 1L,
                            "old source actor exact completion did not advance logical watermark");

                    try (var stage = authority.stage(candidateImage)) {
                        // Admission remains open while logical DATA dispatch is frozen.
                        assertEquals(2L,
                                transport.admit(encodeI64(2)).messageSequence());
                        assertFalse(transport.pumpOne());

                        final byte[] migratedCheckpoint;
                        try (var oldPhysicalFreeze =
                                     oldView.runtime()
                                             .beginSharedSourceActorUpgrade(oldRef)) {
                            migratedCheckpoint =
                                    oldPhysicalFreeze.snapshot(
                                            8, TimeUnit.SECONDS);

                            var restoring = new RestoringSourceActorExecutorV2(
                                    candidateView.executor(),
                                    candidateView.actorTypeName(),
                                    migratedCheckpoint);
                            ActorRuntime.ActorRef<Object> newRef =
                                    candidateView.runtime().spawnCodeActor(
                                            ActorRuntime.ActorKind.SHARED,
                                            candidateView.policy(),
                                            restoring,
                                            candidateView.actorTypeName());
                            newRef.getReady().get(8, TimeUnit.SECONDS);

                            var newRoute =
                                    new SharedSourceActorHotReloadTransportV2.PhysicalRoute(
                                            candidateView.runtime(), newRef);
                            assertTrue(stage.quiescent());
                            assertTrue(stage.publish(
                                    new ActorRoutePublicationV1.StateSnapshot(
                                            candidateImage.stateSchemaSha256(),
                                            migratedCheckpoint),
                                    newRoute));

                            // Publication is now authoritative. Keep the old
                            // physical actor fenced until it is fully retired.
                            oldPhysicalFreeze.commitForRetirement();
                            oldRef.stop();
                            oldRef.getDone().get(8, TimeUnit.SECONDS);

                            assertEquals(2L, authority.current().routeEpoch());
                            assertSame(newRoute,
                                    authority.current().physicalRoute());

                            assertTrue(transport.pumpOne());
                            // Restored state is 8. V2 adds mail(2), then +100.
                            // If candidate on_start incorrectly ran, this would
                            // instead be 1101.
                            assertEquals(110L, nextOutput(newRef));
                            awaitTrue(
                                    () -> mailbox.snapshot()
                                            .completionWatermark() == 2L,
                                    "candidate source actor completion did not advance watermark");
                            assertEquals(0,
                                    mailbox.snapshot().retainedMessages());

                            newRef.stop();
                            newRef.getDone().get(8, TimeUnit.SECONDS);
                        }
                    }
                }

                // Physical actors are gone before their generation contexts
                // lose route pins or are retired.
            }

            manager.retire(oldGeneration.id());
            manager.retire(candidateGeneration.id());
            assertEquals(0, manager.liveGenerations());
        }
    }
}
