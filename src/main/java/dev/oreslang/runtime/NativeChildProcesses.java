package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Context-owned child OS processes; no guest can obtain a raw Process, PID, shell,
 * or Java reflection handle. This is a restricted *root-context* hosted slice.
 *
 * Host-side asynchronous methods intentionally have no "_async" suffix: all
 * guest process operations are non-parking by default. Actor-owned launching,
 * arbitrary env/cwd changes, stdin piping, process trees and detachment are
 * intentionally not admitted until their separate lifecycle contracts exist.
 */
public final class NativeChildProcesses implements AutoCloseable {
    public static final int MAX_CHILDREN = 16;
    public static final int MAX_CAPTURE_BYTES = 1_048_576;
    // The root-context guest API admits no unbounded OS subprocess lifetime.
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(5);
    private static final int MAX_ARGUMENTS = 4096;
    private static final int MAX_ARGUMENT_BYTES = 4_194_304;
    private static final int MAX_PATH_BYTES = 4096;

    /** Caller-bound authority. Called before filesystem probing and again on
     * the resolved path, immediately before starting the process. */
    @FunctionalInterface
    public interface Authorizer {
        void require(String executable, String cwd);
    }

    public record ExitStatus(int code, boolean signaled) {
        public boolean success() { return code == 0 && !signaled; }
    }

    public record Output(String stdout, String stderr) { }

