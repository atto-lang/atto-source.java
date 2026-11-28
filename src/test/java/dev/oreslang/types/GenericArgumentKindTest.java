package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GenericArgumentKindTest {
    @Test
    void integerMetadataCannotBindAnOrdinaryTypeFormal() {
        var arg = Ast.TypeRef.namedParameter("T", Ast.TypeRef.intLiteral("3"));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> GenericCallArguments.bind(List.of("T"), List.of(arg), "test"));
        assertTrue(failure.getMessage().contains("const value"), failure.getMessage());
    }

    @Test
    void rootShapeRejectedButNestedCollectionShapePreserved() {
        var shape = Ast.TypeRef.sequenceShape(List.of(Ast.TypeRef.simple("int")));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> GenericCallArguments.bind(List.of("T"), List.of(shape), "test"));
        assertTrue(failure.getMessage().contains("sequence shape"), failure.getMessage());
        var array = new Ast.TypeRef("Array", List.of(shape), false);
        var bound = GenericCallArguments.bind(List.of("T"), List.of(array), "test");
        assertEquals(array, bound.explicitTypes().get("T"));
    }
}
