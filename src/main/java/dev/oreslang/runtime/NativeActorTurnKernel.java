package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Experimental, trusted-JVM-only JNI owner of one native actor-turn VM.
 * Not exported to Oreslang guest code, not installed in the production scheduler.
 * SourceTask settlement must be called only after a real scheduler terminal
 * and physical-quiescence callback, NEVER after Future notification alone.
 */
final class NativeActorTurnKernel implements AutoCloseable {
    private static final String OPT_IN = "ores.native.actor.turn.experimental";
    private static boolean loaded;

    private long vm;
    private long actor;
    private boolean stopped;
    /** One native actor/VM has only one physical scheduler lane and ID space. */
    private boolean schedulerBound;

    /**
     * A SourceTask ID is meaningful within this kernel's single actor, not
     * within one arbitrary Java scheduler. Two Java schedulers each start at
     * ID 1 and own independent physical carriers; sharing one kernel would
     * collide IDs and bypass Java-side single-flight admission. Bind once.
     */
    synchronized void claimSchedulerWitness() {
        ensureOpen();
        if (stopped || schedulerBound) {
            throw new IllegalStateException(
                    "native actor-turn kernel is stopped or already bound to a scheduler");
        }
        schedulerBound = true;
    }

    static synchronized NativeActorTurnKernel open(long maxObjects) {
        if (!Boolean.getBoolean(OPT_IN)) {
            throw new IllegalStateException("experimental native actor-turn JNI is disabled");
        }
        if (!loaded) {
            System.loadLibrary("oresactorturnjni");
            loaded = true;
        }
        return new NativeActorTurnKernel(maxObjects);
    }

    private NativeActorTurnKernel(long maxObjects) {
        if (maxObjects <= 0) throw new IllegalArgumentException("maxObjects");
        long newVm = nVmNew(maxObjects);
        if (newVm == 0) throw new IllegalStateException("native actor VM allocation refused");
        try {
            long newActor = nRegisterActor(newVm, 1, 1, 1);
            if (newActor == 0) throw new IllegalStateException("native actor registration refused");
            vm = newVm;
            actor = newActor;
        } catch (RuntimeException | Error failure) {
            nVmDestroy(newVm);
            throw failure;
        }
    }

    static final class SourceTask {
        private final NativeActorTurnKernel owner;
        private final long handle;
        private boolean retired;
        private Thread activeCarrier;
        private long activeLease;
        private boolean settled;

        private SourceTask(NativeActorTurnKernel owner, long handle) {
            this.owner = owner;
            this.handle = handle;
        }
    }

    /** Terminal cleanup grants are disjoint from ordinary source execution. */
    static final class CleanupTicket {
        private final NativeActorTurnKernel owner;
        private final SourceTask source;
        private final long handle;
        private boolean claimed;
        private boolean closed;

        private CleanupTicket(NativeActorTurnKernel owner, SourceTask source, long handle) {
            this.owner = owner;
            this.source = source;
            this.handle = handle;
        }
    }

    static final class CleanupTurn {
        private final NativeActorTurnKernel owner;
        private final CleanupTicket ticket;
        private final long handle;
        private final Thread carrier;
        private boolean released;
        private boolean completed;

        private CleanupTurn(NativeActorTurnKernel owner, CleanupTicket ticket, long handle) {
            this.owner = owner;
            this.ticket = ticket;
            this.handle = handle;
            this.carrier = Thread.currentThread();
        }
    }

    synchronized CleanupTicket issueCleanup(SourceTask task) {
        checkLiveTask(task);
        if (stopped) throw new IllegalStateException("actor already stopped");
        return new CleanupTicket(this, task,
                checkedPointer(nIssueCleanup(vm, task.handle)));
    }

    synchronized CleanupTurn claimCleanup(CleanupTicket ticket) {
        checkTicket(ticket);
        if (Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("native cleanup requires a platform carrier thread");
        }
        if (ticket.claimed || ticket.closed) throw new IllegalStateException("cleanup ticket closed");
        long nativeLease = checkedPointer(nClaimCleanup(vm, ticket.handle));
        ticket.claimed = true;
        return new CleanupTurn(this, ticket, nativeLease);
    }

    synchronized void terminalInjected(CleanupTurn turn) {
        checkCleanupTurn(turn);
        check(nTerminalInjected(vm, turn.handle, turn.ticket.source.handle));
    }

    /** Point-in-time check only, NOT authority for a later guest effect. */
    synchronized void checkCleanupEffects(CleanupTurn turn) {
        checkCleanupTurn(turn);
        check(nCheckCleanupEffects(vm, turn.handle));
    }

    synchronized void completeCleanup(CleanupTurn turn) {
        checkCleanupTurn(turn);
        check(nCompleteCleanup(vm, turn.handle, turn.ticket.source.handle));
        turn.completed = true;
        turn.ticket.closed = true;
    }

    synchronized void releaseCleanup(CleanupTurn turn) {
        checkCleanupTurn(turn);
        check(nRelease(vm, turn.handle));
        turn.released = true;
    }

