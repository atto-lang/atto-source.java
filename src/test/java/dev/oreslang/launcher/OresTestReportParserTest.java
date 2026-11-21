package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OresTestReportParserTest {
    @Test
    void validSuiteAndFuzzCounters() {
        var suite = OresTestReportParser.parseSuite(
                "ORES_TEST|PASS|math/add|\n"
                + "ORES_TEST|SUMMARY|total=2|passed=1|failed=0|skipped=1|filtered=3\n");
        assertEquals(2, suite.tested());
        assertEquals(0, suite.failed());
        assertEquals(1, suite.summaries());

        var fuzz = OresTestReportParser.parseFuzz(
                "ORES_TEST|FUZZ|seed=17|cases=97\n", 17L, 97);
        assertEquals(97, fuzz.generatedInputs());
        assertEquals(1, fuzz.generators());
    }

    @Test
    void failedSuitesRemainFailures() {
        var suite = OresTestReportParser.parseSuite(
                "ORES_TEST|SUMMARY|total=3|passed=1|failed=1|skipped=1|filtered=0\n");
        assertEquals(1, suite.failed());
    }

    @Test
    void rejectInvalidAndOverflowingSuiteSummaries() {
        for (String line : new String[] {
                "ORES_TEST|SUMMARY|total=0|passed=0|failed=0|skipped=0|filtered=0\n",
                "ORES_TEST|SUMMARY|total=1|passed=1|failed=1|skipped=0|filtered=0\n",
                "ORES_TEST|SUMMARY|total=1000001|passed=1000001|failed=0|skipped=0|filtered=0\n",
                "ORES_TEST|SUMMARY|total=9999999999999999999999999999999|passed=0|failed=0|skipped=0|filtered=0\n",
                "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=1000001\n",
                "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=0\n"
                    + "ORES_TEST|SUMMARY|total=1000000|passed=1000000|failed=0|skipped=0|filtered=0\n",
                "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=0\n"
                    + "ORES_TEST|SUMMARY|total=broken|passed=1|failed=0|skipped=0|filtered=0\n",
                "not a native test report\n"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresTestReportParser.parseSuite(line), line);
        }
    }

    @Test
    void rejectsTruncatedAndNearMissProtocolMarkersEvenWhenValidReportsExist() {
        String summary = "ORES_TEST|SUMMARY|total=1|passed=1|failed=0|skipped=0|filtered=0\n";
        String fuzz = "ORES_TEST|FUZZ|seed=17|cases=97\n";
        for (String malformed : new String[] {
                "ORES_TEST|SUMMARY",
                "ORES_TEST|SUMMARY garbage",
                "ORES_TEST|SUMMARY_BAD|total=1|passed=1|failed=0|skipped=0|filtered=0",
                "ORES_TEST|SUMMARY\rjunk"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresTestReportParser.parseSuite(summary + malformed + "\n"),
                    malformed);
        }
        for (String malformed : new String[] {
                "ORES_TEST|FUZZ",
                "ORES_TEST|FUZZ garbage",
                "ORES_TEST|FUZZ_BAD|seed=17|cases=97",
                "ORES_TEST|FUZZ\rjunk"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresTestReportParser.parseFuzz(fuzz + malformed + "\n", 17L, 97),
                    malformed);
        }
        // Unrelated native events are permitted: only SUMMARY and FUZZ
        // machine-record categories require these exact schemas.
        assertEquals(1L, OresTestReportParser.parseSuite(
                "ORES_TEST|PASS|example\n" + summary).tested());
        assertEquals(97L, OresTestReportParser.parseFuzz(
                "ORES_TEST|PASS|example\n" + fuzz, 17L, 97).generatedInputs());
    }

    @Test
    void rejectIncorrectGuestFuzzCountsAndSeeds() {
        String normal = "ORES_TEST|FUZZ|seed=17|cases=97\n";
        assertThrows(IllegalArgumentException.class,
                () -> OresTestReportParser.parseFuzz(normal, 17L, 16));
        assertThrows(IllegalArgumentException.class,
                () -> OresTestReportParser.parseFuzz(normal, 18L, 97));
        for (String line : new String[] {
                "ORES_TEST|FUZZ|seed=0|cases=0\n",
                "ORES_TEST|FUZZ|seed=65521|cases=97\n",
                "ORES_TEST|FUZZ|seed=17|cases=100001\n",
                "ORES_TEST|FUZZ|seed=17|cases=999999999999999999999999999999\n",
                "ORES_TEST|FUZZ|seed=17|cases=100000\n".repeat(11),
                "ORES_TEST|FUZZ|seed=17|cases=97\n"
                    + "ORES_TEST|FUZZ|seed=17|cases=nope\n",
                "ORES_TEST|PASS|fake/fuzz|\n"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresTestReportParser.parseFuzz(line, null, null), line);
        }
    }
}
