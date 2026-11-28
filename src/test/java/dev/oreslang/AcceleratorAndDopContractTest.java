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
                assertTrue(failure.getMessage().contains("implicit CPU fallback is forbidden")
                        || failure.getMessage().contains("no implicit CPU fallback"),
                        failure.getMessage());
            }
        }
    }

    @Test
    void acceleratorAdmissionPreservesExistingEffectKeywords() {
        // Forward-port of DOP must not silently drop newer main-branch
        // suspend or exception/supervision token admission.
        assertEquals(Token.Type.SUSPENDS, new Lexer("suspends").scan().getFirst().type());
        assertEquals(Token.Type.THROW, new Lexer("throw").scan().getFirst().type());
        assertEquals(Token.Type.RAISE, new Lexer("raise").scan().getFirst().type());
        assertEquals(Token.Type.PANIC, new Lexer("panic").scan().getFirst().type());
        assertEquals(Token.Type.RECOVER, new Lexer("recover").scan().getFirst().type());
        for (String effect : new String[]{"throw", "raise", "panic", "recover"}) {
            var program = "pub fnc main(): void { " + effect + " 1; }";
            RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> Parser.parse(program));
            assertTrue(rejected.getMessage().contains("checked exception and supervision lowering")
                    || rejected.getMessage().contains("exception"), rejected.getMessage());
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
    void allScalarOffsetsAreUniqueAndBoundedAcrossLayouts() {
        for (Kind kind : Kind.values()) {
            for (int fields = 1; fields <= 8; fields++) {
                for (int rows = 0; rows <= 64; rows++) {
                    int lanes = kind == Kind.AOSOA ? 4 : 1;
                    var plan = new DataOrientedLayout.Plan(kind, rows, fields, 4, 16, lanes);
                    var offsets = new java.util.HashSet<Long>();
                    for (int row = 0; row < rows; row++) {
                        for (int field = 0; field < fields; field++) {
                            long offset = plan.byteOffset(row, field);
                            assertTrue(offset >= 0 && offset + 4 <= plan.byteLength());
                            assertTrue(offsets.add(offset), "overlapping scalar offsets");
                        }
                    }
                }
            }
        }
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

    @Test
    void maximumNativeCompatibleTileAndTailKeepStableCheckedCoordinates() {
        // Host-side reference for the .NET AOT profiled-shard proposal:
        // profile hints choose a NEW layout, never reinterpret existing rows.
        // Java/GraalVM may use a JIT, but physical geometry remains immutable.
        var tiled = new DataOrientedLayout.Plan(Kind.AOSOA, 1025, 2, 8, 8, 1024);
        assertEquals(32_768L, tiled.byteLength(),
                "one extra row requires a second fully reserved tile");
        assertEquals(8_184L, tiled.byteOffset(1023, 0));
        assertEquals(16_384L, tiled.byteOffset(1024, 0));
        assertEquals(24_576L, tiled.byteOffset(1024, 1));
        assertNotEquals(tiled.byteOffset(1023, 0), tiled.byteOffset(1024, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> tiled.byteOffset(1025, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> tiled.byteOffset(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> tiled.byteOffset(1024, 2));

        var aos = new DataOrientedLayout.Plan(Kind.AOS, 1025, 2, 8, 8, 1);
        var soa = new DataOrientedLayout.Plan(Kind.SOA, 1025, 2, 8, 8, 1);
        assertEquals(16_400L, aos.byteLength());
        assertEquals(16_400L, soa.byteLength());
        assertEquals(16_384L, aos.byteOffset(1024, 0));
        assertEquals(8_192L, soa.byteOffset(1024, 0));
        assertEquals(16_392L, soa.byteOffset(1024, 1));
        // A workload hint cannot change byteOffset for an existing plan.
        assertEquals(16_384L, tiled.byteOffset(1024, 0));

        assertThrows(IllegalArgumentException.class,
                () -> new DataOrientedLayout.Plan(Kind.AOSOA, 1025, 2, 8, 8, 2048));
        assertThrows(IllegalArgumentException.class,
                () -> new DataOrientedLayout.Plan(Kind.AOSOA, 1025, 2, 8, 8, 1023));
        assertThrows(IllegalArgumentException.class,
                () -> new DataOrientedLayout.Plan(Kind.SOA, 1025, 2, 8, 8, 1024));
    }
}
