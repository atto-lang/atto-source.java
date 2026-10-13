package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ExplicitVisibilityContractTest {
    private static Ast.Decl onlyDeclaration(String source) {
        Ast.Program parsed = Parser.parse(source);
        assertEquals(1, parsed.modules().size());
        assertEquals(1, parsed.modules().getFirst().declarations().size());
        return parsed.modules().getFirst().declarations().getFirst();
    }

    @Test
    void classAndAliasDeclarationsDefaultPrivateWithOrWithoutDefine() {
        for (String text : new String[] {
                "class Hidden as end",
                "define class Hidden as end",
                "private class Hidden as end"
        }) {
            Ast.ClassDecl decl = assertInstanceOf(Ast.ClassDecl.class, onlyDeclaration(text));
            assertEquals(Ast.Visibility.PRIVATE, decl.visibility(), text);
        }

        for (String text : new String[] {
                "pub class Shared as end",
                "pub define class Shared as end",
                "define pub class Shared as end",
                "class pub define Shared as end"
        }) {
            Ast.ClassDecl decl = assertInstanceOf(Ast.ClassDecl.class, onlyDeclaration(text));
            assertEquals(Ast.Visibility.PUBLIC, decl.visibility(), text);
        }

        for (String text : new String[] {
                "type Secret = int;",
                "define type Secret = int;"
        }) {
            Ast.TypeAliasDecl decl = assertInstanceOf(Ast.TypeAliasDecl.class, onlyDeclaration(text));
            assertEquals(Ast.Visibility.PRIVATE, decl.visibility(), text);
        }
        for (String text : new String[] {
                "pub type Exported = int;",
                "pub define type Exported = int;",
                "define pub type Exported = int;"
        }) {
            Ast.TypeAliasDecl decl = assertInstanceOf(Ast.TypeAliasDecl.class, onlyDeclaration(text));
            assertEquals(Ast.Visibility.PUBLIC, decl.visibility(), text);
        }
    }

    @Test
    void moduleFieldsAndFunctionsDefaultPrivate() {
        Ast.Program parsed = Parser.parse("""
                define module library as
                  val int secret = 7;
                  fnc hidden(): int { return 7; }
                  pub val int visible = 8;
                  pub fnc exported(): int { return 8; }
                end
                """);
        var declarations = parsed.modules().getFirst().declarations();
        assertEquals(Ast.Visibility.PRIVATE, assertInstanceOf(Ast.FieldDecl.class, declarations.get(0)).visibility());
        assertEquals(Ast.Visibility.PRIVATE, assertInstanceOf(Ast.FunctionDecl.class, declarations.get(1)).visibility());
        assertEquals(Ast.Visibility.PUBLIC, assertInstanceOf(Ast.FieldDecl.class, declarations.get(2)).visibility());
        assertEquals(Ast.Visibility.PUBLIC, assertInstanceOf(Ast.FunctionDecl.class, declarations.get(3)).visibility());
    }

    @Test
    void privateAliasCannotBeImportedEvenFromSameDirectory() {
        var files = Map.of(
                "lib/types.ores", "type PrivateId = int; pub type PublicId = int;",
                "lib/consumer.ores", "import type PrivateId from './types'; pub fnc main(): void { return; }");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> new IncrementalCompiler().compile(files));
        assertTrue(error.getMessage().contains("does not match an exported declaration"), error.getMessage());
    }

    @Test
    void explicitPublicAliasIsImportable() {
        var files = Map.of(
                "lib/types.ores", "type PrivateId = int; define pub type PublicId = int;",
                "lib/consumer.ores", "import type PublicId from './types'; pub fnc main(): void { return; }");
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(files));
    }

    @Test
    void abstractSignatureCannotSilentlyBecomePublic() {
        Ast.ClassDecl shape = assertInstanceOf(Ast.ClassDecl.class,
                onlyDeclaration("define abstract class Shape as area(): int; end"));
        assertEquals(Ast.Visibility.PRIVATE, shape.methods().getFirst().visibility());
        assertEquals(Ast.Visibility.PUBLIC, assertInstanceOf(Ast.ClassDecl.class,
                onlyDeclaration("define abstract class Shape as pub area(): int; end"))
                .methods().getFirst().visibility());
    }

    @Test
    void conflictingPublicPrivateModifiersFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub private class Vault as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub define pub type Vault = int;"));
    }
}
