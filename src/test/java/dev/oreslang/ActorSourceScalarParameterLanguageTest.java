package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canonical .atto acceptance fixture for the Java source/compiler path.
 *
 * Authored, NOT_EXECUTED until an admitted exact-head GraalVM/JUnit runner
 * actually executes the fixture. It proves scalar argument integrity and
 * mailbox order only when it runs, not full per-actor physical stack safety.
 */
final class ActorSourceScalarParameterLanguageTest {
    @Test
    void scalarSourceTaskParametersSurviveCooperateBeforeSecondActorMail()
            throws Exception {
        String resource = "/actor-stack/source-task-typed-args-cooperate.atto";
        String code;
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "missing canonical .atto source fixture");
            code = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(code.contains("suspends fnc scalar_after_cooperate"));
        assertTrue(code.contains("rt cooperate"));
        assertTrue(code.contains("define actor ScalarWorker"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code,
                "source-task-typed-args-cooperate.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("before|42|second", output.toString(StandardCharsets.UTF_8),
                "actor receive must remain non-reentrant through suspended " +
                "source scalar parameter execution");
    }
}
