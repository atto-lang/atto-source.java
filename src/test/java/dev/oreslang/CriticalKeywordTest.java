package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CriticalKeywordTest {
    @Test void criticalModifiersPreserveFileAndInstanceEffects() {
        Ast.Program parsed = Parser.parse("""
                pub critical fnc file_counter(): int { return 1; }
                define actor Foo as
                    suspend critical critical_section(): int { return 3; }
                    receive(ActorMail<String> mail): void { return; }
                end
                """);
        Ast.ModuleDecl root = parsed.modules().getFirst();
        Ast.FunctionDecl file = (Ast.FunctionDecl) root.declarations().get(0);
        Ast.ClassDecl actor = (Ast.ClassDecl) root.declarations().get(1);
        Ast.MethodDecl method = actor.methods().getFirst();
        assertTrue(file.critical());
        assertFalse(file.suspends());
        assertTrue(method.critical());
        assertTrue(method.suspends());
        assertFalse(actor.methods().get(1).critical());
    }

    @Test void criticalIsNotSilentlyAllowedOnDataOrContainers() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical critical fnc bad(): void { return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical generator fnc bad(): int { yield 1; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("critical define class Foo as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define actor Foo as critical let int value = 1; end"));
    }

    @Test void criticalDeclarationsCompileAndExecute() throws Exception {
        String code = """
                pub critical fnc file_value(): int { return 23; }
                define class Foo as
                  pub critical answer(): int { return 11; }
                end
                pub fnc main(): void {
                    val foo = new Foo();
                    stdio.stdout.write(foo.answer());
                    stdio.stdout.write("|");
                    stdio.stdout.write(file_value());
                    return;
                }
                """;
        dev.oreslang.compiler.OresCompiler.parseAndTypeCheck(code);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        try (org.graalvm.polyglot.Context context =
                     org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                             .allowAllAccess(false).out(stdout).build()) {
            context.eval(org.graalvm.polyglot.Source
                    .newBuilder(OresLanguage.ID, code, "critical-run.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("11|23", stdout.toString().strip());
    }

    @Test void criticalCanAppearAfterVisibilityOrBeforeStatic() {
        Ast.Program parsed = Parser.parse("""
                define class Foo as
                    pub critical run(): int { return 1; }
                    critical static fnc value(): int { return 2; }
                end
                """);
        Ast.ClassDecl owner = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        assertTrue(owner.methods().get(0).critical());
        assertTrue(owner.methods().get(1).critical());
    }
}
