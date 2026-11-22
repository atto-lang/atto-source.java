package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Top-level export contracts: syntax, forward resolution and visibility. */
final class DeclarativeExportsParserTest {
    private static final String DEFINITIONS = """
            define class Foo as
            end
            define class Bar as
            end
            fnc compute(int x): int { return x + 1; }
            """;
    private static final String EXPORTS = """
            export pub Foo;
            export pub[pkg] class Bar;
            export pub fnc compute;
            """;

    @Test void acceptsInferredAndExplicitKindAtEitherEndOfFile() {
        var top = Parser.parse(EXPORTS + DEFINITIONS);
        var bottom = Parser.parse(DEFINITIONS + EXPORTS);
        assertEquals(top.exports(), bottom.exports());
        assertEquals(3, top.exports().size());
        assertEquals(Ast.ExportScope.PUBLIC, top.exports().get(0).scope());
        assertNull(top.exports().get(0).kind());
        assertEquals(Ast.ExportKind.CLASS, top.exports().get(1).kind());
        assertEquals(Ast.ExportScope.PACKAGE, top.exports().get(1).scope());
        assertEquals(Ast.ExportKind.FNC, top.exports().get(2).kind());
    }

    @Test void exportsCanPrecedeImportsAfterNamespaceHeader() {
        String source = "use namespace test;\nexport pub Foo;\n" + DEFINITIONS;
        assertEquals("Foo", Parser.parse(source).exports().getFirst().name());
    }

    @Test void rejectsUnboundWrongKindDuplicateAndVisibilityConflict() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("export pub Missing;\n" + DEFINITIONS));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("export pub fnc Foo;\n" + DEFINITIONS));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("export pub Foo; export pub class Foo;\n" + DEFINITIONS));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("export pub[pkg] Foo; pub define class Foo as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("export pub[world] class Foo;\n" + DEFINITIONS));
    }

    @Test void methodBodiesCannotContainExportDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc main(): int {
                  export pub Foo;
                  return 0;
                }
                """));
    }
}
