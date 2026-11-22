package dev.oreslang.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

/** Standalone JDK-only smoke; guest .atto integration is separate and pending. */
public final class TrustedChildProcessRunnerSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) { child(args[0]); return; }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString();
        String cp = System.getProperty("java.class.path");
        try (var runner = new TrustedChildProcessRunner(2, (spec, exe) -> {
            check(exe.isAbsolute(), "executable must be canonical");
            if (spec.argv().contains("denied")) throw new SecurityException("denied run");
        })) {
            var base = List.of(java, "-cp", cp, TrustedChildProcessRunnerSmoke.class.getName(), "child");
            var ok = runner.run(new TrustedChildProcessRunner.LaunchSpec(
                    base, Map.of(), null, Duration.ofSeconds(5), 1024)).join();
            check(ok.succeeded(), "child run");
            check(new String(ok.stdout()).contains("OUT"), "stdout captured");
            check(new String(ok.stderr()).contains("ERR"), "stderr captured");
            check(ok.stdout().length <= 1024 && ok.stderr().length <= 1024, "bounded capture");
            var exceed = runner.run(new TrustedChildProcessRunner.LaunchSpec(
                    base, Map.of(), null, Duration.ofSeconds(5), 2)).join();
            check(exceed.captureExceeded(), "capture limit");
            var deny = new java.util.ArrayList<>(base);
            deny.add("denied");
            try { runner.run(new TrustedChildProcessRunner.LaunchSpec(
                  deny, Map.of(), null, Duration.ofSeconds(5), 1024)).join();
                  throw new AssertionError("security gate not invoked");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof SecurityException, "security denial is not an exit code");
            }
            try { runner.run(new TrustedChildProcessRunner.LaunchSpec(
                  List.of("java", "-version"), Map.of(), null, Duration.ofSeconds(1), 1024));
                  throw new AssertionError("relative executable admitted");
            } catch (IllegalArgumentException expected) { }
            try { runner.run(new TrustedChildProcessRunner.LaunchSpec(
                  List.of(java, "\0"), Map.of(), null, Duration.ofSeconds(1), 1024));
                  throw new AssertionError("NUL admitted");
            } catch (IllegalArgumentException expected) { }
            var sleep = new java.util.ArrayList<>(base);
            sleep.set(sleep.size() - 1, "sleep");
            var timeout = runner.run(new TrustedChildProcessRunner.LaunchSpec(
                    sleep, Map.of(), null, Duration.ofMillis(50), 1024)).join();
            check(timeout.timedOut(), "timeout must destroy and reap");
        }
        System.out.println("STD_PROCESS_HOST_SMOKE_PASS");
    }
    // Child fixture. Never spawns a second process.
    private static void child(String what) throws Exception {
        if ("sleep".equals(what)) Thread.sleep(3000);
        else { System.out.print("OUT"); System.err.print("ERR"); }
    }
}
