package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CanonicalDefineScopeTest {
    private static Ast.Decl onlyDeclaration(String source) {
        Ast.Program program = Parser.parse(source);
        assertEquals(1, program.modules().size());
        assertEquals(1, program.modules().getFirst().declarations().size());
        return program.modules().getFirst().declarations().getFirst();
    }

    @Test
    void fileRootCallableAcceptsBothDefineVisibilityOrdersWithoutChangingApi() {
        for (String spelling : new String[] {
                "define pub fnc entry(): int { return 7; }",
                "pub define fnc entry(): int { return 7; }",
                "pub fnc entry(): int { return 7; }"
        }) {
            Ast.FunctionDecl function =
                    assertInstanceOf(Ast.FunctionDecl.class, onlyDeclaration(spelling));
            assertEquals("entry", function.name());
            assertEquals(Ast.Visibility.PUBLIC, function.visibility());
            assertEquals("int", function.returnType().name());
        }
        assertInstanceOf(Ast.FunctionDecl.class,
                onlyDeclaration("define routine background(): void { return; }"));
    }

    @Test
    void moduleAcceptsRedundantDefineOnCallablesAndNestedTypeDeclarations() {
        Ast.Program program = Parser.parse("""
                define module inventory as
                  pub fnc unchanged(): int { return 1; }
                  define pub fnc canonical(): int { return 2; }
                  pub define fnc legacy(): int { return 3; }
                  pub class Item as
                  end
                  pub define class LegacyItem as
                  end
                end
                """);
        var decls = program.modules().getFirst().declarations();
        assertEquals(5, decls.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(Ast.Visibility.PUBLIC,
                    assertInstanceOf(Ast.FunctionDecl.class, decls.get(i)).visibility());
        }
        assertEquals(Ast.Visibility.PUBLIC,
                assertInstanceOf(Ast.ClassDecl.class, decls.get(3)).visibility());
        assertEquals(Ast.Visibility.PUBLIC,
                assertInstanceOf(Ast.ClassDecl.class, decls.get(4)).visibility());
    }

    @Test
    void classAndActorMemberDefineIsPureSyntaxCompatibility() {
        for (String member : new String[] {
                "pub fnc new(): Cart { return new Cart(); }",
                "pub define fnc new(): Cart { return new Cart(); }",
                "define pub fnc new(): Cart { return new Cart(); }"
        }) {
            Ast.ClassDecl cart = assertInstanceOf(Ast.ClassDecl.class, onlyDeclaration(
                    "define class Cart as " + member + " end"));
            assertEquals("new", cart.methods().getFirst().name());
            assertEquals(Ast.Visibility.PUBLIC, cart.methods().getFirst().visibility());
        }

        for (String member : new String[] {
                "pub fnc ping(): int { return 1; }",
                "define pub fnc ping(): int { return 1; }",
                "pub define fnc ping(): int { return 1; }"
        }) {
            Ast.ClassDecl actor = assertInstanceOf(Ast.ClassDecl.class, onlyDeclaration(
                    "define actor Probe as " + member + " end"));
            assertEquals("ping", actor.methods().getFirst().name());
            assertEquals(Ast.Visibility.PUBLIC, actor.methods().getFirst().visibility());
        }
    }

    @Test
    void cannotRedefineNestedTypesOrRepeatModifierMarkers() {
        for (String source : new String[] {
                "define class Outer as define class Inner as end end",
                "define class Outer as define define fnc bad(): int { return 1; } end",
                "pub define pub fnc bad(): int { return 1; }",
                "define pub pub fnc bad(): int { return 1; }"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }
}
