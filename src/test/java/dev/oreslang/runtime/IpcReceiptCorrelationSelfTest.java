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
        // Every receipt-ledger operation that can acquire its monitor rejects
        // actor carriers before touching synchronized state.
        var carrierBook = new IpcReceiptCorrelation(2);
        var carrierMessage = env(1, "carrier-ledger");
        carrierBook.admit(carrierMessage);
        RuntimeBlockingGuard.enterActorCarrier();
        try {
            denies(() -> carrierBook.admit(env(2, "carrier-ledger")));
            denies(() -> carrierBook.commitDispatch(carrierMessage));
            denies(() -> carrierBook.acceptVerified(
                    carrierMessage, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
            denies(() -> carrierBook.rejectBeforeRemote(carrierMessage));
            denies(() -> carrierBook.retireTransportLost(carrierMessage));
            denies(carrierBook::counters);
            denies(carrierBook::invariant);
        } finally {
            RuntimeBlockingGuard.exitActorCarrier();
        }
        check(carrierBook.counters().equals(
                new IpcReceiptCorrelation.Counters(1, 1)));
        carrierBook.rejectBeforeRemote(carrierMessage);
        check(carrierBook.counters().equals(
                new IpcReceiptCorrelation.Counters(0, 0)));
        check(carrierBook.invariant());

        var book = new IpcReceiptCorrelation(2);
        var first = env(1, "sessionA");
        var second = env(2, "sessionB");
        book.admit(first);
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        denies(() -> book.admit(first));
        var wrong = env(1, "sessionB");
        denies(() -> book.acceptVerified(wrong, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
        // A peer-authenticated receipt cannot assert uncertainty. That is
        // exclusively a trusted local transport-liveness determination.
        denies(() -> book.acceptVerified(first, IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN));
        denies(() -> book.acceptVerified(first, IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE));
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        denies(() -> book.acceptVerified(first, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
        denies(() -> book.acceptVerified(first, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED));
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        book.commitDispatch(first);
        denies(() -> book.commitDispatch(first));
        denies(() -> book.rejectBeforeRemote(first));
        book.acceptVerified(first, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        denies(() -> book.acceptVerified(first, IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN));
        denies(() -> book.acceptVerified(first, IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE));
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(1, 0)));
        book.admit(second);
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(2, 1)));
        var lost = book.retireTransportLost(first);
        check(lost.size() == 1 && lost.get(0).envelope().equals(first));
        check(lost.get(0).lastObserved() == IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        check(book.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        check(book.retireTransportLost(first).isEmpty());
        book.commitDispatch(second);
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

        // Message and physical frame sequences are independently
        // monotonic. A peer-visible frame sequence cannot roll back merely
        // because the logical message sequence advanced.
        var frameOrder = new IpcReceiptCorrelation(2);
        var frameOne = env(1, "frame-order");
        frameOrder.admit(frameOne);
        frameOrder.rejectBeforeRemote(frameOne);
        var rolledFrame = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "frame-order", "a".repeat(64),
                1, 2, 3, 4, 2, 1);
        denies(() -> frameOrder.admit(rolledFrame));
        var frameTwo = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "frame-order", "a".repeat(64),
                1, 2, 3, 4, 2, 2);
        frameOrder.admit(frameTwo);
        frameOrder.rejectBeforeRemote(frameTwo);
        check(frameOrder.invariant());

        // Verify capacity, recovery after a definitive terminal, and
        // monotonic route/capability generations through a hot-route change.
        var evolving = new IpcReceiptCorrelation(1);
        var generationOne = env(1, "sessionA");
        evolving.admit(generationOne);
        denies(() -> evolving.admit(env(2, "sessionB")));
        evolving.commitDispatch(generationOne);
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
        check(evolving.retireTransportLost(upgraded).size() == 1);
        check(evolving.invariant());

        // A stale socket-close notification must not retire a new route or
        // capability generation which reused exactly the same session text.
        var epochs = new IpcReceiptCorrelation(3);
        var previous = env(1, "reused");
        var current = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "reused", "a".repeat(64),
                1, 2, 4, 5, 2, 2);
        epochs.admit(previous);
        epochs.commitDispatch(previous);
        epochs.admit(current);
        epochs.commitDispatch(current);
        var previousLoss = epochs.retireTransportLost(previous);
        check(previousLoss.size() == 1);
        check(previousLoss.get(0).envelope().equals(previous));
        check(epochs.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        check(epochs.retireTransportLost(previous).isEmpty());
        epochs.acceptVerified(current, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        epochs.acceptVerified(current, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(epochs.counters().equals(new IpcReceiptCorrelation.Counters(0, 0)));
        check(epochs.invariant());

        // Incarnation fencing must also hold when a textual session label is
        // reused in a fresh correlation ledger after an actor restart.
        var newActor = new IpcReceiptCorrelation(1);
        var restarted = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "reused", "a".repeat(64),
                2, 3, 1, 1, 1, 1);
        newActor.admit(restarted);
        newActor.commitDispatch(restarted);
        check(newActor.retireTransportLost(previous).isEmpty());
        check(newActor.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        check(newActor.retireTransportLost(restarted).size() == 1);
        check(newActor.invariant());

        var racing = new IpcReceiptCorrelation(1);
        var raceMessage = env(1, "session");
        racing.admit(raceMessage);
        racing.commitDispatch(raceMessage);
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
        check(racing.retireTransportLost(raceMessage).size() == 1);
        check(racing.invariant());

        // A trusted local dispatch failure cannot become a peer-authenticated
        // rejection and is terminal only before REMOTE_ACCEPTED.
        var local = new IpcReceiptCorrelation(1);
        var localMessage = env(1, "session");
        local.admit(localMessage);
        denies(() -> local.acceptVerified(localMessage,
                IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE));
        check(local.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        local.rejectBeforeRemote(localMessage);
        check(local.counters().equals(new IpcReceiptCorrelation.Counters(0, 0)));
        denies(() -> local.rejectBeforeRemote(localMessage));
        var postAck = new IpcReceiptCorrelation(1);
        postAck.admit(localMessage);
        postAck.commitDispatch(localMessage);
        postAck.acceptVerified(localMessage, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        denies(() -> postAck.rejectBeforeRemote(localMessage));
        check(postAck.counters().equals(new IpcReceiptCorrelation.Counters(1, 0)));

        // Dispatch and never-sent rejection compete on one linearization
        // point. Exactly one may win, including under carrier races.
        for (int trial = 0; trial < 512; ++trial) {
            var gate = new IpcReceiptCorrelation(1);
            var msg = env(1, "race");
            gate.admit(msg);
            CountDownLatch fire = new CountDownLatch(1);
            AtomicInteger published = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            Thread dispatch = new Thread(() -> {
                try {
                    fire.await();
                    gate.commitDispatch(msg);
                    published.incrementAndGet();
                } catch (IllegalArgumentException | InterruptedException expected) { }
            });
            Thread decline = new Thread(() -> {
                try {
                    fire.await();
                    gate.rejectBeforeRemote(msg);
                    rejected.incrementAndGet();
                } catch (IllegalArgumentException | InterruptedException expected) { }
            });
            dispatch.start();
            decline.start();
            fire.countDown();
            dispatch.join();
            decline.join();
            check(published.get() + rejected.get() == 1);
            check(gate.invariant());
            if (published.get() == 1) {
                check(gate.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
                denies(() -> gate.rejectBeforeRemote(msg));
                var failures = gate.retireTransportLost(msg);
                check(failures.size() == 1);
                check(failures.get(0).lastObserved()
                        == IpcReceiptCorrelation.Stage.DISPATCH_COMMITTED);
            } else {
                check(gate.counters().equals(new IpcReceiptCorrelation.Counters(0, 0)));
                denies(() -> gate.commitDispatch(msg));
                check(gate.retireTransportLost(msg).isEmpty());
            }
            check(gate.counters().equals(new IpcReceiptCorrelation.Counters(0, 0)));
            check(gate.invariant());
        }

        Random rng = new Random(20261018);
        for (int trial = 0; trial < 2048; trial++) {
            var ledger = new IpcReceiptCorrelation(3);
            for (int turn = 1; turn <= 30; ++turn) {
                var msg = env(turn, "session");
                try {
                    ledger.admit(msg);
                    int choice = rng.nextInt(4);
                    if (choice == 0) {
                        denies(() -> ledger.acceptVerified(
                                msg, IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE));
                        ledger.rejectBeforeRemote(msg);
                    } else if (choice == 1) {
                        // Trust-domain distinction: only a *local*
                        // disconnected transport can report uncertainty.
                        denies(() -> ledger.acceptVerified(
                                msg, IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN));
                        ledger.commitDispatch(msg);
                        var uncertain = ledger.retireTransportLost(msg);
                        check(uncertain.size() == 1);
                        check(uncertain.get(0).lastObserved()
                                == IpcReceiptCorrelation.Stage.DISPATCH_COMMITTED);
                    } else {
                        ledger.commitDispatch(msg);
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
