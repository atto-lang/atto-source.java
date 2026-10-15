package dev.oreslang;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Static guard against accidentally admitting unreviewed private source to a runner. */
final class PrivateExactMainValidationWorkflowSecurityTest {
    private static final Path WORKFLOW =
            Path.of(".github/workflows/private-exact-main-validation.yml");

    @Test
    void mainOnlyWorkflowMustRejectUnreviewedTriggersAndHostedFallback() throws Exception {
        String text = Files.readString(WORKFLOW);

        assertTrue(text.contains("on:\n  workflow_dispatch:\n"));
        assertFalse(text.contains("\n  pull_request:"));
        assertFalse(text.contains("\n  pull_request_target:"));
        assertFalse(text.contains("\n  push:"));
        assertFalse(text.contains("\n  schedule:"));
        assertTrue(text.contains("permissions:\n  contents: read"));
        assertTrue(text.contains("runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]"));
        assertFalse(text.contains("|| 'ubuntu-24.04'"),
                "private tests must not silently fall back to hosted jobs");

        String jobs = text.substring(text.indexOf("\njobs:\n") + "\njobs:\n".length());
        long declaredJobs = java.util.regex.Pattern
                .compile("(?m)^  [a-z][a-z0-9-]*:$")
                .matcher(jobs).results().count();
        assertEquals(2L, declaredJobs,
                "new unguarded jobs must require explicit security review");

        String denial = section(text, "  deny-unapproved:", "  validate-exact-main:");
        String requiredDenial =
                "if: ${{ github.repository != 'ores-truffle-oreslang/oreslang-source.java'"
                + " || github.event.repository.private != true"
                + " || github.ref != 'refs/heads/main'"
                + " || vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER != 'true'"
                + " || vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == ''"
                + " || vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA != github.sha"
                + " || vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE == '' }}";
        assertTrue(denial.contains(requiredDenial),
                "a rejected dispatch must never become a green skipped-job run");
        assertTrue(denial.contains("runs-on: ubuntu-24.04"));
        assertTrue(denial.contains("exit 1"));
        assertFalse(denial.contains("actions/checkout@"),
                "denial must never clone private source");
    }

    @Test
    void runnerJobNeedsExactCommitPrivateIdentityAndImmutableTree() throws Exception {
        String job = Files.readString(WORKFLOW);
        job = job.substring(job.indexOf("  validate-exact-main:"));

        String expectedGate =
                "if: ${{ github.repository == 'ores-truffle-oreslang/oreslang-source.java'"
                + " && github.event.repository.private == true"
                + " && github.ref == 'refs/heads/main'"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true'"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA != ''"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == github.sha"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE != '' }}";
        assertTrue(job.contains(expectedGate),
                "approval must be an exact, unconditional gate before runner admission");
        assertTrue(job.contains("test \"$GITHUB_SHA\" = \"$APPROVED_SHA\""));
        assertTrue(job.contains(
                "test \"$(git rev-parse HEAD^{tree})\" = \"$APPROVED_TREE\""));
        assertTrue(job.contains("persist-credentials: false"));
        assertTrue(job.contains("actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683"));
        assertTrue(job.indexOf("Verify source identity and immutable Git tree")
                < job.indexOf("graalvm/setup-graalvm@"));
    }

    @Test
    void requiredValidationPhasesMayNotBeDropped() throws Exception {
        String job = Files.readString(WORKFLOW);
        for (String required : new String[] {
                "ChannelCompletionPublicationIsolationTest",
                "FutureSelectAttachmentPublicationBarrierTest",
                "FutureChannelSelectTest",
                "ChannelCommittedCompletionDrainTest",
                "ChannelMappedCancellationStressTest",
                "OresFutureHostWaiterDetachmentTest",
                "mvn -B -ntp -Dores.runtime.carriers=native verify",
                "seq 1 10",
                "-Pnative-aot", "-Pnative-hybrid", "-Pnative-isolate",
                "examples/native-channel-select-smoke.ores",
                "examples/native-future-select-smoke.ores"
        }) {
            assertTrue(job.contains(required), "required gate missing: " + required);
        }
    }

    private static String section(String text, String from, String until) {
        int start = text.indexOf(from);
        int end = text.indexOf(until, start + from.length());
        assertTrue(start >= 0 && end > start, "workflow jobs changed unexpectedly");
        return text.substring(start, end);
    }
}
