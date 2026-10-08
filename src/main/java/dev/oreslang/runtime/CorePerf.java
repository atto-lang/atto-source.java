package dev.oreslang.runtime;

import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Opt-in, core-only diagnostic flight recorder. No Ores guest objects, userland
 * telemetry, logging formatters or I/O execute on an instrumented hot path.
 *
 * The first bounded set of completed events is retained so cold-start work
 * cannot be evicted by a warmed server's steady-state requests.
 */
public final class CorePerf {
    public static final int VM_CONTROL_STARTUP = 1;
    public static final int ACTOR_POOLS_STARTUP = 2;
    public static final int CONTEXT_STARTUP = 3;
    public static final int HTTP_LISTEN = 4;
    public static final int HTTP_RECEIVE = 5;
    public static final int HTTP_DISPATCH = 6;
    public static final int ACTOR_READY = 7;
    public static final int HTTP_CLAIM = 8;
    public static final int HTTP_IO_QUEUE = 9;
    public static final int HTTP_IO_WORK = 10;
    public static final int ASYNC_IO_QUEUE = 11;
    public static final int ASYNC_IO_WORK = 12;

    private static final String[] NAMES = {
        "unused", "vm.control.startup", "actor.pools.startup",
        "context.startup", "http.listen", "http.receive",
        "http.dispatch", "actor.ready", "http.claim",
        "http.io.queue", "http.io.work",
        "async.io.queue", "async.io.work"
    };
    private static final int DEFAULT_CAPACITY = 8192;
    private static volatile Recorder active;

    private CorePerf() { }

    /**
     * Called once by the host CLI, before an Ores context is constructed.
     * Nothing, including a JVM shutdown hook, is installed when disabled.
     */
    public static synchronized void configure(boolean enabled) {
        if (!enabled || active != null) return;
        Recorder recorder = new Recorder(DEFAULT_CAPACITY);
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> recorder.dump(System.err), "ores-core-perf-dump"));
        active = recorder;
        System.err.println("ores-core-perf: enabled; first " + DEFAULT_CAPACITY
                + " completed phases buffered; JSONL exported at JVM shutdown");
    }

    /** Zero allocation and no clock read on the disabled hot path. */
    public static long start() {
        Recorder recorder = active;
        return recorder == null || recorder.saturated ? 0L : System.nanoTime();
    }

    public static void end(int phase, long started) {
        if (started == 0L) return;
        Recorder recorder = active;
        if (recorder != null && !recorder.saturated) {
            recorder.record(phase, started, System.nanoTime());
        }
    }

    /** Reuse timestamps already obtained for actor lifecycle bookkeeping. */
    public static void elapsed(int phase, long started, long ended) {
        Recorder recorder = active;
        if (recorder != null && !recorder.saturated
                && started != 0L && ended - started >= 0L) {
            recorder.record(phase, started, ended);
        }
    }

    /** Package-visible recorder for tests; separate from global CLI state. */
    static final class Recorder {
        private final int capacity;
        private final long origin = System.nanoTime();
        private final AtomicInteger cursor = new AtomicInteger();
        private final AtomicIntegerArray published;
        private final long[] offsets;
        private final long[] durations;
        private final long[] threadIds;
        private volatile boolean saturated;

        Recorder(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            this.capacity = capacity;
            this.published = new AtomicIntegerArray(capacity);
            this.offsets = new long[capacity];
            this.durations = new long[capacity];
            this.threadIds = new long[capacity];
        }

        void record(int phase, long started, long finished) {
            if (phase < 1 || phase >= NAMES.length || finished - started < 0L) {
                throw new IllegalArgumentException("invalid core perf sample");
            }
            if (saturated) return;
            int index = cursor.getAndIncrement();
            if (index >= capacity) {
                saturated = true;
                return;
            }
            offsets[index] = Math.max(0L, started - origin);
            durations[index] = finished - started;
            threadIds[index] = Thread.currentThread().threadId();
            // Release-publish last. Dumps use acquire-reads, never partial rows.
            published.set(index, phase);
        }

        void dump(PrintStream out) {
            int reserved = Math.min(capacity, cursor.get());
            out.println("{\"schema\":\"ores-core-perf.v1\",\"kind\":\"begin\",\"capacity\":" + capacity + "}");
            for (int i = 0; i < reserved; i++) {
                int phase = published.get(i);
                if (phase == 0) continue; // unfinished concurrent event
                out.println("{\"schema\":\"ores-core-perf.v1\",\"kind\":\"phase\",\"phase\":\""
                        + NAMES[phase] + "\",\"start_ns\":" + offsets[i]
                        + ",\"duration_ns\":" + durations[i]
                        + ",\"thread_id\":" + threadIds[i] + "}");
            }
            out.println("{\"schema\":\"ores-core-perf.v1\",\"kind\":\"end\",\"recorded\":"
                    + reserved + ",\"saturated\":" + saturated + "}");
            out.flush();
        }
    }
}
