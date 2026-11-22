package dev.oreslang.launcher;

import dev.oreslang.runtime.ConcurrencyTestProbe;
import dev.oreslang.runtime.ConcurrencySourceFingerprint;
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
        boolean seedSpecified = false;

        try {
            for (String arg : args) {
                if (arg.equals("--deadlock")) deadlock = true;
                else if (arg.equals("--fuzz")) fuzz = true;
                else if (arg.equals("--schedule-fuzz")) scheduleFuzz = true;
                else if (arg.equals("--race")) race = true;
                else if (arg.equals("--model-check")) modelCheck = true;
                else if (arg.startsWith("--seed=")) {
                    seed = Long.parseLong(arg.substring(7));
                    seedSpecified = true;
                }
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
            if (traceArg != null && iterations != 1) {
                throw new IllegalArgumentException("--trace-output requires one iteration; otherwise traces overwrite each other");
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
        if (traceArg != null) {
            Path traceOutput = Path.of(traceArg);
            if (Files.isSymbolicLink(traceOutput)) {
                System.err.println("oreslang test: trace output cannot be a symbolic link");
                return 2;
            }
            if (entryPath.toAbsolutePath().normalize()
                    .equals(traceOutput.toAbsolutePath().normalize())
                    || (Files.exists(traceOutput) && Files.isSameFile(entryPath, traceOutput))) {
                System.err.println("oreslang test: trace output cannot overwrite the test entrypoint");
                return 2;
            }
            if (replayArg != null) {
                Path replayInput = Path.of(replayArg);
                if (traceOutput.toAbsolutePath().normalize()
                        .equals(replayInput.toAbsolutePath().normalize())
                        || (Files.exists(traceOutput) && Files.exists(replayInput)
                                && Files.isSameFile(traceOutput, replayInput))) {
                    System.err.println("oreslang test: trace output cannot overwrite the replay input");
                    return 2;
                }
            }
        }
        // This is the entry file digest, not a full linked-import closure hash.
        // Replaying a program with changed entry bytes is always refused.
        if (Files.size(entryPath) > 8L * 1024 * 1024) {
            System.err.println("oreslang test: entry file exceeds 8 MiB provenance limit");
            return 2;
        }
        String entrySha256 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(entryPath)));
        String backendId = mode + "-" + platform;
        String workspaceSha256;
        try {
            workspaceSha256 = ConcurrencySourceFingerprint.ofWorkspace(
                    Path.of("").toAbsolutePath());
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            System.err.println("oreslang test: source workspace fingerprint failed: "
                    + invalid.getMessage());
            return 2;
        }
        if (!backendId.matches("[A-Za-z0-9_.-]{1,80}")) {
            System.err.println("oreslang test: invalid backend provenance identity");
            return 2;
        }
        List<String> replay = null;
        if (replayArg != null) {
            var trace = ConcurrencyTestProbe.readTraceData(Path.of(replayArg));
            if (trace.workspaceSha256() == null
                    || !entrySha256.equals(trace.entrySha256())
                    || !backendId.equals(trace.backend())
                    || !workspaceSha256.equals(trace.workspaceSha256())) {
                System.err.println("oreslang test: replay entry/workspace/backend mismatch or unbound legacy trace");
                return 2;
            }
            if (seedSpecified && seed != trace.seed()) {
                System.err.println("oreslang test: replay seed differs from recorded trace seed");
                return 2;
            }
            seed = trace.seed();
            replay = trace.events();
        }
        if (iterations > 1 && seed > Long.MAX_VALUE - (iterations - 1L)) {
            System.err.println("oreslang test: seed range overflows signed 64-bit integer");
            return 2;
        }
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
                    runSeed, scheduleFuzz || fuzz, replay,
                    entrySha256, backendId, workspaceSha256)) {
                try {
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
                    if (result == 0) session.verifyReplayComplete();
                } catch (Exception failure) {
                    // Preserve the event prefix on compile, runtime and replay
                    // failures instead of discarding it on exception unwind.
                    result = 1;
                    System.err.println("oreslang test: " + failure.getClass().getSimpleName()
                            + ": " + failure.getMessage());
                }
                detected = session.detectedDeadlocks();
                String text = out.toString(StandardCharsets.UTF_8);
                System.out.print(text);
                System.err.print(err.toString(StandardCharsets.UTF_8));
                if (result == 0 && !validNativeSuiteSummary(text)) {
                    System.err.println("oreslang test: missing, malformed or inconsistent native test summary");
                    result = 1;
                }
                // A cycle observed by the runtime is a failing test run even
                // without --deadlock; that flag cannot weaken safety checks.
                if (detected > 0) {
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

    /** One independently consistent native test-suite receipt per invocation. */
    static boolean validNativeSuiteSummary(String output) {
        java.util.regex.Pattern summary = java.util.regex.Pattern.compile(
                "ORES_TEST\\|SUMMARY\\|total=(\\d+)\\|passed=(\\d+)"
                + "\\|failed=(\\d+)\\|skipped=(\\d+)\\|filtered=(\\d+)");
        long[] counted = new long[3];
        int seen = 0;
        long total = -1, passed = -1, failed = -1, skipped = -1;
        for (String line : output.lines().toList()) {
            // A native suite must emit case records before the one final
            // summary. Unknown protocol records cannot be silently ignored.
            if (line.startsWith("ORES_TEST|") && seen != 0) return false;
            if (line.startsWith("ORES_TEST|PASS|")) {
                if (line.length() <= "ORES_TEST|PASS|".length()) return false;
                counted[0]++;
            } else if (line.startsWith("ORES_TEST|FAIL|")) {
                if (line.length() <= "ORES_TEST|FAIL|".length()) return false;
                counted[1]++;
            } else if (line.startsWith("ORES_TEST|SKIP|")) {
                if (line.length() <= "ORES_TEST|SKIP|".length()) return false;
                counted[2]++;
            } else if (line.startsWith("ORES_TEST|SUMMARY|")) {
                var match = summary.matcher(line);
                if (!match.matches()) return false;
                if (++seen > 1) return false;
                try {
                    total = Long.parseLong(match.group(1));
                    passed = Long.parseLong(match.group(2));
                    failed = Long.parseLong(match.group(3));
                    skipped = Long.parseLong(match.group(4));
                    long filtered = Long.parseLong(match.group(5));
                    if (total < 1 || passed < 1 || failed != 0 || skipped < 0 || filtered < 0
                            || total != passed + failed + skipped) return false;
                } catch (NumberFormatException malformed) {
                    return false;
                }
            } else if (line.startsWith("ORES_TEST|")) {
                return false;
            }
        }
        return seen == 1 && counted[0] == passed
                && counted[1] == failed && counted[2] == skipped
                && counted[0] + counted[1] + counted[2] == total;
    }
}
