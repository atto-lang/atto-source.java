package dev.oreslang;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-compiler integration fixtures. Successful execution must exercise
 * the real .atto frontend and evaluator's actor invocation-frame trampoline.
 * The Java implementation is NOT yet a stackless evaluator or persistent
 * SourceTask continuation-frame lowering.
 */
final class ActorGuestInvocationLanguageTest {
    @Test
    void sourceActorNonTailCallsProduceCorrectResult() throws Exception {
        assertEquals("8", run("non-tail-actor-frame.atto"));
    }

    @Test
    void sourceActorTailTransfersRemainBoundedForHundredThousandHops()
            throws Exception {
        assertEquals("100000", run("proper-tail-actor-frame.atto"));
    }

    @Test
    void siblingActorStillAnswersAfterAnotherActorExhaustsGuestBudget()
            throws Exception {
        // This asserts source-level sibling progress after the failing
        // actor began its turn. Terminal supervisor CONTROL, generation
        // fencing and native low-stack survival require separate receipts.
        assertEquals("starting|alive", run("sibling-after-budget-failure.atto"));
    }

    private static String run(String scenario) throws Exception {
        String resource = "/actor-stack/" + scenario;
        String sourceText;
        try (InputStream input = ActorGuestInvocationLanguageTest.class
                .getResourceAsStream(resource)) {
            assertNotNull(input, "fixture missing from test resources: " + resource);
            sourceText = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(sourceText.isBlank());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        org.junit.jupiter.api.function.Executable execute = () -> dev.oreslang.runtime.LinkedProgramRunner.run(
                java.nio.file.Path.of(ActorGuestInvocationLanguageTest.class.getResource(resource).toURI()),
                dev.oreslang.runtime.IsolatePolicy.developer(),
                dev.oreslang.runtime.ExecutionProfile.serverJit(),
                output, new ByteArrayOutputStream());
        if (scenario.equals("sibling-after-budget-failure.atto")) {
            RuntimeException failure = assertThrows(RuntimeException.class, execute);
            assertTrue(failure.toString().contains("ActorFrameArena$BudgetExceeded: actor-owned frame byte quota exhausted"),
                    () -> "expected the failed actor's physical completion outcome: " + failure);
        } else {
            assertDoesNotThrow(execute);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
