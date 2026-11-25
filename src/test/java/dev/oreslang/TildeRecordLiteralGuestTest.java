package dev.oreslang;

import dev.oreslang.ast.Ast;
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

/** Canonical Record.of/Tuple.of migration fixtures; not a backend parity claim.\n * Positive guest tests require the separately stacked Java #2735 lowering. */
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
    void canonicalRecordsCompileAndExecuteAsReadonlyStructuralValues() {
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
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(fixture("records-readonly-reject.atto")));
        String diagnostic = error.getMessage().toLowerCase();
        assertTrue(diagnostic.contains("readonly") || diagnostic.contains("mutat")
                        || diagnostic.contains("assign"),
                "must reject mutation, not merely fail to parse Record.of: " + error.getMessage());
    }

    @Test
    void duplicateAndMixedEntriesFailBeforeRuntime() {
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("records-duplicate-reject.atto")));
        assertTrue(duplicate.getMessage().toLowerCase().contains("duplicate"),
                "must diagnose duplicate fields, not missing Record.of lowering: " + duplicate.getMessage());
        IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("records-mixed-reject.atto")));
        assertTrue(mixed.getMessage().toLowerCase().contains("mixed"),
                "must diagnose record/map mixing, not missing Record.of lowering: " + mixed.getMessage());
    }

    @Test
    void emptyAndDynamicKeyAmbiguitiesFailClosed() {
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("records-empty-reject.atto")));
        assertTrue(empty.getMessage().contains("empty") || empty.getMessage().contains("removed"), empty.getMessage());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("records-explicit-empty-pass.atto")));

        // The old tilde spellings are intentionally invalid after removal.
        // Canonical Map.of semantic checks have separate guest fixtures.
        for (String file : List.of(
                "maps-short-string-not-enabled.atto",
                "maps-short-symbol-not-enabled.atto",
                "maps-explicit-not-enabled.atto",
                "maps-identifier-key-not-enabled.atto",
                "maps-computed-key-not-enabled.atto",
                "maps-indexed-key-not-enabled.atto")) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(fixture(file)), file);
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("records-bare-spread-reject.atto")));
    }

    @Test
    void unaryTildeStillParsesIndependentlyOfAggregateSyntax() {
        assertDoesNotThrow(() -> Parser.parse(fixture("unary-tilde-pass.atto")));
    }

    @Test
    void canonicalTupleFactoriesAdmitZeroAndMultipleElements() {
        assertDoesNotThrow(() -> Parser.parse(fixture("tuples-empty-pass.atto")));
        assertDoesNotThrow(() -> Parser.parse(fixture("tuples-two-pass.atto")));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("tuples-two-pass.atto")));
    }

    @Test
    void canonicalTypedEmptyFactoriesParseWithoutOldTildeAstWitness() {
        for (String file : List.of(
                "typed-empty-map-parser-only.atto",
                "typed-empty-record-parser-only.atto",
                "typed-empty-tuple-parser-only.atto")) {
            assertDoesNotThrow(() -> Parser.parse(fixture(file)), file);
        }
        // ~<T>() is retired, even for a generic type witness.
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(fixture("typed-empty-generic-parser-only.atto")));
    }

    @Test
    void typedEmptyConcreteRecordAndTupleAreAdmitted() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("typed-empty-record-parser-only.atto")));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("typed-empty-tuple-parser-only.atto")));
    }

    @Test
    void typedEmptyConcreteRecordAndTupleCompileAndExecute() {
        String program = fixture("typed-empty-guest-pass.atto");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = assertDoesNotThrow(() -> Source.newBuilder(
                OresLanguage.ID, program, "typed-empty-guest-pass.atto")
                .mimeType(OresLanguage.MIME_TYPE).build());
        assertDoesNotThrow(() -> {
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false).out(out).build()) {
                context.eval(source);
            }
        });
        assertEquals(fixture("typed-empty-guest-pass.stdout"),
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void retiredTildeTypedEmptyWitnessesAllFailClosed() {
        for (String file : List.of(
                "typed-empty-generic-parser-only.atto",
                "typed-empty-required-record-reject.atto",
                "typed-empty-required-tuple-reject.atto",
                "typed-empty-unresolved-reject.atto",
                "typed-empty-nominal-reject.atto",
                "typed-empty-argument-reject.atto",
                "typed-empty-missing-type-reject.atto")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(fixture(file)), file);
        }
    }

    @Test
    void unsupportedSpreadStillFailsInTheOwnershipTypeGate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        fixture("records-spread-ownership-reject.atto")));
        assertTrue(e.getMessage().toLowerCase().contains("spread"),
                "must reject unsupported spread semantics, not just Record.of syntax: " + e.getMessage());
    }
}