    synchronized void cancelCleanup(CleanupTicket ticket) {
        checkTicket(ticket);
        if (ticket.claimed || ticket.closed) throw new IllegalStateException("cleanup already claimed");
        check(nCancelCleanup(vm, ticket.handle));
        ticket.closed = true;
    }

    private void checkTicket(CleanupTicket ticket) {
        ensureOpen();
        if (Objects.requireNonNull(ticket, "ticket").owner != this || ticket.closed) {
            throw new IllegalStateException("foreign or closed cleanup ticket");
        }
    }

    private void checkCleanupTurn(CleanupTurn turn) {
        ensureOpen();
        if (Objects.requireNonNull(turn, "turn").owner != this || turn.released
                || turn.carrier != Thread.currentThread()) {
            throw new IllegalStateException("foreign, released or wrong-carrier cleanup lease");
        }
    }

    synchronized SourceTask register(long taskId, long generation) {
        ensureOpen();
        if (stopped) throw new IllegalStateException("actor already stopped");
        if (taskId <= 0 || generation <= 0) throw new IllegalArgumentException("task identity");
        return new SourceTask(this, checkedPointer(nRegisterSource(vm, actor, taskId, generation)));
    }

    synchronized void enter(SourceTask task) {
        checkLiveTask(task);
        // JNI TLS identity is the OS carrier, not the virtual Java thread.
        // A virtual thread can migrate between enter() and exit() JNI calls.
        if (Thread.currentThread().isVirtual()) {
            throw new IllegalStateException("native source lease requires a platform carrier thread");
        }
        if (task.activeLease != 0) throw new IllegalStateException("task already executing");
        if (stopped) throw new IllegalStateException("actor stopped");
        long lease = checkedPointer(nClaimSource(vm, task.handle));
        task.activeCarrier = Thread.currentThread();
        task.activeLease = lease;
    }

    synchronized void exit(SourceTask task) {
        checkLiveTask(task);
        if (task.activeLease == 0 || task.activeCarrier != Thread.currentThread()) {
            throw new IllegalStateException("source lease belongs to another carrier or is absent");
        }
        check(nRelease(vm, task.activeLease));
        task.activeLease = 0;
        task.activeCarrier = null;
    }

    /** Trusted scheduler TERMINAL + physical-quiescence callback only. */
    synchronized void settled(SourceTask task) {
        checkLiveTask(task);
        if (task.settled) throw new IllegalStateException("task settled twice");
        check(nSourceSettled(vm, task.handle));
        task.settled = true;
    }

    synchronized void retire(SourceTask task) {
        checkLiveTask(task);
        if (!task.settled) throw new IllegalStateException("task has not settled");
        check(nRetireSource(vm, task.handle));
        task.retired = true;
    }

    /**
     * Retry-safe trusted scheduler terminal barrier. Native settlement and
     * retirement are separate operations: a successful first settlement can
     * precede a failed retirement (for example, a still-active actor lease).
     * A retry must never reissue the one-shot settlement witness.
     */
    synchronized void settleAndRetire(SourceTask task) {
        checkLiveTask(task);
        if (!task.settled) settled(task);
        retire(task);
    }

    synchronized void stop() {
        ensureOpen();
        if (!stopped) {
            check(nStop(vm, actor));
            stopped = true;
        }
    }

    @Override
    public synchronized void close() {
        if (vm == 0) return;
        if (!stopped) stop();
        // A live task or carrier leaves the native VM intact; caller must retry.
        check(nRetireActor(vm, actor));
        check(nVmDestroy(vm));
        vm = actor = 0;
    }

    private void ensureOpen() {
        if (vm == 0) throw new IllegalStateException("native actor VM closed");
    }

    private void checkLiveTask(SourceTask task) {
        ensureOpen();
        if (Objects.requireNonNull(task, "task").owner != this || task.retired) {
            throw new IllegalStateException("foreign or retired SourceTask capability");
        }
    }

    private static long checkedPointer(long handle) {
        if (handle == 0) throw new IllegalStateException("native actor capability unavailable");
        return handle;
    }

    /** A native capability/barrier failure, not arbitrary guest Java failure. */
    static final class NativeStatusFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        NativeStatusFailure(int status) {
            super("native actor-turn status=" + status);
        }
    }

    private static void check(int code) {
        if (code != 0) throw new NativeStatusFailure(code);
    }

    private static native long nIssueCleanup(long vm, long source);
    private static native long nClaimCleanup(long vm, long ticket);
    private static native int nTerminalInjected(long vm, long lease, long source);
    private static native int nCheckCleanupEffects(long vm, long lease);
    private static native int nCompleteCleanup(long vm, long lease, long source);
    private static native int nCancelCleanup(long vm, long ticket);
    private static native long nVmNew(long capacity);
    private static native int nVmDestroy(long vm);
    private static native long nRegisterActor(long vm, long hi, long lo, long incarnation);
    private static native long nRegisterSource(long vm, long actor, long id, long generation);
    private static native long nClaimSource(long vm, long source);
    private static native int nRelease(long vm, long lease);
    private static native int nSourceSettled(long vm, long source);
    private static native int nRetireSource(long vm, long source);
    private static native int nStop(long vm, long actor);
    private static native int nRetireActor(long vm, long actor);
}
