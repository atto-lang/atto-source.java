package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MacroSourceUnitIdentityTest {
    @Test
    void attachmentCanonicalizesLikeIncrementalUnitIds() {
        Ast.Program program = Parser.parse("fnc main() -> void { return; }");
        MacroSourceUnitIdentity.attach(program, "pkg/tmp/../unit.ores");

        assertEquals("pkg/unit.ores", MacroSourceUnitIdentity.unitId(program));
        assertDoesNotThrow(
                () -> MacroSourceUnitIdentity.attach(program, "pkg/unit.ores"));
    }

    @Test
    void normalizationIsBackendNeutralAndRejectsWorkspaceDependentPaths() {
        Ast.Program windowsSpelling =
                Parser.parse("fnc main() -> void { return; }");
        MacroSourceUnitIdentity.attach(
                windowsSpelling, "pkg\\tmp\\..\\unit.ores");
        assertEquals(
                "pkg/unit.ores",
                MacroSourceUnitIdentity.unitId(windowsSpelling));

        for (String invalid : new String[] {
                "/workspace/pkg/unit.ores",
                "C:/workspace/pkg/unit.ores",
                "../outside.ores",
                "pkg/../../outside.ores"
        }) {
            Ast.Program program =
                    Parser.parse("fnc main() -> void { return; }");
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> MacroSourceUnitIdentity.attach(program, invalid),
                    invalid);
            assertTrue(
                    failure.getMessage().contains("logical relative path")
                            || failure.getMessage().contains("logical root"),
                    failure::toString);
        }
    }

    @Test
    void rejectsDriveRelativeAlternateStreamsControlsAndMalformedUnicode() {
        for (String invalid : new String[] {
                "C:relative\\unit.atto",
                "pkg/name.atto:stream",
                "pkg/name.atto" + (char) 0 + "hidden",
                "pkg/name.atto\nother",
                "pkg/name.atto\tother",
                "pkg/name.atto" + (char) 0x2028 + "other",
                "pkg/broken" + (char) 0xD800 + ".atto",
                "pkg/broken" + (char) 0xDC00 + ".atto",
                "a/".repeat(MacroSourceUnitIdentity.MAX_UNIT_ID_CHARS * 3)
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> MacroSourceUnitIdentity.normalizeUnitId(invalid),
                    "unsafe macro logical unit ID must fail closed");
        }
        assertEquals("pkg/value.atto",
                MacroSourceUnitIdentity.normalizeUnitId("pkg/parts/../value.atto"));
        String utf16 = "pkg/" + new String(Character.toChars(0x1F680)) + ".atto";
        assertEquals(utf16, MacroSourceUnitIdentity.normalizeUnitId(utf16));
    }

    @Test
    void oneProgramCannotBeReboundToDifferentCanonicalUnit() {
        Ast.Program program = Parser.parse("fnc main() -> void { return; }");
        MacroSourceUnitIdentity.attach(program, "pkg/unit.ores");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroSourceUnitIdentity.attach(program, "pkg/other.ores"));
        assertTrue(failure.getMessage().contains("different macro source-unit identity"),
                failure::toString);
    }

    @Test
    void macroExpansionRetainsCanonicalIdentityAcrossNewProgramAst() {
        Ast.Program parsed = MacroSourceUnitIdentity.attach(
                Parser.parse("""
                        define macro identity(Syntax<Expr> value) -> Syntax<Expr> {
                            return value;
                        }
                        fnc answer() -> int {
                            return identity!(syntax expr 42);
                        }
                        """), "pkg/parts/../answer.ores");

        Ast.Program expanded = MacroExpander.expand(parsed);
        assertNotSame(parsed, expanded);
        assertEquals("pkg/answer.ores", MacroSourceUnitIdentity.unitId(expanded));
        assertEquals("pkg/answer.ores", MacroSourceUnitIdentity.unitId(parsed));

        Ast.Program checked = OresCompiler.analyze(parsed);
        assertEquals("pkg/answer.ores", MacroSourceUnitIdentity.unitId(checked),
                "type/annotation expansion must not erase the incremental source ID");
    }

    @Test
    void linkedAndDirectChecksPreserveCanonicalIdentityWithoutMacros() {
        Ast.Program parsed = MacroSourceUnitIdentity.attach(
                Parser.parse("fnc answer() -> int { return 42; }"),
                "pkg/source.ores");

        assertEquals("pkg/source.ores",
                MacroSourceUnitIdentity.unitId(OresCompiler.analyze(parsed)));
        assertEquals("pkg/source.ores",
                MacroSourceUnitIdentity.unitId(OresCompiler.analyze(parsed, java.util.Map.of())));
    }

    @Test
    void memoryIdentityRemainsExplicitAndStable() {
        Ast.Program program = Parser.parse("fnc main() -> void { return; }");
        MacroSourceUnitIdentity.attach(
                program, MacroSourceUnitIdentity.MEMORY_UNIT);
        assertEquals(
                MacroSourceUnitIdentity.MEMORY_UNIT,
                MacroSourceUnitIdentity.unitId(program));
    }
}
