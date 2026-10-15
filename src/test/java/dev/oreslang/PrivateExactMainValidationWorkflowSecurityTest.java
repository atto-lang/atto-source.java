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

        // Presence-only tests can be bypassed by retaining the safe string
        // inside a comment while changing the actual workflow trigger/guard.
        String triggers = between(text, "\non:\n", "\npermissions:");
        assertEquals("\non:\n  workflow_dispatch:\n", triggers,
                "workflow_dispatch must be the only event");
        assertEquals("\npermissions:\n  contents: read\n",
                between(text, "\npermissions:", "\nconcurrency:"),
                "the workflow token must be read-only");
        assertEquals(1, countLines(text, "permissions:"),
                "there must be only one global permissions block");
        assertFalse(java.util.regex.Pattern.compile("(?m)^[ \\t]+permissions[ \\t]*:")
                .matcher(text).find(),
                "job-level permissions may not override the read-only token");
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
        assertEquals(requiredDenial, jobProperty(denial, "if"),
                "the actual denial condition must match exactly, not a comment");
        assertEquals("runs-on: ubuntu-24.04", jobProperty(denial, "runs-on"));
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
        assertEquals(expectedGate, jobProperty(job, "if"),
                "the actual runner admission condition must match exactly");
        assertEquals("runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]",
                jobProperty(job, "runs-on"),
                "no hosted or shared-runner fallback is permitted");
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

    /** Extract the actual YAML job property at exactly four-space indentation. */
    @Test
    void disguisedOrDuplicatedAdmissionConditionsAreNotAccepted() throws Exception {
        String workflow = Files.readString(WORKFLOW);
        String job = workflow.substring(workflow.indexOf("  validate-exact-main:"));
        String reviewed = jobProperty(job, "if");

        // A presence-only assertion would accept the reviewed gate in a YAML
        // comment while letting the executable job run unconditionally.
        String permissive = job.replace("    " + reviewed + "\n",
                "    if: ${{ true }}\n    # " + reviewed + "\n");
        assertEquals("if: ${{ true }}", jobProperty(permissive, "if"));
        assertNotEquals(reviewed, jobProperty(permissive, "if"));

        String duplicate = job.replace("    " + reviewed + "\n",
                "    " + reviewed + "\n    " + reviewed + "\n");
        assertThrows(AssertionError.class, () -> jobProperty(duplicate, "if"),
                "duplicate conditions must not pass as a single reviewed guard");

        String injectedTrigger = workflow.replace("\non:\n",
                "\non:\n  pull_request:\n");
        assertNotEquals("\non:\n  workflow_dispatch:\n",
                between(injectedTrigger, "\non:\n", "\npermissions:"));
    }

    @Test
    void securityCriticalFilesMustRequestCodeOwnerReview() throws Exception {
        String owners = Files.readString(Path.of(".github/CODEOWNERS"));
        for (String exact : new String[] {
                "/.github/CODEOWNERS @ORESoftware",
                "/.github/workflows/private-exact-main-validation.yml @ORESoftware",
                "/src/test/java/dev/oreslang/PrivateExactMainValidationWorkflowSecurityTest.java @ORESoftware",
                "/docs/security/PRIVATE_EXACT_MAIN_VALIDATION.md @ORESoftware"
        }) {
            assertTrue(owners.lines().anyMatch(exact::equals),
                    "missing private-runner CODEOWNERS rule: " + exact);
        }
    }

    private static String jobProperty(String job, String property) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?m)^    " + java.util.regex.Pattern.quote(property) + ": ([^\\r\\n]+)$")
                .matcher(job);
        assertTrue(matcher.find(), "missing job property: " + property);
        String value = matcher.group(1);
        assertFalse(matcher.find(), "duplicate job property: " + property);
        return property + ": " + value;
    }

    private static long countLines(String text, String exact) {
        return java.util.regex.Pattern.compile(
                "(?m)^[ \\t]*" + java.util.regex.Pattern.quote(exact) + "$")
                .matcher(text).results().count();
    }

    private static String between(String text, String from, String until) {
        int start = text.indexOf(from);
        int end = text.indexOf(until, start + from.length());
        assertTrue(start >= 0 && end > start, "workflow structure changed unexpectedly");
        return text.substring(start, end);
    }

    private static String section(String text, String from, String until) {
        return between(text, from, until);
    }
}
