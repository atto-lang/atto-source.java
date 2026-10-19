package dev.oreslang.launcher;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fail-closed native test entry point. The test body and suite orchestration
 * remain Oreslang source (stdlib/testing.ores), not Java/JUnit.
 *
 * --race remains unavailable until OresVM has true memory instrumentation.
 * --fuzz accepts only real native seeded integer property generators;
 * bounded CriticalGate stress must not be represented as guest schedule fuzzing.
 */
final class OresTestCommand {
    private static final int MAX_CAPTURE_BYTES = 16 * 1024 * 1024;

    /** Refuse oversized guest output before allocating an unbounded buffer. */
    private static final class BoundedCapture extends ByteArrayOutputStream {
        @Override
        public synchronized void write(int value) {
            if (count >= MAX_CAPTURE_BYTES) {
                throw new IllegalStateException("oreslang test output exceeds 16 MiB");
            }
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length > MAX_CAPTURE_BYTES - count) {
                throw new IllegalStateException("oreslang test output exceeds 16 MiB");
            }
            super.write(bytes, offset, length);
        }
    }

    private OresTestCommand() { }

    static int run(String[] args) throws Exception {
        Path file = null;
        Path replay = null;
        Path record = null;
        boolean race = false, fuzz = false, deadlock = false;
        boolean scheduleFuzz = false, modelCheck = false;
        long seed = 1L;
        int runs = 256;
        boolean seedSpecified = false, runsSpecified = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--race")) race = true;
            else if (arg.equals("--fuzz")) fuzz = true;
            else if (arg.equals("--deadlock")) deadlock = true;
            else if (arg.equals("--schedule-fuzz")) scheduleFuzz = true;
            else if (arg.equals("--model-check")) modelCheck = true;
            else if (arg.equals("--replay") || arg.equals("--trace-out")) {
                if (++i >= args.length) {
                    System.err.println("oreslang test: " + arg + " requires a file");
                    return 2;
                }
                if (arg.equals("--replay")) {
                    if (replay != null) return usage("duplicate --replay");
                    replay = Path.of(args[i]);
                } else {
                    if (record != null) return usage("duplicate --trace-out");
                    record = Path.of(args[i]);
                }
            } else if (arg.startsWith("--replay=")) {
                if (replay != null) return usage("duplicate --replay");
                replay = Path.of(arg.substring("--replay=".length()));
            } else if (arg.startsWith("--trace-out=")) {
                if (record != null) return usage("duplicate --trace-out");
                record = Path.of(arg.substring("--trace-out=".length()));
            } else if (arg.startsWith("--seed=")) {
                try { seed = Long.parseLong(arg.substring(7)); seedSpecified = true; }
                catch (NumberFormatException bad) { return usage("invalid --seed"); }
            } else if (arg.startsWith("--runs=")) {
                try { runs = Integer.parseInt(arg.substring(7)); runsSpecified = true; }
                catch (NumberFormatException bad) { return usage("invalid --runs"); }
                if (runs < 1 || runs > 100_000) {
                    return usage("--runs must be between 1 and 100000");
                }
            } else if (arg.startsWith("-")) {
                return usage("unknown option: " + arg);
            } else if (file != null) {
                return usage("supply exactly one .ores test entrypoint");
            } else {
                try { file = Path.of(arg); }
                catch (java.nio.file.InvalidPathException bad) {
                    return usage("invalid source path");
                }
            }
        }

        if (file == null || !file.toString().endsWith(".ores")
                || !Files.isRegularFile(file)) {
            return usage("expected a regular .ores test file");
        }
        if (record != null && replay != null) {
            return usage("--trace-out cannot be combined with --replay");
        }
        if (record != null && !(fuzz || scheduleFuzz || modelCheck || deadlock)) {
            return usage("--trace-out requires a bounded exploration mode");
        }

        // A true race detector needs instrumented guest reads/writes and
        // happens-before epochs. These cannot be inferred from repeated tests
        // or JVM ThreadMXBean results. Never return a false clean verdict.
        if (race) {
            return unsupported("--race", "guest shadow-memory and happens-before tracking "
                    + "is not implemented; see Java issue #1417");
        }

        OresTestTrace.Data replayData = null;
        if (replay != null) {
            if (fuzz || deadlock || scheduleFuzz || modelCheck
                    || seedSpecified || runsSpecified) {
                return usage("--replay owns the seed, count, and exploration mode");
            }
            try {
                replayData = OresTestTrace.read(replay, file);
                seed = replayData.seed();
                runs = replayData.runs();
                modelCheck = replayData.modelCheck();
            } catch (java.io.IOException invalid) {
                return usage("invalid replay: " + invalid.getMessage());
            }
        }

        // Trace reproducibility needs the complete reachable source closure
        // to remain stable across guest execution and runtime stress.
        // This snapshot does not capture scheduler nondeterminism.
        String sourceHashBefore = null;
        if (replayData != null || record != null) {
            try {
                sourceHashBefore = OresTestTrace.hash(file);
                if (replayData != null
                        && !sourceHashBefore.equals(replayData.closureHash())) {
                    return usage("source closure changed after trace validation");
                }
            } catch (java.io.IOException changed) {
                return usage("cannot snapshot replay source: " + changed.getMessage());
            }
        }

        // Native TestSuite orchestration always runs guest Oreslang.
        ByteArrayOutputStream out = new BoundedCapture();
        ByteArrayOutputStream err = new BoundedCapture();
        // Verified tests must compile the exact source-unit snapshots whose
        // closure digest matched the preflight trace. A source rewrite after
        // the preflight check is rejected before guest compilation/execution.
        LinkedProgramRunner.RunResult result;
        if (sourceHashBefore != null) {
            try {
                result = LinkedProgramRunner.runWithExitStatusVerified(
                        file, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                        sourceHashBefore, out, err);
            } catch (java.io.IOException changedClosure) {
                return usage("cannot execute verified source closure: "
                        + changedClosure.getMessage());
            }
        } else {
            result = LinkedProgramRunner.runWithExitStatus(
                    file, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    out, err);
        }
        System.out.write(out.toByteArray());
        System.err.write(err.toByteArray());
        if (result.exitStatus() != 0) return result.exitStatus();
        if (sourceHashBefore != null) {
            try {
                if (!sourceHashBefore.equals(OresTestTrace.hash(file))) {
                    return usage("source closure changed during native guest execution; "
                            + "refusing to record/replay an ambiguous trace");
                }
            } catch (java.io.IOException changed) {
                return usage("cannot verify replay source after execution: "
                        + changed.getMessage());
            }
        }

        String guestOutput = out.toString(StandardCharsets.UTF_8);
        final OresTestReportParser.Suite suite;
        try {
            suite = OresTestReportParser.parseSuite(guestOutput);
        } catch (IllegalArgumentException invalidReport) {
            System.err.println("oreslang test: " + invalidReport.getMessage());
            return 1;
        }
        if (suite.failed() > 0) return 1;

        if (fuzz) {
            // The suite must run native fuzz_int_cases and produce bounded,
            // consistent input counts. CriticalGate stress alone is not
            // guest-language input fuzzing.
            final OresTestReportParser.FuzzCoverage coverage;
            try {
                coverage = OresTestReportParser.parseFuzz(
                        guestOutput,
                        seedSpecified ? Long.valueOf(seed) : null,
                        runsSpecified ? Integer.valueOf(runs) : null);
            } catch (IllegalArgumentException invalidFuzz) {
                return unsupported("--fuzz", invalidFuzz.getMessage());
            }
            System.out.println("ORES_TEST|INPUT_FUZZ|scope=seeded-int-property"
                    + "|generators=" + coverage.generators()
                    + "|generated=" + coverage.generatedInputs()
                    + "|coverage_guided=false");
        }

        if (fuzz || deadlock || scheduleFuzz || modelCheck || replayData != null) {
            // This probes *actual* runtime CriticalGate transitions, and a
            // separate finite two-task/two-lock reference model. It does NOT
            // fuzz arbitrary guest inputs or enumerate all guest schedules.
            boolean explore = modelCheck || deadlock;
            int stress = (fuzz || scheduleFuzz || replayData != null) ? runs : 0;
            dev.oreslang.runtime.OresTestExploration.Report report;
            try {
                report = dev.oreslang.runtime.OresTestExploration.run(seed, stress, explore);
            } catch (RuntimeException | AssertionError failedProbe) {
                System.err.println("oreslang test: critical runtime exploration failed, seed="
                        + seed + ": " + failedProbe.getMessage());
                return 1;
            }
            System.out.println("ORES_TEST|EXPLORATION|scope=critical-gate-runtime"
                    + "|seed=" + seed + "|states=" + report.exploredStates()
                    + "|cycle_rejections=" + report.rejectedCycles()
                    + "|stress_cases=" + report.stressCases()
                    + "|input_fingerprint=" + report.inputFingerprint()
                    + "|waiting=" + report.strandedWaiters()
                    + "|jvm_deadlocks=" + report.jvmDeadlockedThreads());
            if ((explore && report.rejectedCycles() == 0)
                    || report.strandedWaiters() != 0 || report.jvmDeadlockedThreads() != 0) {
                return 1;
            }
            if (replayData != null) {
                try {
                    OresTestTrace.verify(replayData, report);
                } catch (java.io.IOException mismatch) {
                    return usage("exploration replay mismatch: " + mismatch.getMessage());
                }
                System.out.println("ORES_TEST|REPLAY|scope=critical-gate-inputs|status=matched");
            }
            if (record != null) {
                try {
                    // Also check after runtime stress, before writing the
                    // trace. Write itself hashes once more and refuses to
                    // overwrite an existing trace file.
                    if (!sourceHashBefore.equals(OresTestTrace.hash(file))) {
                        return usage("source closure changed during runtime exploration");
                    }
                    OresTestTrace.write(record, file, seed, stress, explore, report);
                } catch (java.io.IOException invalid) {
                    return usage("could not save bounded exploration trace: "
                            + invalid.getMessage());
                }
                System.out.println("ORES_TEST|TRACE|scope=critical-gate-inputs|file=" + record);
            }
            // A successful result proves only the explicitly reported runtime
            // subset, not guest-program-wide concurrency correctness.
            if (fuzz || scheduleFuzz || modelCheck) {
                System.err.println("oreslang test: limited instrumentation: "
                        + "no arbitrary guest input fuzzing or guest schedule enumeration; "
                        + "scope=critical-gate-runtime");
            }
        }
        return 0;
    }

    private static int unsupported(String feature, String reason) {
        System.err.println("oreslang test: " + feature + " unavailable: " + reason
                + " (no uninstrumented fallback)");
        return 2;
    }

    private static int usage(String problem) {
        System.err.println("oreslang test: " + problem);
        return 2;
    }
}
