package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * All source annotation applications default to non-repeatable until a
 * registered schema defines explicit repeatability. The check is performed
 * before any target-specific expansion can discard or reinterpret duplicates.
 */
final class ParserAnnotationRepeatabilityTest {
    @Test
    void rejectsDuplicatesOnCallableModuleFieldAndMethodTargets() {
        String[] sources = {
                "@Ret(int) @Ret(int) fnc main(): int { return 1; }",
                "@BuildMetadata @BuildMetadata define module demo as end",
                "define class Person as @FromJson(\"id\") @FromJson(\"id\") id: int; end",
                "define class Person as @Audited @Audited read(): int { return 1; } end",
                "@Audited @Audited fnc main(): int { return 1; }",
                "@NamedParams(x=1) @NamedParams(x=2) fnc main(): int { return 1; }"
        };
        for (String source : sources) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(ex.getMessage().contains("duplicate @"),
                    () -> source + ": " + ex.getMessage());
        }
    }

    @Test
    void separateDeclarationApplicationsDoNotCollide() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                @BuildMetadata define module first as end
                @BuildMetadata define module second as end
                """));
        assertEquals(2, program.modules().size());
        assertEquals("BuildMetadata", program.modules().getFirst().annotations().getFirst().name());
        assertEquals("BuildMetadata", program.modules().getLast().annotations().getFirst().name());
    }

    @Test
    void keepsSingleImplementedReturnAndFieldAnnotationPath() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                @BuildMetadata define module demo as
                  @Ret(int) fnc answer(): int { return 42; }
                  define class User as
                    @FromJson("user_id") id: int;
                  end
                end
                """));
        assertEquals(1, program.modules().size());
        assertEquals("BuildMetadata", program.modules().getFirst().annotations().getFirst().name());
    }

    @Test
    void rejectsDuplicateNamedArgumentsBeforeAnyExpansion() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("@NamedParams(x=1,x=2) fnc main(): int { return 1; }"));
        assertTrue(ex.getMessage().contains("duplicate named type parameter"),
                ex.getMessage());
    }
}
