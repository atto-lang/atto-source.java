package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free deterministic smoke gate for exact-file external CI. */
public final class ActorExecutionAuthorityProof {
    public static void main(String[] args) throws Exception {
        var gate = new ActorExecutionAuthority();
        var start = new CountDownLatch(1);
        var entered = new CountDownLatch(12);
        var release = new CountDownLatch(1);
        var winner = new AtomicInteger();
        var children = new Thread[12];
        for (int i = 0; i < children.length; i++) {
            int carrier = i + 1;
            children[i] = new Thread(() -> {
                try {
                    start.await();
                    var c = gate.enter(1, carrier);
                    if (c.isPresent()) winner.incrementAndGet();
                    entered.countDown();
                    if (c.isPresent()) {
                        release.await();
                        if (!gate.leave(c.orElseThrow(), false)) throw new AssertionError("release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            children[i].start();
        }
        start.countDown();
        check(entered.await(5, TimeUnit.SECONDS), "carrier claims timed out");
        check(winner.get() == 1 && gate.ownerActive(), "overlapping carrier entry");
        check(gate.enter(1, 99).isEmpty(), "second owner while active");
        release.countDown();
        for (Thread child : children) child.join(5000);
        check(gate.phase() == ActorExecutionAuthority.Phase.READY, "unreleased claim");
        var first = gate.enter(1, 1).orElseThrow();
        check(!gate.acknowledgeRetired(1), "retired without stop");
        check(gate.requestStop(1), "stop failed");
        check(!gate.acknowledgeRetired(1), "retired active guest");
        check(gate.leave(first, false), "owner failed to exit");
        check(gate.acknowledgeRetired(1), "retirement failed");
        check(gate.restart().orElseThrow() == 2, "generation fence");
        check(gate.enter(1, 1).isEmpty(), "stale incarnation entered");
        var second = gate.enter(2, 2).orElseThrow();
        check(!gate.leave(first, false), "old carrier released successor");
        check(gate.leave(second, true), "suspend exit");
        var resumed = gate.enter(2, 3).orElseThrow();
        check(!gate.leave(second, false), "stale suspended carrier released");
        check(gate.leave(resumed, false), "resumed leave");
        System.out.println("ORES_JAVA_SUPERVISOR_EXECUTION_AUTHORITY_PASS");
    }
    private static void check(boolean assertion, String label) {
        if (!assertion) throw new AssertionError(label);
    }
}
