package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
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

    @Test
    void mutualTailCallsAcrossFunctionsKeepNativeStackBounded() throws Exception {
        assertEquals("1", run("stackless-mutual-tail.atto"));
    }

    @Test
    void recursiveBinaryExpressionsResumeBothReturnContinuations() throws Exception {
        assertEquals("21", run("stackless-fibonacci.atto"));
    }

    @Test
    void unsupportedLocalBindingsUseExistingGuardedEvaluator() throws Exception {
        // The M3 subset cannot silently discard a local declaration or
        // partially run it before falling back to the original evaluator.
        assertEquals("6", run("stackless-fallback-binding.atto"));
    }

    @Test
    void actorScalarCallsBeforeAndAfterSourceTaskCooperateKeepCorrectValues()
            throws Exception {
        // This exercises real .atto source actors and the integrated M3/M1.7
        // code paths, but does NOT suspend an active scalar call frame.
        assertEquals("21|100000",
                run("m4-scalar-before-after-actor-suspension.atto"));
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
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, scenario)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
