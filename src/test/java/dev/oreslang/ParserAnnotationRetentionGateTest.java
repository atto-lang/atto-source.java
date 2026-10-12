package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ParserAnnotationRetentionGateTest {
    @Test
    void rejectsAnnotationsThatDeclarationAstsCannotRetain() {
        String[] sources = {
                "@Audited define class Example as end",
                "@Audited define actor Worker as end",
                "@Audited actor Worker as end",
                "@Audited interface Example as end",
                "@Audited define interface Example as end",
                "@Audited type Alias = int;"
        };
        for (String source : sources) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("silently discarded"),
                    () -> source + ": " + error.getMessage());
        }
    }

    @Test
    void preservesExistingModuleAndFieldAnnotationPaths() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                @BuildMetadata
                define module demo as
                  define class Person as
                    @FromJson("user_id")
                    id: int;
                  end
                end
                """));
        assertEquals("BuildMetadata", program.modules().getFirst().annotations().getFirst().name());
        Ast.ClassDecl person = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals("FromJson", person.fields().getFirst().annotations().getFirst().name());
    }

    @Test
    void unimplementedCompilerAnnotationsCannotSilentlyBecomeNoOpMetadata() {
        for (String annotation : new String[] {
                "@derive(Eq)", "@repr(c)", "@align(64)", "@cfg(test)",
                "@inline(always)", "@GenerateBuilder", "@GenerateSchema",
                "@ActorMessage", "@RequiresState(read)", "@expand(aot)"
        }) {
            String source = annotation + "\n" + "define module demo as end";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), annotation);
            assertTrue(ex.getMessage().contains("guest-AOT lowering"), ex.getMessage());
        }
    }

    @Test
    void rejectsCodeGeneratingFromJsonOnModuleTargets() {
        // The parser retains module metadata. Semantic admission must still
        // reject a code-generating attribute when no target accessors exist.
        for (String source : new String[] {
                "@FromJson(\"id\") define module app as end",
                "@FromJson(\"id\") define module app as pub fnc main(): int { return 42; } end"
        }) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), source);
            assertTrue(ex.getMessage().contains("not module"), ex.getMessage());
        }
    }

    @Test
    void implementedAlignMetadataSurvivesFailClosedAnnotationAdmission() {
        Ast.Program parsed = assertDoesNotThrow(() -> Parser.parse("""
                define module demo as
                    define class Packet as
                        @Align(16)
                        let int payload = 1;
                    end
                    fnc read(): int {
                        @Align(8)
                        let int value = 2;
                        return value;
                    }
                end
                """));
        Ast.ClassDecl packet = (Ast.ClassDecl) parsed.modules().getFirst()
                .declarations().getFirst();
        Ast.TypeRef aligned = packet.fields().getFirst().type();
        assertTrue(aligned.arguments().stream()
                        .anyMatch(arg -> arg.isNamedParameter()
                                && arg.namedParameterName().equals("align")),
                "implemented @Align field metadata must remain in the type AST");
    }

    @Test
    void rejectsImplementedTypeOnlyMetadataOnUnsupportedTargets() {
        String[] sources = {
                "@Align(16) define module demo as end",
                "define module demo as @Align(16) fnc sample(): int { return 1; } end",
                """
                define module demo as
                  define class Packet as
                    @Align(16)
                    fnc read(): int { return 1; }
                  end
                end
                """
        };
        for (String source : sources) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), source);
            assertTrue(error.getMessage().contains("compile-time type metadata"),
                    () -> source + ": " + error.getMessage());
        }
    }

    @Test
    void retainsSpecializedCallableAnnotationError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("@structural define class Example as end"));
        assertTrue(error.getMessage().contains("@structural"), error.getMessage());
    }
}
