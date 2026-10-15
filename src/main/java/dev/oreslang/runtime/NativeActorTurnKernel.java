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

    private static void check(int code) {
        if (code != 0) throw new IllegalStateException("native actor-turn status=" + code);
    }

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
