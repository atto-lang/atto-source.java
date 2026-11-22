package dev.oreslang.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** No JUnit/Maven/Graal dependencies; compiles the exact production provider. */
public final class NativeChildProcessesSmoke {
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static String javaBinary() {
        // In a native-image executable java.home need not point at a JDK.
        String configured = System.getenv("ATTO_TEST_JAVA_EXECUTABLE");
        if (configured != null && !configured.isBlank())
            return Path.of(configured).toAbsolutePath().normalize().toString();
        String ext = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + ext)
                .toAbsolutePath().normalize().toString();
    }

    private static String cwd() {
        return Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize().toString();
    }

    private static List<String> child(String mode) {
        String classes = System.getenv("ATTO_TEST_JAVA_CLASSPATH");
        return List.of("-cp", classes != null ? classes : System.getProperty("java.class.path"),
                NativeChildProcessesSmoke.class.getName(), mode);
    }

    private static void assertFailureKind(
            CompletableFuture<?> future, Class<? extends Throwable> expected) throws Exception {
        try {
            future.get(8, TimeUnit.SECONDS);
            throw new AssertionError("expected failure " + expected.getName());
        } catch (ExecutionException failure) {
            check(expected.isInstance(failure.getCause()),
                    "wrong error kind " + failure.getCause());
        }
    }

    private static void smoke() throws Exception {
        String executable = javaBinary();
        String directory = cwd();
        try (NativeChildProcesses manager = new NativeChildProcesses()) {
            var child = manager.spawn(executable, child("exit42"), directory,
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            check(child.waitForExit().get(8, TimeUnit.SECONDS).code() == 42,
                    "child exit42 result");
            var captured = child.collectOutput().get(8, TimeUnit.SECONDS);
            check(captured.stdout().contains("OUT") && captured.stderr().contains("ERR"),
                    "concurrent stdout and stderr");
            check(!child.alive(), "exit42 still alive");

            AtomicInteger authCalls = new AtomicInteger();
            assertFailureKind(manager.spawn(executable, child("exit42"), directory,
                    (exe, dir) -> {
                        if (authCalls.incrementAndGet() == 2) {
                            throw new SecurityException("canonical RUN denied");
                        }
                    }), SecurityException.class);
            check(authCalls.get() == 2, "canonical reauthorization not performed");

            List<String> arguments = new ArrayList<>(child("literal"));
            arguments.add("a; echo injected $(whoami) \"quoted\"");
            var literal = manager.spawn(executable, arguments, directory,
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            check(literal.collectOutput().get(8, TimeUnit.SECONDS).stdout().equals(arguments.getLast()),
                    "shell metacharacters were evaluated or changed");

            var env = manager.spawn(executable, child("env"), directory,
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            check(env.collectOutput().get(8, TimeUnit.SECONDS).stdout().equals("empty"),
                    "child inherited environment");

            var producer = manager.spawn(executable, child("infinite"), directory,
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            assertFailureKind(producer.collectOutput(),
                    NativeChildProcesses.OutputLimitException.class);
            producer.waitForExit().get(8, TimeUnit.SECONDS);
            check(!producer.alive(), "unbounded producer was not terminated");

            var silent = manager.spawn(executable, child("sleep"), directory,
                    (exe, dir) -> { }, Duration.ofMillis(150)).get(8, TimeUnit.SECONDS);
            assertFailureKind(silent.waitForExit(), TimeoutException.class);
            check(!silent.alive(), "timed-out process still alive");

            var closed = manager.spawn(executable, child("sleep"), directory,
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            closed.close();
            assertFailureKind(closed.waitForExit(),
                    java.util.concurrent.CancellationException.class);
            assertFailureKind(closed.collectOutput(),
                    java.util.concurrent.CancellationException.class);
            check(!closed.alive(), "owner-closed child remains alive");

            var alreadyExited = manager.spawn(executable, child("exit42"), directory,
                    (exe, dir) -> { }).get(8, TimeUnit.SECONDS);
            check(alreadyExited.waitForExit().get(8, TimeUnit.SECONDS).code() == 42,
                    "normal exited-child status missing");
            alreadyExited.close();
            check(alreadyExited.waitForExit().get(8, TimeUnit.SECONDS).code() == 42,
                    "close retroactively cancelled completed child");

            List<String> invalid = new ArrayList<>(child("exit42"));
            invalid.add(null);
            try {
                manager.spawn(executable, invalid, directory, (exe, dir) -> { });
                throw new AssertionError("null argv accepted");
            } catch (IllegalArgumentException expected) { }
        }

        NativeChildProcesses closing = new NativeChildProcesses();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        try {
            CompletableFuture<NativeChildProcesses.Child> pending = closing.spawn(
                    executable, child("sleep"), directory, (exe, dir) -> {
                        if (calls.incrementAndGet() == 2) {
                            entered.countDown();
                            try {
                                if (!release.await(8, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("latch expired");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("latch interrupted", interrupted);
                            }
                        }
                    });
            check(entered.await(8, TimeUnit.SECONDS),
                    "launch never reached canonical authorization");
            Thread teardown = Thread.ofVirtual().start(() -> {
                try { closing.close(); }
                catch (Throwable failure) { closeFailure.set(failure); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!closing.isAdmissionClosed() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            check(closing.isAdmissionClosed(), "close failed to seal admission");
            release.countDown();
            assertFailureKind(pending, java.util.concurrent.RejectedExecutionException.class);
            teardown.join(8000);
            check(!teardown.isAlive(), "close blocked forever");
            check(closeFailure.get() == null, "close failed " + closeFailure.get());
            check(closing.reservedCount() == 0, "failed to retire close-racing reservation");
        } finally {
            release.countDown();
            closing.close();
        }
        System.out.println("NATIVE_CHILD_PROCESS_STANDALONE_SMOKE_PASS");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { smoke(); return; }
        switch (args[0]) {
            case "exit42" -> {
                System.out.println("OUT");
                System.err.println("ERR");
                System.exit(42);
            }
            case "literal" -> System.out.print(args[1]);
            case "env" -> System.out.print(System.getenv().isEmpty() ? "empty" : "inherited");
            case "sleep" -> Thread.sleep(30_000);
            case "infinite" -> {
                byte[] bytes = new byte[8192];
                java.util.Arrays.fill(bytes, (byte)'x');
                while (true) System.out.write(bytes);
            }
            default -> throw new IllegalArgumentException("unknown child mode");
        }
    }
}
