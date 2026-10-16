package dev.oreslang;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Exact, mutation-resistant source-local publisher admission contract. */
final class PrivateExactPublisherAdmissionMutationTest {
    private static final Path WORKFLOW = Path.of(
            ".github/workflows/private-exact-publisher-validation.yml");

    private static final String EXPECTED_DENIAL = "${{ github.repository != 'ores-truffle-oreslang/oreslang-source.java' || github.event.repository.id != 1400792274 || github.event.repository.private != true || github.ref != 'refs/heads/main' || github.ref_protected != true || vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER != 'true' || vars.ORESLANG_PRIVATE_PUBLISHER_WORKFLOW_SHA == '' || vars.ORESLANG_PRIVATE_PUBLISHER_WORKFLOW_SHA != github.sha || vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_SHA == '' || vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_SHA != inputs.candidate_sha || vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_TREE == '' }}";
    private static final String EXPECTED_ADMISSION = "${{ github.repository == 'ores-truffle-oreslang/oreslang-source.java' && github.event.repository.id == 1400792274 && github.event.repository.private == true && github.ref == 'refs/heads/main' && github.ref_protected == true && vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true' && vars.ORESLANG_PRIVATE_PUBLISHER_WORKFLOW_SHA != '' && vars.ORESLANG_PRIVATE_PUBLISHER_WORKFLOW_SHA == github.sha && vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_SHA != '' && vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_SHA == inputs.candidate_sha && vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_TREE != '' }}";

    @Test
    void publisherRunnerAdmissionMustMatchReviewedExpressionExactly() throws Exception {
        String yaml = Files.readString(WORKFLOW);
        String denial = yaml.substring(yaml.indexOf("\n  deny-unapproved:"),
                yaml.indexOf("\n  validate-reviewed-publisher:"));
        String admission = yaml.substring(yaml.indexOf("\n  validate-reviewed-publisher:"));
        assertEquals(EXPECTED_DENIAL, singleIf(denial),
                "the fail-closed denial condition must remain unchanged");
        assertEquals(EXPECTED_ADMISSION, singleIf(admission),
                "the approved runner condition must remain unchanged");
        assertEquals("\non:\n  workflow_dispatch:\n    inputs:\n",
                yaml.substring(yaml.indexOf("\non:\n"),
                        yaml.indexOf("      candidate_sha:")),
                "no unapproved PR/push trigger is permitted");
        assertEquals("\npermissions:\n  contents: read\n",
                yaml.substring(yaml.indexOf("\npermissions:"),
                        yaml.indexOf("\nconcurrency:")));
    }

    @Test
    void relaxingAnyAdmissionGateMustFailExactContract() {
        for (String fragment : new String[] {
                "github.repository == 'ores-truffle-oreslang/oreslang-source.java'",
                "github.event.repository.id == 1400792274",
                "github.event.repository.private == true",
                "github.ref == 'refs/heads/main'",
                "github.ref_protected == true",
                "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER == 'true'",
                "vars.ORESLANG_PRIVATE_PUBLISHER_WORKFLOW_SHA == github.sha",
                "vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_SHA == inputs.candidate_sha",
                "vars.ORESLANG_PRIVATE_PUBLISHER_APPROVED_TREE != ''" }) {
            assertTrue(EXPECTED_ADMISSION.contains(fragment));
            assertNotEquals(EXPECTED_ADMISSION,
                    EXPECTED_ADMISSION.replace(fragment, "true"),
                    "mutated admission should be denied: " + fragment);
        }
        assertNotEquals(EXPECTED_ADMISSION, EXPECTED_ADMISSION + " || true");
        assertNotEquals(EXPECTED_DENIAL, EXPECTED_DENIAL + " || false");
    }

    private static String singleIf(String job) {
        var matcher = Pattern.compile("(?m)^    if: ([^\\r\\n]+)$").matcher(job);
        assertTrue(matcher.find(), "exact runner admission expression missing");
        String found = matcher.group(1);
        assertFalse(matcher.find(), "duplicate admission expressions");
        return found;
    }
}
