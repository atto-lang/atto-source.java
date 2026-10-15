package dev.oreslang;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static regression checks for the opt-in private-only CI runner gates.
 *
 * Each job must reject an unapproved source SHA BEFORE checkout. This test is
 * deliberately independent of GitHub Actions runner availability.
 */
final class PrivateCiRunnerWorkflowSecurityTest {
    private static final String REPO = "ores-truffle-oreslang/oreslang-source.java";
    private static final String APPROVED = "vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA == github.sha";
    private static final String ENABLED = "vars.ORESLANG_PRIVATE_EPHEMERAL_RUNNER";
    private static final String RUNNER_LABEL = "oreslang-private-ephemeral";

    @Test
    void runtimeWorkflowMustGuardEveryRunnerJob() throws Exception {
        checkWorkflow(
                Path.of(".github/workflows/pr949-native-channel-select.yml"),
                "integration/future-select-runtime-current-main-20261014");
    }

    @Test
    void dependentCompilerWorkflowMustGuardEveryRunnerJobWhenPresent()
            throws Exception {
        // #949 intentionally does not yet contain the compiler-only workflow;
        // #950 inherits this test and checks it on the child integration tree.
        Path compiler = Path.of(".github/workflows/private-future-select-guest.yml");
        if (Files.exists(compiler)) {
            checkWorkflow(compiler, "integration/future-select-guest-on-runtime-20261014");
        }
    }

    private static void checkWorkflow(Path path, String expectedBranch)
            throws Exception {
        String text = Files.readString(path);
        assertFalse(text.contains("pull_request_target:"),
                () -> path + " must not admit untrusted pull_request_target");
        assertFalse(text.contains("\n  pull_request:"),
                () -> path + " must not admit unreviewed PR events");
        assertTrue(text.contains("permissions:\n  contents: read"),
                () -> path + " must have read-only GitHub token permissions");
        assertTrue(text.contains("on:\n  push:"),
                () -> path + " must stay source-branch scoped");
        assertTrue(text.contains("- " + expectedBranch),
                () -> path + " must specify the exact source branch");
        assertTrue(text.contains("jobs:\n"),
                () -> path + " must contain execution gates");
        String jobs = text.substring(text.indexOf("jobs:\n") + "jobs:\n".length());

        Pattern headers = Pattern.compile("(?m)^  ([a-z][a-z0-9-]*):\\s*$");
        Matcher matcher = headers.matcher(jobs);
        int count = 0;
        while (matcher.find()) {
            int start = matcher.end();
            int next = jobs.length();
            Matcher following = headers.matcher(jobs);
            if (following.find(start)) next = following.start();
            String body = jobs.substring(start, next);
            String job = matcher.group(1);
            count++;
            String gate = lineContaining(body, "    if: ");
            assertNotNull(gate, () -> path + " job " + job + " is missing a pre-checkout admission gate");
            assertTrue(gate.contains("github.repository == '" + REPO + "'"),
                    () -> path + " job " + job + " accepts another repository");
            assertTrue(gate.contains("github.event.repository.private == true"),
                    () -> path + " job " + job + " does not require private visibility");
            assertTrue(gate.contains("github.ref == 'refs/heads/" + expectedBranch + "'"),
                    () -> path + " job " + job + " accepts an unintended branch");
            assertTrue(gate.contains(APPROVED),
                    () -> path + " job " + job + " accepts an unreviewed SHA");
            assertTrue(gate.contains("vars.ORESLANG_PRIVATE_EPHEMERAL_APPROVED_SHA != ''"),
                    () -> path + " job " + job + " accepts an empty approved SHA");
            assertTrue(gate.contains(ENABLED + " != 'true'"),
                    () -> path + " job " + job + " does not guard opt-in");
            String runner = lineContaining(body, "    runs-on: ");
            assertNotNull(runner, () -> path + " job " + job + " must choose runner explicitly");
            assertTrue(runner.contains(ENABLED), () -> path + " job " + job + " lost opt-in");
            assertTrue(runner.contains(RUNNER_LABEL),
                    () -> path + " job " + job + " lost dedicated private runner label");
            assertTrue(runner.contains("ubuntu-24.04"),
                    () -> path + " job " + job + " lost hosted fallback");
            assertTrue(body.contains("persist-credentials: false"),
                    () -> path + " job " + job + " retains checkout credentials");
            assertTrue(body.contains("test \"$(git rev-parse HEAD)\" = \"$GITHUB_SHA\""),
                    () -> path + " job " + job + " cannot prove checked-out head");
            assertFalse(body.contains("cache: maven"),
                    () -> path + " job " + job + " publishes a shared Maven cache");
        }
        assertEquals(3, count, () -> path + " expected exactly 3 protected job definitions");
    }

    private static String lineContaining(String text, String prefix) {
        for (String line : text.split("\\R")) {
            if (line.startsWith(prefix)) return line;
        }
        return null;
    }
}