    /** Printable, immutable, non-host-authority failure payload for guest Err. */
    public record ProcessError(String kind, String message) {
        public static ProcessError from(Throwable raw) {
            Throwable failure = raw;
            while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                    && failure.getCause() != null) failure = failure.getCause();
            String kind = failure instanceof SecurityException ? "permission_denied"
                    : failure instanceof OutputLimitException ? "output_limit"
                    : failure instanceof java.util.concurrent.CancellationException ? "cancelled"
                    : failure instanceof java.util.concurrent.TimeoutException ? "timeout"
                    : failure instanceof RejectedExecutionException ? "capacity"
                    : failure instanceof IOException ? "spawn_or_io"
                    : failure instanceof IllegalArgumentException ? "invalid_command"
                    : "host_failure";
            // Never echo a command line, inherited environment or host path.
            return new ProcessError(kind, kind.equals("host_failure")
                    ? "child process operation failed" : kind);
        }
    }

    public static final class OutputLimitException extends IllegalStateException {
        private OutputLimitException() { super("bounded process output limit exceeded"); }
    }

    private record Capture(byte[] bytes, boolean exceeded) { }

    public static final class Child implements AutoCloseable {
        private final Process process;
        private final CompletableFuture<ExitStatus> exit;
        private final AtomicBoolean outputExceeded = new AtomicBoolean();
        private final AtomicBoolean deadlineExceeded = new AtomicBoolean();
        // A guest may call terminate() repeatedly. Escalation is a single
        // per-child state transition, never a new virtual thread per call.
        private final AtomicBoolean terminationRequested = new AtomicBoolean();
        private final CompletableFuture<Capture> stdout = new CompletableFuture<>();
        private final CompletableFuture<Capture> stderr = new CompletableFuture<>();
        private final CompletableFuture<Void> retired;

        private Child(Process process, Duration maxLifetime) {
            this.process = Objects.requireNonNull(process);
            try { process.getOutputStream().close(); }
            catch (IOException ignored) { /* child already owns its end */ }
            exit = process.onExit().thenApply(p -> new ExitStatus(p.exitValue(), false));
            // At most MAX_CHILDREN sleeping virtual threads; interrupt each
            // watchdog as soon as the physical child is reaped.
            Thread watchdog = Thread.ofVirtual().name("atto-process-deadline").start(() -> {
                try {
                    Thread.sleep(maxLifetime);
                } catch (InterruptedException reaped) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!exit.isDone() && process.isAlive()) {
                    deadlineExceeded.set(true);
                    process.destroyForcibly();
                }
            });
            exit.whenComplete((status, failure) -> watchdog.interrupt());
            Thread.ofVirtual().name("atto-process-stdout").start(() ->
                    drain(process.getInputStream(), stdout));
            Thread.ofVirtual().name("atto-process-stderr").start(() ->
                    drain(process.getErrorStream(), stderr));
            retired = CompletableFuture.allOf(exit, stdout, stderr);
        }

        /** Returned future is observer-only: cancelling wait does not kill child. */
        public CompletableFuture<ExitStatus> waitForExit() {
            return exit.thenApply(status -> {
                if (deadlineExceeded.get()) {
                    throw new CompletionException(new TimeoutException(
                            "child process lifetime exceeded"));
                }
                return status;
            });
        }

        /** Both pipes drain concurrently from launch, even if guest only waits. */
        public CompletableFuture<Output> collectOutput() {
            return retired.handle((ignored, failure) -> {
                // An output-limit kill may also close the other pipe. Do not
                // mistakenly report that collateral stream close as I/O error.
                if (outputExceeded.get()) throw new OutputLimitException();
                if (deadlineExceeded.get()) throw new CompletionException(
                        new TimeoutException("child process lifetime exceeded"));
                if (failure != null) throw new CompletionException(failure);
                Capture out = stdout.join();
                Capture err = stderr.join();
                return new Output(
                        new String(out.bytes(), StandardCharsets.UTF_8),
                        new String(err.bytes(), StandardCharsets.UTF_8));
            });
        }

        public ExitStatus tryWait() {
            // Poll cannot encode a ProcessError; wait() reports timeout.
            if (deadlineExceeded.get()) return null;
            return exit.isDone() && !exit.isCompletedExceptionally()
                    ? exit.getNow(null) : null;
        }

        public CompletableFuture<ExitStatus> terminate() {
            if (process.isAlive() && terminationRequested.compareAndSet(false, true)) {
                process.destroy();
                // A graceful signal must not create an unbounded await if
                // the child ignores it. Escalation runs on a virtual thread,
                // never on the caller's actor/root carrier.
                try {
                    Thread.ofVirtual().name("atto-process-escalation").start(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            if (process.isAlive()) process.destroyForcibly();
                        }
                    });
                } catch (RuntimeException | Error failed) {
                    process.destroyForcibly();
                    throw failed;
                }
            }
            return waitForExit();
        }

        public CompletableFuture<ExitStatus> kill() {
            if (process.isAlive()) process.destroyForcibly();
            return waitForExit();
        }

        public boolean alive() { return process.isAlive(); }

        /** Nonblocking revocation of this owned OS process; no PID-based kill. */
        @Override public void close() {
            if (process.isAlive()) process.destroyForcibly();
        }

        private void drain(InputStream input, CompletableFuture<Capture> target) {
            ByteArrayOutputStream bounded = new ByteArrayOutputStream();
            boolean exceeded = false;
            try (input) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    int remaining = MAX_CAPTURE_BYTES - bounded.size();
                    int accepted = Math.min(remaining, read);
                    if (accepted > 0) bounded.write(chunk, 0, accepted);
                    if (accepted < read && !exceeded) {
                        exceeded = true;
                        // Fail closed immediately. Continuing to drain forever
                        // would let an untrusted infinite-output process live
                        // and consume unbounded CPU after the capture budget.
                        outputExceeded.set(true);
                        if (process.isAlive()) process.destroyForcibly();
                    }
                }
                target.complete(new Capture(bounded.toByteArray(), exceeded));
            } catch (IOException failure) {
                if (outputExceeded.get()) {
                    // Killing a child can invalidate a pipe while readers are
                    // draining it. The original output quota is the error.
                    target.complete(new Capture(bounded.toByteArray(), true));
                } else {
                    target.completeExceptionally(failure);
                }
            } catch (RuntimeException failure) {
                target.completeExceptionally(failure);
            }
        }
    }

    private final Object guard = new Object();
    private final Set<Child> live = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicInteger reserved = new AtomicInteger();
    // Admission is explicitly bounded to MAX_CHILDREN before task scheduling.
    // A virtual-thread-per-task executor avoids abandoned queued launch jobs
    // and unreleased reservations during shutdownNow().
    private final ExecutorService launches = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean closed;

    public int reservedCount() { return reserved.get(); }

    public CompletableFuture<Child> spawn(
            String executable, List<String> arguments, String cwd, Authorizer authorizer) {
        return spawn(executable, arguments, cwd, authorizer, MAX_LIFETIME);
    }

    /** Host-side bounded override for tests and separately authorized callers. */
    CompletableFuture<Child> spawn(
            String executable, List<String> arguments, String cwd,
            Authorizer authorizer, Duration maxLifetime) {
        Objects.requireNonNull(authorizer, "process authorizer");
        if (maxLifetime == null || maxLifetime.compareTo(Duration.ofMillis(50)) < 0
                || maxLifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("child lifetime must be 50ms..5min");
        }
        String requestedExe = checkedString(executable, MAX_PATH_BYTES, "executable");
        String requestedCwd = checkedString(cwd, MAX_PATH_BYTES, "cwd");
        Path lexicalExe = Path.of(requestedExe);
        Path lexicalCwd = Path.of(requestedCwd);
        if (!lexicalExe.isAbsolute() || !lexicalCwd.isAbsolute()
                || !lexicalExe.normalize().equals(lexicalExe)
                || !lexicalCwd.normalize().equals(lexicalCwd)) {
            throw new IllegalArgumentException("process paths must be absolute and normalized");
        }
        List<String> argv = checkedArguments(arguments);
        // Authorize BEFORE any path metadata checks, using the caller's actor
        // policy as well as its context policy. No actor authority may migrate
        // silently to this off-carrier worker.
        authorizer.require(requestedExe, requestedCwd);
        synchronized (guard) {
            if (closed) throw new RejectedExecutionException("process context closed");
            if (reserved.incrementAndGet() > MAX_CHILDREN) {
                reserved.decrementAndGet();
                throw new RejectedExecutionException("child process capacity exhausted");
            }
        }

        CompletableFuture<Child> published = new CompletableFuture<>();
        try {
            launches.execute(() -> {
                boolean registered = false;
                try {
                    Path executablePath = lexicalExe.toRealPath();
                    Path workingDir = lexicalCwd.toRealPath();
                    if (Files.isSymbolicLink(lexicalExe)
                            || !Files.isRegularFile(executablePath, LinkOption.NOFOLLOW_LINKS)
                            || !Files.isExecutable(executablePath)
                            || !Files.isDirectory(workingDir)) {
                        throw new IllegalArgumentException("unusable executable or cwd");
                    }
                    // Authorize the canonical paths after resolution, before
                    // crossing the actual ProcessBuilder.start boundary.
                    authorizer.require(executablePath.toString(), workingDir.toString());
                    List<String> command = new ArrayList<>(argv.size() + 1);
                    command.add(executablePath.toString());
                    command.addAll(argv);
                    ProcessBuilder builder = new ProcessBuilder(command);
                    builder.environment().clear(); // Never inherit secrets.
                    builder.directory(workingDir.toFile());
                    builder.redirectErrorStream(false);
                    if (closed) throw new RejectedExecutionException("process context closed");
                    // Never hold the manager's lifecycle lock across the OS
                    // spawn syscall: context shutdown must not wait behind an
                    // unbounded native process creation.
                    Process process = builder.start();
                    Child child;
                    try {
                        child = new Child(process, maxLifetime);
                    } catch (RuntimeException | Error failed) {
                        process.destroyForcibly();
                        throw failed;
                    }
                    synchronized (guard) {
                        if (closed) {
                            child.close();
                            throw new RejectedExecutionException("process context closed");
                        }
                        live.add(child);
                        registered = true;
                        child.retired.whenComplete((ignored, failure) -> {
                            live.remove(child);
                            reserved.decrementAndGet();
                        });
                    }
                    if (!published.complete(child)) child.close();
                } catch (IOException | RuntimeException failed) {
                    if (!registered) reserved.decrementAndGet();
                    published.completeExceptionally(failed);
                }
            });
        } catch (RejectedExecutionException failed) {
            reserved.decrementAndGet();
            published.completeExceptionally(failed);
        }
        return published;
    }

    private static String checkedString(String text, int maxBytes, String name) {
        if (text == null || text.isEmpty() || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid process " + name);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c)) throw new IllegalArgumentException("invalid control byte");
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("malformed unicode");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("malformed unicode");
            }
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("process " + name + " too long");
        }
        return text;
    }

    private static List<String> checkedArguments(List<String> args) {
        Objects.requireNonNull(args, "process argv");
        if (args.size() > MAX_ARGUMENTS) throw new IllegalArgumentException("too many arguments");
        int used = 0;
        List<String> copy = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null) throw new IllegalArgumentException("null argv element");
            // Empty argv elements are valid; command path and cwd may not be.
            String value = checkedString(arg.isEmpty() ? " " : arg, MAX_ARGUMENT_BYTES, "argument");
            if (arg.isEmpty()) value = "";
            int bytes = arg.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_ARGUMENT_BYTES - used) {
                throw new IllegalArgumentException("argument byte budget exceeded");
            }
            used += bytes;
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    @Override public void close() {
        List<Child> owned;
        synchronized (guard) {
            closed = true;
            launches.shutdownNow();
            owned = List.copyOf(live);
        }
        for (Child child : owned) child.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean interrupted = false;
        try {
            for (Child child : owned) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try {
                    child.retired.get(remaining, TimeUnit.NANOSECONDS);
                } catch (ExecutionException failure) {
                    // Failed pipe collection still has a completed physical reader.
                } catch (java.util.concurrent.TimeoutException failure) {
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                    break;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!live.isEmpty() || reserved.get() != 0) {
            // Keep manager and native children reachable on context quarantine;
            // never certify clean teardown while launch or reaping is pending.
            throw new IllegalStateException("child process retirement incomplete");
        }
    }
}
