package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.DataOrientedLayout.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class AcceleratorAndDopContractTest {
    @Test
    void acceleratorKeywordsAreReservedAndNotImplicitCpu() {
        assertEquals(Token.Type.GPU, new Lexer("gpu").scan().getFirst().type());
        assertEquals(Token.Type.QUANTUM, new Lexer("quantum").scan().getFirst().type());

        for (String marker : new String[]{"gpu", "quantum"}) {
            for (String declaration : new String[]{
                    marker + " fnc kernel(): int { return 1; }",
                    "pub " + marker + " fnc kernel(): int { return 1; }",
                    "define module compute as\n" + marker + " fnc kernel(): int { return 1; }\nend",
                    "pub fnc main(): int { " + marker + " { return 1; } return 0; }"
            }) {
                RuntimeException failure = assertThrows(RuntimeException.class, () -> Parser.parse(declaration),
                        declaration);
                assertTrue(failure.getMessage().contains("implicit CPU fallback is forbidden"),
                        failure.getMessage());
            }
        }
    }

    @Test
    void identicalAosSoaAndAosoaOffsetsHaveCheckedPaddedSizes() {
        var aos = new DataOrientedLayout.Plan(Kind.AOS, 3, 2, 4, 16, 1);
        var soa = new DataOrientedLayout.Plan(Kind.SOA, 3, 2, 4, 16, 1);
        var aosoa = new DataOrientedLayout.Plan(Kind.AOSOA, 5, 2, 4, 16, 4);
        assertEquals(48, aos.byteLength());
        assertEquals(36, aos.byteOffset(2, 1));
        assertEquals(32, soa.byteLength());
        assertEquals(24, soa.byteOffset(2, 1));
        assertEquals(64, aosoa.byteLength());
        assertEquals(48, aosoa.byteOffset(4, 1));
        assertEquals(0, new DataOrientedLayout.Plan(Kind.SOA, 0, 2, 4, 16, 1).byteLength());
        assertThrows(IndexOutOfBoundsException.class, () -> soa.byteOffset(3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> soa.byteOffset(0, 2));
    }

    @Test
    void rejectsOverflowAndInvalidPowerOfTwoAndLanes() {
        assertThrows(ArithmeticException.class,
                () -> new DataOrientedLayout.Plan(Kind.AOS, Long.MAX_VALUE, 2, 8, 16, 1));
        assertThrows(ArithmeticException.class,
                () -> new DataOrientedLayout.Plan(Kind.SOA, Long.MAX_VALUE, 2, 8, 16, 1));
        assertThrows(ArithmeticException.class,
                () -> new DataOrientedLayout.Plan(Kind.AOSOA, Long.MAX_VALUE, 2, 8, 16, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new DataOrientedLayout.Plan(Kind.AOS, 3, 2, 4, 3, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DataOrientedLayout.Plan(Kind.AOSOA, 3, 2, 4, 16, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new DataOrientedLayout.Plan(Kind.SOA, 3, 2, 4, 16, 4));
    }
}
