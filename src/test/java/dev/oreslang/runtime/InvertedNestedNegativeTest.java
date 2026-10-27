package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class InvertedNestedNegativeTest {
    @Test void nestedValueCodecRejectsReferenceAndMutableShapes() {
        for (String type : new String[]{
                "string", "Array<i64>", "Option<i64>",
                "struct{x: i64}", "InvertedArray<Row>"}) {
            String source = "define inverted class Packet as "
                    + "data: record{inner: " + type + "}; end";
            Ast.ClassDecl decl = (Ast.ClassDecl) Parser.parse(source)
                    .modules().getFirst().declarations().getFirst();
            assertThrows(IllegalArgumentException.class,
                    () -> InvertedArray.Schema.fromClass(decl), type);
        }
    }
}
