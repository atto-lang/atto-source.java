package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Trusted, owner-thread-confined native allocation scope for the JVM backend.
 *
 * <p>Uses real ActorLinearArena.allocateOwned/dropOwned (generation-fenced
 * native byte storage), but hides all primitive handles from guests. Only the
 * compiler/runtime may construct this adapter and supply destructor hooks.
 * Atto's ReservationLedger and DropStack remain source-level policy models:
 * this class does NOT yet lower guest ownership expressions automatically.
 *
 * <p>Destructors run in reverse acquisition order exactly once on normal
 * exit or Java unwinding. A throwing destructor poisons its allocation:
 * memory remains live/quarantined and is NOT silently recycled or dropped
 * as though destruction succeeded. Arena shutdown may still scrub all bytes.
 * Scope closure is rejected while any borrow is live.
 */
final class TrustedAllocationScopeV1 implements AutoCloseable {
    private final ActorLinearArena arena;
    private final Thread ownerThread;
    private final ArrayList<Owner> owners = new ArrayList<>();
    private boolean closed;
    // Prevent destructor callbacks from re-entering allocation, borrowing or
    // another destructor while the enclosing owner is in a partial drop state.
    private boolean dispatchingDestructor;

    private TrustedAllocationScopeV1(ActorLinearArena arena) {
        this.arena = Objects.requireNonNull(arena, "native actor arena");
        this.ownerThread = Thread.currentThread();
        if (arena.closed()) throw new IllegalStateException("actor arena already closed");
    }

    /** Runtime/compiled-guest-lowering entrypoint, never a guest value API. */
    static TrustedAllocationScopeV1 trusted_open(ActorLinearArena arena) {
        return new TrustedAllocationScopeV1(arena);
    }

