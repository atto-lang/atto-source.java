package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema AST admission is production parser work, not a guest execution pass.
 * This test must be flipped to real LinkedProgramRunner guest behavior only
 * after typed row-lifetime MIR and a compiler-to-SoA ABI exist.
 */
final class DopInvertedGuestAdmissionTest {
    private static final String MISSING_BACKEND =
            "DOP_INVERTED_BACKEND_UNSUPPORTED";

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("contracts", "dop-inverted-v1", name));
    }

    private static String schemaOnly(String text) {
        int main = text.indexOf("fnc main()");
        assertTrue(main > 0, "canonical DOP fixture must contain a main");
        return text.substring(0, main);
    }

    private static Ast.InvertedStructDecl parsedSchema(String source) {
        Ast.Program program = Parser.parse(source);
        assertEquals(1, program.modules().size());
        assertEquals(1, program.modules().getFirst().declarations().size());
        return assertInstanceOf(Ast.InvertedStructDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    private static void rejectsWithoutGuestFallback(String source) {
        var parser = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source));
        assertTrue(parser.getMessage().contains(MISSING_BACKEND),
                "unlowered storage constructor cannot become an ordinary Java call");
        var compiler = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(compiler.getMessage().contains(MISSING_BACKEND),
                "compiler must reject unimplemented guest storage ABI");
    }

    @Test
    void canonicalSchemaParsesIntoNominalImmutableAst() throws Exception {
        for (String name : List.of("swap-reuse.atto", "removed-index-trap.atto")) {
            var schema = parsedSchema(schemaOnly(fixture(name)));
            assertEquals("Row", schema.name());
            assertEquals(List.of("value", "token"),
                    schema.columns().stream().map(Ast.InvertedColumnDecl::name).toList());
            assertEquals(List.of("int", "int"),
                    schema.columns().stream().map(c -> c.type().name()).toList());
            // The canonical fixture uses 'let value' and an implicit
            // immutable token. Losing this bit makes future MIR allow a
            // write to a value column that LLVM already treats as fixed.
            assertEquals(List.of(true, false),
                    schema.columns().stream().map(Ast.InvertedColumnDecl::mutableField).toList());
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(schemaOnly(fixture(name))),
                    "schema parsed but not executable until ownership MIR exists");
        }
    }

    @Test
    void canonicalSwapReuseCannotMasqueradeAsJavaGuestParity() throws Exception {
        String source = fixture("swap-reuse.atto");
        assertTrue(source.contains("rows.erase_swap(0)"));
        assertTrue(source.contains("return rows.get(1, value)"));
        rejectsWithoutGuestFallback(source);
    }

    @Test
    void canonicalRemovedIndexTrapCannotMasqueradeAsJavaGuestParity() throws Exception {
        String source = fixture("removed-index-trap.atto");
        assertTrue(source.contains("rows.erase_swap(0)"));
        rejectsWithoutGuestFallback(source);
    }

    @Test
    void nominalSchemaIsBoundByFieldCountTypesAndUniqueNames() {
        String prefix = "define inverted struct Row as ";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(prefix + "value: int; end"))
                .getMessage().contains("exactly two columns"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(prefix + "value: int; value: int; end"))
                .getMessage().contains("duplicate inverted column"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(prefix + "value: int; token: bool; end"))
                .getMessage().contains("scalar int"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(prefix + "value: int; token: int; extra: int; end"))
                .getMessage().contains("exactly two columns"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define inverted struct Row<T> as value: int; token: int; end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(prefix + "value: int = 7; token: int; end"));
    }

    @Test
    void perColumnMutabilitySurvivesParsingWithoutAuthorizingGuestExecution() {
        var allImmutable = parsedSchema("define inverted struct R as "
                + "x: int; y: int; end");
        assertEquals(List.of(false, false),
                allImmutable.columns().stream().map(Ast.InvertedColumnDecl::mutableField).toList());
        var bothMutable = parsedSchema("define inverted struct R as "
                + "let x: int; let y: int; end");
        assertEquals(List.of(true, true),
                bothMutable.columns().stream().map(Ast.InvertedColumnDecl::mutableField).toList());
        var mixed = parsedSchema("define inverted struct R as "
                + "x: int; let y: int; end");
        assertEquals(List.of(false, true),
                mixed.columns().stream().map(Ast.InvertedColumnDecl::mutableField).toList());
        for (var schema : List.of(allImmutable, bothMutable, mixed)) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            "define inverted struct R as "
                                    + (schema.columns().getFirst().mutableField() ? "let " : "")
                                    + "x: int; "
                                    + (schema.columns().get(1).mutableField() ? "let " : "")
                                    + "y: int; end")).getMessage().contains(MISSING_BACKEND));
        }
    }

    @Test
    void moduleAndPublicModifiersKeepNominalSchemaButStillRejectTypecheck() {
        String text = "define module M\n"
                + "  define inverted struct Row as let value: int; token: int; end\n"
                + "end";
        var program = Parser.parse(text);
        assertEquals("M", program.modules().getFirst().name());
        assertInstanceOf(Ast.InvertedStructDecl.class,
                program.modules().getFirst().declarations().getFirst());
        String publicSchema = "pub define inverted struct Row as "
                + "value: int; token: int; end";
        assertEquals(Ast.Visibility.PUBLIC, parsedSchema(publicSchema).visibility());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(text))
                .getMessage().contains(MISSING_BACKEND));
    }

    @Test
    void invertedClassRemainsUnsupportedAndContextualNameRemainsLegal() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define /*gap*/ inverted class Vec as end"))
                .getMessage().contains(MISSING_BACKEND));
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc inverted(): int { return 42; }"));
    }
}
