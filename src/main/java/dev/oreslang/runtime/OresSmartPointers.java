package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime substrates for ownership-aware smart pointers.
 *
 * <p>The compiler remains authoritative for moves, borrows, Send/Sync/Share
 * and deterministic drop insertion. These host objects provide fail-closed
 * runtime behavior for the interpreter and a lowering contract for native
 * backends; they are not a substitute for static ownership checking.</p>
 */
public final class OresSmartPointers {
    private OresSmartPointers() { }

    private static final long MAX_REFERENCE_COUNT = Long.MAX_VALUE - 1;

    public static <T> Box<T> box(T value) {
        return new Box<>(value);
    }

    public static <T> Arc<T> arc(T value) {
        return new Arc<>(new ArcControl<>(value), false);
    }

    public static <T> Rc<T> rc(T value) {
        return new Rc<>(new RcControl<>(value, Thread.currentThread()), false);
    }

    public static <T> RefCell<T> refCell(T value) {
        return new RefCell<>(value);
    }

    /** Unique heap-style ownership. */
    public static final class Box<T> implements AutoCloseable {
        private T value;
        private boolean live = true;

        private Box(T value) {
            this.value = Objects.requireNonNull(
                    value, "Box<T> cannot contain host null; use Option<T>");
        }

        public synchronized T value() {
            requireLive();
            return value;
        }

        /**
         * Consumes the runtime box and returns its payload exactly once.
         *
         * <p>Source lowering must model this as a consuming operation.</p>
         */
        public synchronized T take() {
            requireLive();
            T result = value;
            value = null;
            live = false;
            return result;
        }

        public synchronized boolean live() {
            return live;
        }

        @Override
        public synchronized void close() {
            if (!live) return;
            value = null;
            live = false;
        }

        private void requireLive() {
            if (!live) {
                throw new IllegalStateException("Box<T> has been consumed or dropped");
            }
        }
    }

    private static final class ArcControl<T> {
        private final AtomicLong strong = new AtomicLong(1);
        /**
         * Includes one implicit weak reference while strong > 0, mirroring
         * the standard Arc control-block lifetime rule.
         */
        private final AtomicLong weak = new AtomicLong(1);
        private final AtomicReference<T> value;

        private ArcControl(T value) {
            this.value = new AtomicReference<>(Objects.requireNonNull(
                    value, "Arc<T> cannot contain host null; use Option<T>"));
        }
    }

    /**
     * Atomic shared ownership. Individual Arc handles are linear resources.
     * Handle methods are serialized so a concurrent close cannot invalidate
     * an in-flight retain, downgrade or payload access on the same handle.
     * Source lowering still owns long-lived loans and drop insertion.
     */
    public static final class Arc<T> implements AutoCloseable {
        private final ArcControl<T> control;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Arc(ArcControl<T> control, boolean alreadyRetained) {
            this.control = Objects.requireNonNull(control, "control");
            if (!alreadyRetained) {
                // Initial construction owns the control block's first strong ref.
                return;
            }
        }

        public synchronized T value() {
            requireLiveHandle();
            T value = control.value.get();
            if (value == null || control.strong.get() <= 0) {
                throw new IllegalStateException("Arc<T> payload has been dropped");
            }
            return value;
        }

        /** Explicit Arc clone. */
        public synchronized Arc<T> retain() {
            requireLiveHandle();
            // Allocate the host handle before reserving ownership, so an OOME
            // cannot strand a strong count without a corresponding Arc handle.
            Arc<T> retained = new Arc<>(control, true);
            incrementAtomic(control.strong, "Arc<T> strong count");
            return retained;
        }

        public synchronized Weak<T> downgrade() {
            requireLiveHandle();
            // A failed allocation must never strand a weak count.
            Weak<T> weak = new Weak<>(control);
            incrementAtomic(control.weak, "Arc<T> weak count");
            return weak;
        }

        public synchronized long strongCount() {
            requireLiveHandle();
            return control.strong.get();
        }

