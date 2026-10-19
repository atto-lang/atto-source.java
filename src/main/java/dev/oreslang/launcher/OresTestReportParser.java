package dev.oreslang.launcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict, bounded parser of the native ORES_TEST line protocol.
 *
 * The protocol is guest-authored stdout; this validates formatting and
 * internal consistency, NOT the authenticity of who produced the lines.
 */
final class OresTestReportParser {
    private static final Pattern SUMMARY = Pattern.compile(
            "(?m)^ORES_TEST\\|SUMMARY\\|total=(\\d+)\\|passed=(\\d+)"
                    + "\\|failed=(\\d+)\\|skipped=(\\d+)\\|filtered=(\\d+)\\r?$");
    private static final Pattern FUZZ = Pattern.compile(
            "(?m)^ORES_TEST\\|FUZZ\\|seed=(\\d+)\\|cases=(\\d+)\\r?$");
    private static final long MAX_TOTAL = 1_000_000;
    private static final long MAX_FUZZ_PER_GENERATOR = 100_000;

    record Suite(long tested, long failed, int summaries) { }
    record FuzzCoverage(long generatedInputs, int generators) { }

    private OresTestReportParser() { }

    static Suite parseSuite(String output) {
        rejectMalformedLines(output, "ORES_TEST|SUMMARY", SUMMARY);
        Matcher matches = SUMMARY.matcher(output);
        long tested = 0;
        long failures = 0;
        int summaries = 0;
        while (matches.find()) {
            summaries++;
            long total = positiveNumber(matches.group(1), "total");
            long passed = positiveNumber(matches.group(2), "passed");
            long failed = positiveNumber(matches.group(3), "failed");
            long skipped = positiveNumber(matches.group(4), "skipped");
            long filtered = positiveNumber(matches.group(5), "filtered");
            if (total == 0 || total > MAX_TOTAL
                    || passed > total || failed > total || skipped > total
                    || passed + failed + skipped != total
                    || filtered > MAX_TOTAL || tested > MAX_TOTAL - total) {
                throw new IllegalArgumentException("inconsistent native test summary counts");
            }
            tested += total;
            failures += failed;
        }
        if (summaries == 0 || tested == 0) {
            throw new IllegalArgumentException("no native ORES_TEST summary; "
                    + "call run_suite with machine=true and require_success");
        }
        return new Suite(tested, failures, summaries);
    }

    static FuzzCoverage parseFuzz(String output, Long requestedSeed, Integer requestedRuns) {
        rejectMalformedLines(output, "ORES_TEST|FUZZ", FUZZ);
        Matcher matches = FUZZ.matcher(output);
        long inputs = 0;
        int generators = 0;
        while (matches.find()) {
            long seed = positiveNumber(matches.group(1), "seed");
            long cases = positiveNumber(matches.group(2), "cases");
            if (seed > 65520 || cases < 1 || cases > MAX_FUZZ_PER_GENERATOR
                    || (requestedSeed != null && requestedSeed.longValue() != seed)
                    || (requestedRuns != null && requestedRuns.intValue() != cases)
                    || inputs > MAX_TOTAL - cases) {
                throw new IllegalArgumentException(
                        "native fuzz seed/cases differ from requested --seed/--runs or exceed limits");
            }
            inputs += cases;
            generators++;
        }
        if (generators == 0) {
            throw new IllegalArgumentException(
                    "native suite did not execute a fuzz_int_cases property generator");
        }
        return new FuzzCoverage(inputs, generators);
    }

    private static void rejectMalformedLines(String output, String prefix, Pattern expected) {
        // A valid report mixed with a malformed second event must never
        // bypass validation merely because Matcher.find() skips bad lines.
        // Include truncated/near-miss category tags without the final '|'.
        // Other legitimate categories (PASS, FAIL, etc.) remain unaffected.
        for (String line : output.split("\\n", -1)) {
            if (line.startsWith(prefix) && !expected.matcher(line).matches()) {
                throw new IllegalArgumentException("malformed native test protocol line");
            }
        }
    }

    private static long positiveNumber(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "malformed or overflowing ORES_TEST " + field, invalid);
        }
    }
}
