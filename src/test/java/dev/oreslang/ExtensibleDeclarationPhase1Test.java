package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Parser-only phase 1a: never evidence of a standalone native guest. */
final class ExtensibleDeclarationPhase1Test {
    private static final String HEADER = "define keyword entity as class end\n";

    @Test void ordinaryClassAstAndVisibilityArePreserved() {
        for (String visibility : new String[] {"", "pub "}) {
            var manual = Parser.parseStrictAtto("define " + visibility + "class Customer as\nend\n");
            var custom = Parser.parseStrictAtto(HEADER + "define " + visibility + "entity Customer as\nend\n");
            assertEquals(manual, custom);
        }
    }

    @Test void descriptorDoesNotLeakAcrossFilesOrLegacyMode() {
        Parser.parseStrictAtto(HEADER + "define entity Customer as end");
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parseStrictAtto("define entity Customer as end"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(HEADER + "define entity Customer as end"));
    }

    @Test void unsupportedFormsFailClosed() {
        String tail = "\ndefine class Control as end";
        String[][] cases = {
            {"define keyword entity as actor end", "KIND_BASE_UNSUPPORTED"},
            {"define keyword entity as class requires Entity; end", "KIND_REQUIREMENTS_UNSUPPORTED"},
            {"define pub keyword entity as class end", "KIND_VISIBILITY_UNSUPPORTED"},
            {"define keyword workflow as class end", "KIND_RESERVED"},
            {"define keyword Map as class end", "KIND_RESERVED"},
            {HEADER + HEADER, "KIND_DUPLICATE"},
            {"define class First as end\n" + HEADER, "KIND_HEADER_REQUIRED"},
            {HEADER + "define entity Map as end", "KIND_HEADER_UNSUPPORTED"},
            {HEADER + "define entity Box<T> as end", "KIND_HEADER_UNSUPPORTED"},
        };
        for (String[] c : cases) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto(c[0] + tail), c[0]);
            assertTrue(e.getMessage().contains(c[1]), e::getMessage);
        }
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parseStrictAtto(HEADER + "define module inner as\ndefine entity Box as end\nend"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parseStrictAtto("define entity Box as end\n" + HEADER));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parseStrictAtto("import keyword entity from \"std.database\";\ndefine entity Box as end"));
    }

    @Test void contextualNameRemainsAnOrdinaryLocalIdentifier() {
        var manual = Parser.parseStrictAtto("define class Customer as end\n"
                + "define fnc value(int entity): int { return entity; }");
        var custom = Parser.parseStrictAtto(HEADER + "define entity Customer as end\n"
                + "define fnc value(int entity): int { return entity; }");
        assertEquals(manual, custom);
    }

    @Test void boundedHeaderRejectsExcessiveDescriptors() {
        var src = new StringBuilder();
        for (int i = 0; i < 129; i++) src.append("define keyword k").append(i).append(" as class end\n");
        src.append("define class Control as end");
        var e = assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(src.toString()));
        assertTrue(e.getMessage().contains("KIND_BUDGET"), e::getMessage);
    }
}