        /** Number of explicit weak handles; excludes the implicit weak ref. */
        public synchronized long weakCount() {
            requireLiveHandle();
            long strong = control.strong.get();
            long weak = control.weak.get();
            long explicit = weak - (strong > 0 ? 1 : 0);
            if (explicit < 0) {
                throw new IllegalStateException("Arc<T> weak count invariant violated");
            }
            return explicit;
        }

        public boolean closed() {
            return closed.get();
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) return;
            long remaining = decrementAtomic(control.strong, "Arc<T> strong count");
            if (remaining == 0) {
                control.value.set(null);
                decrementAtomic(control.weak, "Arc<T> implicit weak count");
            }
        }

        private void requireLiveHandle() {
            if (closed.get()) {
                throw new IllegalStateException("Arc<T> handle has been dropped");
            }
        }
    }

    /**
     * Non-owning atomic Arc edge. Operations on one weak handle are
     * serialized with close; control-block strong upgrades remain CAS-based.
     */
    public static final class Weak<T> implements AutoCloseable {
        private final ArcControl<T> control;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Weak(ArcControl<T> control) {
            this.control = Objects.requireNonNull(control, "control");
        }

        public synchronized Optional<Arc<T>> upgrade() {
            requireLiveHandle();
            long strong = control.strong.get();
            if (strong == 0) return Optional.empty();
            if (strong >= MAX_REFERENCE_COUNT) {
                throw new IllegalStateException("Arc<T> strong count overflow");
            }
            // Construct both Arc and Optional before incrementing strong:
            // failed host allocation cannot leak a strong count. Reuse this
            // unpublished candidate across retries; CAS still decides whether
            // an upgrade owns a reference, and 0 can never be resurrected.
            Optional<Arc<T>> upgraded = Optional.of(new Arc<>(control, true));
            while (true) {
                if (strong == 0) return Optional.empty();
                if (strong >= MAX_REFERENCE_COUNT) {
                    throw new IllegalStateException("Arc<T> strong count overflow");
                }
                if (control.strong.compareAndSet(strong, strong + 1)) {
                    return upgraded;
                }
                strong = control.strong.get();
            }
        }

        public synchronized long strongCount() {
            requireLiveHandle();
            return control.strong.get();
        }

        public synchronized long weakCount() {
            requireLiveHandle();
            long strong = control.strong.get();
            long weak = control.weak.get();
            return weak - (strong > 0 ? 1 : 0);
        }

        public boolean closed() {
            return closed.get();
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) return;
            decrementAtomic(control.weak, "Arc<T> weak count");
        }

        private void requireLiveHandle() {
            if (closed.get()) {
                throw new IllegalStateException("Weak<T> handle has been dropped");
            }
        }
    }

    private static final class RcControl<T> {
        private long strong = 1;
        /** Includes the implicit weak ref while strong > 0. */
        private long weak = 1;
        private T value;
        private final Thread owner;

        private RcControl(T value, Thread owner) {
            this.value = Objects.requireNonNull(
                    value, "Rc<T> cannot contain host null; use Option<T>");
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException(
                        "Rc<T> is local and cannot cross a carrier/thread boundary");
            }
        }
    }

    /**
     * Non-atomic shared ownership.
     *
     * <p>The bootstrap runtime binds Rc to its creating carrier. Future
     * scheduler-local tasks may replace this with a stable local-task domain,
     * but widening the domain before pinning support would be unsound.</p>
     */
    public static final class Rc<T> implements AutoCloseable {
        private final RcControl<T> control;
        private boolean closed;

        private Rc(RcControl<T> control, boolean alreadyRetained) {
            this.control = Objects.requireNonNull(control, "control");
            if (alreadyRetained) {
                // Counter was incremented by the caller.
            }
        }

        public T value() {
            requireLiveHandle();
            if (control.value == null || control.strong <= 0) {
                throw new IllegalStateException("Rc<T> payload has been dropped");
            }
            return control.value;
        }

        public Rc<T> retain() {
            requireLiveHandle();
            // Like Arc, construct before changing the count: a failed host
            // allocation must not strand an unowned strong reference.
            Rc<T> retained = new Rc<>(control, true);
            incrementLocalStrong(control);
            return retained;
        }

        public RcWeak<T> downgrade() {
            requireLiveHandle();
            RcWeak<T> weak = new RcWeak<>(control);
            incrementLocalWeak(control);
            return weak;
        }

        public long strongCount() {
            requireLiveHandle();
            return control.strong;
        }

        public long weakCount() {
            requireLiveHandle();
            return control.weak - (control.strong > 0 ? 1 : 0);
        }

        public boolean closed() {
            control.requireOwner();
            return closed;
        }

        @Override
        public void close() {
            control.requireOwner();
            if (closed) return;
            closed = true;
            if (control.strong <= 0) {
                throw new IllegalStateException("Rc<T> strong count underflow");
            }
            control.strong--;
            if (control.strong == 0) {
                control.value = null;
                if (control.weak <= 0) {
                    throw new IllegalStateException("Rc<T> weak count invariant violated");
                }
                control.weak--;
            }
        }

        private void requireLiveHandle() {
            control.requireOwner();
            if (closed) {
                throw new IllegalStateException("Rc<T> handle has been dropped");
            }
        }
    }

    /** Non-owning Rc edge, confined to the same local carrier. */
    public static final class RcWeak<T> implements AutoCloseable {
        private final RcControl<T> control;
        private boolean closed;

        private RcWeak(RcControl<T> control) {
            this.control = Objects.requireNonNull(control, "control");
        }

        public Optional<Rc<T>> upgrade() {
            requireLiveHandle();
            if (control.strong == 0) return Optional.empty();
            // Both objects must exist before acquiring another strong ref.
            Optional<Rc<T>> upgraded = Optional.of(new Rc<>(control, true));
            incrementLocalStrong(control);
            return upgraded;
        }

        public long strongCount() {
            requireLiveHandle();
            return control.strong;
        }

        public long weakCount() {
            requireLiveHandle();
            return control.weak - (control.strong > 0 ? 1 : 0);
        }

        @Override
        public void close() {
            control.requireOwner();
            if (closed) return;
            closed = true;
            if (control.weak <= 0) {
                throw new IllegalStateException("Rc<T> weak count underflow");
            }
            control.weak--;
        }

        private void requireLiveHandle() {
            control.requireOwner();
            if (closed) {
                throw new IllegalStateException("RcWeak<T> handle has been dropped");
            }
        }
    }

    /**
     * Local interior-mutability cell with runtime borrow-state checking.
     *
     * <p>RefCell itself can be moved when T is Send, but its outstanding
     * guards are thread-local and must never cross suspension/task boundaries.</p>
     */
    public static final class RefCell<T> {
        private T value;
        private int readers;
        private boolean writer;

        private RefCell(T value) {
            this.value = Objects.requireNonNull(
                    value, "RefCell<T> cannot contain host null; use Option<T>");
        }

        public synchronized Ref<T> borrow() {
            if (writer) {
                throw new IllegalStateException(
                        "RefCell<T> is already mutably borrowed");
            }
            if (readers == Integer.MAX_VALUE) {
                throw new IllegalStateException("RefCell<T> shared borrow count overflow");
            }
            // Allocate the guard before admitting its shared loan; a failed
            // allocation must not leave the cell permanently borrowed.
            Ref<T> guard = new Ref<>(this, Thread.currentThread());
            readers++;
            return guard;
        }

        public synchronized RefMut<T> borrowMut() {
            if (writer || readers != 0) {
                throw new IllegalStateException(
                        "RefCell<T> is already borrowed");
            }
            // The exclusive flag may only be published after the guard
            // exists; allocation failure must leave borrowMut retryable.
            RefMut<T> guard = new RefMut<>(this, Thread.currentThread());
            writer = true;
            return guard;
        }

        private synchronized T readForGuard(Thread owner, boolean mutable) {
            requireGuardThread(owner);
            if (mutable) {
                if (!writer) {
                    throw new IllegalStateException(
                            "RefCell<T> mutable guard is no longer active");
                }
            } else if (readers <= 0) {
                throw new IllegalStateException(
                        "RefCell<T> shared guard is no longer active");
            }
            return value;
        }

        private synchronized void writeForGuard(Thread owner, T replacement) {
            requireGuardThread(owner);
            if (!writer) {
                throw new IllegalStateException(
                        "RefCell<T> mutable guard is no longer active");
            }
            value = Objects.requireNonNull(
                    replacement,
                    "RefCell<T> cannot contain host null; use Option<T>");
        }

        private synchronized void releaseShared(Thread owner) {
            requireGuardThread(owner);
            if (readers <= 0) {
                throw new IllegalStateException(
                        "RefCell<T> shared borrow count underflow");
            }
            readers--;
        }

        private synchronized void releaseMutable(Thread owner) {
            requireGuardThread(owner);
            if (!writer) {
                throw new IllegalStateException(
                        "RefCell<T> mutable borrow is already released");
            }
            writer = false;
        }

        private static void requireGuardThread(Thread owner) {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException(
                        "RefCell<T> borrow guard cannot cross a carrier/thread boundary");
            }
        }
    }

    public static final class Ref<T> implements AutoCloseable {
        private final RefCell<T> cell;
        private final Thread owner;
        private boolean closed;

        private Ref(RefCell<T> cell, Thread owner) {
            this.cell = cell;
            this.owner = owner;
        }

        public T value() {
            requireLive();
            return cell.readForGuard(owner, false);
        }

        @Override
        public void close() {
            if (closed) return;
            cell.releaseShared(owner);
            closed = true;
        }

        private void requireLive() {
            if (closed) {
                throw new IllegalStateException("RefCell<T> shared guard has been released");
            }
        }
    }

    public static final class RefMut<T> implements AutoCloseable {
        private final RefCell<T> cell;
        private final Thread owner;
        private boolean closed;

        private RefMut(RefCell<T> cell, Thread owner) {
            this.cell = cell;
            this.owner = owner;
        }

        public T value() {
            requireLive();
            return cell.readForGuard(owner, true);
        }

        public void set(T replacement) {
            requireLive();
            cell.writeForGuard(owner, replacement);
        }

        @Override
        public void close() {
            if (closed) return;
            cell.releaseMutable(owner);
            closed = true;
        }

        private void requireLive() {
            if (closed) {
                throw new IllegalStateException("RefCell<T> mutable guard has been released");
            }
        }
    }

    private static void incrementAtomic(AtomicLong counter, String label) {
        while (true) {
            long current = counter.get();
            if (current <= 0) {
                throw new IllegalStateException(label + " is already zero");
            }
            if (current >= MAX_REFERENCE_COUNT) {
                throw new IllegalStateException(label + " overflow");
            }
            if (counter.compareAndSet(current, current + 1)) return;
        }
    }

    private static long decrementAtomic(AtomicLong counter, String label) {
        while (true) {
            long current = counter.get();
            if (current <= 0) {
                throw new IllegalStateException(label + " underflow");
            }
            long next = current - 1;
            if (counter.compareAndSet(current, next)) return next;
        }
    }

    private static <T> void incrementLocalStrong(RcControl<T> control) {
        control.requireOwner();
        if (control.strong <= 0) {
            throw new IllegalStateException("Rc<T> payload has been dropped");
        }
        if (control.strong >= MAX_REFERENCE_COUNT) {
            throw new IllegalStateException("Rc<T> strong count overflow");
        }
        control.strong++;
    }

    private static <T> void incrementLocalWeak(RcControl<T> control) {
        control.requireOwner();
        if (control.weak <= 0) {
            throw new IllegalStateException("Rc<T> control block has been dropped");
        }
        if (control.weak >= MAX_REFERENCE_COUNT) {
            throw new IllegalStateException("Rc<T> weak count overflow");
        }
        control.weak++;
    }
}
