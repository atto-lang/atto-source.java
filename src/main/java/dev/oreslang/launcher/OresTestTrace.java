package dev.oreslang.launcher;

import dev.oreslang.runtime.OresTestExploration;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.HashMap;

/**
 * Replay artifact for the *bounded runtime CriticalGate input explorer*.
 * This is NOT a record of arbitrary guest scheduling or VM nondeterminism.
 */
final class OresTestTrace {
    private static final String HEADER = "oreslang-critical-exploration-v2";
    record Data(String closureHash, long seed, int runs, boolean modelCheck,
                long states, long rejectedCycles, long stressCases,
                long inputFingerprint) { }

    private OresTestTrace() { }

    /**
     * Digest the complete compiler import closure. The v1 trace format only
     * fingerprinted the entry file and is intentionally not replay-compatible.
     */
    static String hash(Path source) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.size(source) > 16L * 1024 * 1024) {
            throw new IOException("source is not a bounded regular file for replay");
        }
        try {
            return LinkedProgramRunner.sourceClosureSha256(source);
        } catch (IllegalArgumentException | SecurityException invalid) {
            throw new IOException("could not fingerprint source closure: "
                    + invalid.getMessage(), invalid);
        }
    }

    static void write(Path output, Path source, long seed, int runs, boolean modelCheck,
                      OresTestExploration.Report report) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("trace already exists; never overwrite a failure artifact");
        }
        validateNumbers(runs, modelCheck, report.exploredStates(),
                report.rejectedCycles(), report.stressCases());
        String encoded = HEADER + "\n"
                + "source_closure_sha256=" + hash(source) + "\n"
                + "seed=" + seed + "\n"
                + "runs=" + runs + "\n"
                + "model_check=" + modelCheck + "\n"
                + "states=" + report.exploredStates() + "\n"
                + "rejected_cycles=" + report.rejectedCycles() + "\n"
                + "stress_cases=" + report.stressCases() + "\n"
                + "input_fingerprint=" + report.inputFingerprint() + "\n";
        Files.writeString(output, encoded, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static Data read(Path input, Path source) throws IOException {
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a bounded regular critical exploration trace");
        }
        // Bound the actual read, not just a separate Files.size() check:
        // a concurrent writer must not bypass the 4 KiB trace limit.
        final byte[] traceBytes;
        try (java.io.InputStream traceInput = Files.newInputStream(input)) {
            traceBytes = traceInput.readNBytes(4096 + 1);
        }
        if (traceBytes.length > 4096) {
            throw new IOException("not a bounded regular critical exploration trace");
        }
        final String content = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(traceBytes)).toString();
        String[] lines = content.split("\n", -1);
        if (lines.length != 10 || !HEADER.equals(lines[0]) || !lines[9].isEmpty()) {
            throw new IOException("unsupported trace header or malformed field count");
        }
        Map<String,String> entries = new HashMap<>();
        for (int i = 1; i <= 8; i++) {
            String[] parts = lines[i].split("=", 2);
            if (parts.length != 2 || entries.putIfAbsent(parts[0], parts[1]) != null) {
                throw new IOException("duplicate/invalid trace field");
            }
        }
        if (!entries.keySet().equals(java.util.Set.of("source_closure_sha256","seed","runs",
                "model_check","states","rejected_cycles","stress_cases",
                "input_fingerprint"))) {
            throw new IOException("unknown or missing trace field");
        }
        try {
            String hash = entries.get("source_closure_sha256");
            if (!hash.matches("[0-9a-f]{64}") || !hash.equals(hash(source))) {
                throw new IOException("source closure SHA-256 differs; replay requires exact imports and bytes");
            }
            String modelFlag = entries.get("model_check");
            if (!modelFlag.equals("true") && !modelFlag.equals("false")) {
                throw new IOException("invalid model flag");
            }
            int runs = Integer.parseInt(entries.get("runs"));
            boolean modelCheck = Boolean.parseBoolean(modelFlag);
            long states = Long.parseLong(entries.get("states"));
            long rejectedCycles = Long.parseLong(entries.get("rejected_cycles"));
            long stressCases = Long.parseLong(entries.get("stress_cases"));
            validateNumbers(runs, modelCheck, states, rejectedCycles, stressCases);
            return new Data(hash, Long.parseLong(entries.get("seed")), runs,
                    modelCheck, states, rejectedCycles, stressCases,
                    Long.parseLong(entries.get("input_fingerprint")));
        } catch (NumberFormatException bad) {
            throw new IOException("invalid numeric trace field", bad);
        }
    }

    /**
     * A trace is a recording of successfully completed, bounded input probes.
     * Reject contradictory metadata rather than replaying a different mode or
     * silently truncating what the trace claims to cover.
     */
    private static void validateNumbers(int runs, boolean modelCheck,
                                        long states, long rejectedCycles,
                                        long stressCases) throws IOException {
        if (runs < 0 || runs > 100_000 || stressCases != runs
                || states < 0 || rejectedCycles < 0
                || (modelCheck && (states == 0 || rejectedCycles == 0))
                || (!modelCheck && (runs == 0
                        || states != 0 || rejectedCycles != 0))) {
            throw new IOException("inconsistent critical exploration trace metadata");
        }
    }

    static void verify(Data recorded, OresTestExploration.Report observed) throws IOException {
        if (recorded.states() != observed.exploredStates()
                || recorded.rejectedCycles() != observed.rejectedCycles()
                || recorded.stressCases() != observed.stressCases()
                || recorded.inputFingerprint() != observed.inputFingerprint()) {
            throw new IOException("bounded exploration trace is not reproducible");
        }
    }
}
