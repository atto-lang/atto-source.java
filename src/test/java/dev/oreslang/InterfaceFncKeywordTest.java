package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class InterfaceFncKeywordTest {
    @Test
    void legacyFnIsRejected() {
        String legacy = "f" + "n";
        var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(("define interface Api %s run() => void; end").formatted(legacy)));
        assertTrue(error.getMessage().contains("fnc"));
    }

    @Test
    void allThreeSpellingsProduceIdenticalAst() {
        for (String kind : new String[]{"interface", "trait"}) {
            for (String signature : new String[]{"run(): int;", "identity<T>(T value): T;", "mtd(): int;"}) {
                var bare = Parser.parse("define " + kind + " Api as " + signature + " end");
                assertEquals(bare, Parser.parse("define " + kind + " Api as fnc " + signature + " end"));
                assertEquals(bare, Parser.parse("define " + kind + " Api as mtd " + signature + " end"));
            }
        }
    }

    @Test
    void genericFieldsRemainFieldsAndReceiverSyntaxDoesNotEscapeModuleContracts() {
        assertDoesNotThrow(() -> Parser.parse("define interface Api as List<int> values; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define interface Api as mtd run(Actor self)(): int; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define interface Api as fnc mtd run(): int; end"));
    }
}
