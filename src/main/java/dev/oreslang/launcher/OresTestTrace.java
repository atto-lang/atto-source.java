package dev.oreslang.launcher;

import dev.oreslang.runtime.OresTestExploration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashMap;

/**
 * Replay artifact for the *bounded runtime CriticalGate input explorer*.
 * This is NOT a record of arbitrary guest scheduling or VM nondeterminism.
 */
final class OresTestTrace {
    private static final String HEADER = "oreslang-critical-exploration-v1";
    record Data(String sourceHash, long seed, int runs, boolean modelCheck,
                long states, long rejectedCycles, long stressCases,
                long inputFingerprint) { }

    private OresTestTrace() { }

    static String hash(Path source) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.size(source) > 16L * 1024 * 1024) {
            throw new IOException("source is not a bounded regular file for replay");
        }
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(hash.digest(Files.readAllBytes(source)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 unavailable", missing);
        }
    }

    static void write(Path output, Path source, long seed, int runs, boolean modelCheck,
                      OresTestExploration.Report report) throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("trace already exists; never overwrite a failure artifact");
        }
        String encoded = HEADER + "\n"
                + "source_sha256=" + hash(source) + "\n"
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
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)
                || Files.size(input) > 4096) {
            throw new IOException("not a bounded regular critical exploration trace");
        }
        String content = Files.readString(input, StandardCharsets.UTF_8);
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
        if (!entries.keySet().equals(java.util.Set.of("source_sha256","seed","runs",
                "model_check","states","rejected_cycles","stress_cases",
                "input_fingerprint"))) {
            throw new IOException("unknown or missing trace field");
        }
        try {
            String hash = entries.get("source_sha256");
            if (!hash.matches("[0-9a-f]{64}") || !hash.equals(hash(source))) {
                throw new IOException("source SHA-256 differs; replay requires exact bytes");
            }
            String modelFlag = entries.get("model_check");
            if (!modelFlag.equals("true") && !modelFlag.equals("false")) {
                throw new IOException("invalid model flag");
            }
            int runs = Integer.parseInt(entries.get("runs"));
            if (runs < 0 || runs > 100_000) throw new IOException("invalid run count");
            return new Data(hash, Long.parseLong(entries.get("seed")), runs,
                    Boolean.parseBoolean(modelFlag),
                    Long.parseLong(entries.get("states")),
                    Long.parseLong(entries.get("rejected_cycles")),
                    Long.parseLong(entries.get("stress_cases")),
                    Long.parseLong(entries.get("input_fingerprint")));
        } catch (NumberFormatException bad) {
            throw new IOException("invalid numeric trace field", bad);
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
