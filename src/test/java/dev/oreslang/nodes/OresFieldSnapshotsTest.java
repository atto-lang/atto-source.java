package dev.oreslang.nodes;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only concurrent slot-map integrity; not an Oreslang guest execution. */
final class OresFieldSnapshotsTest {
    @Test
    void snapshotPreservesFieldOrderAndNullAndDoesNotAliasMutableMap() {
        Map<String, Object> fields = new LinkedHashMap<>();
        Object marker = new Object();
        fields.put("first", null);
        fields.put("producer", marker);
        Map<String, Object> snapshot = OresFieldSnapshots.copy(fields);
        assertEquals(List.of("first", "producer"), new ArrayList<>(snapshot.keySet()));
        assertNull(snapshot.get("first"));
        assertSame(marker, snapshot.get("producer"));
        synchronized (fields) {
            fields.put("producer", new Object());
        }
        assertSame(marker, snapshot.get("producer"),
                "snapshot must preserve the pre-mutation field-slot identity");
    }

    @Test
    void concurrentMovesAndReinitializationsNeverTearASnapshot() throws Exception {
        Map<String, Integer> fields = new LinkedHashMap<>();
        fields.put("version", 0);
        fields.put("owner", 0);
        int iterations = 20000;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> workers = new ArrayList<>();
        try {
            workers.add(pool.submit(() -> {
                start.await();
                for (int i = 1; i <= iterations; i++) {
                    synchronized (fields) {
                        fields.put("version", i);
                        fields.put("owner", i);
                    }
                }
                return null;
            }));
            for (int reader = 0; reader < 3; reader++) {
                workers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        Map<String, Integer> snapshot = OresFieldSnapshots.copy(fields);
                        assertEquals(snapshot.get("version"), snapshot.get("owner"),
                                "snapshot cannot observe a half-completed field update");
                        assertEquals(2, snapshot.size());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) worker.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void classFieldMoveConsumesExactlyOneOwnerAndRejectsReplay() {
        Map<String, Object> fields = new LinkedHashMap<>();
        Object uninitialized = new Object();
        Object moved = new Object();
        Object owner = new Object();
        fields.put("producer", owner);
        assertSame(owner, OresFieldSnapshots.take(
                fields, "TaskCell", "producer", uninitialized, moved));
        assertSame(moved, fields.get("producer"));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> OresFieldSnapshots.take(
                        fields, "TaskCell", "producer", uninitialized, moved));
        assertTrue(failure.getMessage().contains("was already moved"));
    }

    @Test
    void competingThreadsCannotBothExtractOneClassHeldTask() throws Exception {
        Object uninitialized = new Object();
        Object moved = new Object();
        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int round = 0; round < 100; round++) {
                Map<String, Object> fields = new LinkedHashMap<>();
                Object owner = new Object();
                fields.put("producer", owner);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> attempts = new ArrayList<>();
                for (int i = 0; i < contenders; i++) {
                    attempts.add(pool.submit(() -> {
                        start.await();
                        try {
                            assertSame(owner, OresFieldSnapshots.take(
                                    fields, "TaskCell", "producer", uninitialized, moved));
                            return true;
                        } catch (IllegalStateException alreadyMoved) {
                            assertTrue(alreadyMoved.getMessage().contains("already moved"));
                            return false;
                        }
                    }));
                }
                start.countDown();
                int winners = 0;
                for (Future<Boolean> attempt : attempts) {
                    if (attempt.get(30, TimeUnit.SECONDS)) winners++;
                }
                assertEquals(1, winners, "exactly one owner must win the move race");
                assertSame(moved, fields.get("producer"));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void movedClassFieldMayReceiveFreshOwnerButCannotReplayOldOwner() {
        Map<String, Object> fields = new LinkedHashMap<>();
        Object uninitialized = new Object();
        Object moved = new Object();
        Object first = new Object();
        Object second = new Object();
        fields.put("producer", first);
        assertSame(first, OresFieldSnapshots.take(
                fields, "TaskCell", "producer", uninitialized, moved));
        synchronized (fields) {
            fields.put("producer", second);
        }
        assertSame(second, OresFieldSnapshots.take(
                fields, "TaskCell", "producer", uninitialized, moved));
        assertSame(moved, fields.get("producer"));
    }

    @Test
    void missingAndUninitializedClassFieldsCannotBeMoved() {
        Map<String, Object> fields = new LinkedHashMap<>();
        Object uninitialized = new Object();
        Object moved = new Object();
        assertThrows(IllegalArgumentException.class,
                () -> OresFieldSnapshots.take(fields, "TaskCell", "producer", uninitialized, moved));
        fields.put("producer", uninitialized);
        assertThrows(IllegalArgumentException.class,
                () -> OresFieldSnapshots.take(fields, "TaskCell", "producer", uninitialized, moved));
        assertSame(uninitialized, fields.get("producer"));
    }

    @Test
    void snapshotDoesNotHoldMapMonitorDuringFurtherGuestTraversal() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("producer", new Object());
        Map<String, Object> snapshot = OresFieldSnapshots.copy(fields);
        assertFalse(Thread.holdsLock(fields), "monitor must be released after copying");
        assertEquals(1, snapshot.size());
    }
}
