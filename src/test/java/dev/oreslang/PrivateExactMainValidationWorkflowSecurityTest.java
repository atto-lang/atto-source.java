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
    void exactMainMustVerifyAdmissionBeforeCheckout() throws Exception {
        String workflow = Files.readString(WORKFLOW);
        String runner = workflow.substring(workflow.indexOf("  validate-exact-main:"));
        String checkedOut = "- uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4";
        String approvalName = "      - name: Verify exact-main admission before source checkout";
        int firstStep = runner.indexOf("\n    steps:\n") + "\n    steps:\n".length();
        assertTrue(firstStep > 0, "runner must declare its steps");
        assertEquals(approvalName, runner.substring(firstStep).lines().findFirst().orElse(""),
                "runner must validate approval before any checkout/action");
        String pre = section(runner, approvalName,
                "      " + checkedOut);
        for (String guard : new String[] {
                "test \"$GITHUB_REPOSITORY\" = 'ores-truffle-oreslang/oreslang-source.java'",
                "test \"$GITHUB_REPOSITORY_ID\" = '1400792274'",
                "test \"$GITHUB_REF\" = 'refs/heads/main'",
                "test \"$GITHUB_REF_PROTECTED\" = 'true'",
                "[[ \"$GITHUB_SHA\" =~ ^[a-f0-9]{40}$ ]]",
                "[[ \"$APPROVED_SHA\" =~ ^[a-f0-9]{40}$ ]]",
                "[[ \"$APPROVED_TREE\" =~ ^[a-f0-9]{40}$ ]]",
                "test \"$GITHUB_SHA\" = \"$APPROVED_SHA\""
        }) {
            assertTrue(hasExactLine(pre, 10, guard),
                    "pre-checkout admission must execute: " + guard);
            String commented = pre.replace("          " + guard + "\n",
                    "          # " + guard + "\n");
            assertFalse(hasExactLine(commented, 10, guard),
                    "commented-out gate must not pass: " + guard);
        }
        assertTrue(pre.contains("          set -euo pipefail"),
                "all pre-checkout checks must fail closed");
        assertFalse(pre.contains("uses: actions/checkout@"),
                "pre-checkout validation may not run GitHub Actions code");
        assertTrue(runner.indexOf(approvalName)
                < runner.indexOf(checkedOut),
                "private Git checkout must happen after admission");
        assertTrue(runner.indexOf(checkedOut)
                < runner.indexOf("Verify source identity and immutable Git tree"),
                "commit/tree proof must run after source checkout");
        String moved = runner.replace(approvalName, "      # " + approvalName.stripLeading());
        assertNotEquals(approvalName,
                moved.substring(firstStep).lines().findFirst().orElse(""),
                "removing the first executable admission step must break the contract");
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
                "mvn -B -ntp -Dores.runtime.carriers=native -DskipTests=false -Dmaven.test.skip=false clean verify",
                "seq 1 10",
                "-Pnative-aot", "-Pnative-hybrid", "-Pnative-isolate",
                "examples/native-channel-select-smoke.ores",
                "examples/native-future-select-smoke.ores"
        }) {
            assertTrue(job.contains(required), "required gate missing: " + required);
        }
    }

    @Test
    void exactMainMustAttestFreshFullJUnitCoverageAndRejectBadReports() throws Exception {
        String workflow = Files.readString(WORKFLOW);
        String job = workflow.substring(workflow.indexOf("  validate-exact-main:"));
        String exactCommand = "run: mvn -B -ntp -Dores.runtime.carriers=native "
                + "-DskipTests=false -Dmaven.test.skip=false clean verify";
        assertTrue(hasExactLine(job, 8, exactCommand),
                "full exact-main Maven suite must be clean and tests cannot be skipped");
        String proof = section(workflow,
                "      - name: Prove fresh exact-main full JUnit suite execution",
                "      - name: Ten deterministic Future/channel stress replays");
        assertTrue(job.indexOf("Complete GraalVM JDK 25 Maven verification")
                < job.indexOf("Prove fresh exact-main full JUnit suite execution"));
        assertTrue(job.indexOf("Prove fresh exact-main full JUnit suite execution")
                < job.indexOf("Ten deterministic Future/channel stress replays"));
        assertTrue(hasExactLine(proof, 10, "python3 - <<'PY'"),
                "JUnit attestation must run from the trusted workflow");
        String toolchain = section(workflow,
                "      - name: Verify toolchain", 
                "      - name: Focused Future/select and exception-isolation regression suites");
        assertTrue(hasExactLine(toolchain, 10, "python3 --version"),
                "exact-main must fail early if Python 3 is unavailable on the runner");
        for (String classname : new String[] {
                "ChannelCompletionPublicationIsolationTest",
                "FutureSelectAttachmentPublicationBarrierTest",
                "FutureChannelSelectTest",
                "ChannelCommittedCompletionDrainTest",
                "ChannelMappedCancellationStressTest",
                "OresFutureHostWaiterDetachmentTest"
        }) {
            assertTrue(proof.contains("dev.oreslang.runtime." + classname),
                    "required focused class missing from exact-main evidence: " + classname);
        }
        for (String token : new String[] {
                "Path(\"target/surefire-reports\")",
                "if len(reports) <= len(required):",
                "if report.is_symlink() or not report.is_file():",
                "if not 0 < report.stat().st_size <= 16 * 1024 * 1024:",
                "declared < 1 or declared != len(cases) or any(n != 0 for n in totals)",
                "if not required.issubset(executed):",
                "additional = executed - required",
                "if not additional:",
                "\"flakyFailure\"", "\"flakyError\"",
                "\"rerunFailure\"", "\"rerunError\""
        }) {
            assertTrue(proof.contains(token), "missing fail-closed exact-main suite guard: " + token);
        }
        assertFalse(proof.contains("python3 scripts/"),
                "untrusted source checkout must not provide the report verifier");
        String commented = proof.replace("          python3 - <<'PY'",
                "          # python3 - <<'PY'");
        assertFalse(hasExactLine(commented, 10, "python3 - <<'PY'"),
                "disabled exact-main attestation is not executable evidence");
    }

    @Test
    void focusedExactMainTestsMustCompileFromFreshSource() throws Exception {
        String workflow = Files.readString(WORKFLOW);
        String runner = workflow.substring(workflow.indexOf("  validate-exact-main:"));
        String command = "run: mvn -B -ntp "
                + "'-Dtest=ChannelCompletionPublicationIsolationTest,"
                + "FutureSelectAttachmentPublicationBarrierTest,FutureChannelSelectTest,"
                + "ChannelCommittedCompletionDrainTest,ChannelMappedCancellationStressTest,"
                + "OresFutureHostWaiterDetachmentTest' clean test";
        assertTrue(hasExactLine(runner, 8, command),
                "focused exact-main tests must compile from a clean Maven target");
        String incremental = runner.replace(" clean test", " test");
        assertFalse(hasExactLine(incremental, 8, command),
                "incremental test execution cannot satisfy this security check");
        String disabled = runner.replace("        " + command,
                "        # " + command);
        assertFalse(hasExactLine(disabled, 8, command),
                "commented-out clean test commands are not executable evidence");
    }

    @Test
    void exactMainNativeStagesMustBuildFreshImagesBeforeGuestSmoke() throws Exception {
        String workflow = Files.readString(WORKFLOW);
        String[] profiles = {"native-aot", "native-hybrid", "native-isolate"};
        String[] stages = {
                "Native AOT image and compiled guest smoke",
                "Native hybrid image and compiled guest smoke",
                "Strict polyglot isolate and compiled guest smoke"
        };
        for (int i = 0; i < stages.length; i++) {
            String start = "      - name: " + stages[i] + "\n";
            String stage = i < stages.length - 1
                    ? section(workflow, start, "      - name: " + stages[i + 1] + "\n")
                    : workflow.substring(workflow.indexOf(start));
            String command = "mvn -B -ntp -P" + profiles[i] + " -DskipTests clean package";
            assertEquals(1L, stage.lines()
                    .filter(line -> line.equals("          " + command)).count(),
                    "the exact-main native profile must build exactly once from clean output");
            assertTrue(stage.indexOf(command) < stage.indexOf("timeout 180s"),
                    "a clean native image must be built before any guest execution");
            assertFalse(stage.contains("-DskipTests package"),
                    "incremental package risks executing stale native artifacts");

            String disabled = stage.replace("          " + command,
                    "          # " + command);
            assertFalse(disabled.lines()
                    .anyMatch(line -> line.equals("          " + command)),
                    "commented-out clean image compilation is not executable evidence");
            String stale = stage.replace("          " + command,
                    "          " + command.replace("clean ", ""));
            assertFalse(stale.lines()
                    .anyMatch(line -> line.equals("          " + command)),
                    "an incremental build must be rejected by the review contract");
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
                "RuntimePermissionsNetworkAuditTest", "RuntimePermissionsTest",
                "RuntimePermissionsExplicitPolicyTest",
                "IsolatePolicySerializationAuditTest", "ScopedPermissionInteropTest",
                "mvn -B -ntp -Dores.runtime.carriers=native",
                "mvn -B -ntp -Dores.runtime.carriers=native -DskipTests=false -Dmaven.test.skip=false clean verify",
                "-Pnative-aot", "-Pnative-hybrid", "-Pnative-isolate"
        }) {
            assertTrue(runner.contains(required), "candidate validation phase missing " + required);
        }
    }

    @Test
    void candidatePermissionsMustRunAllSixClassesWithNativeCarrier() throws Exception {
        String workflow = Files.readString(
                Path.of(".github/workflows/private-exact-candidate-validation.yml"));
        String runner = workflow.substring(
                workflow.indexOf("  validate-approved-candidate:"));
        String expected = "run: mvn -B -ntp -Dores.runtime.carriers=native "
                + "'-Dtest=DenoCliDefaultPermissionsTest,RuntimePermissionsNetworkAuditTest,"
                + "RuntimePermissionsTest,RuntimePermissionsExplicitPolicyTest,"
                + "IsolatePolicySerializationAuditTest,ScopedPermissionInteropTest' clean test";
        assertTrue(hasExactLine(runner, 8, expected),
                "the reviewed focused Maven command must execute the full policy suite");

        // Mentioning a class elsewhere (or only in a YAML comment) cannot
        // substitute for invoking it in the actual Maven test command.
        String omitted = runner.replace("RuntimePermissionsExplicitPolicyTest,", "");
        assertFalse(hasExactLine(omitted, 8, expected),
                "dropping a permission class must invalidate the review fixture");
        String commented = runner.replace("        " + expected,
                "        # " + expected);
        assertFalse(hasExactLine(commented, 8, expected),
                "commented-out Maven commands must not pass");
    }

    @Test
    void candidateMustClearCandidateControlledSurefireReportsBeforeTests() throws Exception {
        String workflow = Files.readString(
                Path.of(".github/workflows/private-exact-candidate-validation.yml"));
        String cleanup = section(workflow,
                "      - name: Clear and attest focused Surefire report provenance",
                "      - name: Focused permissions and scoped host interop regressions");
        String runner = workflow.substring(workflow.indexOf("  validate-approved-candidate:"));
        assertTrue(runner.indexOf("Verify immutable candidate and clean checkout before tool setup")
                < runner.indexOf("Clear and attest focused Surefire report provenance"));
        assertTrue(runner.indexOf("Clear and attest focused Surefire report provenance")
                < runner.indexOf("Focused permissions and scoped host interop regressions"));
        assertTrue(runner.indexOf("Focused permissions and scoped host interop regressions")
                < runner.indexOf("Prove all six security JUnit classes executed"));

        for (String command : new String[] {
                "set -euo pipefail",
                "test ! -L target",
                "test ! -L target/surefire-reports",
                "rm -rf -- target/surefire-reports",
                "test ! -e target/surefire-reports"
        }) {
            assertTrue(hasExactLine(cleanup, 10, command),
                    "candidate-controlled Surefire output was not rejected/cleared: " + command);
            String commented = cleanup.replace("          " + command + "\n",
                    "          # " + command + "\n");
            assertFalse(hasExactLine(commented, 10, command),
                    "commented-out report provenance commands do not count");
        }
        assertTrue(hasExactLine(cleanup, 10,
                "if test -e target/surefire-reports && test ! -d target/surefire-reports; then"),
                "existing non-directory paths must fail before cleanup");
        assertFalse(cleanup.contains("python3 scripts/"),
                "test-result provenance must not depend on candidate scripts");
    }

    @Test
    void candidateMustProveExecutedSurefireResultsBeforeFullVerification() throws Exception {
        String workflow = Files.readString(
                Path.of(".github/workflows/private-exact-candidate-validation.yml"));
        String proof = section(workflow,
                "      - name: Prove all six security JUnit classes executed",
                "      - name: Full Java 25 Maven candidate verification");
        String runner = workflow.substring(
                workflow.indexOf("  validate-approved-candidate:"));
        assertTrue(runner.indexOf("Focused permissions and scoped host interop regressions")
                < runner.indexOf("Prove all six security JUnit classes executed"));
        assertTrue(runner.indexOf("Prove all six security JUnit classes executed")
                < runner.indexOf("Full Java 25 Maven candidate verification"));

        assertTrue(hasExactLine(proof, 10, "python3 - <<'PY'"),
                "the verifier must be part of the protected workflow, not candidate scripts");
        for (String className : new String[] {
                "dev.oreslang.launcher.DenoCliDefaultPermissionsTest",
                "dev.oreslang.runtime.RuntimePermissionsNetworkAuditTest",
                "dev.oreslang.runtime.RuntimePermissionsTest",
                "dev.oreslang.runtime.RuntimePermissionsExplicitPolicyTest",
                "dev.oreslang.runtime.IsolatePolicySerializationAuditTest",
                "dev.oreslang.runtime.ScopedPermissionInteropTest"
        }) {
            assertTrue(hasExactLine(proof, 14, "\"" + className + "\","),
                    "a required security class was omitted from report verification: " + className);
        }
        for (String executable : new String[] {
                "for classname in sorted(required):",
                "report = report_dir / f\"TEST-{classname}.xml\"",
                "if report.is_symlink() or not report.is_file():",
                "if not 0 < report.stat().st_size <= 16 * 1024 * 1024:",
                "if root.tag != \"testsuite\" or root.get(\"name\") != classname:",
                "cases = root.findall(\"testcase\")",
                "declared = int(root.get(\"tests\", \"-1\"))",
                "if declared < 1 or declared != len(cases):",
                "for attribute in (\"failures\", \"errors\", \"skipped\"):",
                "count = int(root.get(attribute, \"-1\"))",
                "if count != 0:",
                "\"flakyFailure\"",
                "\"flakyError\"",
                "\"rerunFailure\"",
                "\"rerunError\"",
                "if case.get(\"classname\") != classname:",
                "missing = sorted(name for name, count in executed.items() if count == 0)",
                "if missing:"
        }) {
            assertTrue(proof.contains(executable), "missing fail-closed assertion: " + executable);
        }
        assertTrue(proof.contains("root.findall(\"testcase\")"));
        assertTrue(hasExactLine(proof, 10,
                "report_dir = Path(\"target/surefire-reports\")"),
                "proof must use the single-module Maven output, not candidate fixtures");
        assertTrue(proof.contains("report_dir.is_symlink()"),
                "symlinked report folders are not trustworthy evidence");
        assertTrue(proof.contains("report.is_symlink()"),
                "symlinked report XML files are not trustworthy evidence");
        assertFalse(proof.contains("rglob(\"TEST-*.xml\")"),
                "recursive search can accept committed fake reports from nested fixtures");
        assertTrue(proof.contains("\"failure\", \"error\", \"skipped\""),
                "failed, erroneous and skipped test cases must all be rejected");
        assertFalse(proof.contains("python3 scripts/"),
                "proof must not execute a script from the untrusted candidate checkout");

        String disabled = proof.replace("          python3 - <<'PY'",
                "          # python3 - <<'PY'");
        assertFalse(hasExactLine(disabled, 10, "python3 - <<'PY'"),
                "commented-out Surefire verification cannot satisfy the gate");
    }

    @Test
    void fullMavenVerificationMustExecuteFreshTestsBeyondFocusedSubset() throws Exception {
        String workflow = Files.readString(
                Path.of(".github/workflows/private-exact-candidate-validation.yml"));
        String runner = workflow.substring(
                workflow.indexOf("  validate-approved-candidate:"));
        String expected = "run: mvn -B -ntp -Dores.runtime.carriers=native "
                + "-DskipTests=false -Dmaven.test.skip=false clean verify";
        assertTrue(hasExactLine(runner, 8, expected),
                "full Maven verify must be clean and explicitly execute tests");
        assertFalse(hasExactLine(runner.replace("clean verify", "verify"), 8, expected),
                "an incremental full-suite build cannot satisfy the admission gate");

        String proof = section(workflow,
                "      - name: Prove full Maven suite executed beyond focused security classes",
                "      - name: Native AOT candidate build and guest execution");
        assertTrue(runner.indexOf("Full Java 25 Maven candidate verification")
                < runner.indexOf("Prove full Maven suite executed beyond focused security classes"));
        assertTrue(runner.indexOf("Prove full Maven suite executed beyond focused security classes")
                < runner.indexOf("Native AOT candidate build and guest execution"));
        assertTrue(hasExactLine(proof, 10, "python3 - <<'PY'"),
                "full-suite attestation must live in the reviewed workflow");
        for (String required : new String[] {
                "if len(reports) <= len(focused):",
                "if focused_seen != focused:",
                "extra = executed - focused",
                "if not extra:",
                "report_dir = Path(\"target/surefire-reports\")",
                "if report.is_symlink() or not report.is_file():",
                "declared < 1 or declared != len(cases) or any(n != 0 for n in totals)",
                "case.get(\"classname\") != classname",
                "raise SystemExit(f\"Failed/skipped/flaky testcase in {report}\")",
                "\"flakyFailure\"",
                "\"flakyError\"",
                "\"rerunFailure\"",
                "\"rerunError\"",
                "VERIFIED_FULL_JUNIT_EXTRA_CLASSES="
        }) {
            assertTrue(proof.contains(required), "full-suite evidence guard missing: " + required);
        }
        assertFalse(proof.contains("python3 scripts/"),
                "attestation must not run code from the untrusted candidate checkout");
        for (String classname : new String[] {
                "dev.oreslang.launcher.DenoCliDefaultPermissionsTest",
                "dev.oreslang.runtime.RuntimePermissionsNetworkAuditTest",
                "dev.oreslang.runtime.RuntimePermissionsTest",
                "dev.oreslang.runtime.RuntimePermissionsExplicitPolicyTest",
                "dev.oreslang.runtime.IsolatePolicySerializationAuditTest",
                "dev.oreslang.runtime.ScopedPermissionInteropTest"
        }) {
            assertTrue(proof.contains("\"" + classname + "\""),
                    "full Maven suite must re-execute focused class: " + classname);
        }
    }

    @Test
    void candidateNativeStagesMustRebuildFromCleanTargets() throws Exception {
        String workflow = Files.readString(
                Path.of(".github/workflows/private-exact-candidate-validation.yml"));
        String runner = workflow.substring(
                workflow.indexOf("  validate-approved-candidate:"));

        String[] profiles = {"native-aot", "native-hybrid", "native-isolate"};
        String[] stageNames = {
                "Native AOT candidate build and guest execution",
                "Native hybrid candidate build and guest execution",
                "Strict polyglot isolate candidate build and guest execution"
        };
        for (int i = 0; i < profiles.length; i++) {
            String expected = "mvn -B -ntp -P" + profiles[i] + " -DskipTests clean package";
            String start = "      - name: " + stageNames[i] + "\n";
            String stage = i + 1 < profiles.length
                    ? section(workflow, start, "      - name: " + stageNames[i + 1])
                    : workflow.substring(workflow.indexOf(start));

            assertEquals(1, stage.lines()
                            .filter(line -> line.equals("          " + expected)).count(),
                    "native profile must execute exactly one fresh image build: " + profiles[i]);
            assertTrue(stage.indexOf(expected) < stage.indexOf("timeout 180s"),
                    "native compilation must precede the corresponding guest smoke");
            assertFalse(stage.contains(" -DskipTests package"),
                    "incremental build can execute stale native-image output");

            String commented = stage.replace("          " + expected,
                    "          # " + expected);
            assertFalse(commented.lines()
                            .anyMatch(line -> line.equals("          " + expected)),
                    "commenting out the fresh native-image build must fail review");
        }
        assertTrue(runner.indexOf("Full Java 25 Maven candidate verification")
                < runner.indexOf(stageNames[0]),
                "focused security and full Maven verify must precede native builds");
    }

    @Test
    void nativeIsolateLibrariesMustBeUniqueFreshRegularFiles() throws Exception {
        for (String workflowFile : new String[] {
                ".github/workflows/private-exact-main-validation.yml",
                ".github/workflows/private-exact-candidate-validation.yml"
        }) {
            String workflow = Files.readString(Path.of(workflowFile));
            String nativeStage = workflow.substring(
                    workflow.indexOf("      - name: Strict polyglot isolate "));
            for (String command : new String[] {
                    "test -d target && test ! -L target",
                    "libraries=()",
                    "for candidate in target/oresvm.so target/liboresvm.so; do",
                    "if test -e \"$candidate\" || test -L \"$candidate\"; then",
                    "if ! test -f \"$candidate\" || test -L \"$candidate\" || ! test -s \"$candidate\"; then",
                    "echo \"::error::Reject invalid native isolate artifact: $candidate\" >&2",
                    "exit 1",
                    "libraries+=(\"$candidate\")",
                    "test \"${#libraries[@]}\" -eq 1",
                    "lib=\"${libraries[0]}\"",
                    "echo \"VERIFIED_ISOLATE_LIBRARY=$lib\""
            }) {
                assertTrue(hasExactLine(nativeStage, 10, command)
                        || hasExactLine(nativeStage, 12, command)
                        || hasExactLine(nativeStage, 14, command)
                        || hasExactLine(nativeStage, 16, command),
                        "missing executable isolate library proof: " + command);
            }
            assertFalse(nativeStage.contains("find target -maxdepth 1"),
                    "non-deterministic first-match isolate selection must be rejected");
            assertTrue(nativeStage.indexOf("test \"${#libraries[@]}\" -eq 1")
                    < nativeStage.indexOf("IsolateLibrary=$PWD/$lib"),
                    "validate unique isolate library before executing guest code");
            String noUniqueCheck = nativeStage.replace(
                    "          test \"${#libraries[@]}\" -eq 1",
                    "          # test \"${#libraries[@]}\" -eq 1");
            assertFalse(hasExactLine(noUniqueCheck, 10,
                    "test \"${#libraries[@]}\" -eq 1"),
                    "a disabled uniqueness guard must not count");
        }
    }

    @Test
    void nativeIsolateSelectionRejectsInvalidArtifactsInRealBash() throws Exception {
        // Execute the actual trusted-workflow shell fragment. Structural
        // assertions alone previously missed Bash's set -e exceptions for
        // a failed AND-list followed by a successful array append.
        for (String workflowFile : new String[] {
                ".github/workflows/private-exact-main-validation.yml",
                ".github/workflows/private-exact-candidate-validation.yml"
        }) {
            String workflow = Files.readString(Path.of(workflowFile));
            String stage = workflow.substring(
                    workflow.indexOf("      - name: Strict polyglot isolate "));
            String first = "          test -d target && test ! -L target";
            String last = "          echo \"VERIFIED_ISOLATE_LIBRARY=$lib\"";
            int start = stage.indexOf(first);
            int end = stage.indexOf(last);
            assertTrue(start >= 0 && end > start, "missing isolate selection shell code");
            String program = "set -euo pipefail\n"
                    + stage.substring(start, end + last.length()) + "\n";

            for (int i = 0; i < 10; i++) {
                Path temp = Files.createTempDirectory("ores-isolate-library-proof-");
                try {
                    Path target = temp.resolve("target");
                    if (i < 7) {
                        Files.createDirectory(target);
                    }
                    switch (i) {
                        case 0 -> Files.write(target.resolve("oresvm.so"), new byte[] {1});
                        case 1 -> Files.write(target.resolve("liboresvm.so"), new byte[] {1});
                        case 2 -> {
                            Files.write(target.resolve("oresvm.so"), new byte[] {1});
                            Files.write(target.resolve("liboresvm.so"), new byte[] {1});
                        }
                        case 3 -> {
                            Files.write(temp.resolve("real.so"), new byte[] {1});
                            Files.createSymbolicLink(target.resolve("oresvm.so"),
                                    Path.of("../real.so"));
                        }
                        case 4 -> Files.createSymbolicLink(
                                target.resolve("oresvm.so"), Path.of("missing.so"));
                        case 5 -> Files.createDirectory(target.resolve("oresvm.so"));
                        case 6 -> Files.write(target.resolve("oresvm.so"), new byte[0]);
                        case 7 -> { /* missing target */ }
                        case 8 -> {
                            Files.createDirectory(temp.resolve("real-target"));
                            Files.write(temp.resolve("real-target/oresvm.so"),
                                    new byte[] {1});
                            Files.createSymbolicLink(target, Path.of("real-target"));
                        }
                        case 9 -> Files.write(target, new byte[] {1});
                        default -> throw new AssertionError("unexpected fixture");
                    }
                    Process shell = new ProcessBuilder("bash", "-c", program)
                            .directory(temp.toFile())
                            .redirectErrorStream(true)
                            .start();
                    String output = new String(shell.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    int exitCode = shell.waitFor();
                    assertEquals(i < 2, exitCode == 0,
                            "isolate selection exit status for fixture " + i + " in "
                                    + workflowFile + ": " + output);
                } finally {
                    try (var files = Files.walk(temp)) {
                        for (Path candidate : files.sorted(
                                java.util.Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(candidate);
                        }
                    }
                }
            }
        }
    }

    @Test
    void trustedPrivateValidationWorkflowsMustShareOneConcurrencyGroup() throws Exception {
        String[] paths = {
                ".github/workflows/private-exact-main-validation.yml",
                ".github/workflows/private-exact-candidate-validation.yml",
                ".github/workflows/private-exact-publisher-validation.yml"
        };
        final String approved = "\nconcurrency:\n"
                + "  group: private-oreslang-isolated-validation\n"
                + "  cancel-in-progress: false\n";
        for (String path : paths) {
            String workflow = Files.readString(Path.of(path));
            assertEquals(1, workflow.split("\\nconcurrency:\\n", -1).length - 1,
                    "multiple or missing concurrency blocks: " + path);
            assertEquals(1, workflow.split("\\n  group: ", -1).length - 1,
                    "multiple or missing workflow concurrency groups: " + path);
            assertTrue(workflow.contains(approved),
                    "all private runner workflows must serialize together: " + path);
            String separated = workflow.replace(
                    "  group: private-oreslang-isolated-validation",
                    "  group: " + path);
            assertFalse(separated.contains(approved),
                    "isolated concurrency queue changes must not pass review");
            String cancel = workflow.replace("  cancel-in-progress: false",
                    "  cancel-in-progress: true");
            assertFalse(cancel.contains(approved),
                    "a new dispatch must not interrupt an active security validation");
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
