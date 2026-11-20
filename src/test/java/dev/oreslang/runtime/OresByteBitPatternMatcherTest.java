package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the very same matcher used by OresEvalRootNode, not a separate JS
 * model or mocked Bytes storage. Does not prove full guest AOT lowering.
 */
final class OresByteBitPatternMatcherTest {
    private static Ast.SequenceElement lit(int value) {
        return new Ast.SequenceElement(value, null);
    }
    private static Ast.SequenceElement bind(String name) {
        return new Ast.SequenceElement(null, name);
    }
    private static Ast.SequencePattern bytes(List<Ast.SequenceElement> elements, String tail) {
        return new Ast.SequencePattern(Ast.SequenceUnit.BYTE, elements, tail);
    }
    private static Ast.SequencePattern bits(List<Ast.SequenceElement> elements, String tail) {
        return new Ast.SequencePattern(Ast.SequenceUnit.BIT, elements, tail);
    }

    @Test
    void literalOnlyMatchesReturnSharedEmptyMapWithoutCreatingCaptures() {
        try (OresBytes b = OresBytes.copyOf(new byte[]{3})) {
            assertSame(Map.of(), OresByteBitPatternMatcher.captures(bytes(List.of(lit(3)), null), b));
            assertNull(OresByteBitPatternMatcher.captures(bytes(List.of(lit(4)), null), b));
            assertNull(OresByteBitPatternMatcher.captures(bytes(List.of(), null), b));
            assertNull(OresByteBitPatternMatcher.captures(bytes(List.of(lit(3),lit(4)), null), b));
        }
    }

    @Test
    void prefixPatternConsumesExpectedBytesWithoutAllocatingTail() {
        try (OresBytes b = OresBytes.copyOf(new byte[]{71,69,84,33})) {
            assertEquals(Map.of(), OresByteBitPatternMatcher.captures(
                    bytes(List.of(lit(71),lit(69),lit(84)), "_"),b));
            assertNull(OresByteBitPatternMatcher.captures(
                    bytes(List.of(lit(80),lit(69),lit(84)), "_"), b));
            assertNull(OresByteBitPatternMatcher.captures(
                    bytes(List.of(lit(71),lit(69),lit(84)), null), b));
            assertNull(OresByteBitPatternMatcher.captures(bytes(
                    List.of(lit(71), lit(69),lit(84),lit(33),lit(0)), "_"),b));
        }
    }

    @Test
    void emptyBytesAndBitsAreExactOnly() {
        try (OresBytes e = OresBytes.empty(); OresBytes nonempty = OresBytes.copyOf(new byte[]{0})) {
            assertEquals(Map.of(), OresByteBitPatternMatcher.captures(bytes(List.of(), null),e));
            assertEquals(Map.of(), OresByteBitPatternMatcher.captures(bits(List.of(), null),e));
            assertNull(OresByteBitPatternMatcher.captures(bytes(List.of(), null),nonempty));
            assertNull(OresByteBitPatternMatcher.captures(bits(List.of(), null),nonempty));
            assertThrows(IllegalArgumentException.class,() -> bytes(List.of(),"_"));
        }
    }

    @Test
    void bitLiteralWidthIsOneBitButBytesHasEightBitsPerInput() {
        try (OresBytes b = OresBytes.copyOf(new byte[]{(byte) 0xa5})) {
            assertNull(OresByteBitPatternMatcher.captures(bits(List.of(lit(1)),null),b));
            assertEquals(Map.of(), OresByteBitPatternMatcher.captures(bits(List.of(
                    lit(1),lit(0),lit(1),lit(0),lit(0),lit(1),lit(0),lit(1)), null),b));
            assertEquals(Map.of(), OresByteBitPatternMatcher.captures(bits(List.of(
                    lit(1),lit(0),lit(1)), "_"),b));
            assertNull(OresByteBitPatternMatcher.captures(bits(List.of(
                    lit(0),lit(0),lit(1)), "_"),b));
        }
    }

    @Test
    void bitMatcherCrossesByteBoundaryWithoutRounding() {
        try (OresBytes b = OresBytes.copyOf(new byte[]{(byte) 0x80, (byte) 0x7f})) {
            List<Ast.SequenceElement> values = new ArrayList<>();
            values.add(lit(1));
            for (int i=0;i<8;i++) values.add(lit(0));
            values.add(lit(1));
            assertEquals(Map.of(), OresByteBitPatternMatcher.captures(bits(values, "_"),b));
            values.set(8,lit(1));
            assertNull(OresByteBitPatternMatcher.captures(bits(values, "_"),b));
        }
    }

    @Test
    void scalarBindingsDoNotEscapeAfterMismatch() {
        try (OresBytes b = OresBytes.copyOf(new byte[]{5,6})) {
            assertNull(OresByteBitPatternMatcher.captures(bytes(List.of(bind("first"),lit(7)),null),b));
            Map<String,Object> captured = OresByteBitPatternMatcher.captures(
                    bytes(List.of(bind("first"),lit(6)), null),b);
            assertEquals(Map.of("first",5L),captured);
            assertEquals(Long.class,captured.get("first").getClass());

            Map<String,Object> flags= OresByteBitPatternMatcher.captures(
                    bits(List.of(bind("flag"),bind("_"),lit(0)), "_"),b);
            assertEquals(Map.of("flag",false),flags);
            assertEquals(Boolean.class,flags.get("flag").getClass());
        }
    }

    @Test
    void forgedNamedTailRejectsEvenWhenInputWouldNotMatch() {
        Ast.SequencePattern forged=bytes(List.of(lit(71),lit(69),lit(84)),"rest");
        assertThrows(IllegalArgumentException.class,()->OresByteBitPatternMatcher.captures(forged, null));
        try (OresBytes b = OresBytes.empty()) {
            assertThrows(IllegalArgumentException.class,()->OresByteBitPatternMatcher.captures(forged,b));
        }
    }

    @Test
    void staleOwnerTakeOrCloseRejectsBeforeReadingBits() {
        OresBytes original=OresBytes.copyOf(new byte[]{1});
        OresBytes receiver=original.take();
        try {
            assertThrows(IllegalStateException.class,()->OresByteBitPatternMatcher.captures(
                    bytes(List.of(lit(1)),null),original));
            assertEquals(Map.of(),OresByteBitPatternMatcher.captures(
                    bytes(List.of(lit(1)),null),receiver));
        } finally {
            receiver.close();
        }
        assertThrows(IllegalStateException.class,()->OresByteBitPatternMatcher.captures(
                bits(List.of(lit(0)),"_"),receiver));
    }

    @Test
    void randomizedExactAndPrefixChecksAreDeterministic() {
        Random random=new Random(20261114L);
        for(int sample=0;sample<4096;sample++){
            byte[] raw=new byte[random.nextInt(64)+1];
            random.nextBytes(raw);
            try(OresBytes b=OresBytes.copyOf(raw)){
                assertEquals(Map.of(),OresByteBitPatternMatcher.captures(
                        bytes(List.of(lit(raw[0]&255)),"_"),b));
                assertEquals(raw.length==1 ? Map.of() : null,
                        OresByteBitPatternMatcher.captures(
                                bytes(List.of(lit(raw[0]&255)),null),b));
                assertEquals(Map.of(),OresByteBitPatternMatcher.captures(
                        bits(List.of(lit((raw[0] >>> 7)&1)),"_"),b));
                assertNull(OresByteBitPatternMatcher.captures(
                        bits(List.of(lit(1-((raw[0] >>> 7)&1))),"_"),b));
            }
        }
    }
}
