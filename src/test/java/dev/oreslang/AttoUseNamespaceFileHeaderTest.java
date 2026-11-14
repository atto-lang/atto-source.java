package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact parser/ABI gate for Atto's canonical first-line namespace syntax. */
final class AttoUseNamespaceFileHeaderTest {
    private static final String MAIN =
            "pub routine main(): void { return; }\n";

    @Test void canonicalUseNamespaceHeaderBindsSourceIdentity() {
        var parsed = Parser.parse("use namespace guest;\n" + MAIN);
        assertEquals("guest", parsed.namespace());
        assertEquals(Parser.parse("namespace guest;\n" + MAIN).namespace(),
                parsed.namespace());
    }

    @Test void stillAcceptsLegacyNamespaceHeader() {
        assertEquals("legacy",
                Parser.parse("namespace legacy;\n" + MAIN).namespace());
        assertNull(Parser.parse(MAIN).namespace());
    }

    @Test void canonicalHeaderParticipatesInIncrementalSourceIdentity() {
        var build = new IncrementalCompiler().compile(Map.of(
                "probe.ores", "use namespace guest;\n" + MAIN));
        assertEquals("guest", build.units().get("probe.ores").namespace());
        assertEquals("guest", build.units().get("probe.ores").packageId());
    }

    @Test void useIsStillAnOrdinaryCallableIdentifier() {
        assertNotNull(Parser.parse(
                "pub routine use(): void { return; }\n"));
    }

    @Test void rejectsDottedNamespaceAndMissingSemicolon() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("use namespace guest.other;\n" + MAIN));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("use namespace guest\n" + MAIN));
    }

    @Test void rejectsDuplicateOrLateNamespaceHeaders() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("use namespace guest;\n"
                        + "use namespace other;\n" + MAIN));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("use namespace guest;\n"
                        + "namespace other;\n" + MAIN));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(MAIN + "use namespace guest;\n"));
    }

    @Test void rejectsForgedUseHeaderAndMissingName() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("use module guest;\n" + MAIN));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("use namespace;\n" + MAIN));
    }
}
