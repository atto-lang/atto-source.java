package dev.oreslang;

import dev.oreslang.parser.FlatMachineTable;
import dev.oreslang.parser.FlatMachineTable.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.oreslang.ScalarMachineCommitReference.*;

public final class ScalarMachineCommitSmoke {
    private static int passed;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("check " + (passed + 1));
        passed++;
    }
    private static FlatMachineTable table() {
        return FlatMachineTable.verify(1, List.of(
                new Node("Done", true, List.of()),
                new Node("Idle", false, List.of(new Edge("Add", 1), new Edge("Finish", 0)))));
    }
    private static ScalarMachineCommitReference machine(long initial, long limit, long delta) {
        return new ScalarMachineCommitReference(table(),
                List.of(new Operation(1, 0, delta), new Operation(1, 1, 0)), initial, limit);
    }
    private static void reject(Runnable test) {
        try { test.run(); } catch (IllegalArgumentException expected) { passed++; return; }
        throw new AssertionError("accepted invalid plan");
    }
    public static void main(String[] args) throws InterruptedException {
        var m = machine(1, Long.MAX_VALUE, 2);
        var start = m.snapshot();
        check(start.equals(new Snapshot(1, 1, 0, 1)));
        check(m.apply(0, 1, 0, true) == Outcome.CANCELLED);
        check(m.snapshot() == start);
        check(m.apply(0, 1, -1, false) == Outcome.INVALID_EVENT);
        check(m.snapshot() == start);
        check(m.apply(0, 1, 0, false) == Outcome.COMMITTED);
        check(m.snapshot().equals(new Snapshot(1, 3, 1, 2)));
        var after = m.snapshot();
        check(m.apply(0, 1, 0, false) == Outcome.STALE);
        check(m.apply(1, 1, 0, false) == Outcome.STALE);
        check(m.snapshot() == after);
        check(m.apply(1, 2, 1, false) == Outcome.COMMITTED);
        check(m.snapshot().equals(new Snapshot(0, 3, 2, 3)));
        check(m.apply(2, 3, 0, false) == Outcome.TERMINAL);
        var negative = machine(1, 20, -2);
        check(negative.apply(0, 1, 0, false) == Outcome.INVARIANT);
        check(negative.snapshot().equals(start));
        var overflow = machine(Long.MAX_VALUE, 20, 1);
        check(overflow.apply(0, 1, 0, false) == Outcome.OVERFLOW);
        check(overflow.snapshot().value() == Long.MAX_VALUE && overflow.snapshot().version() == 0);
        var exhausted = machine(1, 2, 1);
        check(exhausted.apply(0, 1, 0, false) == Outcome.COMMITTED);
        var last = exhausted.snapshot();
        check(exhausted.apply(1, 2, 0, false) == Outcome.EXHAUSTED);
        check(exhausted.snapshot() == last);
        reject(() -> new ScalarMachineCommitReference(table(), List.of(), 0, 1));
        reject(() -> new ScalarMachineCommitReference(table(), List.of(new Operation(1,0,1),new Operation(1,0,2)),0,1));
        reject(() -> new ScalarMachineCommitReference(table(), List.of(new Operation(1,0,1),new Operation(0,1,2)),0,1));
        var concurrent = machine(1, 20, 1);
        var gate = new CountDownLatch(1);
        var commits = new AtomicInteger();
        var stale = new AtomicInteger();
        Runnable action = () -> {
            try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            var result = concurrent.apply(0,1,0,false);
            if (result == Outcome.COMMITTED) commits.incrementAndGet();
            if (result == Outcome.STALE) stale.incrementAndGet();
        };
        var first = new Thread(action);
        var second = new Thread(action);
        first.start(); second.start(); gate.countDown(); first.join(); second.join();
        check(commits.get()==1 && stale.get()==1);
        check(concurrent.snapshot().equals(new Snapshot(1,2,1,2)));
        System.out.println(passed + " scalar host reference checks passed");
    }
}
