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
    void unmarkedClassFieldsAndMethodsArePrivate() {
        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class, onlyDeclaration("""
                define pub class Vault as
                  val int secret = 3;
                  pub val int count = 5;
                  get_secret(): int { return self.secret; }
                  pub get_count(): int { return self.count; }
                  static fnc hidden(): int { return 7; }
                  pub static fnc visible(): int { return 8; }
                end
                """));
        assertEquals(Ast.Visibility.PRIVATE, klass.fields().get(0).visibility());
        assertEquals(Ast.Visibility.PUBLIC, klass.fields().get(1).visibility());
        assertEquals(Ast.Visibility.PRIVATE, klass.methods().get(0).visibility());
        assertEquals(Ast.Visibility.PUBLIC, klass.methods().get(1).visibility());
        assertEquals(Ast.Visibility.PRIVATE, klass.methods().get(2).visibility());
        assertEquals(Ast.Visibility.PUBLIC, klass.methods().get(3).visibility());
    }

    @Test
    void implicitPrivateFieldAndMethodAreNotCallableFromOtherClass() {
        IllegalArgumentException field = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.check(Parser.parse("""
                        define pub class Vault as
                          val int secret = 7;
                        end
                        fnc steal(Vault item): int { return item.secret; }
                        """)));
        assertTrue(field.getMessage().contains("private field"), field.getMessage());

        IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.check(Parser.parse("""
                        define pub class Vault as
                          hidden(): int { return 42; }
                        end
                        fnc steal(Vault item): int { return item.hidden(); }
                        """)));
        assertTrue(method.getMessage().contains("private method"), method.getMessage());
    }

    @Test
    void conflictingPublicPrivateModifiersFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define pub private class Vault as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub define pub type Vault = int;"));
    }
}
