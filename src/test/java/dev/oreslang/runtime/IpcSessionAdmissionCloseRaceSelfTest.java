package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-only race proof for atomic OPEN-session admission + dispatch commitment.
 * No socket, guest actor, or remote peer is involved.
 */
public final class IpcSessionAdmissionCloseRaceSelfTest {
    private IpcSessionAdmissionCloseRaceSelfTest() { }
    private static int checks;

    private static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("session admission/close race invariant failed");
    }

    private static IpcReceiptCorrelation.Envelope env(long sequence) {
        return new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "race",
                "a".repeat(64), 1, 2, 3, 4, sequence, sequence);
    }

    public static void main(String[] ignored) throws Exception {
        ExecutorService cleanup = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "ipc-admission-close-cleanup"));
        try {
            AtomicInteger admittedWins = new AtomicInteger();
            AtomicInteger closeWins = new AtomicInteger();

            for (int trial = 0; trial < 512; ++trial) {
                var bridge = new IpcReceiptCompletionBridge(1);
                var message = env(1);
                Object owner = new Object();
                bridge.claimPhysicalTransport(owner, message, 77);
                var lease = new IpcTransportSessionLease(
                        message, 77, 1, bridge, owner,
                        (ignoredMessage, ignoredFrame) -> { },
                        () -> { },
                        cleanup::execute);

                CountDownLatch fire = new CountDownLatch(1);
                AtomicReference<IpcTransportSessionLease.WriteAttempt> admitted =
                        new AtomicReference<>();
                AtomicReference<Throwable> problem = new AtomicReference<>();
                AtomicInteger rejected = new AtomicInteger();

                Thread sender = new Thread(() -> {
                    try {
                        fire.await();
                        admitted.set(lease.sendPreparedFrame(
                                77, message, new byte[] {1}));
                    } catch (IpcTransportSessionLease.PreDispatchRejectedException expected) {
                        rejected.incrementAndGet();
                    } catch (Throwable failure) {
                        problem.compareAndSet(null, failure);
                    }
                }, "ipc-admission-racer");

                Thread closer = new Thread(() -> {
                    try {
                        fire.await();
                        lease.requestClose();
                    } catch (Throwable failure) {
                        problem.compareAndSet(null, failure);
                    }
                }, "ipc-close-racer");

                sender.start();
                closer.start();
                fire.countDown();
                sender.join();
                closer.join();

                check(problem.get() == null);
                check((admitted.get() == null) == (rejected.get() == 1));
                check(rejected.get() == 0 || rejected.get() == 1);
                check(lease.awaitTerminal(3000));
                check(lease.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
                check(lease.snapshot().activeWrites() == 0);
                check(bridge.invariant());

                if (admitted.get() == null) {
                    closeWins.incrementAndGet();
                    check(bridge.counters().equals(
                            new IpcReceiptCorrelation.Counters(0, 0)));
                } else {
                    admittedWins.incrementAndGet();
                    check(admitted.get().outcome().toCompletableFuture().join()
                            == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
                    check(admitted.get().error() == null);
                    check(bridge.counters().equals(
                            new IpcReceiptCorrelation.Counters(0, 0)));
                }
            }

            // Deterministic close-before-admission: no receipt-table entry is
            // ever created by the rejected send attempt.
            {
                var bridge = new IpcReceiptCompletionBridge(1);
                var message = env(1);
                Object owner = new Object();
                bridge.claimPhysicalTransport(owner, message, 88);
                var lease = new IpcTransportSessionLease(
                        message, 88, 1, bridge, owner,
                        (ignoredMessage, ignoredFrame) -> { },
                        () -> { },
                        cleanup::execute);
                lease.requestClose();
                check(lease.awaitTerminal(3000));
                try {
                    lease.sendPreparedFrame(88, message, new byte[] {1});
                    throw new AssertionError("closed session admitted receipt");
                } catch (IpcTransportSessionLease.PreDispatchRejectedException expected) {
                    ++checks;
                }
                check(bridge.counters().equals(
                        new IpcReceiptCorrelation.Counters(0, 0)));
            }

            // Deterministic admission-before-close: hold the physical writer
            // open after commitment, then close. The one-call API retains the
            // pin until the writer unwinds; there is no abandonable token.
            {
                var bridge = new IpcReceiptCompletionBridge(1);
                var message = env(1);
                Object owner = new Object();
                bridge.claimPhysicalTransport(owner, message, 89);
                CountDownLatch writerEntered = new CountDownLatch(1);
                CountDownLatch releaseWriter = new CountDownLatch(1);
                var lease = new IpcTransportSessionLease(
                        message, 89, 1, bridge, owner,
                        (ignoredMessage, ignoredFrame) -> {
                            writerEntered.countDown();
                            try {
                                releaseWriter.await();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new java.io.IOException(
                                        "writer interrupted", interrupted);
                            }
                        },
                        () -> { },
                        cleanup::execute);
                AtomicReference<IpcTransportSessionLease.WriteAttempt> result =
                        new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread sender = new Thread(() -> {
                    try {
                        result.set(lease.sendPreparedFrame(
                                89, message, new byte[] {1}));
                    } catch (Throwable problem) {
                        failure.set(problem);
                    }
                }, "ipc-deterministic-admitted-writer");
                sender.start();
                check(writerEntered.await(2, TimeUnit.SECONDS));
                check(lease.snapshot().activeWrites() == 1);
                lease.requestClose();
                check(lease.snapshot().phase() == IpcTransportSessionLease.Phase.CLOSING);
                check(!lease.awaitTerminal(20));
                releaseWriter.countDown();
                sender.join(3000);
                check(!sender.isAlive());
                check(failure.get() == null);
                check(result.get() != null);
                check(result.get().error() == null);
                check(lease.awaitTerminal(3000));
                check(result.get().outcome().toCompletableFuture().join()
                        == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
                check(bridge.invariant());
            }

            System.out.println("JAVA_IPC_SESSION_ADMISSION_CLOSE_PASS checks=" + checks
                    + " races=512 admittedWins=" + admittedWins.get()
                    + " closeWins=" + closeWins.get());
        } finally {
            cleanup.shutdownNow();
        }
    }
}
