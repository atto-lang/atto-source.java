package dev.oreslang;

import dev.oreslang.runtime.ScalarStateContainer;
import dev.oreslang.runtime.StateContainerAccessGate;
import java.util.ArrayList;
import java.util.Random;
import static dev.oreslang.runtime.StateContainerAccessGate.Mode.*;

/** Host substrate differential; not guest compiler or fair scheduler evidence. */
final class StateContainerDifferentialProbe {
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("state admission model mismatch");
    }
    private static void denied(Runnable op) {
        try { op.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("unauthorized lease accepted");
    }
    static void run() {
        var random = new Random(12345);
        int operations = 0;
        for (int batch = 0; batch < 32; batch++) {
            var gates = new StateContainerAccessGate[4];
            var live = new ArrayList<ArrayList<StateContainerAccessGate.Lease>>();
            var closed = new boolean[4];
            for (int k = 0; k < 4; k++) {
                gates[k] = new StateContainerAccessGate(2);
                live.add(new ArrayList<>());
            }
            for (int n = 0; n < 256; n++) {
                int k = random.nextInt(4), op = random.nextInt(8);
                var g = gates[k];
                var loans = live.get(k);
                if (op <= 3) {
                    boolean write = op % 2 == 1;
                    boolean writer = loans.stream().anyMatch(l -> l.mode() == WRITE);
                    boolean allow = !closed[k] && !writer && (write ? loans.isEmpty() : loans.size() < 2);
                    var lease = write ? g.tryWrite() : g.tryRead();
                    check((lease != null) == allow);
                    if (allow) {
                        loans.add(lease);
                        g.requireOwnedActive(lease, write ? WRITE : READ);
                        denied(() -> gates[(k + 1) % 4].requireOwnedActive(lease, READ));
                        if (!write) denied(() -> g.requireOwnedActive(lease, WRITE));
                    }
                } else if (op <= 5 && !loans.isEmpty()) {
                    var lease = loans.removeLast();
                    lease.close(); lease.close();
                    denied(() -> g.requireOwnedActive(lease, READ));
                } else if (op == 6 && n > 180) {
                    g.close(); g.close(); closed[k] = true;
                    for (var lease : loans) denied(() -> g.requireOwnedActive(lease, READ));
                }
                for (int i = 0; i < 4; i++) {
                    var expected = live.get(i);
                    check(gates[i].readerCount() == expected.stream().filter(l -> l.mode() == READ).count());
                    check(gates[i].writerActive() == expected.stream().anyMatch(l -> l.mode() == WRITE));
                    check(gates[i].leasesQuiescentAfterClose() == (closed[i] && expected.isEmpty()));
                }
                operations++;
            }
            for (int k = 0; k < 4; k++) {
                gates[k].close();
                for (var lease : live.get(k)) lease.close();
                check(gates[k].leasesQuiescentAfterClose());
                check(gates[k].tryRead() == null && gates[k].tryWrite() == null);
            }
        }
        check(operations == 8192);
        for (int n = 0; n < 1024; n++) {
            try (var state = new ScalarStateContainer(Long.MAX_VALUE, 2)) {
                boolean overflow = false;
                try { state.tryAddChecked(1); } catch (ArithmeticException expected) { overflow = true; }
                check(overflow && state.tryRead().orElseThrow() == Long.MAX_VALUE);
                check(state.tryAddChecked(-1).orElseThrow() == Long.MAX_VALUE - 1);
                state.close();
                check(state.readyForReclamation());
                check(state.tryRead().isEmpty() && state.trySet(0).isEmpty() && state.tryAddChecked(0).isEmpty());
            }
        }
    }
    public static void main(String[] args) {
        run();
        System.out.println("8192 admission operations and 1024 overflow/release cycles passed");
    }
}
