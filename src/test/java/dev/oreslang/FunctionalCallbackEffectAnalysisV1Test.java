package dev.oreslang;

import dev.oreslang.compiler.FunctionalCallbackEffectAnalysisV1;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.EffectContracts.Effect;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real parser/typecheck/ownership path plus guest-execution requirement.
 * A source-side effect report is not itself compiled/native guest evidence.
 */
final class FunctionalCallbackEffectAnalysisV1Test {
    private static final Path FIXTURES =
            Path.of("tests/fixtures/functional-callback-effects-v1");

    private static String fixture(String name) throws Exception {
        assertTrue(name.matches("[a-z-]+\\.atto"));
        Path file = FIXTURES.resolve(name);
        assertTrue(Files.isRegularFile(file) && !Files.isSymbolicLink(file),
                "missing or symlinked .atto guest: " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void inlineOptionMapCallbackIsTypecheckedBeforeEffectSpecialization() throws Exception {
        String source = fixture("pure-inline.atto");
        FunctionalCallbackEffectAnalysisV1.Report report =
                OresCompiler.analyzeFunctionalCallbacksV1(source);
        assertTrue(report.complete(), "unexamined source must not be reported complete");
        assertEquals(1, report.sites().size());
        var site = report.sites().getFirst();
        assertEquals("increment", site.function());
        assertEquals("map", site.operation());
        assertEquals(Set.of(Effect.THROW), site.inferredEffects(),
                "checked arithmetic can fail; observational purity does not mean no throw");
        assertTrue(site.eligibleForPure());
    }

    @Test
    void sameExactAttoFixtureRunsThroughRealGraalGuest() throws Exception {
        String source = fixture("pure-inline.atto");
        assertEquals(1, OresCompiler.analyzeFunctionalCallbacksV1(source).sites().size());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "pure-inline.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8)
                .strip().replace("\r", ""));
    }

    @Test
    void namedCallInsideTypedCallbackIsNotDeclaredPureWithoutResolvedEffectMetadata()
            throws Exception {
        String source = fixture("unresolved-helper.atto");
        var report = OresCompiler.analyzeFunctionalCallbacksV1(source);
        assertTrue(report.complete());
        assertEquals(1, report.sites().size());
        assertEquals(Set.of(Effect.UNKNOWN_CALL),
                report.sites().getFirst().inferredEffects());
        assertFalse(report.sites().getFirst().eligibleForPure());
    }

    @Test
    void callbackWithWrongOutputTypeFailsSemanticCheckingBeforeEffectAnalysis()
            throws Exception {
        String source = fixture("reject-wrong-return.atto");
        assertDoesNotThrow(() -> Parser.parse(source),
                "a lexical error is not a type-checker rejection");
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyzeFunctionalCallbacksV1(source));
    }

    @Test
    void closureCaptureIsNotSilentlyTreatedAsPure() {
        String source = """
                fnc with_capture(Option<int> source, int delta): Option<int> {
                  return source.map((int x) -> x + delta);
                }
                """;
        var report = OresCompiler.analyzeFunctionalCallbacksV1(source);
        assertEquals(1, report.sites().size());
        assertTrue(report.sites().getFirst().inferredEffects()
                .contains(Effect.UNKNOWN_CALL));
        assertFalse(report.sites().getFirst().eligibleForPure());
    }

    @Test
    void distinctInlineCallbackInvocationsDoNotShareEffectAuthority() {
        String source = """
                fnc increment(int x): int {
                  return x + 1;
                }
                fnc compare(Option<int> source): int {
                  val first = source.map((int x) -> x + 1);
                  val second = source.map((int x) -> increment(x));
                  return 42;
                }
                """;
        var report = OresCompiler.analyzeFunctionalCallbacksV1(source);
        assertTrue(report.complete());
        assertEquals(2, report.sites().size());
        assertTrue(report.sites().get(0).eligibleForPure());
        assertFalse(report.sites().get(1).eligibleForPure());
        assertTrue(report.sites().get(1).inferredEffects().contains(Effect.UNKNOWN_CALL));
    }

    @Test
    void unexaminedControlFlowNeverReceivesFullCoverageClaim() {
        String source = """
                fnc classify(Option<int> source, bool condition): int {
                  if condition then
                    val candidate = source.map((int x) -> x + 1);
                    return candidate.unwrap();
                  else
                    return 0;
                  fi
                }
                """;
        var report = OresCompiler.analyzeFunctionalCallbacksV1(source);
        assertFalse(report.complete(), "branch bodies are not walked by v1");
    }
}
