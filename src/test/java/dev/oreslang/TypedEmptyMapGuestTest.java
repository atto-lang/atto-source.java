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
    void expectedTypeEmptyMapsCompileAndExecuteForDirectTypedBindings() {
        String program = fixture("empty-map-contextual-guest-pass.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, program, "empty-map-contextual-guest-pass.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals(fixture("empty-map-contextual-guest-pass.stdout"),
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void missingOrInvalidExpectedMapTypeFailsBeforeRuntime() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(fixture("empty-map-untyped.atto")));
        assertTrue(missing.getMessage().contains("directly declared")
                        && missing.getMessage().contains("Map<K,V>"),
                missing.getMessage());
        IllegalArgumentException wrongType = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(fixture("empty-map-wrong-declared-kind.atto")));
        assertTrue(wrongType.getMessage().contains("directly declared"), wrongType.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(fixture("empty-map-contextless-return.atto")));
        IllegalArgumentException unverified = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(fixture("empty-map-unsupported-generic.atto")));
        assertTrue(unverified.getMessage().contains("unverified key/value domain"),
                unverified.getMessage());
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
    void unresolvedContextualMapPostfixCannotEvadeTypeOrOwnershipChecks() {
        // The parser retains a scoped contextual AST inside member access,
        // but no generated guest or host Map may escape type/ownership admission.
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "fnc bad(): void { const value = ~map().size; return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "fnc bad(): void { const value = ~map().missing; return; }"));
    }

    @Test
    void newlyAdmittedNonemptyMapStillRequiresTypedAndBoundedSyntax() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("empty-map-nonempty-reject.atto")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("empty-map-bad-arity.atto")));
    }
}
