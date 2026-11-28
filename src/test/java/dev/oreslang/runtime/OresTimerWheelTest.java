package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class OresTimerWheelTest {
    private static final long MS = 1_000_000L;

    private static final class Sink {
        final long[] handles = new long[4096];
        final Object[] values = new Object[4096];
    }

    private static List<Object> poll(OresTimerWheel<Object> w, long now, int max, Sink sink) {
        int n = w.poll(now, max, sink.handles, sink.values);
        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(sink.values[i]);
        return out;
    }

    @Test
    void firesAtDeadlineNeverEarlier() {
        OresTimerWheel<Object> w = new OresTimerWheel<>(8, 8, MS, 0);
        Sink s = new Sink();
        w.arm(5 * MS, "a");
        assertEquals(List.of(), poll(w, 5 * MS - 1, 10, s));
        assertEquals(List.of("a"), poll(w, 5 * MS, 10, s));
        assertEquals(0, w.size());
    }

    @Test
    void subTickDeadlinesKeepNanosecondOrderingAcrossPolls() {
        OresTimerWheel<Object> w = new OresTimerWheel<>(8, 8, MS, 0);
        Sink s = new Sink();
        w.arm(MS + 900, "late");
        w.arm(MS + 100, "early");
        assertEquals(List.of(), poll(w, MS + 99, 10, s));
        assertEquals(List.of("early"), poll(w, MS + 500, 10, s));
        assertEquals(MS + 900, w.nextDeadlineNanos());
        assertEquals(List.of("late"), poll(w, MS + 900, 10, s));
    }

    @Test
    void cascadesThroughEveryLevelAndOverflow() {
        long tick = 1; // 1 ns ticks reach the overflow list with modest numbers
        OresTimerWheel<Object> w = new OresTimerWheel<>(64, 64, tick, 0);
        Sink s = new Sink();
        long[] deadlines = {1, 63, 64, 65, 4095, 4096, 1L << 18, (1L << 24) + 7,
                (1L << 30) - 1, (1L << 35) + 3, (1L << 36) - 1, 1L << 36,
                (1L << 37) + 5, (1L << 50) + 11};
        for (long d : deadlines) w.arm(d, d);
        for (long d : deadlines) {
            assertTrue(w.nextDeadlineNanos() <= d, "next deadline is a lower bound");
            assertEquals(List.of(), poll(w, d - 1, 100, s), "not before " + d);
            assertEquals(List.of((Object) d), poll(w, d, 100, s), "exactly at " + d);
        }
        assertEquals(OresTimerWheel.NO_DEADLINE, w.nextDeadlineNanos());
    }

    @Test
    void latePollPreservesExpiryTickOrderThroughCascadeAndOverflow() {
        for (long base : new long[] {0, 1L << 36}) {
            OresTimerWheel<Object> w = new OresTimerWheel<>(8, 8, 1, 0);
            Sink s = new Sink();
            assertNotEquals(0, w.arm(base + 127, 127L));
            assertNotEquals(0, w.arm(base + 64, 64L));
            assertNotEquals(0, w.arm(base + 65, 65L));
            long cancelled = w.arm(base + 65, 66L);
            assertNotEquals(0, cancelled);
            assertEquals(List.of(), poll(w, base + 63, 8, s));
            assertEquals(List.of(64L), poll(w, base + 127, 1, s));
            assertTrue(w.cancel(cancelled));
            assertEquals(List.of(65L, 127L), poll(w, base + 127, 8, s));
            assertEquals(0, w.size());
        }
        OresTimerWheel<Object> w = new OresTimerWheel<>(8, 8, 10, 0);
        Sink s = new Sink();
        w.arm(649, 2L);
        w.arm(641, 1L);
        assertEquals(List.of(2L, 1L), poll(w, 649, 8, s));
    }

    @Test
    void longIdleJumpVisitsOnlyOccupiedSlots() {
        OresTimerWheel<Object> w = new OresTimerWheel<>(8, 8, MS, 0);
        Sink s = new Sink();
        w.arm(30L * 24 * 3600 * 1000 * MS, "month"); // ~2.6e9 ticks away
        long t0 = System.nanoTime();
        assertEquals(List.of(), poll(w, 30L * 24 * 3600 * 1000 * MS - 1, 10, s));
        assertEquals(List.of("month"), poll(w, 30L * 24 * 3600 * 1000 * MS, 10, s));
        assertTrue(System.nanoTime() - t0 < 1_000 * MS, "idle jump must not walk every tick");
    }

    @Test
    void cancelAndStaleHandlesAreGenerationFenced() {
        OresTimerWheel<Object> w = new OresTimerWheel<>(1, 1, MS, 0);
        Sink s = new Sink();
        long a = w.arm(10 * MS, "a");
        assertTrue(w.cancel(a));
        assertFalse(w.cancel(a), "double cancel");
        long b = w.arm(10 * MS, "b"); // reuses the only slot
        assertNotEquals(a, b);
        assertFalse(w.cancel(a), "stale handle cannot cancel the reused slot");
        assertTrue(w.isLive(b));
        assertEquals(List.of("b"), poll(w, 10 * MS, 10, s));
        assertFalse(w.cancel(b), "emitted handle is dead");
        assertFalse(w.cancel(0));
        assertFalse(w.cancel(-1));
        assertEquals(0, w.arm(1, "x") == 0 ? 1 : 0, "capacity 1 reusable after emit");
    }

    @Test
    void boundedDrainingIsFifoAndQueuedTimersStayCancellable() {
        OresTimerWheel<Object> w = new OresTimerWheel<>(1024, 1024, MS, 0);
        Sink s = new Sink();
        long[] h = new long[300];
        for (int i = 0; i < 300; i++) h[i] = w.arm(7 * MS, i);
        List<Object> first = poll(w, 7 * MS, 100, s);
        assertEquals(100, first.size());
        for (int i = 0; i < 100; i++) assertEquals(i, first.get(i), "arm order within a tick");
        assertTrue(w.cancel(h[150]), "due but not yet emitted");
        List<Object> rest = new ArrayList<>(poll(w, 7 * MS, 1000, s));
        assertEquals(199, rest.size());
        assertFalse(rest.contains(150));
        assertEquals(100, rest.get(0));
    }

    @Test
    void capacityBoundsAndGrowth() {
        OresTimerWheel<Object> w = new OresTimerWheel<>(2, 5, MS, 0);
        for (int i = 0; i < 5; i++) assertNotEquals(0, w.arm(MS, i));
        assertEquals(5, w.capacity());
        assertEquals(0, w.arm(MS, "full"));
        assertThrows(IllegalArgumentException.class, () -> w.arm(-1, "neg"));
    }

    @Test
    void clockRegressionIsClampedNotFiredEarly() {
        OresTimerWheel<Object> w = new OresTimerWheel<>(4, 4, MS, 100 * MS);
        Sink s = new Sink();
        w.arm(150 * MS, "x");
        assertEquals(List.of(), poll(w, 10 * MS, 10, s));
        assertEquals(100 * MS, w.lastNowNanos());
        assertEquals(List.of("x"), poll(w, 150 * MS, 10, s));
    }

    @Test
    void randomizedDifferentialAgainstSortedModel() {
        Random rnd = new Random(0xA770L);
        OresTimerWheel<Object> w = new OresTimerWheel<>(16, 1 << 16, MS, 0);
        Sink s = new Sink();
        Map<Long, Long> live = new HashMap<>(); // handle -> deadline
        Map<Long, Integer> idOf = new HashMap<>();
        Map<Integer, Long> deadlineOfId = new HashMap<>();
        long now = 0;
        int nextId = 0;
        for (int step = 0; step < 200_000; step++) {
            int op = rnd.nextInt(10);
            if (op < 5) {
                long delay = switch (rnd.nextInt(4)) {
                    case 0 -> rnd.nextLong(2 * MS);
                    case 1 -> rnd.nextLong(500 * MS);
                    case 2 -> rnd.nextLong(3_600_000 * MS);
                    default -> rnd.nextLong(1L << 61);
                };
                long d = now + delay;
                int id = nextId++;
                long h = w.arm(d, id);
                assertNotEquals(0, h);
                live.put(h, d);
                idOf.put(h, id);
                deadlineOfId.put(id, d);
            } else if (op < 7 && !live.isEmpty()) {
                Long h = live.keySet().iterator().next();
                assertTrue(w.cancel(h));
                live.remove(h);
            } else {
                now += rnd.nextInt(20) == 0 ? rnd.nextLong(1L << 50) : rnd.nextLong(50 * MS);
                long minLive = live.values().stream().mapToLong(Long::longValue).min()
                        .orElse(Long.MAX_VALUE);
                long bound = w.nextDeadlineNanos();
                assertTrue(bound <= minLive || bound == OresTimerWheel.NO_DEADLINE && live.isEmpty(),
                        "next deadline must never be later than the earliest live timer");
                Set<Integer> expected = new HashSet<>();
                for (Map.Entry<Long, Long> e : live.entrySet()) {
                    if (e.getValue() <= now) expected.add(idOf.get(e.getKey()));
                }
                Set<Integer> got = new HashSet<>();
                int n;
                while ((n = w.poll(now, 4096, s.handles, s.values)) > 0) {
                    for (int i = 0; i < n; i++) {
                        int id = (Integer) s.values[i];
                        assertTrue(deadlineOfId.get(id) <= now, "fired early");
                        assertTrue(got.add(id), "fired twice");
                        assertNotNull(live.remove(s.handles[i]), "emitted handle was live");
                    }
                }
                assertEquals(expected, got);
            }
            assertEquals(live.size(), w.size());
        }
    }

    @Test
    void oneMillionTimers() {
        int n = 1_000_000;
        OresTimerWheel<Object> w = new OresTimerWheel<>(1024, n, MS, 0);
        Random rnd = new Random(7);
        long[] deadlines = new long[n];
        Integer boxed = 0;
        for (int i = 0; i < n; i++) {
            deadlines[i] = rnd.nextLong(600_000 * MS); // within ten minutes
            assertNotEquals(0, w.arm(deadlines[i], boxed));
        }
        Sink s = new Sink();
        long fired = 0;
        for (long now = 0; now <= 600_000 * MS; now += 250 * MS) {
            int k;
            while ((k = w.poll(now, 4096, s.handles, s.values)) > 0) fired += k;
        }
        assertEquals(n, fired);
        assertEquals(0, w.size());
    }

    @Test
    void steadyStateArmCancelPollDoesNotAllocate() {
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!mx.isThreadAllocatedMemorySupported()) return;
        OresTimerWheel<Object> w = new OresTimerWheel<>(4096, 4096, MS, 0);
        Sink s = new Sink();
        Object token = new Object();
        long[] h = new long[2048];
        long now = 0;
        for (int warm = 0; warm < 3; warm++) {
            for (int round = 0; round < 1000; round++) {
                for (int i = 0; i < h.length; i++) h[i] = w.arm(now + (i % 97) * MS, token);
                for (int i = 0; i < h.length; i += 3) w.cancel(h[i]);
                now += 100 * MS;
                while (w.poll(now, 4096, s.handles, s.values) > 0) { }
            }
            long tid = Thread.currentThread().threadId();
            long before = mx.getThreadAllocatedBytes(tid);
            for (int round = 0; round < 1000; round++) {
                for (int i = 0; i < h.length; i++) h[i] = w.arm(now + (i % 97) * MS, token);
                for (int i = 0; i < h.length; i += 3) w.cancel(h[i]);
                now += 100 * MS;
                while (w.poll(now, 4096, s.handles, s.values) > 0) { }
            }
            long allocated = mx.getThreadAllocatedBytes(tid) - before;
            if (allocated < 64 * 1024) return; // ~2M operations, effectively zero
            if (warm == 2) fail("steady-state timer operations allocated " + allocated + " bytes");
        }
    }

    /**
     * Cross-backend conformance vector (contracts/TIMER_ENGINE_V1.md): the
     * native LLVM runtime's wheel must produce the same checksum.
     */
    @Test
    void sharedConformanceVector() {
        assertEquals(TimerConformanceVector.EXPECTED, TimerConformanceVector.run());
    }

    static final class TimerConformanceVector {
        static final String EXPECTED =
                "fired=11085 cancelled=793 full=0 polls=4074 checksum=cdc3f321e0578fc5";

        static String run() {
            OresTimerWheel<Object> w = new OresTimerWheel<>(4096, 4096, MS, 0);
            long[] outH = new long[64];
            Object[] outV = new Object[64];
            int steps = 20_000;
            long[] handles = new long[steps];
            long[] deadlines = new long[steps];
            long x = 0x5EEDL;
            long now = 0, checksum = 0;
            int nextId = 0, fired = 0, cancelled = 0, full = 0, polls = 0;
            for (int step = 0; step < steps; step++) {
                x = x * 6364136223846793005L + 1442695040888963407L;
                long r = x >>> 33;
                long op = r % 10;
                x = x * 6364136223846793005L + 1442695040888963407L;
                long r2 = x >>> 33;
                if (op < 6) {
                    long delay = switch ((int) (r2 % 4)) {
                        case 0 -> (r2 >>> 2) % 2_000_000L;
                        case 1 -> (r2 >>> 2) % 100_000_000L;
                        case 2 -> (r2 >>> 2) % 60_000_000_000L;
                        default -> (r2 >>> 2) * 4_096L; // up to ~2^42 ns: deep levels
                    };
                    int id = nextId++;
                    deadlines[id] = now + delay;
                    long h = w.arm(deadlines[id], (long) id);
                    if (h == 0) full++;
                    handles[id] = h;
                } else if (op < 8) {
                    if (nextId > 0) {
                        int id = (int) (r2 % nextId);
                        if (handles[id] != 0 && w.cancel(handles[id])) cancelled++;
                    }
                } else {
                    now += (r2 % 97 == 0) ? (r2 % 1_000_000_000_000L) : (r2 % 50_000_000L);
                    int max = (int) (1 + (r2 >>> 8) % 64);
                    polls++;
                    int n = w.poll(now, max, outH, outV);
                    for (int i = 0; i < n; i++) {
                        long id = (Long) outV[i];
                        if (deadlines[(int) id] > now) throw new AssertionError("early " + id);
                        checksum = checksum * 1_000_003L + (id ^ ((long) polls << 20));
                        fired++;
                    }
                }
            }
            // Final drain with a far-future clock.
            now = Long.MAX_VALUE / 2;
            int n;
            while ((n = w.poll(now, 64, outH, outV)) > 0) {
                polls++;
                for (int i = 0; i < n; i++) {
                    long id = (Long) outV[i];
                    checksum = checksum * 1_000_003L + (id ^ ((long) polls << 20));
                    fired++;
                }
            }
            return "fired=" + fired + " cancelled=" + cancelled + " full=" + full
                    + " polls=" + polls + " checksum=" + Long.toUnsignedString(checksum, 16);
        }
    }
}
