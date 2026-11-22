package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Trusted-host-only process execution building block. NOT guest-exposed.
 *
 * <p>An authoritative bridge must authenticate the actor and incarnation, bind
 * its RUN permission and resource budget to an immutable request, and install
 * a host-only admission callback. This class alone does not provide actor
 * capability authorization or prevent a path from changing between validation
 * and OS creation. Do not wire it to untrusted guest code without both gates.
 * Only direct argv launches are supported, never shell interpolation or fork().</p>
 */
final class TrustedChildProcessRunner implements AutoCloseable {
    @FunctionalInterface
    interface Admission {
        void requireAuthorized(LaunchSpec spec, Path selectedExecutable);
    }

    record LaunchSpec(List<String> argv, Map<String, String> environment,
                      Path cwd, Duration timeout, int captureBytesPerStream) {
        LaunchSpec {
            Objects.requireNonNull(argv, "argv");
            Objects.requireNonNull(environment, "environment");
            Objects.requireNonNull(timeout, "timeout");
            argv = List.copyOf(argv);
            environment = Map.copyOf(environment);
        }
    }

    record Outcome(int exitCode, boolean timedOut, boolean captureExceeded,
                   byte[] stdout, byte[] stderr) {
        Outcome {
            stdout = stdout.clone();
            stderr = stderr.clone();
        }
        @Override public byte[] stdout() { return stdout.clone(); }
        @Override public byte[] stderr() { return stderr.clone(); }
        boolean succeeded() { return !timedOut && !captureExceeded && exitCode == 0; }
    }

    private record Captured(byte[] bytes, boolean exceeded) { }
    private static final int MAX_ARGV = 4096;
    private static final int MAX_CAPTURE = 4 * 1024 * 1024;
    private static final long MAX_TOTAL_ARG_BYTES = 4L * 1024 * 1024;
    private static final long MAX_ENV_BYTES = 1024L * 1024;
    private final Admission admission;
    private final ExecutorService runners;
    private final ExecutorService readers;
    private final java.util.concurrent.Semaphore permits;
    private final Set<Process> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    TrustedChildProcessRunner(int maxChildren, Admission admission) {
        if (maxChildren < 1 || maxChildren > 32)
            throw new IllegalArgumentException("maxChildren must be 1..32");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.permits = new java.util.concurrent.Semaphore(maxChildren);
        this.runners = fixed("atto-child-wait", maxChildren, maxChildren);
        this.readers = fixed("atto-child-pipe", maxChildren * 2, maxChildren * 2);
    }

    private static ExecutorService fixed(String name, int threads, int queueCapacity) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread t = new Thread(runnable, name);
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    CompletableFuture<Outcome> run(LaunchSpec spec) {
        Objects.requireNonNull(spec, "spec");
        // Validation has no side effects. The worker rechecks the selected
        // executable and the trusted admission callback immediately before start.
        validateShape(spec);
        if (closed.get()) return CompletableFuture.failedFuture(
                new RejectedExecutionException("process runner closed"));
        if (!permits.tryAcquire()) return CompletableFuture.failedFuture(
                new RejectedExecutionException("child-process capacity exhausted"));
        CompletableFuture<Outcome> answer = new CompletableFuture<>();
        AtomicReference<Process> childRef = new AtomicReference<>();
        answer.whenComplete((result, error) -> {
            if (answer.isCancelled()) {
                Process child = childRef.get();
                if (child != null) child.destroyForcibly();
            }
        });
        try {
            runners.execute(() -> {
                try {
                    answer.complete(execute(spec, childRef, answer));
                } catch (Throwable error) {
                    answer.completeExceptionally(error);
                } finally {
                    permits.release();
                }
            });
        } catch (RejectedExecutionException rejected) {
            permits.release();
            answer.completeExceptionally(rejected);
        }
        return answer;
    }

