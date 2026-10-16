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
                + " || github.event.repository.id != 1400792274"
                + " || github.event.repository.private != true"
                + " || github.ref != 'refs/heads/main'"
                + " || github.ref_protected != true"
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
                + " && github.event.repository.id == 1400792274"
                + " && github.event.repository.private == true"
                + " && github.ref == 'refs/heads/main'"
                + " && github.ref_protected == true"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true'"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA != ''"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == github.sha"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE != '' }}";
        assertEquals(expectedGate, jobProperty(job, "if"),
                "the actual runner admission condition must match exactly");
        assertEquals("runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]",
                jobProperty(job, "runs-on"),
                "no hosted or shared-runner fallback is permitted");
        assertTrue(hasExactLine(job, 10,
                "test \"$GITHUB_REPOSITORY_ID\" = '1400792274'"));
        assertTrue(hasExactLine(job, 10,
                "test \"$GITHUB_REF_PROTECTED\" = 'true'"));
        assertTrue(hasExactLine(job, 10,
                "[[ \"$GITHUB_SHA\" =~ ^[a-f0-9]{40}$ ]]"));
        assertTrue(hasExactLine(job, 10,
                "[[ \"$APPROVED_SHA\" =~ ^[a-f0-9]{40}$ ]]"));
        assertTrue(hasExactLine(job, 10,
                "[[ \"$APPROVED_TREE\" =~ ^[a-f0-9]{40}$ ]]"));
        assertTrue(hasExactLine(job, 10,
                "test \"$(git rev-parse --verify HEAD)\" = \"$GITHUB_SHA\""));
        assertTrue(hasExactLine(job, 10,
                "test -z \"$(git status --porcelain --untracked-files=all)\""));
        assertTrue(hasExactLine(job, 10,
                "test \"$GITHUB_SHA\" = \"$APPROVED_SHA\""),
                "the approved commit must be checked by an executable shell line");
        assertTrue(hasExactLine(job, 10,
                "test \"$(git rev-parse --verify HEAD^{tree})\" = \"$APPROVED_TREE\""),
                "the approved tree must be checked by an executable shell line");
        assertTrue(hasExactLine(job, 10, "persist-credentials: false"));
        assertTrue(hasExactLine(job, 6,
                "- uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4"));
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
    void commentedOutCommitOrTreeVerificationMustNotPass() throws Exception {
        String job = Files.readString(WORKFLOW);
        job = job.substring(job.indexOf("  validate-exact-main:"));
        for (String check : new String[] {
                "test \"$GITHUB_SHA\" = \"$APPROVED_SHA\"",
                "test \"$(git rev-parse --verify HEAD^{tree})\" = \"$APPROVED_TREE\"",
                "test \"$GITHUB_REPOSITORY_ID\" = '1400792274'",
                "test \"$GITHUB_REF_PROTECTED\" = 'true'",
                "test -z \"$(git status --porcelain --untracked-files=all)\""
        }) {
            assertTrue(hasExactLine(job, 10, check));
            String mutation = job.replace("          " + check + "\n",
                    "          # " + check + "\n");
            assertFalse(hasExactLine(mutation, 10, check),
                    "commented-out provenance checks must not count as executed");
        }
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

    @Test
    void approvedCandidateWorkflowCannotTestArbitraryBranch() throws Exception {
        String workflow = Files.readString(
                Path.of(".github/workflows/private-exact-candidate-validation.yml"));
        String head = between(workflow, "\non:\n", "\npermissions:");
        assertTrue(head.contains("  workflow_dispatch:\n    inputs:\n      candidate_sha:"));
        assertFalse(head.contains("pull_request:"));
        assertFalse(head.contains("pull_request_target:"));
        assertEquals("\npermissions:\n  contents: read\n",
                between(workflow, "\npermissions:", "\nconcurrency:"));
        assertEquals(1, countLines(workflow, "permissions:"));
        assertEquals(2, java.util.regex.Pattern.compile("(?m)^  [a-z][a-z0-9-]*:$")
                .matcher(workflow.substring(workflow.indexOf("\njobs:\n")))
                .results().count());

        String deny = section(workflow, "  deny-unapproved:",
                "  validate-approved-candidate:");
        String admit = workflow.substring(workflow.indexOf("  validate-approved-candidate:"));
        String allowed = jobProperty(admit, "if");
        String rejected = jobProperty(deny, "if");
        String requiredAccept =
                "if: ${{ github.repository == 'ores-truffle-oreslang/oreslang-source.java'"
                + " && github.event.repository.id == 1400792274"
                + " && github.event.repository.private == true"
                + " && github.ref == 'refs/heads/main'"
                + " && github.ref_protected == true"
                + " && vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true'"
                + " && vars.ORESLANG_PRIVATE_CANDIDATE_WORKFLOW_SHA != ''"
                + " && vars.ORESLANG_PRIVATE_CANDIDATE_WORKFLOW_SHA == github.sha"
                + " && vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_SHA != ''"
                + " && vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_SHA == inputs.candidate_sha"
                + " && vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_TREE != '' }}";
        String requiredReject =
                "if: ${{ github.repository != 'ores-truffle-oreslang/oreslang-source.java'"
                + " || github.event.repository.id != 1400792274"
                + " || github.event.repository.private != true"
                + " || github.ref != 'refs/heads/main'"
                + " || github.ref_protected != true"
                + " || vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER != 'true'"
                + " || vars.ORESLANG_PRIVATE_CANDIDATE_WORKFLOW_SHA == ''"
                + " || vars.ORESLANG_PRIVATE_CANDIDATE_WORKFLOW_SHA != github.sha"
                + " || vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_SHA == ''"
                + " || vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_SHA != inputs.candidate_sha"
                + " || vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_TREE == '' }}";
        assertEquals(requiredAccept, allowed, "unreviewed candidate admission expression");
        assertEquals(requiredReject, rejected, "unreviewed candidate refusal expression");
        String weakened = admit.replace(allowed, "if: " + "$" + "{{ true }}");
        assertNotEquals(requiredAccept, jobProperty(weakened, "if"));
        for (String guard : new String[] {
                "github.repository", "github.event.repository.id",
                "github.event.repository.private", "github.ref",
                "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER",
                "vars.ORESLANG_PRIVATE_CANDIDATE_WORKFLOW_SHA",
                "vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_SHA",
                "vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_TREE",
                "inputs.candidate_sha"
        }) {
            assertTrue(allowed.contains(guard), "admission missing " + guard);
            assertTrue(rejected.contains(guard), "rejection missing " + guard);
        }
        assertTrue(allowed.contains("github.event.repository.id == 1400792274"));
        assertTrue(allowed.contains("github.ref == 'refs/heads/main'"));
        assertTrue(allowed.contains("github.ref_protected == true"));
        assertTrue(rejected.contains("github.ref_protected != true"));
        assertTrue(allowed.contains(
                "vars.ORESLANG_PRIVATE_CANDIDATE_WORKFLOW_SHA == github.sha"));
        assertTrue(allowed.contains(
                "vars.ORESLANG_PRIVATE_CANDIDATE_APPROVED_SHA == inputs.candidate_sha"));
        assertEquals("runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]",
                jobProperty(admit, "runs-on"));
        assertEquals("runs-on: ubuntu-24.04", jobProperty(deny, "runs-on"));
        assertFalse(deny.contains("actions/checkout@"));
        assertTrue(deny.contains("exit 1"));
        assertTrue(Files.readString(Path.of(".github/CODEOWNERS"))
                .lines().anyMatch("/.github/workflows/private-exact-candidate-validation.yml @ORESoftware"::equals));
    }

    @Test
    void approvedCandidateCheckoutProvesExactSourceBeforeTests() throws Exception {
        String workflow = Files.readString(
                Path.of(".github/workflows/private-exact-candidate-validation.yml"));
        String runner = workflow.substring(
                workflow.indexOf("  validate-approved-candidate:"));
        assertTrue(runner.indexOf("Verify approvals before any candidate source checkout")
                < runner.indexOf("actions/checkout@"));
        assertTrue(runner.indexOf("actions/checkout@")
                < runner.indexOf("Verify immutable candidate and clean checkout"));
        assertTrue(runner.indexOf("Verify immutable candidate and clean checkout")
                < runner.indexOf("graalvm/setup-graalvm@"));
        assertTrue(hasExactLine(runner, 10,
                "test \"$GITHUB_REPOSITORY_ID\" = '1400792274'"));
        assertTrue(hasExactLine(runner, 10,
                "test \"$GITHUB_REF_PROTECTED\" = 'true'"));
        assertTrue(hasExactLine(runner, 10,
                "test \"$GITHUB_SHA\" = \"$APPROVED_WORKFLOW_SHA\""));
        assertTrue(hasExactLine(runner, 10,
                "test \"$REQUESTED_CANDIDATE_SHA\" = \"$APPROVED_CANDIDATE_SHA\""));
        assertTrue(hasExactLine(runner, 10,
                "test \"$(git rev-parse --verify HEAD)\" = \"$APPROVED_CANDIDATE_SHA\""));
        assertTrue(hasExactLine(runner, 10,
                "test \"$(git rev-parse --verify HEAD^{tree})\" = \"$APPROVED_CANDIDATE_TREE\""));
        assertTrue(hasExactLine(runner, 10,
                "test -z \"$(git status --porcelain --untracked-files=all)\""));
        assertTrue(hasExactLine(runner, 10, "persist-credentials: false"));
        assertTrue(hasExactLine(runner, 10, "ref: " + "$" + "{{ inputs.candidate_sha }}"));
        for (String required : new String[] {
                "DenoCliDefaultPermissionsTest",
                "RuntimePermissionsNetworkAuditTest", "ScopedPermissionInteropTest",
                "mvn -B -ntp -Dores.runtime.carriers=native verify",
                "-Pnative-aot", "-Pnative-hybrid", "-Pnative-isolate"
        }) {
            assertTrue(runner.contains(required), "candidate validation phase missing " + required);
        }
    }

    @Test
    void sourceRepositoryIdPinCannotBeDroppedFromAdmissionExpressions() throws Exception {
        String workflow = Files.readString(WORKFLOW);
        String deny = section(workflow, "  deny-unapproved:", "  validate-exact-main:");
        String run = workflow.substring(workflow.indexOf("  validate-exact-main:"));
        assertTrue(jobProperty(deny, "if").contains("github.event.repository.id != 1400792274"));
        assertTrue(jobProperty(run, "if").contains("github.event.repository.id == 1400792274"));

        // A renamed or recreated repository must not inherit the reviewed
        // runner admission solely by reusing the original slug.
        String loosened = jobProperty(run, "if").replace(
                " && github.event.repository.id == 1400792274", "");
        assertNotEquals(jobProperty(run, "if"), loosened);
    }

    private static boolean hasExactLine(String body, int spaces, String executable) {
        String expected = " ".repeat(spaces) + executable;
        return body.lines().anyMatch(expected::equals);
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
