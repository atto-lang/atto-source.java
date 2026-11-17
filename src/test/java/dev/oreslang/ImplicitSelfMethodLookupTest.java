package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Executable .atto conformance for lexical implicit-self method calls. */
final class ImplicitSelfMethodLookupTest {
    private static String guest(String fixture) throws IOException {
        try (InputStream input = ImplicitSelfMethodLookupTest.class
                .getResourceAsStream("/implicit-self/" + fixture)) {
            assertNotNull(input, "missing .atto fixture: " + fixture);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void inheritedInstanceCallDispatchesOnActualReceiver() throws Exception {
        String program = guest("virtual-dispatch.atto");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "virtual-dispatch.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void privateLexicalHelperIsInvokedViaCheckedSelfDispatch() throws Exception {
        String program = guest("private-helper.atto");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "private-helper.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void actorMayInvokeItsOwnDeclaredHelperWithoutSelfPrefix() throws Exception {
        assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse(guest("actor-helper.atto"))));
    }

    @Test
    void unqualifiedCollisionWithFreeCallableRequiresExplicitQualification() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(guest("ambiguous-free-call.atto"))));
        assertTrue(error.getMessage().contains("ambiguous unqualified call"), error::getMessage);
    }

    @Test
    void staticCallableNeverAcquiresLexicalSelf() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(guest("static-no-self.atto"))));
    }

    @Test
    void LexicalArgumentShadowsAnInstanceMethodWithoutFallingBack() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(guest("local-shadow.atto"))));
    }
}
