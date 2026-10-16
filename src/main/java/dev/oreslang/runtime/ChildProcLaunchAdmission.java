package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Host-only admission for a future ChildProcManager backend.
 *
 * <p>This component does not spawn processes, accept guest objects, or convey
 * an OS sandbox. The actor runtime must call it synchronously on the issuing
 * actor's turn and supply the effective actor-local and isolate permission
 * check, before moving a resulting immutable Plan to an I/O worker.</p>
 */
final class ChildProcLaunchAdmission {
    private static final int MAX_ARGS = 256;
    private static final int MAX_ENV = 128;
    private static final int MAX_SPEC_BYTES = 128 * 1024;

    enum Permission { RUN, READ, ENV }

    @FunctionalInterface
    interface PermissionGuard {
        void require(Permission permission, String resource);
    }

    record Spec(String executable, List<String> args, String cwd,
                Map<String, String> environment) {
        Spec {
            Objects.requireNonNull(executable, "executable");
            Objects.requireNonNull(args, "args");
            Objects.requireNonNull(cwd, "cwd");
            Objects.requireNonNull(environment, "environment");
            // Bound collection size before copying untrusted guest inputs.
            if (args.size() > MAX_ARGS || environment.size() > MAX_ENV) {
                throw new IllegalArgumentException("child process argument/environment count exceeds limit");
            }
            args = List.copyOf(args);
            environment = Map.copyOf(environment);
        }
    }

    record Plan(Path executable, List<String> argv, Path cwd,
                Map<String, String> environment) {
        Plan {
            Objects.requireNonNull(executable, "executable");
            argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
            Objects.requireNonNull(cwd, "cwd");
            environment = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(environment, "environment")));
        }
    }

    private ChildProcLaunchAdmission() { }

    static Plan admit(Spec spec, PermissionGuard guard) throws IOException {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(guard, "guard");
        if (spec.args().size() > MAX_ARGS || spec.environment().size() > MAX_ENV) {
            throw new IllegalArgumentException("child process argument/environment count exceeds limit");
        }

        int bytes = 0;
        String executable = nonempty(spec.executable(), "executable");
        String cwd = nonempty(spec.cwd(), "working directory");
        bytes = addBytes(bytes, executable);
        bytes = addBytes(bytes, cwd);

        for (String arg : spec.args()) bytes = addBytes(bytes, checked(arg, "argument"));

        Map<String, String> environment = new TreeMap<>();
        for (var entry : spec.environment().entrySet()) {
            String key = nonempty(entry.getKey(), "environment key");
            if (!key.matches("[A-Za-z_][A-Za-z_0-9]*")) {
                throw new IllegalArgumentException("invalid environment key: " + key);
            }
            String value = checked(entry.getValue(), "environment value");
            bytes = addBytes(addBytes(bytes, key), value);
            if (environment.put(key, value) != null) {
                throw new IllegalArgumentException("duplicate environment key: " + key);
            }
        }

        Path requested = absolutePath(executable, "executable");
        Path requestedCwd = absolutePath(cwd, "working directory");

        // Check requested names before inspecting the filesystem to avoid
        // leaking the existence of disallowed paths through resolution errors.
        guard.require(Permission.RUN, requested.toString());
        guard.require(Permission.READ, requestedCwd.toString());
        for (String key : environment.keySet()) guard.require(Permission.ENV, key);

        // A symlink/alias to a denied executable must not bypass a deny rule.
        // There remains an unavoidable replacement race without descriptor
        // based exec or an OS-level sandbox; this is NOT an OS confinement API.
        Path executableReal = requested.toRealPath();
        Path cwdReal = requestedCwd.toRealPath();
        guard.require(Permission.RUN, executableReal.toString());
        guard.require(Permission.READ, cwdReal.toString());
        if (!Files.isRegularFile(executableReal) || !Files.isExecutable(executableReal)) {
            throw new IllegalArgumentException("executable is not a regular executable file");
        }
        if (!Files.isDirectory(cwdReal)) {
            throw new IllegalArgumentException("working directory is not a directory");
        }

        List<String> argv = new ArrayList<>(spec.args().size() + 1);
        argv.add(executableReal.toString());
        argv.addAll(spec.args());
        return new Plan(executableReal, argv, cwdReal, environment);
    }

    /**
     * Construct (but DO NOT START) a host-side builder from a preauthorized
     * plan. In particular, ProcessBuilder inherits the parent environment by
     * default, which would bypass per-key ENV permissions unless cleared.
     *
     * <p>The future actor-owned worker is responsible for bounded pipe I/O,
     * cancellation, reaping and OS sandboxing where required; it must never
     * call start() on an actor carrier thread.</p>
     */
    static ProcessBuilder newProcessBuilder(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        ProcessBuilder builder = new ProcessBuilder(plan.argv());
        builder.directory(plan.cwd().toFile());
        builder.environment().clear();
        builder.environment().putAll(plan.environment());
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        builder.redirectErrorStream(false);
        return builder;
    }

    private static String checked(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " contains NUL");
        }
        return value;
    }

    private static String nonempty(String value, String label) {
        checked(value, label);
        if (value.isBlank()) throw new IllegalArgumentException(label + " cannot be blank");
        return value;
    }

    /**
     * Count encoded UTF-8 bytes without allocating a second attacker-sized byte
     * array. Fail early on the total bound and reject malformed UTF-16 instead
     * of silently replacing surrogates with the platform encoder fallback.
     */
    private static int addBytes(int previous, String value) {
        int total = previous;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int width;
            if (ch <= 0x7f) {
                width = 1;
            } else if (ch <= 0x7ff) {
                width = 2;
            } else if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("invalid UTF-16 surrogate in child process spec");
                }
                i++;
                width = 4;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("invalid UTF-16 surrogate in child process spec");
            } else {
                width = 3;
            }
            if (total > MAX_SPEC_BYTES - width) {
                throw new IllegalArgumentException("child process spec exceeds byte quota");
            }
            total += width;
        }
        return total;
    }

    private static Path absolutePath(String value, String label) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(label + " must be an absolute path (PATH lookup is disabled)");
        }
        return path.normalize();
    }
}
