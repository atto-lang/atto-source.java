package dev.oreslang.runtime;

import java.util.concurrent.CompletionStage;

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

    public static void main(String[] ignored) {
        var bridge = new IpcReceiptCompletionBridge(2);
        var epoch = env(1);
        Object owner = new Object();
        Object foreignOwner = new Object();

        bridge.claimPhysicalTransport(owner, epoch, 201);
        deny(() -> bridge.claimPhysicalTransport(foreignOwner, epoch, 202));
        deny(() -> bridge.claimPhysicalTransport(owner, epoch, 201));
         CompletionStage<IpcReceiptCorrelation.Stage> result =
                bridge.admitAndCommit(owner, epoch, 201);
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
        deny(() -> bridge.admitAndCommit(owner, env(2), 201));
        check(bridge.counters().equals(
                new IpcReceiptCorrelation.Counters(0, 0)));
        check(bridge.invariant());

        // Retirement never clears the owner binding. A reconnect must use a
        // fresh bridge, otherwise a delayed old close could acquire authority.
        deny(() -> bridge.claimPhysicalTransport(foreignOwner, epoch, 202));

        var fresh = new IpcReceiptCompletionBridge(1);
        fresh.claimPhysicalTransport(foreignOwner, epoch, 202);
        var next = env(2);
        var nextResult = fresh.admitAndCommit(foreignOwner, next, 202);
        check(fresh.transportLost(foreignOwner, next, 202) == 1);
        check(nextResult.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(fresh.invariant());

        System.out.println("JAVA_IPC_PHYSICAL_BRIDGE_BINDING_PASS checks=" + checks);
    }
}
