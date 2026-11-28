package dev.oreslang;

import dev.oreslang.runtime.ScalarStateContainer;
import dev.oreslang.runtime.StateContainerAccessGate;
import java.util.concurrent.*;
import static dev.oreslang.runtime.StateContainerAccessGate.Mode.*;

/** Host-only lifecycle oracle; also runnable without Maven or JUnit. */
public final class StateTerminalParityChecks {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void inactive(StateContainerAccessGate.Lease lease) {
        try {
            lease.requireActive(READ);
            throw new AssertionError("revoked loan still authorizes access");
        } catch (IllegalStateException expected) { }
    }

    public static void drainReadersAndWriter() throws Exception {
        for (var mode : StateContainerAccessGate.Mode.values()) {
            var gate = new StateContainerAccessGate(2);
            var other = new StateContainerAccessGate(1);
            var first = mode == READ ? gate.tryRead() : gate.tryWrite();
            var second = mode == READ ? gate.tryRead() : null;
            var independent = other.tryWrite();
            check(first != null && independent != null && (mode != READ || second != null), "admission");
            var closer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "state-close-test"); t.setDaemon(true); return t;
            });
            try {
                closer.submit(() -> { gate.close(); gate.close(); }).get(5, TimeUnit.SECONDS);
                check(gate.isClosed() && !gate.leasesQuiescentAfterClose(), "premature quiescence");
                check(gate.tryRead() == null && gate.tryWrite() == null, "post-close admission");
                inactive(first);
                first.close(); first.close();
                if (second != null) {
                    inactive(second);
                    check(!gate.leasesQuiescentAfterClose(), "one reader remains");
                    second.close(); second.close();
                }
                check(gate.leasesQuiescentAfterClose(), "physical drain missing");
                independent.requireActive(WRITE);
                check(!other.isClosed(), "unrelated gate closed");
            } finally {
                first.close(); if (second != null) second.close(); independent.close();
                other.close(); gate.close(); closer.shutdownNow();
            }
        }
    }

    public static void checkedFailureReleasesWriter() {
        for (long initial : new long[]{Long.MIN_VALUE, Long.MAX_VALUE}) {
            var cell = new ScalarStateContainer(initial, 2);
            try {
                cell.tryAddChecked(initial == Long.MIN_VALUE ? -1 : 1);
                throw new AssertionError("overflow was accepted");
            } catch (ArithmeticException expected) { }
            check(cell.tryRead().orElseThrow() == initial, "overflow changed state or stranded lease");
            check(cell.trySet(41).orElseThrow() == 41, "subsequent writer denied");
            check(cell.tryAddChecked(1).orElseThrow() == 42, "write result mismatch");
            cell.close(); cell.close();
            check(cell.readyForReclamation(), "closed cell not drained");
            check(cell.tryRead().isEmpty() && cell.tryAddChecked(1).isEmpty() && cell.trySet(0).isEmpty(), "post-close state access");
        }
    }

    public static void concurrentCloseNeverResurrects() throws Exception {
        var pool = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "state-close-race"); t.setDaemon(true); return t;
        });
        try {
            for (int round = 0; round < 128; round++) {
                var cell = new ScalarStateContainer(0, 2);
                var start = new CountDownLatch(1);
                var writer = pool.submit(() -> { start.await(); return cell.tryAddChecked(1); });
                var reader = pool.submit(() -> { start.await(); return cell.tryRead(); });
                var close = pool.submit(() -> { start.await(); cell.close(); return null; });
                start.countDown();
                close.get(5, TimeUnit.SECONDS);
                check(cell.tryRead().isEmpty() && cell.trySet(9).isEmpty(), "post-close admission in race");
                var w = writer.get(5, TimeUnit.SECONDS);
                var r = reader.get(5, TimeUnit.SECONDS);
                check(w.isEmpty() || w.getAsLong() == 1, "writer result outside linearization");
                check(r.isEmpty() || r.getAsLong() == 0 || r.getAsLong() == 1, "invalid read snapshot");
                check(cell.readyForReclamation(), "closed race leaked loan");
            }
        } finally { pool.shutdownNow(); }
    }

    public static void main(String[] args) throws Exception {
        drainReadersAndWriter(); System.out.println("PASS drainReadersAndWriter");
        checkedFailureReleasesWriter(); System.out.println("PASS checkedFailureReleasesWriter");
        concurrentCloseNeverResurrects(); System.out.println("PASS concurrentCloseNeverResurrects (128 races)");
    }
}
