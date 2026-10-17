package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Bounded, payload-free reliability primitives for the actor runtime.
 *
 * <p>This class deliberately keeps observability out of actor DATA. Events
 * contain only runtime/actor identity, incarnation, stable enum categories and
 * numeric values. No user message, exception text or arbitrary label can enter
 * the event ring, preventing accidental secret capture and unbounded metric
 * cardinality.</p>
 *
 * <p>All time comes from an explicit monotonic provider. The kernel never
 * sleeps, schedules work, invokes guest callbacks, or owns actor authority.</p>
 */
final class ActorReliabilityKernel {
    enum EventKind {
        CALL_REGISTERED,
        CALL_REPLIED,
        CALL_TIMEOUT,
        CALL_CANCELLED,
        CALL_REPLY_REJECTED,
        CALL_CLOSED,
        RESTART_ADMITTED,
        RESTART_SUPPRESSED,
        OVERLOAD_SHED,
        ACTOR_FAILED,
        ACTOR_QUARANTINED
    }

    enum FaultClass {
        NONE,
        OVERLOAD,
        TIMEOUT,
        CANCELLED,
        STALE_OR_DUPLICATE,
        SHUTDOWN,
        HANDLER_FAILURE,
        WATCHDOG,
        CRASH_LOOP,
        TRANSPORT,
        PROTOCOL
    }

    record ActorKey(UUID runtimeId, UUID actorId, long incarnation) {
        ActorKey {
            Objects.requireNonNull(runtimeId, "runtimeId");
            Objects.requireNonNull(actorId, "actorId");
            if (incarnation <= 0) throw new IllegalArgumentException("incarnation must be positive");
        }
    }

    record Event(
            long sequence,
            long monotonicNanos,
            EventKind kind,
            ActorKey actor,
            FaultClass fault,
            long value) {
        Event {
            if (sequence < 0) throw new IllegalArgumentException("sequence must be nonnegative");
            if (monotonicNanos < 0) throw new IllegalArgumentException("monotonic time must be nonnegative");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(fault, "fault");
        }
    }

    record Snapshot(
            long nextSequence,
            long overwrittenEvents,
            List<Event> recentEvents,
            Map<EventKind, Long> counters,
            long clockRegressions,
            long saturatedSequenceDrops) {
        Snapshot {
            recentEvents = List.copyOf(recentEvents);
            counters = Map.copyOf(counters);
        }
    }

    private final Object lock = new Object();
    private final int capacity;
    private final LongSupplier monotonicNanos;
    private final ArrayDeque<Event> recent;
    private final long[] counters = new long[EventKind.values().length];
    private long nextSequence;
    private long overwritten;
    private long clockRegressions;
    private long saturatedSequenceDrops;
    private boolean clockInitialized;
    private long lastClockSample;
    private long monotonicElapsed;

    ActorReliabilityKernel(int capacity, LongSupplier monotonicNanos) {
        if (capacity < 1 || capacity > 65_536)
            throw new IllegalArgumentException("event capacity must be 1..65536");
        this.capacity = capacity;
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.recent = new ArrayDeque<>(Math.min(capacity, 1_024));
    }

    static ActorReliabilityKernel production(int capacity) {
        return new ActorReliabilityKernel(capacity, System::nanoTime);
    }

