package dev.oreslang.runtime;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only proof that a receipt bridge cannot cross physical reconnects. */
public final class IpcReceiptPhysicalBindingSelfTest {
    private IpcReceiptPhysicalBindingSelfTest() { }
    private static int checks;

    private static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("physical bridge binding invariant failed");
    }

    private static void deny(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            ++checks;
            return;
        }
        throw new AssertionError("stale/foreign physical bridge authority accepted");
    }

    private static IpcReceiptCorrelation.Envelope env(long sequence) {
        return new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "session",
                "a".repeat(64), 1, 2, 3, 4, sequence, sequence);
    }

    public static void main(String[] ignored) throws Exception {
        // Every bridge gate operation must reject an actor carrier before
        // Java monitor entry. Hold the actual private gate to prove the guard
        // is outside, not merely inside, the synchronized region.
        var guardedBridge = new IpcReceiptCompletionBridge(1);
        var guardedEpoch = env(90);
        Object guardedOwner = new Object();
        guardedBridge.claimPhysicalTransport(guardedOwner, guardedEpoch, 190);
        var gateField = IpcReceiptCompletionBridge.class.getDeclaredField("gate");
        gateField.setAccessible(true);
        Object bridgeGate = gateField.get(guardedBridge);

        CountDownLatch gateHeld = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        Thread gateHolder = new Thread(() -> {
            synchronized (bridgeGate) {
                gateHeld.countDown();
                try {
                    if (!releaseGate.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("bridge gate hold timed out");
                    }
                } catch (Throwable failure) {
                    holderFailure.set(failure);
                }
            }
        }, "ipc-test-bridge-gate-holder");
        gateHolder.start();
        check(gateHeld.await(3, TimeUnit.SECONDS));

        AtomicInteger gateDenials = new AtomicInteger();
        AtomicReference<Throwable> carrierFailure = new AtomicReference<>();
        Thread carrier = new Thread(() -> {
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                Runnable[] probes = {
                        () -> guardedBridge.claimPhysicalTransport(
                                new Object(), guardedEpoch, 191),
                        () -> guardedBridge.admit(
                                guardedOwner, guardedEpoch, 190),
                        () -> guardedBridge.admitAndCommit(
                                guardedOwner, guardedEpoch, 190),
                        () -> guardedBridge.commitDispatch(
                                guardedOwner, guardedEpoch, 190),
                        () -> guardedBridge.acceptVerified(
                                guardedOwner, guardedEpoch, 190,
                                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED),
                        () -> guardedBridge.applyVerified(
                                guardedOwner, guardedEpoch, 190,
                                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED),
                        () -> guardedBridge.rejectBeforeRemote(
                                guardedOwner, guardedEpoch, 190),
                        () -> guardedBridge.transportLost(
                                guardedOwner, guardedEpoch, 190),
                        () -> guardedBridge.counters(),
                        () -> guardedBridge.invariant()
                };
                for (Runnable probe : probes) {
                    try {
                        probe.run();
                        throw new AssertionError(
                                "actor carrier entered IPC receipt bridge gate");
                    } catch (IllegalStateException expected) {
                        gateDenials.incrementAndGet();
                    }
                }
            } catch (Throwable failure) {
                carrierFailure.set(failure);
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
        }, "ipc-test-bridge-actor-carrier");
        carrier.start();
        carrier.join(500);
        check(!carrier.isAlive());
        check(carrierFailure.get() == null);
        check(gateDenials.get() == 10);

        releaseGate.countDown();
        gateHolder.join(3000);
        check(!gateHolder.isAlive());
        check(holderFailure.get() == null);
        check(guardedBridge.counters().equals(
                new IpcReceiptCorrelation.Counters(0, 0)));

        // Verified publication has its own monitor because dependent futures
        // may run synchronously. It must obey the same fail-before-monitor rule.
        var publication = new IpcReceiptCompletionBridge.VerifiedCompletion(
                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        CountDownLatch publicationHeld = new CountDownLatch(1);
        CountDownLatch releasePublication = new CountDownLatch(1);
        Thread publicationHolder = new Thread(() -> {
            synchronized (publication) {
                publicationHeld.countDown();
                try {
                    if (!releasePublication.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("publication monitor hold timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }, "ipc-test-publication-monitor-holder");
        publicationHolder.start();
        check(publicationHeld.await(3, TimeUnit.SECONDS));

        AtomicReference<Throwable> publicationFailure = new AtomicReference<>();
        Thread publicationCarrier = new Thread(() -> {
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                try {
                    publication.publish();
                    throw new AssertionError(
                            "actor carrier entered publication monitor");
                } catch (IllegalStateException expected) {
                    // required
                }
            } catch (Throwable failure) {
                publicationFailure.set(failure);
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
        }, "ipc-test-publication-actor-carrier");
        publicationCarrier.start();
        publicationCarrier.join(500);
        check(!publicationCarrier.isAlive());
        check(publicationFailure.get() == null);

        releasePublication.countDown();
        publicationHolder.join(3000);
        check(!publicationHolder.isAlive());

        var bridge = new IpcReceiptCompletionBridge(2);
        var epoch = env(1);
        Object owner = new Object();
        Object foreignOwner = new Object();

        bridge.claimPhysicalTransport(owner, epoch, 201);
        deny(() -> bridge.claimPhysicalTransport(foreignOwner, epoch, 202));
        deny(() -> bridge.claimPhysicalTransport(owner, epoch, 201));
        deny(() -> bridge.transportLost(epoch));

        deny(() -> bridge.admit(epoch));
        CompletionStage<IpcReceiptCorrelation.Stage> result =
                bridge.admit(owner, epoch, 201);
        deny(() -> bridge.commitDispatch(epoch));
        bridge.commitDispatch(owner, epoch, 201);
        deny(() -> bridge.transportLost(foreignOwner, epoch, 201));
        deny(() -> bridge.transportLost(owner, epoch, 202));

        var changedEpoch = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "session",
                "a".repeat(64), 1, 2, 5, 6, 2, 2);
        deny(() -> bridge.transportLost(owner, changedEpoch, 201));
        check(!result.toCompletableFuture().isDone());

        check(bridge.transportLost(owner, epoch, 201) == 1);
        check(result.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(bridge.counters().equals(
                new IpcReceiptCorrelation.Counters(0, 0)));
        check(bridge.invariant());

        // Retirement never clears the owner binding. A reconnect must use a
        // fresh bridge, otherwise a delayed old close could acquire authority.
        deny(() -> bridge.claimPhysicalTransport(foreignOwner, epoch, 202));

        var fresh = new IpcReceiptCompletionBridge(1);
        fresh.claimPhysicalTransport(foreignOwner, epoch, 202);
        var next = env(2);
        var nextResult = fresh.admit(foreignOwner, next, 202);
        fresh.commitDispatch(foreignOwner, next, 202);
        check(fresh.transportLost(foreignOwner, next, 202) == 1);
        check(nextResult.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(fresh.invariant());

        System.out.println("JAVA_IPC_PHYSICAL_BRIDGE_BINDING_PASS checks=" + checks);
    }
}
