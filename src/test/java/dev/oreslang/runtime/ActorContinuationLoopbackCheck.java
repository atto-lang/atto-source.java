package dev.oreslang.runtime;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Host-only JDK NIO2 and production continuation-ledger integration. */
public final class ActorContinuationLoopbackCheck {
    private ActorContinuationLoopbackCheck() { }
    private enum Outcome { LATE_DATA, CLOSE, DEADLINE }
    private record ReadResult(int count, Throwable failure, Thread publisher) { }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static CompletableFuture<ReadResult> read(AsynchronousSocketChannel socket,
            ByteBuffer bytes, long timeoutSeconds) {
        CompletableFuture<ReadResult> result = new CompletableFuture<>();
        socket.read(bytes, timeoutSeconds, TimeUnit.SECONDS, null,
                new CompletionHandler<Integer, Void>() {
                    @Override public void completed(Integer count, Void ignored) {
                        result.complete(new ReadResult(count, null, Thread.currentThread()));
                    }
                    @Override public void failed(Throwable failure, Void ignored) {
                        result.complete(new ReadResult(0, failure, Thread.currentThread()));
                    }
                });
        return result;
    }

    private static void send(AsynchronousSocketChannel socket, int value) throws Exception {
        check(socket.write(ByteBuffer.wrap(new byte[] {(byte) value}))
                .get(5, TimeUnit.SECONDS) == 1, "one-byte TCP write failed");
    }

    private static void replayDenied(ActorContinuationLedger.Admission receipt) {
        try {
            receipt.run();
            throw new AssertionError("terminal receipt replayed");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("receipt replay"), "wrong replay diagnostic");
        }
        receipt.close();
    }

    private static void scenario(Outcome outcome) throws Exception {
        var group = AsynchronousChannelGroup.withFixedThreadPool(1, Executors.defaultThreadFactory());
        try (var listener = AsynchronousServerSocketChannel.open(group)) {
            listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
            var address = listener.getLocalAddress();
            try (var a = AsynchronousSocketChannel.open(group);
                 var b = AsynchronousSocketChannel.open(group)) {
                var acceptA = listener.accept();
                a.connect(address).get(5, TimeUnit.SECONDS);
                try (var peerA = acceptA.get(5, TimeUnit.SECONDS)) {
                    var acceptB = listener.accept();
                    b.connect(address).get(5, TimeUnit.SECONDS);
                    try (var peerB = acceptB.get(5, TimeUnit.SECONDS)) {
                        var ledgerA = new ActorContinuationLedger(2);
                        var ledgerB = new ActorContinuationLedger(1);
                        var aCalls = new AtomicInteger();
                        var aReleases = new AtomicInteger();
                        var cleanupCalls = new AtomicInteger();
                        var bCalls = new AtomicInteger();
                        byte[] heapA = {11}, heapB = {41};
                        var carrier = Thread.currentThread();
                        var receiptA = ledgerA.admit(ActorContinuationLedger.Lane.ORDINARY,
                                () -> { aCalls.incrementAndGet(); heapA[0] = 99; },
                                aReleases::incrementAndGet);
                        var cleanup = ledgerA.admit(ActorContinuationLedger.Lane.SOURCE_TASK,
                                cleanupCalls::incrementAndGet);
                        var receiptB = ledgerB.admit(ActorContinuationLedger.Lane.ORDINARY, () -> {
                            check(Thread.currentThread() == carrier, "B ran on producer thread");
                            bCalls.incrementAndGet(); heapB[0] = 42;
                        });
                        ByteBuffer bufferA = ByteBuffer.allocate(1), bufferB = ByteBuffer.allocate(1);
                        var pendingA = read(a, bufferA, outcome == Outcome.DEADLINE ? 3 : 0);
                        var pendingB = read(b, bufferB, 0);
                        send(peerB, 42);
                        var completedB = pendingB.get(5, TimeUnit.SECONDS);
                        check(completedB.failure() == null && completedB.count() == 1
                                && bufferB.get(0) == 42, "B TCP completion invalid");
                        check(completedB.publisher() != carrier && bCalls.get() == 0,
                                "NIO completion executed guest callback inline");
                        check(!pendingA.isDone(), "A must remain pending while B progresses");
                        receiptB.run();
                        check(bCalls.get() == 1 && heapB[0] == 42 && ledgerB.snapshot().queued() == 0,
                                "B did not progress on host carrier");
                        ledgerA.sealOrdinary();
                        check(ledgerA.snapshot().generation() == 2 && aReleases.get() == 0,
                                "STOP released a queued resource before dispatch");
                        if (outcome == Outcome.LATE_DATA) send(peerA, 99);
                        if (outcome == Outcome.CLOSE) closePending(a);
                        var completedA = pendingA.get(8, TimeUnit.SECONDS);
                        check(completedA.publisher() != carrier, "A completion used carrier");
                        switch (outcome) {
                            case LATE_DATA -> check(completedA.failure() == null
                                    && completedA.count() == 1 && bufferA.get(0) == 99, "late data missing");
                            case CLOSE -> check(completedA.failure() instanceof AsynchronousCloseException,
                                    "pending read did not fail on close");
                            case DEADLINE -> check(completedA.failure() instanceof InterruptedByTimeoutException,
                                    "real NIO read deadline did not expire");
                        }
                        receiptA.run(); // sealed ordinary receipt: discard, never invoke callback
                        check(aCalls.get() == 0 && heapA[0] == 11 && aReleases.get() == 1,
                                "late completion ran stopped callback or leaked resource");
                        replayDenied(receiptA);
                        cleanup.run();
                        replayDenied(cleanup);
                        ledgerA.sealAll();
                        check(cleanupCalls.get() == 1 && aReleases.get() == 1
                                && ledgerA.snapshot().queued() == 0 && ledgerA.snapshot().running() == 0,
                                "cleanup did not retire exactly once");
                        replayDenied(receiptB);
                        check(bCalls.get() == 1 && heapB[0] == 42, "A retirement disturbed B");
                    }
                }
            }
        } finally {
            group.shutdownNow();
            check(group.awaitTermination(5, TimeUnit.SECONDS), "NIO group did not terminate");
        }
    }

    // Separate helper avoids javac's explicit-close warning for a resource.
    private static void closePending(AsynchronousSocketChannel socket) throws Exception {
        socket.close();
    }

    public static void main(String[] args) throws Exception {
        for (Outcome outcome : Outcome.values()) {
            scenario(outcome);
            System.out.println("JAVA_NIO_LEDGER_PASS " + outcome);
        }
    }
}
