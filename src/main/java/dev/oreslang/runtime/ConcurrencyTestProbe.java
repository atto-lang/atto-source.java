package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Opt-in concurrency-test observation. No production task depends on this class.
 *
 * A recorded scheduling decision is reproducible; physical carrier interleavings
 * are NOT yet deterministic. A mismatch fails closed instead of silently
 * substituting a new schedule. This is a test-only foundation, not a claim of
 * guest memory-access race instrumentation or exhaustive model checking.
 */
public final class ConcurrencyTestProbe {
    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();
    private static final int MAX_EVENTS = 100_000;

    private ConcurrencyTestProbe() { }

    public static Session start(long seed, boolean perturb, List<String> replay) {
        Session session = new Session(seed, perturb, replay);
        if (!ACTIVE.compareAndSet(null, session)) {
            throw new IllegalStateException("a concurrency test session is already active");
        }
        return session;
    }

    /** Called only at points outside guest locks and scheduler graph critical sections. */
    public static void checkpoint(String point) {
        Session session = ACTIVE.get();
        if (session != null) session.checkpoint(point);
    }

    /** Report an observed wait cycle even if guest code catches the exception. */
    public static void deadlock(String kind) {
        Session session = ACTIVE.get();
        if (session != null) session.recordDeadlock(kind);
    }

    public static final class Session implements AutoCloseable {
        private final long seed;
        private final boolean perturb;
        private final List<String> replay;
        private final SplittableRandom random;
        private final List<String> events = new ArrayList<>();
        private int deadlocks;
        private boolean closed;

        private Session(long seed, boolean perturb, List<String> replay) {
            this.seed = seed;
            this.perturb = perturb;
            this.replay = replay == null ? null : List.copyOf(replay);
            this.random = new SplittableRandom(seed);
        }

        private void checkpoint(String point) {
            Objects.requireNonNull(point, "checkpoint point");
            if (!point.matches("[a-z][a-z0-9-]{0,47}")) {
                throw new IllegalArgumentException("invalid concurrency checkpoint");
            }
            final int decision;
            synchronized (this) {
                decision = record(point, perturb ? random.nextInt(3) : 0);
            }
            // Never hold the trace monitor while yielding a carrier.
            if (decision == 1) Thread.yield();
            else if (decision == 2) LockSupport.parkNanos(100_000L);
        }

        private void recordDeadlock(String kind) {
            synchronized (this) {
                record("deadlock-" + kind, 0);
                deadlocks++;
            }
        }

        private int record(String point, int choice) {
            if (closed) throw new IllegalStateException("concurrency test session is closed");
            if (events.size() == MAX_EVENTS) {
                throw new IllegalStateException("concurrency trace event budget exceeded");
            }
            String line = point + "|" + choice;
            if (replay != null) {
                if (events.size() >= replay.size()) {
                    throw new IllegalStateException("concurrency replay has extra event: " + line);
                }
                String expected = replay.get(events.size());
                if (!expected.startsWith(point + "|")) {
                    throw new IllegalStateException("concurrency replay mismatch at event "
                            + events.size() + ": expected " + expected + ", got " + line);
                }
                // Replay the recorded scheduling decision, not the PRNG output.
                String raw = expected.substring(point.length() + 1);
                try {
                    choice = Integer.parseInt(raw);
                } catch (NumberFormatException invalid) {
                    throw new IllegalStateException("invalid replay scheduling decision", invalid);
                }
                if (choice < 0 || choice > 2) {
                    throw new IllegalStateException("replay scheduling decision out of range");
                }
                line = expected;
            }
            events.add(line);
            return choice;
        }

        public synchronized int eventCount() {
            return events.size();
        }

        public synchronized int detectedDeadlocks() {
            return deadlocks;
        }

        public synchronized List<String> trace() {
            return List.copyOf(events);
        }

        public long seed() {
            return seed;
        }

        public synchronized void verifyReplayComplete() {
            if (replay != null && events.size() != replay.size()) {
                throw new IllegalStateException("concurrency replay ended early: "
                        + events.size() + "/" + replay.size());
            }
        }

        public synchronized void writeTrace(Path destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            List<String> lines = new ArrayList<>(events.size() + 1);
            lines.add("ORES_CONCURRENCY_TRACE_V1|seed=" + seed);
            lines.addAll(events);
            Files.write(destination, lines, StandardCharsets.UTF_8);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (!ACTIVE.compareAndSet(this, null)) {
                throw new IllegalStateException("concurrency session ownership lost");
            }
        }
    }

    public static List<String> readTrace(Path trace) throws IOException {
        Objects.requireNonNull(trace, "trace");
        if (Files.size(trace) > 8L * 1024 * 1024) {
            throw new IllegalArgumentException("concurrency replay trace exceeds 8 MiB admission limit");
        }
        List<String> lines = Files.readAllLines(trace, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).matches("ORES_CONCURRENCY_TRACE_V1\\|seed=-?[0-9]+")) {
            throw new IllegalArgumentException("not an Oreslang concurrency trace");
        }
        if (lines.size() > MAX_EVENTS + 1) {
            throw new IllegalArgumentException("concurrency trace event budget exceeded");
        }
        return List.copyOf(lines.subList(1, lines.size()));
    }
}