    void record(EventKind kind, ActorKey actor, FaultClass fault, long value) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(fault, "fault");
        synchronized (lock) {
            // Sample inside the publication lock. A clock read before the
            // lock can be overtaken by a second carrier, producing a fake
            // "regression" and dropping otherwise valid fault telemetry.
            // nanoTime is signed and may start negative; all event timestamps
            // are relative to this kernel's first successful sample.
            long sample = monotonicNanos.getAsLong();
            if (!clockInitialized) {
                clockInitialized = true;
                lastClockSample = sample;
            } else {
                long delta = sample - lastClockSample;
                if (delta < 0) {
                    // Unlike scheduling and restart admission, diagnostic
                    // telemetry MUST NOT bring an actor down when its clock
                    // source regresses. Freeze time and count the anomaly.
                    clockRegressions = saturatingIncrement(clockRegressions);
                } else {
                    lastClockSample = sample;
                    monotonicElapsed = saturatingAdd(monotonicElapsed, delta);
                }
            }
            counters[kind.ordinal()] = saturatingIncrement(counters[kind.ordinal()]);
            if (nextSequence == Long.MAX_VALUE) {
                // Never publish duplicate sequence IDs after exhaustion.
                // Keep counters alive and explicitly report dropped history.
                saturatedSequenceDrops = saturatingIncrement(saturatedSequenceDrops);
                return;
            }
            long sequence = nextSequence++;
            if (recent.size() == capacity) {
                recent.removeFirst();
                overwritten = saturatingIncrement(overwritten);
            }
            recent.addLast(new Event(sequence, monotonicElapsed, kind, actor, fault, value));
        }
    }

    Snapshot snapshot() {
        synchronized (lock) {
            EnumMap<EventKind, Long> counts = new EnumMap<>(EventKind.class);
            for (EventKind kind : EventKind.values())
                counts.put(kind, counters[kind.ordinal()]);
            return new Snapshot(
                    nextSequence,
                    overwritten,
                    new ArrayList<>(recent),
                    counts,
                    clockRegressions,
                    saturatedSequenceDrops);
        }
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long saturatingAdd(long left, long right) {
        if (right < 0) throw new IllegalArgumentException("negative elapsed time");
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /**
     * Sliding restart-intensity guard. A supervisor owns one guard per restart
     * domain; therefore no global actor-id map can leak after actor death.
     */
    static final class RestartBudget {
        record Decision(boolean admitted, long retryAfterNanos, int restartsInWindow) {
            Decision {
                if (retryAfterNanos < 0) throw new IllegalArgumentException("negative retry");
                if (restartsInWindow < 0) throw new IllegalArgumentException("negative restart count");
            }
        }

        private final int maxRestarts;
        private final long windowNanos;
        private final long quarantineNanos;
        private final LongSupplier clock;
        private final long[] accepted;
        private int start;
        private int size;
        private long blockedUntil;
        private boolean clockInitialized;
        private long lastRawClock;
        private long elapsedNanos;

        RestartBudget(
                int maxRestarts,
                long windowNanos,
                long quarantineNanos,
                LongSupplier clock) {
            if (maxRestarts < 1 || maxRestarts > 1024)
                throw new IllegalArgumentException("maxRestarts must be 1..1024");
            if (windowNanos <= 0 || quarantineNanos <= 0)
                throw new IllegalArgumentException("window/quarantine must be positive");
            this.maxRestarts = maxRestarts;
            this.windowNanos = windowNanos;
            this.quarantineNanos = quarantineNanos;
            this.clock = Objects.requireNonNull(clock, "clock");
            this.accepted = new long[maxRestarts];
        }

        synchronized Decision tryAcquire() {
            long now = checkedNow();
            evictExpired(now);
            if (now < blockedUntil)
                return new Decision(false, blockedUntil - now, size);
            if (size >= maxRestarts) {
                blockedUntil = saturatingAdd(now, quarantineNanos);
                return new Decision(false, quarantineNanos, size);
            }
            accepted[(start + size) % accepted.length] = now;
            size++;
            return new Decision(true, 0L, size);
        }

        synchronized void resetAfterHealthyPeriod() {
            long now = checkedNow();
            evictExpired(now);
            // A premature external "healthy" callback must not bypass the
            // crash-loop breaker. Full window without an admitted restart
            // AND a completed quarantine are required.
            if (now < blockedUntil || size != 0) {
                throw new IllegalStateException(
                        "restart budget cannot reset before a healthy window and quarantine");
            }
            start = 0;
            blockedUntil = 0L;
        }

        private long checkedNow() {
            long sample = clock.getAsLong();
            if (!clockInitialized) {
                clockInitialized = true;
                lastRawClock = sample;
                return 0L;
            }
            long delta = sample - lastRawClock;
            if (delta < 0) {
                // Unlike observability this controls actor execution.
                // Fail closed instead of allowing restart storms or
                // manufacturing a negative retry delay.
                throw new IllegalStateException("restart monotonic clock regressed");
            }
            lastRawClock = sample;
            elapsedNanos = Math.addExact(elapsedNanos, delta);
            return elapsedNanos;
        }

        private void evictExpired(long now) {
            while (size > 0) {
                long then = accepted[start];
                long age = now - then;
                if (age < 0 || age < windowNanos) break;
                start = (start + 1) % accepted.length;
                size--;
            }
        }

        private static long saturatingAdd(long a, long b) {
            if (Long.MAX_VALUE - a < b) return Long.MAX_VALUE;
            return a + b;
        }
    }

    /**
     * Data-lane load shedding with hysteresis. CONTROL is never shed here.
     * This is an admission decision only and never drops an already-accepted
     * message.
     */
    static final class OverloadGate {
        enum Admission { ACCEPT, SHED, CONTROL_ACCEPT }

        private final int capacity;
        private final int highWatermark;
        private final int recoveryWatermark;
        private boolean shedding;

        OverloadGate(int capacity, int highWatermark, int recoveryWatermark) {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
            if (recoveryWatermark < 0 || recoveryWatermark >= highWatermark
                    || highWatermark > capacity)
                throw new IllegalArgumentException("require 0 <= recovery < high <= capacity");
            this.capacity = capacity;
            this.highWatermark = highWatermark;
            this.recoveryWatermark = recoveryWatermark;
        }

        synchronized Admission admit(int queuedMessages, boolean controlPlane) {
            if (queuedMessages < 0 || queuedMessages > capacity)
                throw new IllegalArgumentException("queue depth outside capacity");
            if (controlPlane) return Admission.CONTROL_ACCEPT;
            if (shedding && queuedMessages <= recoveryWatermark) shedding = false;
            if (!shedding && queuedMessages >= highWatermark) shedding = true;
            return shedding ? Admission.SHED : Admission.ACCEPT;
        }
    }

    /**
     * Deterministic exponential backoff with deterministic jitter. It spreads
     * restart/retry storms without making incident replay depend on a mutable
     * process-global RNG. Same attempt + salt => same delay.
     */
    static final class BackoffPolicy {
        private final long baseNanos;
        private final long maxNanos;

        BackoffPolicy(long baseNanos, long maxNanos) {
            if (baseNanos <= 0 || maxNanos < baseNanos)
                throw new IllegalArgumentException("require 0 < base <= max");
            this.baseNanos = baseNanos;
            this.maxNanos = maxNanos;
        }

        long delayNanos(int attempt, long stableSalt) {
            if (attempt < 0) throw new IllegalArgumentException("attempt must be nonnegative");
            long cap = baseNanos;
            for (int i = 0; i < attempt && cap < maxNanos; i++) {
                cap = cap > maxNanos / 2 ? maxNanos : Math.min(maxNanos, cap * 2);
            }
            long floor = Math.max(1L, cap / 2);
            long span = cap - floor + 1L;
            long mixed = mix64(stableSalt ^ (0x9E3779B97F4A7C15L * (attempt + 1L)));
            long offset = Long.remainderUnsigned(mixed, span);
            return floor + offset;
        }

        private static long mix64(long z) {
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }
}
