package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NominalMessageEnumTest {
    private static final String DECLARATION = """
            define enum Messages as
                Reset struct { reset: int } 1;
                Delta struct { delta: int } 2;
                Ready 3;
            end
            """;

    @Test
    void parsesStableTaggedPayloadSchema() {
        Ast.Program program = Parser.parse(DECLARATION);
        Ast.EnumDecl declaration = (Ast.EnumDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals("Messages", declaration.name());
        assertEquals(3, declaration.variants().size());
        assertEquals(1L, declaration.variants().getFirst().tag());
        assertTrue(declaration.variants().getFirst().payload().isRecordType());
        assertNull(declaration.variants().getLast().payload());
    }

    @Test
    void rejectsAmbiguousAndMissingProtocolTags() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as A 1; B 1; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as A 1; A 2; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as A 0; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as A struct { x: int }; end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define enum M as end"));
    }
}
