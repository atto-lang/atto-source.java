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
    void memoryIdentityRemainsExplicitAndStable() {
        Ast.Program program = Parser.parse("fnc main() -> void { return; }");
        MacroSourceUnitIdentity.attach(
                program, MacroSourceUnitIdentity.MEMORY_UNIT);
        assertEquals(
                MacroSourceUnitIdentity.MEMORY_UNIT,
                MacroSourceUnitIdentity.unitId(program));
    }
}
