package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Host-only first slice of the ChildProcManager process backend.
 *
 * <p>Ownership is captured on the actor turn; authorization occurs before an
 * immutable launch plan leaves that turn. Blocking process creation happens
 * only on the caller-supplied host I/O executor; pipe readers use bounded
 * virtual threads. No guest actor API is registered by this class.</p>
 *
 * <p>Subprocesses are NOT an OS sandbox: an allowed executable may launch
 * descendants or race pathname replacement. A higher-level isolate manager
 * must enforce OS confinement if hostile subprocesses are supported.</p>
 */
final class ChildProcOwnedRuntime implements AutoCloseable {
    private final OresContext context;
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorId owner;
    private final Executor ioExecutor;
    private final int maxChildren;
    private final int maxOutputBytes;
    private final Object lock = new Object();
    private final Map<Long, Slot> slots = new HashMap<>();
    private long nextGeneration = 1;
    private boolean closed;

    private static final class Slot {
        final long generation;
        final CompletableFuture<Child> ready = new CompletableFuture<>();
        Process process;

        Slot(long generation) { this.generation = generation; }
    }

    /** Immutable, defensive-copy exit result; all three streams are complete. */
    record Exit(int code, byte[] stdout, byte[] stderr) {
        Exit {
            stdout = stdout.clone();
            stderr = stderr.clone();
        }
        @Override public byte[] stdout() { return stdout.clone(); }
        @Override public byte[] stderr() { return stderr.clone(); }
    }

    final class Child {
        private final long generation;
        private final long pid;
        private final CompletableFuture<Exit> done;
        private Child(long generation, Process process, CompletableFuture<Exit> done) {
            this.generation = generation;
            this.pid = process.pid();
            this.done = done;
        }
        long generation() { return generation; }
        long pid() { return pid; }
        CompletableFuture<Exit> waitAsync() { return done.minimalCompletionStage().toCompletableFuture(); }

        /** These operations require the owning actor turn, not a worker thread. */
        boolean terminate() { return signal(this, false); }
        boolean kill() { return signal(this, true); }
    }

    private ChildProcOwnedRuntime(OresContext context, ActorRuntime.ActorId owner,
                                  Executor ioExecutor, int maxChildren, int maxOutputBytes) {
        this.context = context;
        this.runtime = context.actors();
        this.owner = owner;
        this.ioExecutor = ioExecutor;
        this.maxChildren = maxChildren;
        this.maxOutputBytes = maxOutputBytes;
    }

