package dev.oreslang;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct AST construction, macro expansion and imported declarations must
 * never bypass the parser's implicit structural readonly capability.
 */
final class AnonymousParamAstInvariantTest {
    private static final Ast.TypeRef ID = Ast.TypeRef.simple("int");

    @Test
    void everyClosedInlineShapeCanonicalizesToStructural() {
        for (Ast.TypeRef shape : List.of(
                Ast.TypeRef.structType(Map.of("id", ID)),
                Ast.TypeRef.recordType(Map.of("id", ID)),
                Ast.TypeRef.readonlyRecordType(Map.of("id", ID)),
                Ast.TypeRef.structType(Map.of()),
                Ast.TypeRef.recordType(Map.of())
        )) {
            Ast.Param implicit = new Ast.Param(shape, "value", false, false);
            Ast.Param explicit = new Ast.Param(shape, "value", true, false);
            assertTrue(implicit.structural(), shape.toString());
            assertEquals(explicit, implicit, shape.toString());
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.Param(shape, "value", false, true));
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.Param(shape, "value", true, true));
        }
    }

    @Test
    void namedContractsAndGenericWrappersRemainNominalByDefault() {
        Ast.TypeRef named = Ast.TypeRef.simple("HasId");
        Ast.TypeRef wrapper = new Ast.TypeRef(
                "Option", List.of(Ast.TypeRef.structType(Map.of("id", ID))), false);
        assertFalse(new Ast.Param(named, "value", false, false).structural());
        assertFalse(new Ast.Param(wrapper, "value", false, false).structural());
        assertTrue(new Ast.Param(named, "value", true, false).structural());
    }
}
