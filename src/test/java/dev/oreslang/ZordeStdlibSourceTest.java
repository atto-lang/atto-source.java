package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Syntax/packaging gate for the standard-library Zorde seed.
 * This does not claim compiled refinement admission or codec validation.
 */
final class ZordeStdlibSourceTest {
    @Test void zordeModuleParsesAsOrdinaryGuestSource() throws Exception {
        String source = Files.readString(Path.of("stdlib", "zorde.ores"));
        assertNotNull(Parser.parse(source));
        for (String fn : new String[] {
                "int_at_least", "int_at_most", "int_between", "int_exact"
        }) {
            assertTrue(source.contains("pub fnc " + fn + "("), fn);
        }
    }
}
