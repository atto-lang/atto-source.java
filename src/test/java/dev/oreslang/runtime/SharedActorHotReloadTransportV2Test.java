package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First physical SHARED vertical slice: the stable logical route sends through
 * a real ActorRuntime mailbox and advances its watermark only after the exact
 * physical message has finished processing.
 *
 * This deliberately uses host-created SHARED actors. Source compilation,
 * checkpoint restore and old-generation physical retirement remain later
 * gates and are not claimed here.
 */
@Timeout(20)
final class SharedActorHotReloadTransportV2Test {
    private static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(
                sha(code), sha('1'), sha('2'), sha('3'));
    }

    private static ActorRoutePublicationV1.StateSnapshot state(int value) {
        return new ActorRoutePublicationV1.StateSnapshot(
                sha('1'), new byte[] {(byte) value});
    }

    private static void awaitTrue(BooleanSupplier condition, String message)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail(message);
            Thread.sleep(2);
        }
    }

    @Test
    void stableLogicalRouteCutsOverBetweenRealSharedActorMailboxes()
            throws Exception {
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch newObserved = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime();
             var coordinator = Executors.newSingleThreadExecutor()) {
            var oldPhysical = runtime.<byte[]>spawnShared(() -> (message, context) -> {
                oldEntered.countDown();
                if (!releaseOld.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("old physical actor release timed out");
                }
            });
            var newPhysical = runtime.<byte[]>spawnShared(() -> (message, context) -> {
                assertArrayEquals(new byte[] {2}, message);
                newObserved.countDown();
            });

            oldPhysical.getReady().get(5, TimeUnit.SECONDS);
            newPhysical.getReady().get(5, TimeUnit.SECONDS);

            try (var gate = new ActorUpgradeEpochGate(7001, image('a'));
                 var mailbox = new LogicalActorProxyMailboxV1(8, 64, 512);
                 var authority = new ActorRoutePublicationV1<>(
                         gate, mailbox, state(1), oldPhysical)) {
                var transport = new SharedActorHotReloadTransportV2(
                        runtime, coordinator, authority);

                assertEquals(1L, transport.admit(new byte[] {1}).messageSequence());
                assertTrue(transport.pumpOne());
                assertTrue(oldEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0L, mailbox.snapshot().completionWatermark());

                try (var staged = authority.stage(image('b'))) {
                    assertEquals(2L,
                            transport.admit(new byte[] {2}).messageSequence());
                    assertFalse(transport.pumpOne(),
                            "DATA dispatch must remain frozen during upgrade");
                    assertFalse(staged.quiescent(),
                            "old physical message still owns the drain watermark");

                    releaseOld.countDown();
                    awaitTrue(staged::quiescent,
                            "exact physical completion did not reach logical watermark");

                    assertTrue(staged.publish(state(2), newPhysical));
                    var published = authority.current();
                    assertEquals(7001L, published.actorIdentity());
                    assertEquals(1L, published.incarnation());
                    assertEquals(2L, published.routeEpoch());
                    assertSame(newPhysical, published.physicalRoute());
                    assertEquals(1L, published.oldDispatchWatermark());
                }

                assertTrue(transport.pumpOne());
                assertTrue(newObserved.await(5, TimeUnit.SECONDS));
                awaitTrue(() -> mailbox.snapshot().completionWatermark() == 2L,
                        "new physical message completion did not advance watermark");
                assertEquals(0, mailbox.snapshot().retainedMessages());

                oldPhysical.stop();
                oldPhysical.getDone().get(5, TimeUnit.SECONDS);
                newPhysical.stop();
                newPhysical.getDone().get(5, TimeUnit.SECONDS);
            }
        } finally {
            releaseOld.countDown();
        }
    }

    @Test
    void completionCoordinatorCannotRunLogicalSettlementOnActorCarrier()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var physical = runtime.<byte[]>spawnShared(() -> (message, context) -> {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("physical actor release timed out");
                }
            });
            physical.getReady().get(5, TimeUnit.SECONDS);

            try (var gate = new ActorUpgradeEpochGate(7002, image('a'));
                 var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
                 var authority = new ActorRoutePublicationV1<>(
                         gate, mailbox, state(1), physical)) {
                // Deliberately hostile/direct executor. Hold the actor until
                // pumpOne() has installed its Future callback, then release it:
                // completion is now guaranteed to originate on the actor
                // carrier and the direct executor would otherwise run inline.
                var transport = new SharedActorHotReloadTransportV2(
                        runtime, Runnable::run, authority);
                transport.admit(new byte[] {9});
                assertTrue(transport.pumpOne());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                release.countDown();

                awaitTrue(() -> authority.quarantineCause() != null,
                        "inline actor-carrier completion was not quarantined");
                assertInstanceOf(IllegalStateException.class,
                        authority.quarantineCause());
                assertThrows(IllegalStateException.class, authority::current);
                assertEquals(0L, mailbox.snapshot().completionWatermark(),
                        "quarantined carrier callback must not forge completion");

                physical.stop();
                physical.getDone().get(5, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
        }
    }
}