    private void requireThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("native allocation scope belongs to another carrier");
        }
    }

    private void requireOpen() {
        requireThread();
        if (dispatchingDestructor) {
            throw new IllegalStateException("native allocation scope is running a destructor");
        }
        if (closed || arena.closed()) {
            throw new IllegalStateException("native allocation scope is closed");
        }
    }

    /** Allocate eight native bytes and retain a private, unforgeable owner. */
    Owner allocate_word(Runnable destructor) {
        requireOpen();
        Objects.requireNonNull(destructor, "trusted destructor hook");
        if (owners.size() == Integer.MAX_VALUE) {
            throw new IllegalStateException("allocation owner table exhausted");
        }
        // Preflight Java list growth before the native transaction.
        owners.ensureCapacity(owners.size() + 1);
        long handle = arena.allocateOwned(Long.BYTES, Long.BYTES);
        if (handle == ActorLinearArena.INVALID_HANDLE) {
            throw new IllegalStateException("native arena admission failed");
        }
        try {
            Owner owner = new Owner(this, handle, destructor);
            owners.add(owner);
            return owner;
        } catch (RuntimeException | Error failure) {
            // If a JVM allocation fails during owner publication, no native
            // handle may escape without an owned cleanup receipt.
            try {
                if (!arena.dropOwned(handle)) {
                    failure.addSuppressed(new IllegalStateException(
                            "native owner rollback failed"));
                }
            } catch (RuntimeException | Error rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    void drop(Owner owner) {
        requireOpen();
        checkOwner(owner);
        dropInternal(owner);
    }

    private void checkOwner(Owner owner) {
        if (owner == null || owner.scope != this) {
            throw new IllegalStateException("foreign or fabricated native owner");
        }
    }

    private void dropInternal(Owner owner) {
        checkOwner(owner);
        if (owner.state != State.LIVE) {
            throw new IllegalStateException("native owner is retired or poisoned");
        }
        if (owner.readers != 0 || owner.writer != null) {
            throw new IllegalStateException("outstanding borrow blocks destructor");
        }
        if (dispatchingDestructor) {
            throw new IllegalStateException("recursive native destructor admission");
        }
        owner.state = State.DROPPING;
        dispatchingDestructor = true;
        try {
            owner.destructor.run();
            // A failed arena call (including an exception) must quarantine the
            // owner instead of leaving it incorrectly marked DROPPING.
            if (!arena.dropOwned(owner.handle)) {
                throw new IllegalStateException("native handle retirement failed");
            }
            owner.state = State.RETIRED;
        } catch (RuntimeException | Error failure) {
            owner.state = State.POISONED;
            throw failure;
        } finally {
            dispatchingDestructor = false;
        }
    }

    @Override
    public void close() {
        requireOpen();
        // No partial cleanup while a loan exists. Compiler lowering must
        // wait for async/FFI quiescence BEFORE this scope is closed.
        for (Owner owner : owners) {
            if (owner.readers != 0 || owner.writer != null) {
                throw new IllegalStateException("scope contains live native borrows");
            }
            if (owner.state == State.DROPPING) {
                throw new IllegalStateException("scope destructor re-entry");
            }
        }
        closed = true;
        Throwable failure = null;
        for (int i = owners.size() - 1; i >= 0; i--) {
            Owner owner = owners.get(i);
            if (owner.state == State.RETIRED) continue;
            try {
                dropInternal(owner);
            } catch (RuntimeException | Error ex) {
                if (failure == null) failure = ex;
                else if (failure != ex) failure.addSuppressed(ex);
            }
        }
        if (failure instanceof RuntimeException ex) throw ex;
        if (failure instanceof Error err) throw err;
    }

    private enum State { LIVE, DROPPING, RETIRED, POISONED }

    /** Opaque per-allocation identity; raw arena handles never escape. */
    static final class Owner {
        private final TrustedAllocationScopeV1 scope;
        private final long handle;
        private final Runnable destructor;
        private State state = State.LIVE;
        private int readers;
        private Loan writer;

        private Owner(TrustedAllocationScopeV1 scope, long handle, Runnable destructor) {
            this.scope = scope;
            this.handle = handle;
            this.destructor = destructor;
        }

        Loan borrow_read() {
            scope.requireOpen();
            if (state != State.LIVE || writer != null || readers == Integer.MAX_VALUE) {
                throw new IllegalStateException("shared native borrow denied");
            }
            Loan loan = new Loan(this, false);
            readers++;
            return loan;
        }

        Loan borrow_write() {
            scope.requireOpen();
            if (state != State.LIVE || readers != 0 || writer != null) {
                throw new IllegalStateException("exclusive native borrow denied");
            }
            Loan loan = new Loan(this, true);
            writer = loan;
            return loan;
        }

        boolean is_retired() {
            scope.requireThread();
            return state == State.RETIRED;
        }
    }

    /** Borrow guard with no raw addresses; user code cannot manufacture one. */
    static final class Loan implements AutoCloseable {
        private final Owner owner;
        private final boolean mutable;
        private boolean released;

        private Loan(Owner owner, boolean mutable) {
            this.owner = owner;
            this.mutable = mutable;
        }

        private void requireLive() {
            owner.scope.requireOpen();
            if (released || owner.state != State.LIVE) {
                throw new IllegalStateException("stale native borrow");
            }
            if (mutable ? owner.writer != this : owner.writer != null || owner.readers == 0) {
                throw new IllegalStateException("revoked native borrow");
            }
        }

        long read_word() {
            requireLive();
            return owner.scope.arena.getLong(owner.handle, 0);
        }

        void write_word(long value) {
            requireLive();
            if (!mutable) throw new IllegalStateException("read-only native borrow");
            if (!owner.scope.arena.putLong(owner.handle, 0, value)) {
                throw new IllegalStateException("native write rejected");
            }
        }

        @Override
        public void close() {
            owner.scope.requireThread();
            if (released) throw new IllegalStateException("borrow released twice");
            if (mutable) {
                if (owner.writer != this) throw new IllegalStateException("foreign write guard");
                owner.writer = null;
            } else {
                if (owner.readers == 0) throw new IllegalStateException("read borrow underflow");
                owner.readers--;
            }
            released = true;
        }
    }
}
