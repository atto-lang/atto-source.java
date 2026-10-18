package dev.oreslang.launcher;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed native test entry point. The test body and suite orchestration
 * remain Oreslang source (stdlib/testing.ores), not Java/JUnit.
 *
 * --race and --fuzz are RESERVED until OresVM can prove their instrumentation
 * and replay contracts; accepting either and running ordinary tests would give
 * users a false safety assurance.
 */
final class OresTestCommand {
    private static final Pattern SUMMARY = Pattern.compile(
            "(?m)^ORES_TEST\\|SUMMARY\\|total=(\\d+)\\|passed=(\\d+)"
            + "\\|failed=(\\d+)\\|skipped=(\\d+)\\|filtered=(\\d+)\\r?$");
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
        for (String arg : args) {
            if (arg.equals("--race") || arg.equals("--fuzz")
                    || arg.startsWith("--fuzz=") || arg.startsWith("--seed=")
                    || arg.startsWith("--runs=") || arg.startsWith("--replay=")) {
                System.err.println("oreslang test: " + arg + " requires OresVM "
                        + "instrumented race/schedule-fuzz runtime and trace replay; "
                        + "not implemented (no uninstrumented fallback)");
                return 2;
            }
            if (arg.startsWith("-")) {
                System.err.println("oreslang test: unknown option: " + arg);
                return 2;
            }
            if (file != null) {
                System.err.println("oreslang test: supply exactly one .ores test entrypoint");
                return 2;
            }
            try {
                file = Path.of(arg);
            } catch (java.nio.file.InvalidPathException badPath) {
                System.err.println("oreslang test: invalid test path");
                return 2;
            }
        }
        if (file == null || !file.toString().endsWith(".ores")
                || !Files.isRegularFile(file)) {
            System.err.println("usage: oreslang test <file.ores> (test file required)");
            return 2;
        }

        // Native Oreslang source must instantiate TestSuite, use
        // TestOptions(..., machine=true), run_suite and require_success.
        // Capture bounded output to validate the authenticated command path
        // before the CLI reports success; output is emitted afterward.
        ByteArrayOutputStream out = new BoundedCapture();
        ByteArrayOutputStream err = new BoundedCapture();
        var result = LinkedProgramRunner.runWithExitStatus(
                file, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                out, err);
        if (out.size() > MAX_CAPTURE_BYTES || err.size() > MAX_CAPTURE_BYTES) {
            System.err.println("oreslang test: output exceeds 16 MiB reporting limit");
            return 1;
        }
        System.out.write(out.toByteArray());
        System.err.write(err.toByteArray());
        if (result.exitStatus() != 0) return result.exitStatus();

        Matcher matches = SUMMARY.matcher(out.toString(StandardCharsets.UTF_8));
        int summaries = 0;
        boolean failed = false;
        int tested = 0;
        while (matches.find()) {
            summaries++;
            int total = Integer.parseInt(matches.group(1));
            int passed = Integer.parseInt(matches.group(2));
            int failures = Integer.parseInt(matches.group(3));
            int skipped = Integer.parseInt(matches.group(4));
            if (total <= 0 || passed + failures + skipped != total) {
                failed = true;
            }
            tested += total;
            failed |= failures > 0;
        }
        if (summaries == 0 || tested == 0) {
            System.err.println("oreslang test: no native ORES_TEST summary; "
                    + "call run_suite with machine=true and require_success");
            return 1;
        }
        return failed ? 1 : 0;
    }
}
