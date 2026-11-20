package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Canonical mutable receivers are `mut self`; pointer spellings are never source syntax. */
final class PointerlessReceiverSyntaxHardeningTest {
    @Test
    void pointerStyleReceiverSpellingsFailClosed() {
        for (String receiver : new String[] {"&mut self", "& self", "*self"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define class Counter as
                      pub let int value = 0;
                      pub bump(%s)(): int { return self.value; }
                    end
                    """.formatted(receiver)), receiver);
        }
    }
}
