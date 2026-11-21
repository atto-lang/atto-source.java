package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A real native .ores guest fixture, not a Java substitution for critical
 * source lowering. It is only a verified pass after Maven executes the test
 * at the exact checked compiler source tree.
 */
@Timeout(30)
final class CriticalActorGuestExecutionTest {
    @Test void actorCriticalGuestReturnsExpectedValueAndReleasesBeforeStop()
            throws Exception {
        assertEquals("42", runGuest("critical-actor-checked-v1.ores"));
    }

    @Test void earlyReturnInsideCriticalReleasesGuardBeforeActorStop()
            throws Exception {
        assertEquals("returned", runGuest("critical-actor-early-return-v1.ores"));
    }

    @Test void canonicalAttoActorCriticalGuestMatchesLegacyGuestOutput()
            throws Exception {
        assertEquals("42", runGuest("critical-actor-checked-v1.atto"));
    }

    @Test void canonicalAttoEarlyReturnGuestReleasesBeforeStop()
            throws Exception {
        assertEquals("returned", runGuest("critical-actor-early-return-v1.atto"));
    }

    @Test void canonicalAttoGuestOutputTracksARealSourceMutation()
            throws Exception {
        // Compiler-backed mutation control: a Java stub that always emits "42"
        // cannot satisfy both observations. This is NOT a two-reader borrow proof.
        String canonical = Files.readString(
                Path.of("examples", "critical-actor-checked-v1.atto"));
        String before = "let int value = 40;";
        String after = "let int value = 41;";
        assertEquals(1, canonical.split(java.util.regex.Pattern.quote(before), -1).length - 1,
                "canonical scalar initialization must occur exactly once");
        String mutated = canonical.replace(before, after);
        assertNotEquals(canonical, mutated, "mutant must change actual .atto source");
        assertEquals("42", runGuestSource(canonical, "critical-actor-checked-v1.atto"));
        assertEquals("43", runGuestSource(mutated, "critical-actor-checked-v1-mutant.atto"));
    }

    @Test void canonicalAttoGuestCriticalBodyMutationChangesOutput()
            throws Exception {
        // A source-independent print stub fails even if it reads the scalar
        // initializer: the change is inside an admitted actor critical body.
        String canonical = Files.readString(
                Path.of("examples", "critical-actor-checked-v1.atto"));
        String original = "value = value + 2;";
        assertEquals(1, canonical.split(java.util.regex.Pattern.quote(original), -1).length - 1,
                "critical arithmetic operation must appear exactly once");
        String changed = canonical.replace(original, "value = value + 3;");
        assertNotEquals(canonical, changed);
        assertEquals("42", runGuestSource(canonical, "critical-actor-body-original.atto"));
        assertEquals("43", runGuestSource(changed, "critical-actor-body-mutant.atto"));
    }

    @Test void canonicalAttoGuestSourceMutationWorksInBothDirections()
            throws Exception {
        String canonical = Files.readString(
                Path.of("examples", "critical-actor-checked-v1.atto"));
        String original = "let int value = 40;";
        assertEquals(1, canonical.split(java.util.regex.Pattern.quote(original), -1).length - 1);
        String decreased = canonical.replace(original, "let int value = 39;");
        assertEquals("41", runGuestSource(decreased, "critical-actor-decremented.atto"));
    }

    private static String runGuest(String file) throws Exception {
        String source = Files.readString(Path.of("examples", file));
        return runGuestSource(source, file);
    }

    private static String runGuestSource(String source, String file) throws Exception {
        OresCompiler.parseAndTypeCheck(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, file)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test void unverifiedFreeFunctionCriticalSourceDoesNotExecute() {
        String source = """
                pub suspends fnc unsafe(): int {
                  critical { return 42; }
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains(
                "requires a suspending actor method with actor-owned gate"),
                failure::toString);
    }
}