    private Outcome execute(LaunchSpec spec, AtomicReference<Process> childRef,
                            CompletableFuture<Outcome> answer) throws Exception {
        Process child = null;
        Future<Captured> stdout = null;
        Future<Captured> stderr = null;
        try {
            if (answer.isCancelled() || closed.get()) throw new CancellationException();
            Path executable = Path.of(spec.argv().get(0)).toRealPath();
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable))
                throw new IOException("not an executable regular file");
            Path cwd = spec.cwd() == null ? null : spec.cwd().toRealPath();
            if (cwd != null && !Files.isDirectory(cwd))
                throw new IOException("working directory is not a directory");
            // The authoritative bridge MUST check its actor identity/epoch and
            // OresContext.requirePermission(RUN, actualExecutable, ...) here.
            admission.requireAuthorized(spec, executable);
            if (answer.isCancelled() || closed.get()) throw new CancellationException();
            List<String> directArgv = new ArrayList<>(spec.argv());
            directArgv.set(0, executable.toString());
            ProcessBuilder builder = new ProcessBuilder(directArgv);
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
            builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
            builder.redirectError(ProcessBuilder.Redirect.PIPE);
            if (cwd != null) builder.directory(cwd.toFile());
            builder.environment().clear();
            builder.environment().putAll(spec.environment());
            child = builder.start();
            active.add(child);
            childRef.set(child);
            if (closed.get() || answer.isCancelled()) child.destroyForcibly();
            child.getOutputStream().close(); // stdin is closed, never inherited
            final Process launched = child;
            final int captureLimit = spec.captureBytesPerStream();
            stdout = readers.submit(() -> capture(launched.getInputStream(), captureLimit, launched));
            stderr = readers.submit(() -> capture(launched.getErrorStream(), captureLimit, launched));
            boolean finished = child.waitFor(spec.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) child.destroyForcibly();
            if (!finished) child.waitFor(); // reap the exact owned Process
            Captured out = stdout.get();
            Captured err = stderr.get();
            return new Outcome(child.exitValue(), !finished,
                    out.exceeded() || err.exceeded(), out.bytes(), err.bytes());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("process wait interrupted");
        } finally {
            if (child != null) {
                if (child.isAlive()) child.destroyForcibly();
                // Interruption is only a request. Physical child reaping is
                // mandatory before releasing the concurrency permit.
                boolean interrupted = Thread.interrupted();
                for (;;) {
                    try { child.waitFor(); active.remove(child); break; }
                    catch (InterruptedException pending) {
                        interrupted = true;
                        child.destroyForcibly();
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static Captured capture(InputStream input, int limit, Process child) throws IOException {
        ByteArrayOutputStream bounded = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[4096];
        boolean exceeded = false;
        try (input) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                int remaining = limit - bounded.size();
                if (count > remaining) {
                    if (remaining > 0) bounded.write(buffer, 0, remaining);
                    exceeded = true;
                    child.destroyForcibly();
                    break;
                }
                bounded.write(buffer, 0, count);
            }
        }
        return new Captured(bounded.toByteArray(), exceeded);
    }

    private static void validateShape(LaunchSpec spec) {
        if (spec.argv().isEmpty() || spec.argv().size() > MAX_ARGV)
            throw new IllegalArgumentException("invalid argv count");
        if (spec.environment().size() > 1024)
            throw new IllegalArgumentException("too many environment entries");
        if (spec.captureBytesPerStream() < 0 || spec.captureBytesPerStream() > MAX_CAPTURE)
            throw new IllegalArgumentException("capture limit out of range");
        if (spec.timeout().isZero() || spec.timeout().isNegative()
                || spec.timeout().compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("finite timeout required");
        if (!Path.of(spec.argv().get(0)).isAbsolute())
            throw new IllegalArgumentException("absolute executable path required");
        long argvBytes = 0;
        for (String argument : spec.argv()) {
            if (argument.indexOf('\0') >= 0)
                throw new IllegalArgumentException("NUL in argv");
            argvBytes += argument.getBytes(StandardCharsets.UTF_8).length;
            if (argvBytes > MAX_TOTAL_ARG_BYTES)
                throw new IllegalArgumentException("argv size exceeded");
        }
        if (spec.argv().get(0).getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("executable path too long");
        long envBytes = 0;
        for (var item : spec.environment().entrySet()) {
            String key = item.getKey(), value = item.getValue();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException("invalid environment entry");
            envBytes += key.getBytes(StandardCharsets.UTF_8).length
                      + value.getBytes(StandardCharsets.UTF_8).length;
            if (envBytes > MAX_ENV_BYTES) throw new IllegalArgumentException("environment size exceeded");
        }
        if (spec.cwd() != null && spec.cwd().toString().getBytes(StandardCharsets.UTF_8).length > 4096)
            throw new IllegalArgumentException("cwd path too long");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Process process : active) process.destroyForcibly();
        runners.shutdown();
        readers.shutdown();
        try {
            if (!runners.awaitTermination(10, TimeUnit.SECONDS)
                    || !readers.awaitTermination(10, TimeUnit.SECONDS)
                    || !active.isEmpty())
                throw new IllegalStateException("process runner still has live workers or children");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("process runner close interrupted", interrupted);
        }
    }
}
