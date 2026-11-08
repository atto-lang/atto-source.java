package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical compiled .atto actor execution acceptance. These are genuine
 * compiler/guest JUnit methods ONLY when executed under a matching head SHA,
 * not when source is authored or a source-free harness is replayed.
 */
final class ActorScalarIterativeLanguageTest {
    private static String run(String fixture) throws Exception {
        String name = "/actor-stack/" + fixture;
        String code;
        try (InputStream source = ActorScalarIterativeLanguageTest.class.getResourceAsStream(name)) {
            assertNotNull(source, "missing .atto compiler fixture " + fixture);
            code = new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(code.contains("define actor"), "must run an actual source actor");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, fixture)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit())
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void canonicalEightNonTailCallsReturnFromActorOwnedActivationPCs() throws Exception {
        assertEquals("8", run("non-tail-actor-frame.atto"));
    }

    @Test
    void canonicalHundredThousandTailCallsUseConstantActorSpace() throws Exception {
        assertEquals("100000", run("proper-tail-actor-frame.atto"));
    }

    @Test
    void nonTailActorQuotaFailureDoesNotKillSiblingActor() throws Exception {
        assertEquals("starting|alive", run("sibling-after-budget-failure.atto"));
    }

    @Test
    void scalarFunctionCallsReturnThroughDifferentCalleeAndCallerPCs() throws Exception {
        assertEquals("46", run("iterative-multi-fnc-returns.atto"));
    }

    @Test
    void canonicalMutualTailCallsDoNotRecursivelyEnterJvmEvaluator() throws Exception {
        assertEquals("0", run("iterative-mutual-tail.atto"));
    }
}
