package dev.oreslang.launcher;

import dev.oreslang.runtime.ConcurrencyTestProbe;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.PermissionCheckMode;
import dev.oreslang.runtime.RuntimePermissions;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explicit Oreslang native-test command. The test file must call the real
 * stdlib/testing.ores runner; ordinary programs are not silently counted as tests.
 *
 * This first slice supports bounded trace/replay and schedule perturbation.
 * It rejects unsupported race/model-check claims rather than reporting success.
 */
final class ConcurrencyTestCommand {
    private ConcurrencyTestCommand() { }

    static int run(String[] args) throws Exception {
        boolean deadlock = false;
        boolean fuzz = false;
        boolean scheduleFuzz = false;
        boolean race = false;
        boolean modelCheck = false;
        String entry = null;
        String replayArg = null;
        String traceArg = null;
        String mode = dev.oreslang.runtime.NativeBuildMode.defaultExecutionMode();
        String platform = "server";
        int iterations = 1;
        long seed = 1;

        try {
            for (String arg : args) {
                if (arg.equals("--deadlock")) deadlock = true;
                else if (arg.equals("--fuzz")) fuzz = true;
                else if (arg.equals("--schedule-fuzz")) scheduleFuzz = true;
                else if (arg.equals("--race")) race = true;
                else if (arg.equals("--model-check")) modelCheck = true;
                else if (arg.startsWith("--seed=")) seed = Long.parseLong(arg.substring(7));
                else if (arg.startsWith("--iterations="))
                    iterations = Integer.parseInt(arg.substring("--iterations=".length()));
                else if (arg.startsWith("--replay=")) replayArg = arg.substring("--replay=".length());
                else if (arg.startsWith("--trace-output=")) traceArg = arg.substring("--trace-output=".length());
                else if (arg.startsWith("--mode=")) mode = arg.substring("--mode=".length());
                else if (arg.startsWith("--platform=")) platform = arg.substring("--platform=".length());
                else if (arg.startsWith("--")) throw new IllegalArgumentException("unknown test flag: " + arg);
                else if (entry == null) entry = arg;
                else throw new IllegalArgumentException("only one test entrypoint can be supplied");
            }
            if (entry == null || !entry.endsWith(".ores")) {
                throw new IllegalArgumentException("test requires a .ores entrypoint using stdlib/testing.ores");
            }
            if (iterations < 1 || iterations > 1_000) {
                throw new IllegalArgumentException("--iterations must be between 1 and 1000");
            }
            if (race) {
                throw new IllegalArgumentException("--race requires guest memory-access and happens-before instrumentation; not implemented yet (fail closed)");
            }
            if (modelCheck) {
                throw new IllegalArgumentException("--model-check requires a bounded guest transition model; not implemented yet (fail closed)");
            }
            if (replayArg != null && (iterations != 1 || fuzz || scheduleFuzz)) {
                throw new IllegalArgumentException("--replay is one exact trace; cannot combine with fuzzing or multiple runs");
            }
        } catch (IllegalArgumentException invalid) {
            System.err.println("oreslang test: " + invalid.getMessage());
            return 2;
        }

        Path entryPath = Path.of(entry);
        if (!Files.isRegularFile(entryPath)) {
            System.err.println("oreslang test: not a file: " + entryPath);
            return 2;
        }
        List<String> replay = replayArg == null ? null
                : ConcurrencyTestProbe.readTrace(Path.of(replayArg));
        ExecutionProfile profile = ExecutionProfile.parse(mode, platform);
        IsolatePolicy policy = IsolatePolicy.developer();
        for (int iteration = 0; iteration < iterations; iteration++) {
            long runSeed = seed + iteration;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Map<String, String> environment = new HashMap<>(System.getenv());
            environment.put("ORES_TEST_SEED", Long.toString(runSeed));
            int result;
            int detected;
            try (ConcurrencyTestProbe.Session session = ConcurrencyTestProbe.start(
                    runSeed, scheduleFuzz || fuzz, replay)) {
                var executed = LinkedProgramRunner.runWithExitStatus(
                        entryPath,
                        policy,
                        RuntimePermissions.fromCapabilities(policy.capabilities()),
                        PermissionCheckMode.COMPILE,
                        profile,
                        Set.of(),
                        environment,
                        out,
                        err);
                result = executed.exitStatus();
                session.verifyReplayComplete();
                detected = session.detectedDeadlocks();
                String text = out.toString(StandardCharsets.UTF_8);
                System.out.print(text);
                System.err.print(err.toString(StandardCharsets.UTF_8));
                if (!text.contains("ORES_TEST|SUMMARY|")) {
                    System.err.println("oreslang test: native test summary missing; this is not a passing test suite");
                    result = 1;
                }
                if (deadlock && detected > 0) {
                    System.err.println("ORES_CONCURRENCY|DEADLOCK|count=" + detected);
                    result = 1;
                }
                if (traceArg != null || result != 0) {
                    Path trace = traceArg != null ? Path.of(traceArg) :
                            Path.of(".oreslang", "failures", "concurrency-" + runSeed + ".trace");
                    Path parent = trace.toAbsolutePath().getParent();
                    if (parent != null) Files.createDirectories(parent);
                    session.writeTrace(trace);
                    System.err.println("ORES_CONCURRENCY|TRACE|" + trace);
                }
                System.out.println("ORES_CONCURRENCY|RUN|seed=" + runSeed
                        + "|events=" + session.eventCount()
                        + "|deadlocks=" + detected
                        + "|schedule=" + (scheduleFuzz || fuzz ? "perturbed" : "normal")
                        + "|race_coverage=none");
            }
            if (result != 0) return result;
        }
        return 0;
    }
}