    /** Call only on the initial owning actor turn. The executor must be host-owned. */
    static ChildProcOwnedRuntime forCurrentActor(OresContext context, Executor ioExecutor,
                                                  int maxChildren, int maxOutputBytes) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(ioExecutor, "ioExecutor");
        if (maxChildren < 1 || maxChildren > 64) {
            throw new IllegalArgumentException("maxChildren must be in 1..64");
        }
        if (maxOutputBytes < 1 || maxOutputBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("maxOutputBytes must be in 1..16 MiB");
        }
        if (!ActorRuntime.inActorExecution()
                || ActorRuntime.currentActorRuntime() != context.actors()
                || ActorRuntime.currentActorId().isEmpty()) {
            throw new SecurityException("creating a process registry requires its owning actor turn");
        }
        if (ActorRuntime.currentActorPolicy() == null
                || ActorRuntime.currentActorPolicy().adversarial()
                || ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.UNTRUSTED) {
            throw new SecurityException("untrusted actors cannot create child process registries");
        }
        return new ChildProcOwnedRuntime(context, ActorRuntime.currentActorId().orElseThrow(),
                ioExecutor, maxChildren, maxOutputBytes);
    }

    private void requireOwner() {
        if (!ActorRuntime.inActorExecution()
                || ActorRuntime.currentActorRuntime() != runtime
                || !ActorRuntime.currentActorId().filter(owner::equals).isPresent()) {
            throw new SecurityException("child process operation belongs to a different actor");
        }
    }

    /**
     * Admit synchronously on the owner turn, start asynchronously on a host
     * executor. A cancelled/closed registry rejects pending and future spawns.
     */
    CompletableFuture<Child> spawnAsync(ChildProcLaunchAdmission.Spec spec) throws IOException {
        requireOwner();
        // Cap pending slots before policy checks, to avoid unbounded in-flight work.
        synchronized (lock) {
            if (closed) throw new IllegalStateException("child process registry is closed");
            if (slots.size() >= maxChildren) {
                throw new IllegalStateException("child process registry capacity exceeded");
            }
        }
        ChildProcLaunchAdmission.Plan plan = ActorChildProcAdmission.admit(context, spec);
        final Slot slot;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("child process registry is closed");
            if (slots.size() >= maxChildren) {
                throw new IllegalStateException("child process registry capacity exceeded");
            }
            if (nextGeneration == Long.MAX_VALUE) {
                throw new IllegalStateException("child process generation exhausted");
            }
            slot = new Slot(nextGeneration++);
            slots.put(slot.generation, slot);
        }
        try {
            ioExecutor.execute(() -> start(slot, plan));
        } catch (RejectedExecutionException rejected) {
            forget(slot);
            slot.ready.completeExceptionally(rejected);
        } catch (RuntimeException failedSubmission) {
            forget(slot);
            slot.ready.completeExceptionally(failedSubmission);
        }
        return slot.ready;
    }

    private void start(Slot slot, ChildProcLaunchAdmission.Plan plan) {
        // Executor implementations are caller supplied. A direct/inline executor
        // must NOT turn the async API into a blocking ProcessBuilder.start() on
        // the actor carrier or on another actor's execution turn.
        if (ActorRuntime.isActorCarrierThread() || ActorRuntime.inActorExecution()) {
            forget(slot);
            slot.ready.completeExceptionally(new IllegalStateException(
                    "subprocess launch worker must not execute on an actor carrier"));
            return;
        }
        synchronized (lock) {
            if (closed || slots.get(slot.generation) != slot) {
                slot.ready.completeExceptionally(new IllegalStateException("child process registry closed"));
                return;
            }
        }
        Process process;
        try {
            process = ChildProcLaunchAdmission.newProcessBuilder(plan).start();
        } catch (IOException | RuntimeException failed) {
            forget(slot);
            slot.ready.completeExceptionally(failed);
            return;
        }

        synchronized (lock) {
            if (closed || slots.get(slot.generation) != slot) {
                destroyTree(process, true);
                slot.ready.completeExceptionally(new IllegalStateException("child process registry closed"));
                return;
            }
            slot.process = process;
        }

        // The first slice is read-only child stdin: close immediately so child
        // processes waiting on input cannot hold an actor's wait forever.
        try { process.getOutputStream().close(); }
        catch (IOException ignored) { /* process exit/reap remains mandatory */ }

        CompletableFuture<byte[]> stdout = capture(process, process.getInputStream());
        CompletableFuture<byte[]> stderr = capture(process, process.getErrorStream());
        CompletableFuture<Exit> done = new CompletableFuture<>();
        process.onExit().thenCombine(stdout, (exited, out) -> new Object[]{exited, out})
                .thenCombine(stderr, (pair, err) ->
                        new Exit(((Process) pair[0]).exitValue(), (byte[]) pair[1], err))
                .whenComplete((exit, failure) -> {
                    forget(slot);
                    if (failure != null) done.completeExceptionally(failure);
                    else done.complete(exit);
                });
        slot.ready.complete(new Child(slot.generation, process, done));
    }

    private CompletableFuture<byte[]> capture(Process process, InputStream stream) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Thread.ofVirtual().name("ores-childproc-pipe").start(() -> {
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int used = 0;
                for (int count; (count = in.read(buffer)) != -1;) {
                    if (count > maxOutputBytes - used) {
                        destroyTree(process, true);
                        throw new IOException("child process output quota exceeded");
                    }
                    out.write(buffer, 0, count);
                    used += count;
                }
                result.complete(out.toByteArray());
            } catch (IOException | RuntimeException failure) {
                result.completeExceptionally(failure);
                destroyTree(process, true);
            }
        });
        return result;
    }

    private boolean signal(Child child, boolean force) {
        requireOwner();
        synchronized (lock) {
            Slot slot = slots.get(child.generation);
            if (closed || slot == null || slot.process == null
                    || slot.process.pid() != child.pid) return false;
            destroyTree(slot.process, force);
            return true;
        }
    }

    private void forget(Slot slot) {
        synchronized (lock) { slots.remove(slot.generation, slot); }
    }

    private static void destroyTree(Process process, boolean force) {
        // Best effort only. Descendants can escape/reparent; actual containment
        // requires a process group, Windows Job Object, or OS-level sandbox.
        try {
            List<ProcessHandle> descendants = process.toHandle().descendants().toList();
            for (int i = descendants.size() - 1; i >= 0; i--) {
                if (force) descendants.get(i).destroyForcibly();
                else descendants.get(i).destroy();
            }
        } catch (RuntimeException ignored) { /* still stop direct child */ }
        if (force) process.destroyForcibly();
        else process.destroy();
    }

    /** Host-owned teardown hook; safe even when the actor turn is gone. */
    @Override public void close() {
        List<Slot> active;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            active = new ArrayList<>(slots.values());
            slots.clear();
        }
        for (Slot slot : active) {
            slot.ready.completeExceptionally(new IllegalStateException("child process registry closed"));
            if (slot.process != null) destroyTree(slot.process, true);
        }
    }
}
