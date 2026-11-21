package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Pure JDK 21 race proof, not guest source execution or compiled native AOT. */
public final class CounterLiveRetirementRaceHarness {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void waitFor(CountDownLatch latch, String name) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError(name + " timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(name + " interrupted", e);
        }
    }

    public static void main(String[] args) throws Exception {
        final int repetitions = 64;
        for (int attempt = 0; attempt < repetitions; attempt++) {
            var machine = new CounterLiveTurnModel();
            var callbackEntered = new CountDownLatch(1);
            var releaseCallback = new CountDownLatch(1);
            var retireReturned = new CountDownLatch(1);
            var result = new AtomicReference<CounterLiveTurnModel.Outcome>();
            var failed = new AtomicReference<Throwable>();
            var actor = new Thread(() -> {
                try {
                    result.set(machine.increment("alice", "12345678", 0, () -> {
                        callbackEntered.countDown();
                        waitFor(releaseCallback, "actor callback release");
                    }));
                } catch (Throwable t) {
                    failed.set(t);
                }
            }, "counter-live-turn");
            Thread retire = new Thread(() -> {
                try { machine.retire(); }
                finally { retireReturned.countDown(); }
            }, "counter-live-retire");
            actor.start();
            boolean preempted;
            try {
                waitFor(callbackEntered, "callback entry");
                retire.start();
                preempted = retireReturned.await(300, TimeUnit.MILLISECONDS);
            } finally {
                // Even on failure, free the callback so the test cannot leak a blocked thread.
                releaseCallback.countDown();
                actor.join(5000);
                if (retire.isAlive()) retire.join(5000);
            }
            check(!actor.isAlive() && !retire.isAlive(), "lifecycle threads must settle");
            check(preempted, "retirement blocked behind a paused actor callback at attempt " + attempt);
            check(result.get() == null && failed.get() instanceof IllegalStateException,
                    "retirement must cancel this uncommitted actor turn");
            check(machine.count() == 0 && machine.revision() == 0, "no state may commit after retirement");
            try {
                machine.increment("alice", "abcdef12", 0);
                throw new AssertionError("retired actor admitted another event");
            } catch (IllegalStateException expected) {
                // Expected: retired actor.
            }
        }
        // A replay and a stale rejection are actor output plans as well; no
        // reply path may bypass retirement admission after the last commit.
        var replied = new CounterLiveTurnModel();
        check(replied.increment("alice", "12345678", 0).applied(), "baseline reply commit");
        replied.retire();
        for (String eventId : new String[]{"12345678", "abcdef12"}) {
            try {
                replied.increment("alice", eventId, 0);
                throw new AssertionError("retired model returned replay/stale output");
            } catch (IllegalStateException expected) {
                // Both replay and stale branches must be fenced.
            }
        }
        System.out.println("PASS CounterLive concurrent retirement races=" + repetitions);
    }
}
