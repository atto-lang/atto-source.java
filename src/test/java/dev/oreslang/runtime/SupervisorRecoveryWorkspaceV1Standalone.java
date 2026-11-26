package dev.oreslang.runtime;

import java.lang.management.ManagementFactory;

final class SupervisorRecoveryWorkspaceV1Standalone {
    private record Cell(long recoveryId, int recoveryDepth, Cell recoveryParent)
            implements SupervisorRecoveryWorkspaceV1.Node { }

    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("recovery workspace invariant");
    }

    private static boolean verify(Cell[] cells, Cell root, long[] output,
            SupervisorRecoveryWorkspaceV1.RefIndex<Cell> refs) {
        int n = SupervisorRecoveryWorkspaceV1.collect(cells, cells.length, root, 64, output);
        if (n != 4094 || output[n - 1] != root.recoveryId()) return false;
        int prior = 65;
        for (int i = 0; i < n; i++) {
            Cell cell = refs.get(output[i]);
            if (cell == null || cell.recoveryDepth() > prior || cell == cells[0] || cell == cells[4095]) return false;
            prior = cell.recoveryDepth();
        }
        return true;
    }

    public static void main(String[] args) {
        Cell[] cells = new Cell[4096];
        long[] output = new long[cells.length];
        var refs = new SupervisorRecoveryWorkspaceV1.RefIndex<Cell>(cells.length);
        cells[0] = new Cell(1000, 1, null);
        for (int i = 1; i < 64; i++) cells[i] = new Cell(1000L + i, i + 1, cells[i - 1]);
        for (int i = 64; i < 4095; i++) cells[i] = new Cell(1000L + i, 64, cells[62]);
        cells[4095] = new Cell(5095, 2, cells[0]);
        for (Cell cell : cells) check(refs.putIfAbsent(cell.recoveryId(), cell) == null);
        check(refs.putIfAbsent(cells[1].recoveryId(), cells[0]) == cells[1]);
        check(!refs.remove(cells[1].recoveryId(), cells[0]));
        try { refs.putIfAbsent(99999, cells[0]); throw new AssertionError("capacity"); }
        catch (IllegalStateException expected) { check(refs.get(99999) == null); }
        check(refs.remove(cells[4095].recoveryId(), cells[4095]));
        check(refs.get(cells[4095].recoveryId()) == null);
        check(refs.putIfAbsent(cells[4095].recoveryId(), cells[4095]) == null);
        // Small independent inventory check: exact set and no duplicate IDs.
        check(SupervisorRecoveryWorkspaceV1.collect(cells, cells.length, cells[1], 64, output) == 4094);
        boolean[] seen = new boolean[cells.length];
        for (int i = 0; i < 4094; i++) {
            int slot = (int) (output[i] - 1000);
            check(slot >= 1 && slot <= 4094 && !seen[slot]);
            seen[slot] = true;
        }
        for (int i = 1; i <= 4094; i++) check(seen[i]);
        check(SupervisorRecoveryWorkspaceV1.collect(cells, cells.length, cells[4095], 64, output) == 1);
        check(output[0] == 5095);
        for (int i = 0; i < 100; i++) check(verify(cells, cells[1], output, refs));
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        boolean valid = true;
        for (int i = 0; i < 128; i++) valid &= verify(cells, cells[1], output, refs);
        long delta = bean.getThreadAllocatedBytes(thread) - before;
        check(valid && before >= 0 && delta == 0);
        System.out.println("PASS SUBTREE_NODES=4096 DEPTH=64 SCANS=128 ALLOCATED_BYTES=0 EXACT_MEMBERSHIP=1 SIBLING_EXCLUDED=1");
    }
}
