package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Real semantic checks and JVM guest execution; not standalone native AOT. */
final class ExtensibleDeclarationSemanticTest {
    private static final String HEADER = "define keyword entity as class requires Entity; end\n";
    private static final String INTERFACE = "define interface Entity as\n answer(): int;\nend\n";
    private static final String CUSTOM = HEADER + INTERFACE + "define entity Customer as\n";
    private static final String MANUAL = INTERFACE + "define class Customer implements Entity as\n";
    private static final String GOOD = " pub answer(): int { return 42; }\nend\n";
    @TempDir Path temp;

    @Test void nonemptyInterfaceUsesOrdinaryTypeChecker() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parseStrictAtto(MANUAL + GOOD)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parseStrictAtto(CUSTOM + GOOD)));
    }

    @Test void missingWrongTypeAndPrivateMethodsCannotSatisfyRequirement() {
        for (String body : new String[] {
            "end\n",
            " pub answer(): bool { return true; }\nend\n",
            " private answer(): int { return 42; }\nend\n"
        }) {
            var ordinary = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parseStrictAtto(MANUAL + body)));
            var custom = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parseStrictAtto(CUSTOM + body)));
            assertTrue(ordinary.getMessage().contains("does not implement interface"), ordinary::getMessage);
            assertEquals(ordinary.getMessage(), custom.getMessage());
        }
    }

    @Test void linkedCustomAndHandwrittenGuestReturn42WithGuestJitDisabled() throws Exception {
        String main = "pub fnc main(): int { let Customer c = new Customer(); return c.answer(); }\n";
        var profile = new ExecutionProfile(ExecutionProfile.Mode.AOT, ExecutionProfile.Platform.SERVER);
        assertFalse(profile.guestJitAllowed());
        for (String source : new String[]{MANUAL + GOOD + main, CUSTOM + GOOD + main}) {
            Path entry = Files.createTempFile(temp, "keyword-", ".atto");
            Files.writeString(entry, source);
            var run = LinkedProgramRunner.runWithExitStatus(entry, IsolatePolicy.developer(), profile,
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
            assertEquals(42, run.exitStatus());
        }
    }
    @Test void linkedSourceProfilesPreserveLegacyIsolationAndOrdinaryAttoCompatibility() {
        String source = CUSTOM + GOOD;
        assertDoesNotThrow(() -> Parser.parseSourceUnit("entry.atto", source));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseSourceUnit("entry.ores", source));
        String ordinary = "pub fnc main(): int { if 1 == 1; then return 42; fi return 0; }";
        assertEquals(Parser.parse(ordinary), Parser.parseSourceUnit("entry.atto", ordinary));
    }
}
