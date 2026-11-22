package dev.oreslang.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
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
        return start(seed, perturb, replay, null, null);
    }

    /** Bound CLI evidence to its entry source bytes and backend configuration. */
    public static Session start(long seed, boolean perturb, List<String> replay,
            String entrySha256, String backend) {
        return start(seed, perturb, replay, entrySha256, backend, null);
    }

    /** Workspace source snapshot is an overapproximation, NOT a compiler import closure. */
    public static Session start(long seed, boolean perturb, List<String> replay,
            String entrySha256, String backend, String workspaceSha256) {
        if ((entrySha256 == null) != (backend == null)) {
            throw new IllegalArgumentException("trace provenance must be complete");
        }
        if (entrySha256 != null && (!entrySha256.matches("[0-9a-f]{64}")
                || !backend.matches("[A-Za-z0-9_.-]{1,80}"))) {
            throw new IllegalArgumentException("invalid trace provenance identity");
        }
        if (workspaceSha256 != null && (entrySha256 == null
                || !workspaceSha256.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("invalid workspace replay provenance");
        }
        Session session = new Session(seed, perturb, replay,
                entrySha256, backend, workspaceSha256);
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
        private final String entrySha256;
        private final String backend;
        private final String workspaceSha256;
        private final List<String> events = new ArrayList<>();
        private int deadlocks;
        private boolean closed;

        private Session(long seed, boolean perturb, List<String> replay,
                String entrySha256, String backend, String workspaceSha256) {
            this.entrySha256 = entrySha256;
            this.backend = backend;
            this.workspaceSha256 = workspaceSha256;
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
            lines.add(workspaceSha256 != null
                    ? "ORES_CONCURRENCY_TRACE_V3|seed=" + seed
                    + "|entry-sha256=" + entrySha256
                    + "|workspace-sha256=" + workspaceSha256
                    + "|backend=" + backend + "|probe=3"
                    : entrySha256 == null
                    ? "ORES_CONCURRENCY_TRACE_V1|seed=" + seed
                    : "ORES_CONCURRENCY_TRACE_V2|seed=" + seed
                    + "|entry-sha256=" + entrySha256
                    + "|backend=" + backend + "|probe=1");
            lines.addAll(events);
            // CREATE_NEW is atomic w.r.t. an existing file or symlink. Never
            // destroy the original evidence when a seed/path is reused or
            // when a file is swapped between CLI validation and this write.
            Files.write(destination, lines, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
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

    /** Parsed trace receipt; the seed is part of replay identity. */
    public record TraceData(long seed, List<String> events,
            String entrySha256, String backend, String workspaceSha256) { }

    public static TraceData readTraceData(Path trace) throws IOException {
        Objects.requireNonNull(trace, "trace");
        if (Files.isSymbolicLink(trace) || !Files.isRegularFile(trace)) {
            throw new IllegalArgumentException("replay trace must be a regular non-symlink file");
        }
        if (Files.size(trace) > 8L * 1024 * 1024) {
            throw new IllegalArgumentException("concurrency replay trace exceeds 8 MiB admission limit");
        }
        // Bound bytes consumed, not merely the pre-read file size: another
        // process may append data after Files.size() and before read().
        byte[] bytes;
        try (InputStream stream = Channels.newInputStream(Files.newByteChannel(
                trace, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
            bytes = stream.readNBytes(8 * 1024 * 1024 + 1);
        }
        if (bytes.length > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("concurrency replay trace exceeds 8 MiB admission limit");
        }
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        List<String> lines = decoded.lines().toList();
        if (lines.isEmpty() || lines.size() > MAX_EVENTS + 1) {
            throw new IllegalArgumentException("invalid concurrency trace event count");
        }
        String header = lines.get(0);
        String v1 = "ORES_CONCURRENCY_TRACE_V1|seed=";
        String v2 = "ORES_CONCURRENCY_TRACE_V2|seed=";
        String v3 = "ORES_CONCURRENCY_TRACE_V3|seed=";
        String raw;
        String entrySha256 = null;
        String backend = null;
        String workspaceSha256 = null;
        if (header.startsWith(v3)) {
            String entryTag = "|entry-sha256=";
            String workspaceTag = "|workspace-sha256=";
            String backendTag = "|backend=";
            String probeTag = "|probe=3";
            int entryAt = header.indexOf(entryTag, v3.length());
            int workspaceAt = entryAt < 0 ? -1
                    : header.indexOf(workspaceTag, entryAt + entryTag.length());
            int backendAt = workspaceAt < 0 ? -1
                    : header.indexOf(backendTag, workspaceAt + workspaceTag.length());
            if (entryAt < 0 || workspaceAt < 0 || backendAt < 0
                    || !header.endsWith(probeTag)) {
                throw new IllegalArgumentException("invalid V3 trace provenance");
            }
            raw = header.substring(v3.length(), entryAt);
            entrySha256 = header.substring(entryAt + entryTag.length(), workspaceAt);
            workspaceSha256 = header.substring(workspaceAt + workspaceTag.length(), backendAt);
            backend = header.substring(backendAt + backendTag.length(),
                    header.length() - probeTag.length());
            if (!entrySha256.matches("[0-9a-f]{64}")
                    || !workspaceSha256.matches("[0-9a-f]{64}")
                    || !backend.matches("[A-Za-z0-9_.-]{1,80}")) {
                throw new IllegalArgumentException("invalid V3 trace provenance");
            }
        } else if (header.startsWith(v2)) {
            int entryAt = header.indexOf("|entry-sha256=", v2.length());
            int backendAt = header.indexOf("|backend=", entryAt + 14);
            if (entryAt < 0 || backendAt < 0 || !header.endsWith("|probe=1")) {
                throw new IllegalArgumentException("invalid V2 trace provenance");
            }
            raw = header.substring(v2.length(), entryAt);
            entrySha256 = header.substring(entryAt + "|entry-sha256=".length(), backendAt);
            backend = header.substring(backendAt + "|backend=".length(),
                    header.length() - "|probe=1".length());
            if (!entrySha256.matches("[0-9a-f]{64}") ||
                    !backend.matches("[A-Za-z0-9_.-]{1,80}")) {
                throw new IllegalArgumentException("invalid V2 trace provenance");
            }
        } else if (header.startsWith(v1)) {
            raw = header.substring(v1.length());
        } else {
            throw new IllegalArgumentException("not an Oreslang concurrency trace");
        }
        long seed;
        try {
            if (!raw.matches("-?[0-9]+")) throw new NumberFormatException(raw);
            seed = Long.parseLong(raw);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid concurrency trace seed", invalid);
        }
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).matches("[a-z][a-z0-9-]{0,47}\\|[012]")) {
                throw new IllegalArgumentException("invalid concurrency event at " + i);
            }
        }
        return new TraceData(seed, List.copyOf(lines.subList(1, lines.size())),
                entrySha256, backend, workspaceSha256);
    }

    public static List<String> readTrace(Path trace) throws IOException {
        return readTraceData(trace).events();
    }
}
