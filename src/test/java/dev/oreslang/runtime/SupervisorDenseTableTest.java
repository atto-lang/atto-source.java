package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class SupervisorDenseTableTest {
    @Test void allThreeLayoutsAndStableHandleMigration() {
        for (SupervisorDenseTable.Layout initial : SupervisorDenseTable.Layout.values()) {
            SupervisorDenseTable table = new SupervisorDenseTable(initial, 0, 32);
            List<SupervisorDenseTable.Handle> handles = new ArrayList<>();
            for (int i = 0; i < 12_000; i++) handles.add(table.add(i, i % 3, i + 1, 7, i * 10L));
            assertEquals(12_000, table.size());
            for (SupervisorDenseTable.Layout next : SupervisorDenseTable.Layout.values()) {
                table.setLayout(next);
                assertEquals(next, table.layout());
                for (int i = 0; i < handles.size(); i += 37) {
                    assertArrayEquals(new long[]{i, i % 3, i+1, 7, i*10L}, table.snapshot(handles.get(i)));
                }
            }
            SupervisorDenseTable.Handle stale = handles.get(100);
            SupervisorDenseTable.Handle moved = handles.get(11_999);
            table.eraseSwap(stale);
            assertThrows(IllegalArgumentException.class, () -> table.snapshot(stale));
            assertEquals(11_999, table.get(moved, SupervisorDenseTable.Field.ACTOR_ID));
            SupervisorDenseTable.Handle replacement = table.add(99_999, 1, 0, 0, 0);
            assertEquals(stale.slot(), replacement.slot());
            assertNotEquals(stale.generation(), replacement.generation());
            assertThrows(IllegalArgumentException.class, () -> table.get(stale, SupervisorDenseTable.Field.ACTOR_ID));
            table.close();
            assertThrows(IllegalStateException.class, () -> table.get(replacement, SupervisorDenseTable.Field.ACTOR_ID));
        }
    }

    @Test void foreignHandlesAndRandomizedCoherence() {
        SupervisorDenseTable a = new SupervisorDenseTable(SupervisorDenseTable.Layout.AOSOA, 1, 64);
        SupervisorDenseTable b = new SupervisorDenseTable(SupervisorDenseTable.Layout.AOS, 1, 16);
        var h = a.add(42, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> b.snapshot(h));
        var handles = new ArrayList<SupervisorDenseTable.Handle>();
        var expected = new ArrayList<Long>();
        handles.add(h); expected.add(42L);
        Random rng = new Random(20261019);
        for (int i = 0; i < 4_000; i++) {
            int choice = rng.nextInt(4);
            if (choice == 0 && !handles.isEmpty()) {
                int ix = rng.nextInt(handles.size());
                var gone = handles.remove(ix); expected.remove(ix);
                a.eraseSwap(gone);
                assertThrows(IllegalArgumentException.class, () -> a.snapshot(gone));
            } else if (choice == 1 && !handles.isEmpty()) {
                int ix = rng.nextInt(handles.size());
                a.set(handles.get(ix), SupervisorDenseTable.Field.ACTOR_ID, i);
                expected.set(ix, (long)i);
            } else if (choice == 2) {
                a.setLayout(SupervisorDenseTable.Layout.values()[rng.nextInt(3)]);
            } else {
                handles.add(a.add(i, 0, 0, 0, 0)); expected.add((long)i);
            }
            for (int k = 0; k < 3 && !handles.isEmpty(); k++) {
                int ix = rng.nextInt(handles.size());
                assertEquals(expected.get(ix).longValue(), a.get(handles.get(ix), SupervisorDenseTable.Field.ACTOR_ID));
            }
        }
        a.close(); b.close();
    }

    @Test void concurrentUpdatesDuringLayoutTransitions() throws Exception {
        var table = new SupervisorDenseTable(SupervisorDenseTable.Layout.SOA, 0, 32);
        var handles = new ArrayList<SupervisorDenseTable.Handle>();
        for (int i = 0; i < 2_000; i++) handles.add(table.add(i, 0, 0, 1, 0));
        var start = new java.util.concurrent.CountDownLatch(1);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var threads = new ArrayList<Thread>();
        for (int worker = 0; worker < 4; worker++) {
            final int first = worker * 500;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 10; round++) {
                        for (int i = first; i < first + 500; i++) {
                            table.set(handles.get(i), SupervisorDenseTable.Field.RESTARTS, round + 1);
                        }
                    }
                } catch (Throwable e) { error.compareAndSet(null, e); }
            });
            threads.add(t); t.start();
        }
        Thread conversion = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < 120; i++)
                    table.setLayout(SupervisorDenseTable.Layout.values()[i % 3]);
            } catch (Throwable e) { error.compareAndSet(null, e); }
        });
        conversion.start(); start.countDown();
        for (var thread : threads) thread.join();
        conversion.join();
        if (error.get() != null) throw new AssertionError(error.get());
        for (var handle : handles) assertEquals(10, table.get(handle, SupervisorDenseTable.Field.RESTARTS));
        table.close();
    }

}
