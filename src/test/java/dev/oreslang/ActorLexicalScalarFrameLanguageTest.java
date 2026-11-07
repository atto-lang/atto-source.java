package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical compiler and actor-mailbox guest regression. AUTHORED only:
 * physical native stack isolation and return PC lowering are not proven.
 */
final class ActorLexicalScalarFrameLanguageTest {
    @Test
    void mutableLexicalScalarSurvivesTwoCooperatesBeforeSecondMail()
            throws Exception {
        String scenario = "lexical-actor-frame-cooperate.atto";
        String sourceText;
        try (InputStream input = getClass().getResourceAsStream("/actor-stack/" + scenario)) {
            assertNotNull(input, "canonical source fixture missing");
            sourceText = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sourceText.contains("let int running = starting"));
        assertTrue(sourceText.contains("running = running + 1"));
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
        assertEquals("before|42|second", output.toString(StandardCharsets.UTF_8),
                "actor-local mutable scalar must retain its value and source " +
                "mailbox must not reenter across cooperate");
    }
}
