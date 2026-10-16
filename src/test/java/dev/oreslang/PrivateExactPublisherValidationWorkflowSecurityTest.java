package dev.oreslang;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Fail-closed static contract for the review-only private publisher runner. */
final class PrivateExactPublisherValidationWorkflowSecurityTest {
    private static final Path WORKFLOW = Path.of(
            ".github/workflows/private-exact-publisher-validation.yml");

    @Test
    void onlyApprovedProtectedPrivateMainMayAdmitPublisherTests() throws Exception {
        String text = Files.readString(WORKFLOW);
        String triggers = between(text, "\non:\n", "\npermissions:");
        assertTrue(triggers.contains("  workflow_dispatch:"));
        assertFalse(triggers.contains("pull_request:"));
        assertFalse(triggers.contains("push:"));
        assertEquals("\npermissions:\n  contents: read\n",
                between(text, "\npermissions:", "\nconcurrency:"));
        assertEquals(2L, Pattern.compile("(?m)^  [a-z][a-z0-9-]*:$")
                .matcher(text.substring(text.indexOf("\njobs:") + 6))
                .results().count(), "all jobs must have explicit admission gates");

        String denial = between(text, "  deny-unapproved:",
                "  validate-reviewed-publisher:");
        String approval = text.substring(text.indexOf("  validate-reviewed-publisher:"));
        String denyIf = jobIf(denial);
        String runIf = jobIf(approval);
        for (String required : new String[] {
                "github.repository", "github.event.repository.id",
                "github.event.repository.private", "github.ref",
                "github.ref_protected",
                "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER",
                "vars.ORESLANG_PRIVATE_PUBLISHER_WORKFLOW_SHA",
                "vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_SHA",
                "vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_TREE" }) {
            assertTrue(denyIf.contains(required), "denial gate omitted " + required);
            assertTrue(runIf.contains(required), "positive gate omitted " + required);
        }
        assertTrue(denyIf.contains("github.event.repository.id != 1400792274"));
        assertTrue(runIf.contains("github.event.repository.id == 1400792274"));
        assertTrue(denyIf.contains("github.ref_protected != true"));
        assertTrue(runIf.contains("github.ref_protected == true"));
        assertTrue(runIf.contains(
                "vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_SHA == inputs.candidate_sha"));
        assertTrue(runIf.contains(
                "vars.ORESLANG_PRIVATE_PUBLISHER_WORKFLOW_SHA == github.sha"));
        assertTrue(approval.contains(
                "runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]"));
        assertFalse(text.contains("secrets."));
        assertFalse(text.contains("|| 'ubuntu-24.04'"));
    }

    @Test
    void exactTreeAndFullSuiteMustBeVerifiedWithNoSkippedTests() throws Exception {
        String text = Files.readString(WORKFLOW);
        String approval = text.substring(text.indexOf("  validate-reviewed-publisher:"));
        assertTrue(approval.contains(
                "actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683"));
        assertTrue(approval.contains("persist-credentials: false"));
        assertTrue(approval.contains("ref: ${{ inputs.candidate_sha }}"));
        assertTrue(approval.contains("git rev-parse --verify HEAD"));
        assertTrue(approval.contains("git rev-parse --verify HEAD^{tree}"));
        assertTrue(approval.contains("git status --porcelain --untracked-files=all"));
        assertTrue(approval.contains("bash scripts/test-private-ci-destination.sh"));
        assertTrue(approval.contains("spec_from_file_location"));
        assertTrue(approval.contains("loadTestsFromModule(module)"));
        assertTrue(approval.contains("suite.countTestCases()"));
        assertTrue(approval.contains("if count < 62:"));
        assertTrue(approval.contains("for round in 1 2 3; do"));
        assertTrue(approval.contains("result.testsRun != count"));
        assertTrue(approval.contains("not result.wasSuccessful()"));
        assertTrue(approval.contains("result.skipped"));
        assertTrue(approval.contains("result.expectedFailures"));
        assertTrue(approval.contains("result.unexpectedSuccesses"));
        assertTrue(approval.indexOf("Prove exact candidate commit")
                < approval.indexOf("Execute destination-policy fail-closed Bash suite"));
    }

    private static String jobIf(String job) {
        var matcher = Pattern.compile("(?m)^    if: ([^\\r\\n]+)$").matcher(job);
        assertTrue(matcher.find(), "missing admission condition");
        String result = matcher.group(1);
        assertFalse(matcher.find(), "multiple ambiguous admission conditions");
        return result;
    }

    private static String between(String text, String from, String until) {
        int begin = text.indexOf(from);
        int end = text.indexOf(until, begin + from.length());
        assertTrue(begin >= 0 && end > begin, "expected trusted workflow section missing");
        return text.substring(begin, end);
    }
}
