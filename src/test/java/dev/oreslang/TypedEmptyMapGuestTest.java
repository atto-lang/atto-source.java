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

/** Canonical Map.of guest targets stacked on the integrated #2735/#3140 parser.\n * Positive guest behavior and JDK25 JUnit execution remain unverified. */
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
        // Canonical Map.of can be parsed as a call before symbol-resolved
        // builtin lowering; enforce the negative expectation at typecheck.
        for (String file : new String[]{
                "empty-map-untyped.atto",
                "empty-map-wrong-declared-kind.atto",
                "empty-map-contextless-return.atto",
                "empty-map-unsupported-generic.atto"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(fixture(file)), file);
        }
    }

    @Test
    void typedMapValueIsNotAnUnboundedGenericHostDictionary() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                "fnc read(): void { const m = Map.of<string,int>(); return; }"));
        for (String file : new String[]{"empty-map-bad-key.atto", "empty-map-bad-value.atto"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(fixture(file)), file);
            assertTrue(failure.getMessage().contains("unverified key/value domain"),
                    "must reject an unsupported Map.of<K,V> domain, not factory syntax: "
                            + file + ": " + failure.getMessage());
        }
    }

    @Test
    void unresolvedContextualMapPostfixCannotEvadeTypeOrOwnershipChecks() {
        // A context-free Map.of() must not gain a type witness through postfix
        // members or silently allocate an unrestricted host map.
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "fnc bad(): void { const value = Map.of().size; return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "fnc bad(): void { const value = Map.of().missing; return; }"));
    }

    @Test
    void retiredTildeEmptyMapAliasesRemainHardParserErrors() {
        for (String old : new String[]{
                "~map()", "~map<string,int>()", "~<Map<string,int>>()"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc read(): void { const m = " + old + "; return; }"), old);
        }
    }

    @Test
    void newlyAdmittedNonemptyMapStillRequiresTypedAndBoundedSyntax() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                fixture("empty-map-nonempty-reject.atto")));
        IllegalArgumentException arityError = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(fixture("empty-map-bad-arity.atto")));
        assertTrue(arityError.getMessage().contains("exactly two positional type witnesses"),
                "Map.of<K,V> arity must fail in the compiler-owned parser: "
                        + arityError.getMessage());
    }
}
