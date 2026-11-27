package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Per-invocation logical guest stack accounting (spec chapter 25).
 *
 * <p>Every admitted actor receive invocation runs inside its own
 * {@link Segment}: a logical stack identity with its own guest-frame quota.
 * The quota is independent of how deep the carrier thread already is, so an
 * actor overflows at the same guest depth on every carrier. A second,
 * per-carrier <em>ceiling</em>, derived from that thread's real stack size,
 * bounds the sum of nested segments so the host JVM stack is never the first
 * thing to run out.</p>
 *
 * <p>Exceeding either bound raises {@link LogicalStackOverflowException}, an
 * ordinary {@link RuntimeException} that guest {@code trap}/{@code catch}
 * cannot consume. Unlike a host {@link StackOverflowError} it is not a
 * {@link VirtualMachineError}, so the actor turn loop fails only the owning
 * actor incarnation; the carrier thread, sibling actors and the process keep
 * running.</p>
 *
 * <p>Once a segment overflows it is <em>poisoned</em>. Unwinding continues on
 * the same logical stack, and {@code defer} cleanup gets a bounded
 * {@link #UNWIND_RESERVE_FRAMES} headroom so it can still run. Cleanup that
 * exhausts the reserve fails too (recorded as suppressed on the original
 * overflow) but cannot extend the stack further. A poisoned segment never
 * becomes healthy again: {@link Segment#overflowFailure()} still reports the
 * overflow when trusted host code swallowed the exception.</p>
 *
 * <p>Carrier identity is not stack identity: the thread-local slot only
 * records which logical segment the carrier is executing right now. Source
 * continuations resumed after {@code await} re-enter the interpreter from the
 * scheduler with a shallow host stack, so suspended frames are heap-held
 * continuation state, not charged carrier frames.</p>
 */
public final class LogicalStackQuota {
    /** Default guest frames per receive invocation (and per implicit carrier root). */
    public static final int DEFAULT_SEGMENT_FRAMES = 1_024;
    /** Extra frames granted to cleanup while a poisoned segment unwinds. */
    public static final int UNWIND_RESERVE_FRAMES = 64;
    /**
     * Host bytes budgeted per guest frame when deriving a carrier ceiling.
     * See docs/ACTOR_LOGICAL_STACKS.md for the calibration; the budget keeps
     * at least 1.5x headroom over the measured worst case. 32-bit JVMs use
     * half-width slots.
     */
    static final int HOST_BYTES_PER_GUEST_FRAME = is32BitJvm() ? 4_096 : 8_192;
    /**
     * Host stack kept free below the ceiling for the carrier's own base frames,
     * runtime calls, the unwind reserve and failure handling: a quarter of the
     * stack, at most 1 MiB.
     */
    static final long MAX_HOST_RESERVE_BYTES = 1024L * 1024;
    /**
     * Stack size requested for Oreslang-owned guest carriers (control plane,
     * user schedulers, actor dispatchers). Reserved, not committed, on
     * HotSpot/Linux/macOS, so RSS is pay-as-used. Smaller on 32-bit hosts
     * where address space, not RSS, is the constraint.
     */
    public static final long CARRIER_STACK_BYTES =
            boundedLongProperty("ores.stack.carrierBytes",
                    is32BitJvm() ? 6L * 1024 * 1024 : 16L * 1024 * 1024,
                    // Upper bound matches the native pthread carrier cap
                    // (ores.actor.carrier-stack-bytes, NativeCarrierExecutor).
                    512L * 1024, 64L * 1024 * 1024);

    private static final int SEGMENT_FRAMES =
            boundedProperty("ores.stack.segmentFrames", DEFAULT_SEGMENT_FRAMES, 16, 1 << 20);
    /** Optional override for the derived per-carrier ceiling (testing, calibration). */
    private static final int CARRIER_FRAMES_OVERRIDE =
            boundedProperty("ores.stack.carrierFrames", 0, 0, 1 << 20);
    private static final long DEFAULT_THREAD_STACK_BYTES = defaultThreadStackBytes();

    private static final ThreadLocal<Carrier> CARRIER =
            ThreadLocal.withInitial(() -> new Carrier(DEFAULT_THREAD_STACK_BYTES));

    private LogicalStackQuota() { }

    /** Guest frames a fresh segment may use, unless a caller passes its own quota. */
    public static int segmentFrames() { return SEGMENT_FRAMES; }

    /** Cross-segment ceiling for the current carrier thread. */
    public static int carrierFrames() { return CARRIER.get().ceiling; }

    /** Ceiling a carrier with {@code stackBytes} of host stack receives. */
    public static int carrierFramesFor(long stackBytes) {
        if (CARRIER_FRAMES_OVERRIDE > 0) return CARRIER_FRAMES_OVERRIDE;
        long reserve = Math.min(MAX_HOST_RESERVE_BYTES, stackBytes / 4);
        long usable = Math.max(0, stackBytes - reserve);
        long frames = usable / HOST_BYTES_PER_GUEST_FRAME;
        return (int) Math.max(16, Math.min(1 << 20, frames));
    }

    /**
     * Wrap a carrier thread body so the thread reports its real stack size.
     * Use together with an explicit {@code stackSize(CARRIER_STACK_BYTES)} on
     * the thread builder; unmarked threads assume the JVM default stack.
     */
    public static Runnable carrierBody(long stackBytes, Runnable body) {
        Objects.requireNonNull(body, "body");
        if (stackBytes <= 0) throw new IllegalArgumentException("carrier stack size must be positive");
        return () -> {
            CARRIER.set(new Carrier(stackBytes));
            try {
                body.run();
            } finally {
                CARRIER.remove();
            }
        };
    }

    /** Open a new logical stack segment on the current carrier with the default quota. */
    public static Segment enter(Object owner) {
        return enter(owner, SEGMENT_FRAMES);
    }

    /**
     * Open a new logical stack segment on the current carrier. Segments nest
     * strictly (LIFO); the caller must {@link Segment#close()} it in a
     * {@code finally} on the same thread. {@code owner} is formatted only if
     * the segment overflows, so passing an identity object costs nothing.
     */
    public static Segment enter(Object owner, int maxFrames) {
        Objects.requireNonNull(owner, "owner");
        if (maxFrames < 1) throw new IllegalArgumentException("logical stack quota must be positive");
        Carrier carrier = CARRIER.get();
        Segment segment = new Segment(carrier, carrier.current, owner, maxFrames, false);
        carrier.current = segment;
        return segment;
    }

    /**
     * Charge one guest frame to the active segment. Must be paired with
     * {@link #popFrame()} in a {@code finally}. Threads without an explicit
     * segment (embedding callers, the main program, Task steps) use an
     * implicit per-thread root segment with the default quota.
     */
    public static void pushFrame() {
        Carrier carrier = CARRIER.get();
        Segment segment = carrier.current;
        if (segment == null) {
            segment = new Segment(carrier, null, "carrier-root", SEGMENT_FRAMES, true);
            carrier.current = segment;
        }
        boolean unwinding = segment.poisoned;
        int segmentLimit = unwinding ? segment.maxFrames + UNWIND_RESERVE_FRAMES : segment.maxFrames;
        int carrierLimit = unwinding ? carrier.ceiling + UNWIND_RESERVE_FRAMES : carrier.ceiling;
        if (segment.depth >= segmentLimit || carrier.depth >= carrierLimit) {
            throw segment.overflow(carrier.depth >= carrierLimit);
        }
        segment.depth++;
        carrier.depth++;
        if (segment.depth > segment.peak) segment.peak = segment.depth;
    }

    /**
     * Heap-held guest frames (spec ch. 25, ch. 27 rule 8). A call to a
     * potentially suspending callee, recursive call cycles included, runs as
     * a source continuation instead of a host frame. Its frame is still part
     * of the caller's logical stack, so it is charged here: the callee's turn
     * starts from the depth it was launched at, and a launch past the heap
     * frame quota fails with {@link LogicalStackOverflowException} exactly like
     * a host-frame overflow. Without this, unbounded recursion on the
     * continuation path grows the heap until the process runs out of memory.
     *
     * <p>Heap frames cost no host stack, so their quota is larger than the
     * host-frame segment quota: {@link #heapFrames()}, by default 16 times
     * {@link #segmentFrames()} ({@code ores.stack.heapFrames}). A call in tail
     * position ({@code return f(...)} with no pending defer) inherits its
     * caller's depth.</p>
     */
    public static final class HeapTurn {
        private final int depth;
        private final int maxFrames;
        private final int syncBase;
        private final HeapTurn previous;
        private boolean tailPending;

        private HeapTurn(int depth, int maxFrames, int syncBase, HeapTurn previous) {
            this.depth = depth;
            this.maxFrames = maxFrames;
            this.syncBase = syncBase;
            this.previous = previous;
        }
    }

    private static final int HEAP_FRAMES =
            boundedProperty("ores.stack.heapFrames", SEGMENT_FRAMES * 16, 16, 1 << 24);
    private static final ThreadLocal<HeapTurn> HEAP_TURN = new ThreadLocal<>();
    private static final ThreadLocal<boolean[]> ROOT_TAIL = ThreadLocal.withInitial(() -> new boolean[1]);

    /** Heap-held frames one logical stack may hold. */
    public static int heapFrames() { return HEAP_FRAMES; }

    /** The next {@link #chargeHeapCall()} on this thread is a tail call. */
    public static void markTailCall() {
        HeapTurn turn = HEAP_TURN.get();
        if (turn != null) turn.tailPending = true;
        else ROOT_TAIL.get()[0] = true;
    }

    public static void clearTailCall() {
        HeapTurn turn = HEAP_TURN.get();
        if (turn != null) turn.tailPending = false;
        else ROOT_TAIL.get()[0] = false;
    }

    /**
     * Charge the heap-held frame of a source call being launched now and
     * return its logical depth: every frame of the chain, this one included.
     * The callee's own synchronous invocation frame is already on the carrier.
     */
    public static int chargeHeapCall() {
        Carrier carrier = CARRIER.get();
        HeapTurn turn = HEAP_TURN.get();
        int synchronous = Math.max(carrier.depth - (turn == null ? 0 : turn.syncBase), 1);
        int base = turn == null ? 0 : turn.depth;
        boolean tail;
        if (turn != null) {
            tail = turn.tailPending;
            turn.tailPending = false;
        } else {
            boolean[] rootTail = ROOT_TAIL.get();
            tail = rootTail[0];
            rootTail[0] = false;
        }
        int maxFrames = heapCallQuota();
        int depth = Math.max(base + synchronous - (tail ? 1 : 0), 1);
        if (depth > maxFrames) {
            throw new LogicalStackOverflowException("source-call-chain", depth - 1, maxFrames, false);
        }
        return depth;
    }

    /**
     * Run one turn of a heap-held call frame at {@code depth} (from
     * {@link #chargeHeapCall()}, or 0 for a new Task's stack). Restore with
     * {@link #exitHeapTurn} in a {@code finally} on the same thread.
     */
    public static HeapTurn enterHeapTurn(int depth, int maxFrames) {
        if (depth < 0 || maxFrames < 1) throw new IllegalArgumentException("heap frame depth must be non-negative and the quota positive");
        HeapTurn previous = HEAP_TURN.get();
        HeapTurn turn = new HeapTurn(depth, maxFrames, CARRIER.get().depth, previous);
        HEAP_TURN.set(turn);
        return turn;
    }

    public static void exitHeapTurn(HeapTurn turn) {
        Objects.requireNonNull(turn, "heap turn");
        if (HEAP_TURN.get() != turn) throw new IllegalStateException("heap turns nest strictly");
        if (turn.previous == null) HEAP_TURN.remove();
        else HEAP_TURN.set(turn.previous);
    }

    /** The heap-frame quota a call launched now inherits. */
    public static int heapCallQuota() {
        HeapTurn turn = HEAP_TURN.get();
        return turn != null ? turn.maxFrames : HEAP_FRAMES;
    }

    /** Release one guest frame charged by {@link #pushFrame()}. */
    public static void popFrame() {
        Carrier carrier = CARRIER.get();
        Segment segment = carrier.current;
        if (segment == null || segment.depth <= 0 || carrier.depth <= 0) {
            throw new IllegalStateException("logical stack frame underflow");
        }
        segment.depth--;
        carrier.depth--;
        if (segment.implicitRoot && segment.depth == 0) {
            // The implicit root carries no identity beyond its frames; drop it
            // so the next entry on this carrier starts from a clean segment.
            carrier.current = null;
        }
    }

    /** Active segment on this carrier, or {@code null} when none is bound. */
    public static Segment current() { return CARRIER.get().current; }

    /** Guest frames currently charged on this carrier across all segments. */
    public static int carrierDepth() { return CARRIER.get().depth; }

    static boolean is32BitJvm() {
        return "32".equals(System.getProperty("sun.arch.data.model"));
    }

    private static long defaultThreadStackBytes() {
        // HotSpot defaults (-Xss unset): 1 MiB on 64-bit Linux/macOS/Windows,
        // 320 KiB on 32-bit. Threads Oreslang creates declare their real size
        // via carrierBody(); this covers embedder threads only.
        return boundedLongProperty("ores.stack.defaultThreadBytes",
                is32BitJvm() ? 320L * 1024 : 1024L * 1024,
                128L * 1024, 1024L * 1024 * 1024);
    }

    private static int boundedProperty(String name, int fallback, int min, int max) {
        String raw = System.getProperty(name);
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be in [" + min + "," + max + "]");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static long boundedLongProperty(String name, long fallback, long min, long max) {
        String raw = System.getProperty(name);
        if (raw == null) return fallback;
        try {
            long value = Long.parseLong(raw.trim());
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be in [" + min + "," + max + "]");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be an integer", invalid);
        }
    }

    private static final class Carrier {
        final int ceiling;
        Segment current;
        int depth;

        Carrier(long stackBytes) {
            this.ceiling = carrierFramesFor(stackBytes);
        }
    }

    /** One logical stack segment: a receive invocation or the implicit carrier root. */
    public static final class Segment implements AutoCloseable {
        private final Carrier carrier;
        private final Segment previous;
        private final Object owner;
        private final int maxFrames;
        private final int baseCarrierDepth;
        private final boolean implicitRoot;
        private int depth;
        private int peak;
        private boolean poisoned;
        private boolean closed;
        private LogicalStackOverflowException overflow;

        private Segment(Carrier carrier, Segment previous, Object owner, int maxFrames,
                        boolean implicitRoot) {
            this.carrier = carrier;
            this.previous = previous;
            this.owner = owner;
            this.maxFrames = maxFrames;
            this.implicitRoot = implicitRoot;
            this.baseCarrierDepth = carrier.depth;
        }

        private LogicalStackOverflowException overflow(boolean carrierCeiling) {
            LogicalStackOverflowException failure =
                    new LogicalStackOverflowException(String.valueOf(owner), depth, maxFrames, carrierCeiling);
            if (!poisoned) {
                poisoned = true;
                overflow = failure;
            } else if (overflow != null) {
                // Cleanup exhausted the unwind reserve. Keep the original
                // overflow authoritative and record the cleanup failure on it.
                overflow.addSuppressed(failure);
            }
            return failure;
        }

        public Object owner() { return owner; }
        public int maxFrames() { return maxFrames; }
        public int depth() { return depth; }
        public int peakDepth() { return peak; }
        public boolean poisoned() { return poisoned; }

        /** The first overflow raised on this segment, if any. */
        public LogicalStackOverflowException overflowFailure() { return overflow; }

        /**
         * Unbind this segment from its carrier. Fails closed when frames are
         * still charged (an unbalanced push/pop is a runtime bug) or when the
         * segment is not the innermost one.
         */
        @Override
        public void close() {
            if (closed) return;
            // Validate before marking closed: a rejected close must leave the
            // segment closable by its rightful owner, not stranded on the carrier.
            if (carrier != CARRIER.get()) {
                throw new IllegalStateException("logical stack segment closed on a foreign carrier");
            }
            if (carrier.current != this) {
                throw new IllegalStateException("logical stack segments must close innermost-first");
            }
            closed = true;
            carrier.current = previous;
            if (depth != 0 || carrier.depth != baseCarrierDepth) {
                // Restore the carrier so the next turn is not charged for frames
                // this segment leaked, then report the invariant failure.
                carrier.depth = baseCarrierDepth;
                throw new IllegalStateException(
                        "logical stack segment closed with " + depth + " live guest frames");
            }
        }
    }
}
