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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Fixture-based guest compiler/runtime admission; not a backend parity claim. */
final class TildeRecordLiteralGuestTest {
    private static String fixture(String file) {
        try (InputStream in = TildeRecordLiteralGuestTest.class.getResourceAsStream(
                "/atto/tilde-aggregates/" + file)) {
            assertNotNull(in, "missing AttoLang fixture " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Test
    void shortAndExplicitRecordsCompileAndExecuteAsReadonlyStructuralValues() {
        String program = fixture("records-guest-pass.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, program, "records-guest-pass.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals(fixture("records-guest-pass.stdout"),
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void nestedRecordsAndQuotedStaticFieldsRemainStructural() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("records-nested-pass.atto")));
    }

    @Test
    void readonlyRecordsCannotRegainMutationAuthority() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                fixture("records-readonly-reject.atto")));
    }

    @Test
    void duplicateAndMixedEntriesFailBeforeRuntime() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                fixture("records-duplicate-reject.atto")));
        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("records-mixed-reject.atto")));
        assertTrue(mixed.getMessage().contains("mixed record"), mixed.getMessage());
    }

    @Test
    void emptyAndDynamicKeyAmbiguitiesFailClosed() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("records-empty-reject.atto")));
        assertTrue(empty.getMessage().contains("ambiguous"), empty.getMessage());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("records-explicit-empty-pass.atto")));

        for (String file : List.of(
                "maps-short-string-not-enabled.atto",
                "maps-short-symbol-not-enabled.atto",
                "maps-explicit-not-enabled.atto",
                "maps-identifier-key-not-enabled.atto",
                "maps-computed-key-not-enabled.atto",
                "maps-indexed-key-not-enabled.atto",
                "maps-generic-not-enabled.atto")) {
            IllegalArgumentException diagnostic = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(fixture(file)), file);
            assertTrue(diagnostic.getMessage().contains("typed Map<K,V>"),
                    file + ": " + diagnostic.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("records-bare-spread-reject.atto")));
    }

    @Test
    void unaryTildeStillParsesIndependentlyOfAggregateSyntax() {
        assertDoesNotThrow(() -> Parser.parse(fixture("unary-tilde-pass.atto")));
    }

    @Test
    void unsupportedSpreadStillFailsInTheOwnershipTypeGate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        fixture("records-spread-ownership-reject.atto")));
        assertTrue(e.getMessage().contains("aggregate spread"), e.getMessage());
    }
}
