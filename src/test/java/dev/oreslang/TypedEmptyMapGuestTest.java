package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Guest admission for the explicitly typed, empty-only Map slice. */
final class TypedEmptyMapGuestTest {
    private static String fixture(String file) {
        try (InputStream input = TypedEmptyMapGuestTest.class.getResourceAsStream(
                "/atto/typed-empty-map/" + file)) {
            assertNotNull(input, "missing fixture " + file);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException problem) {
            throw new UncheckedIOException(problem);
        }
    }

    @Test
    void explicitEmptyMapsCompileAndExecuteWithZeroSize() {
        String program = fixture("empty-map-guest-pass.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, program, "empty-map-guest-pass.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals(fixture("empty-map-guest-pass.stdout"),
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void typedMapValueIsNotAnUnboundedGenericHostDictionary() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                "fnc read(): void { const m = ~<Map<string,int>>(); return; }"));
        for (String file : new String[]{"empty-map-bad-key.atto", "empty-map-bad-value.atto"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(fixture(file)), file);
            assertTrue(failure.getMessage().contains("unverified key/value domain"),
                    file + ": " + failure.getMessage());
        }
    }

    @Test
    void untypedAndNonemptyMapConstructionCannotBypassAdmission() {
        for (String file : new String[]{
                "empty-map-untyped.atto",
                "empty-map-nonempty-reject.atto",
                "empty-map-bad-arity.atto"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(fixture(file)), file);
        }
    }
}
