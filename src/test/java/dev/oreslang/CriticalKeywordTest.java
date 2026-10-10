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
