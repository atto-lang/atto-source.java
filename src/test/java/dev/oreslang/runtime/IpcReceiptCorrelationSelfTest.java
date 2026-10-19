package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolated JDK test, not a Graal guest-language or network authentication test.
 * Run: javac -d /tmp/receipt-test <both .java files> && java -cp ...
 */
public final class IpcReceiptCorrelationSelfTest {
    private IpcReceiptCorrelationSelfTest() { }
    private static int assertions;

    private static void check(boolean condition) {
        ++assertions;
        if (!condition) throw new AssertionError("Java IPC parity contract failed");
    }

    private static void denies(Runnable callback) {
        try {
            callback.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            ++assertions;
            return;
        }
        throw new AssertionError("illegitimate correlation transition accepted");
    }

    private static IpcReceiptCorrelation.Envelope env(long seq, String session) {
        return new IpcReceiptCorrelation.Envelope("tenant", "sender", "worker",
                session, "a".repeat(64), 1, 2, 3, 4, seq, seq);
    }

    public static void main(String[] ignored) throws InterruptedException {
        var book = new IpcReceiptCorrelation(2);
        var first = env(1, "sessionA");
        var second = env(2, "sessionB");
        book.admit(first);
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        denies(() -> book.admit(first));
        var wrong = env(1, "sessionB");
        denies(() -> book.acceptVerified(wrong, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
        book.acceptVerified(first, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        denies(() -> book.acceptVerified(first, IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE));
        book.admit(second);
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(2, 1)));
        var lost = book.retireTransportLost("sessionA");
        check(lost.size() == 1 && lost.get(0).envelope().equals(first));
        check(lost.get(0).lastObserved() == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        check(book.retireTransportLost("sessionA").isEmpty());
        book.acceptVerified(second, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        book.acceptVerified(second, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(0, 0)));
        check(book.invariant());
        denies(() -> book.admit(first));
        denies(() -> new IpcReceiptCorrelation.Envelope(
                "bad token", "sender", "worker", "session", "a".repeat(64),
                1, 2, 3, 4, 1, 1));
        denies(() -> new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "session", "A".repeat(64),
                1, 2, 3, 4, 1, 1));

        // Verify capacity, recovery after a definitive terminal, and
        // monotonic route/capability generations through a hot-route change.
        var evolving = new IpcReceiptCorrelation(1);
        var generationOne = env(1, "sessionA");
        evolving.admit(generationOne);
        denies(() -> evolving.admit(env(2, "sessionB")));
        evolving.acceptVerified(generationOne,
                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        evolving.acceptVerified(generationOne,
                IpcReceiptCorrelation.Stage.HANDLER_FAILED);
        var staleRoute = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "sessionB", "a".repeat(64),
                1, 2, 2, 4, 2, 2);
        denies(() -> evolving.admit(staleRoute));
        var staleGrant = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "sessionB", "a".repeat(64),
                1, 2, 4, 3, 2, 2);
        denies(() -> evolving.admit(staleGrant));
        var upgraded = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "sessionB", "a".repeat(64),
                1, 2, 4, 5, 2, 2);
        evolving.admit(upgraded);
        var restartedActor = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "sessionC", "a".repeat(64),
                1, 3, 5, 5, 3, 3);
        denies(() -> evolving.admit(restartedActor));
        check(evolving.retireTransportLost("sessionB").size() == 1);
        check(evolving.invariant());

        var racing = new IpcReceiptCorrelation(1);
        var raceMessage = env(1, "session");
        racing.admit(raceMessage);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> carriers = new ArrayList<>();
        for (int i = 0; i < 16; ++i) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    racing.acceptVerified(raceMessage, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
                    accepted.incrementAndGet();
                } catch (IllegalArgumentException | InterruptedException ignoredError) { }
            });
            carriers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread t : carriers) t.join();
        check(accepted.get() == 1);
        check(racing.counters().equals(new IpcReceiptCorrelation.Counters(1, 0)));
        check(racing.retireTransportLost("session").size() == 1);
        check(racing.invariant());

        Random rng = new Random(20261018);
        for (int trial = 0; trial < 2048; trial++) {
            var ledger = new IpcReceiptCorrelation(3);
            for (int turn = 1; turn <= 30; ++turn) {
                var msg = env(turn, "session");
                try {
                    ledger.admit(msg);
                    int choice = rng.nextInt(4);
                    if (choice == 0) {
                        ledger.acceptVerified(msg, IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
                    } else if (choice == 1) {
                        ledger.acceptVerified(msg, IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
                    } else {
                        ledger.acceptVerified(msg, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
                        ledger.acceptVerified(msg, choice == 2
                                ? IpcReceiptCorrelation.Stage.HANDLER_COMMITTED
                                : IpcReceiptCorrelation.Stage.HANDLER_FAILED);
                    }
                } catch (IllegalStateException exhausted) {
                    throw new AssertionError("no capacity leak allowed", exhausted);
                }
                check(ledger.invariant());
            }
        }
        System.out.println("JAVA_IPC_RECEIPTS_PASS assertions=" + assertions
                + " seeded=61440");
    }
}
