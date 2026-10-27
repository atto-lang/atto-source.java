package dev.oreslang;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Source-free admission tests for the exact-main workflow ONLY. */
final class PrivateExactMainValidationWorkflowSecurityTest {
    private static final Path WORKFLOW =
            Path.of(".github/workflows/private-exact-main-validation.yml");

    private static String workflow() throws Exception {
        return Files.readString(WORKFLOW);
    }

    private static String region(String text, String start, String end) {
        int from = text.indexOf(start);
        assertTrue(from >= 0, "missing start delimiter: " + start);
        int to = text.indexOf(end, from + start.length());
        assertTrue(to > from, "missing end delimiter: " + end);
        return text.substring(from, to);
    }

    @Test
    void onlyExplicitDispatchCanRunWithReadOnlyToken() throws Exception {
        String text = workflow();
        assertEquals("on:\n  workflow_dispatch:",
                region(text, "on:\n", "\npermissions:"));
        assertEquals("permissions:\n  contents: read",
                region(text, "permissions:\n", "\nconcurrency:"));
        assertFalse(Pattern.compile("(?m)^[ \\t]+(?:permissions|pull_request|pull_request_target|push):")
                .matcher(text).find(), "no nested token overrides or implicit source triggers");
        assertEquals(1, text.split("\npermissions:\n", -1).length - 1);
        assertTrue(text.contains("group: private-oreslang-isolated-validation"));
        assertTrue(text.contains("cancel-in-progress: false"));
    }

    @Test
    void jobsRequireOpposedFailClosedConditionsAndDedicatedRunner() throws Exception {
        String text = workflow();
        assertEquals(2, Pattern.compile("(?m)^  (?:deny-unapproved|validate-exact-main):$")
                .matcher(text).results().count(), "exactly one rejection and one gated worker");
        String denial = region(text, "  deny-unapproved:\n", "  validate-exact-main:\n");
        String permit = text.substring(text.indexOf("  validate-exact-main:\n"));
        for (String common : List.of("github.repository",
                "github.event.repository.id", "github.event.repository.private",
                "github.ref", "github.ref_protected",
                "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER",
                "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA",
                "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_TREE")) {
            assertTrue(denial.contains(common), "denial missing " + common);
            assertTrue(permit.contains(common), "admission missing " + common);
        }
        assertTrue(denial.contains("github.ref_protected != true"));
        assertTrue(permit.contains("github.ref_protected == true"));
        assertTrue(denial.contains("github.event.repository.id != 1400792274"));
        assertTrue(permit.contains("github.event.repository.id == 1400792274"));
        assertTrue(denial.contains("vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA != github.sha"));
        assertTrue(permit.contains("vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == github.sha"));
        assertFalse(denial.contains("actions/checkout@"), "denial must not copy source");
        assertTrue(permit.contains("runs-on: [self-hosted, linux, x64, oreslang-private-ephemeral]"));
    }

    @Test
    void admissionAndImmutableCheckoutPrecedeToolchain() throws Exception {
        String text = workflow();
        int guard = text.indexOf("Verify exact-main admission before source checkout");
        int checkout = text.indexOf("actions/checkout@");
        int identity = text.indexOf("Verify source identity and immutable Git tree");
        int toolchain = text.indexOf("graalvm/setup-graalvm@");
        assertTrue(guard > 0 && guard < checkout && checkout < identity
                && identity < toolchain, "trusted admission must precede execution");
        assertTrue(text.contains("actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683"));
        assertTrue(text.contains("persist-credentials: false"));
        for (String expected : List.of(
                "test \"$GITHUB_REPOSITORY_ID\" = '1400792274'",
                "test \"$GITHUB_REF_PROTECTED\" = 'true'",
                "test \"$GITHUB_SHA\" = \"$APPROVED_SHA\"",
                "test \"$(git rev-parse --verify HEAD^{tree})\" = \"$APPROVED_TREE\"",
                "test -z \"$(git status --porcelain --untracked-files=all)\"")) {
            assertTrue(text.lines().anyMatch(s -> s.strip().equals(expected)),
                    "missing executable exact-tree check: " + expected);
        }
    }

    @Test
    void completeFreshJvmAndNativeExecutionMustBePresent() throws Exception {
        String text = workflow();
        for (String token : List.of(
                "mvn -B -ntp -Dores.runtime.carriers=native -DskipTests=false -Dmaven.test.skip=false clean verify",
                "Prove fresh exact-main full JUnit suite execution",
                "Ten deterministic Future/channel stress replays",
                "seq 1 10", "mvn -B -ntp -Pnative-aot -DskipTests clean package",
                "mvn -B -ntp -Pnative-hybrid -DskipTests clean package",
                "mvn -B -ntp -Pnative-isolate -DskipTests clean package",
                "timeout 180s", "flakyFailure", "rerunFailure")) {
            assertTrue(text.contains(token), "review-required execution missing: " + token);
        }
    }

    @Test
    void codeOwnerRulesRetainV3ExportControlSuffix() throws Exception {
        String text = Files.readString(Path.of(".github/CODEOWNERS"));
        for (String line : List.of(
                "/.github/workflows/private-exact-main-validation.yml @ORESoftware",
                "/src/test/java/dev/oreslang/PrivateExactMainValidationWorkflowSecurityTest.java @ORESoftware",
                "/docs/security/PRIVATE_EXACT_MAIN_VALIDATION.md @ORESoftware")) {
            assertTrue(text.lines().anyMatch(line::equals), "missing owner: " + line);
        }
        String[] finalRules = {
                "/.github/CODEOWNERS @ORESoftware",
                "/.github/private-ci-approved-repositories.txt @ORESoftware",
                "/.github/private-ci-carrier-contracts.json @ORESoftware",
                "/scripts/check-private-ci-destination.sh @ORESoftware",
                "/scripts/run-in-private-ci-destination.sh @ORESoftware",
                "/scripts/publish-private-ci-source.py @ORESoftware",
                "/scripts/publish-private-ci-source-v3.py @ORESoftware"
        };
        String[] actual = text.stripTrailing().split("\n");
        assertTrue(actual.length >= finalRules.length);
        for (int i = 0; i < finalRules.length; ++i) {
            assertEquals(finalRules[i], actual[actual.length - finalRules.length + i],
                    "V3 exporter ownership must remain final and ordered");
        }
    }
}
