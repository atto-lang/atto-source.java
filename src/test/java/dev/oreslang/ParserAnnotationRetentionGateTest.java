package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
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
                "@Audited type Alias = int;",
                "@Audited define pub type Alias = int;",
                "define module demo as @Audited define pub type Alias = int; end"
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
                "@ActorMessage", "@RequiresState(read)", "@expand(aot)",
                "@CallerRole(Admin)", "@CallableBy(Admin)", "@DefaultArgVals(value=1)"
        }) {
            String source = annotation + "\n" + "define module demo as end";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), annotation);
            assertTrue(ex.getMessage().contains("guest-AOT lowering"), ex.getMessage());
        }
    }

    @Test
    void rejectsDuplicateCallableReturnAnnotations() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("@Ret(int) @Ret(bool) fnc main(): int { return 1; }"));
        assertTrue(error.getMessage().contains("duplicate @Ret"), error.getMessage());
    }

    @Test
    void rejectsCompilerDirectivesMisplacedOnModulesButKeepsOpaqueMetadata() {
        for (String directive : new String[] {
                "@Ret(int)", "@FromJson(\"id\")", "@ActorMaxThreads",
                "@Critical(1)", "@RequiresPermission(\"ffi\")",
                "@CallerRole(Admin)", "@NamedParams(x=1)", "@Unsafe"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(directive + " define module demo as end"), directive);
            assertTrue(error.getMessage().contains("not a supported module annotation")
                    || error.getMessage().contains("guest-AOT lowering"), error.getMessage());
        }
        assertDoesNotThrow(() -> Parser.parse("@BuildMetadata define module demo as end"));
    }

    @Test
    void privilegeAndEffectAnnotationsCannotBeInertOnCallables() {
        for (String directive : new String[] {
                "@Critical(1)", "@RequiresPermission(\"ffi\")",
                "@CallerRole(Admin)", "@CallableBy(Admin)", "@ActorMaxThreads",
                "@ActorMiddleware(Middleware)", "@Unsafe", "@AllowAll"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(directive + " fnc main(): int { return 1; }"), directive);
            assertTrue(error.getMessage().contains("guest-AOT lowering"), error.getMessage());
        }
    }

    @Test
    void rejectsMisplacedReturnAnnotationBeforeAstOrExpansion() {
        for (String source : new String[] {
                "define class Example as @Ret(int) id: int; end",
                "define class Example as @Ret(int) constructor() {} end",
                "define module demo as @Ret(int) let int id = 1; end"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("@Ret is only valid"), error.getMessage());
        }
    }

    @Test
    void retainsSpecializedCallableAnnotationError() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("@structural define class Example as end"));
        assertTrue(error.getMessage().contains("@structural"), error.getMessage());
    }
}
