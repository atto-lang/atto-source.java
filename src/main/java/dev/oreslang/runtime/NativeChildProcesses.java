package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Context-owned child-process authority. Guest code receives only opaque
 * handles; raw Process/ProcessHandle instances never cross the language boundary.
 */
public final class NativeChildProcesses implements AutoCloseable {
    private static final Duration STOP_GRACE = Duration.ofSeconds(2);
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    public String spawn(
            OresContext context,
            String rawExecutable,
            String argument) {
        context.requireCapability(
                IsolatePolicy.Capability.CHILD_PROCESS,
                "process.spawn");
        if (rawExecutable == null || rawExecutable.isBlank()) {
            throw new IllegalArgumentException("process.spawn executable cannot be blank");
        }
        if (argument == null) {
            throw new IllegalArgumentException("process.spawn argument cannot be null");
        }

        final Path lexical;
        try {
            lexical = Path.of(rawExecutable);
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IllegalArgumentException("invalid process executable path", invalid);
        }
        if (!lexical.isAbsolute()) {
            throw new IllegalArgumentException(
                    "process.spawn requires an absolute executable path");
        }
        if (Files.isSymbolicLink(lexical)) {
            throw new SecurityException(
                    "process.spawn refuses a terminal symlink executable: " + lexical);
        }

        final Path executable;
        try {
            executable = lexical.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "process.spawn executable is unavailable: " + lexical,
                    failure);
        }
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(executable)) {
            throw new IllegalArgumentException(
                    "process.spawn target is not an executable regular file: " + executable);
        }

        context.requirePermission(
                RuntimePermissions.Permission.RUN,
                executable.toString(),
                "process.spawn");

        ProcessBuilder builder = new ProcessBuilder(
                executable.toString(), argument);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();

        final Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "process.spawn failed for " + executable,
                    failure);
        }

        try {
            process.getOutputStream().close();
        } catch (IOException closeFailure) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "process.spawn failed to close child stdin for " + executable,
                    closeFailure);
        }

        String handle = UUID.randomUUID().toString();
        Process previous = processes.putIfAbsent(handle, process);
        if (previous != null) {
            process.destroyForcibly();
            throw new IllegalStateException("process handle collision");
        }
        return handle;
    }

    public boolean running(OresContext context, String handle) {
        context.requireCapability(
                IsolatePolicy.Capability.CHILD_PROCESS,
                "process.running");
        return require(handle).isAlive();
    }

    public long pid(OresContext context, String handle) {
        context.requireCapability(
                IsolatePolicy.Capability.CHILD_PROCESS,
                "process.pid");
        return require(handle).pid();
    }

    public boolean stop(OresContext context, String handle) {
        context.requireCapability(
                IsolatePolicy.Capability.CHILD_PROCESS,
                "process.stop");
        Process process = require(handle);
        if (!process.isAlive()) return true;

        process.destroy();
        if (!await(process, STOP_GRACE)) {
            process.destroyForcibly();
            if (!await(process, STOP_GRACE)) {
                return false;
            }
        }
        return !process.isAlive();
    }

    private Process require(String handle) {
        if (handle == null || handle.isBlank()) {
            throw new IllegalArgumentException("process handle cannot be blank");
        }
        Process process = processes.get(handle);
        if (process == null) {
            throw new IllegalArgumentException("unknown child process handle");
        }
        return process;
    }

    private static boolean await(Process process, Duration timeout) {
        try {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for child process",
                    interrupted);
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (Process process : processes.values()) {
            if (!process.isAlive()) continue;
            try {
                process.destroy();
                if (!await(process, Duration.ofMillis(250))) {
                    process.destroyForcibly();
                    await(process, STOP_GRACE);
                }
            } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        processes.clear();
        if (failure != null) throw failure;
    }
}
