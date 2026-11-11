package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
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
        for (String spelling : new String[] {
                "define pub fnc entry(): int { return 7; }",
                "pub define fnc entry(): int { return 7; }",
                "pub fnc entry(): int { return 7; }"
        }) {
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(spelling)), spelling);
        }
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
    @Test
    void intrinsicNewRemainsIndependentOfTheNewableFactoryContract() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                define pub class Plain as
                end
                pub fnc make(): Plain {
                  return new Plain();
                }
                """)));
        IllegalArgumentException noFactory = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse("""
                        define pub class Plain as
                        end
                        pub fnc make(): Plain {
                          return Plain.new();
                        }
                        """)));
        assertTrue(noFactory.getMessage().contains("static function")
                        || noFactory.getMessage().contains("new"),
                noFactory.getMessage());
    }

    @Test
    void compilerBuiltinNewableRejectsGenericClassWitnessUntilAotSpecialization() {
        String program = """
                define pub class Box<T> implements Newable[Box<T>] as
                  pub fnc new(): Box<T> {
                    return new Box<T>();
                  }
                end
                """;
        // Backends may reject generic constructor syntax earlier; either way,
        // generic type-level witnesses must never silently be admitted.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(program)));
    }

    @Test
    void newIsOnlyAnExplicitNewableFactoryAndNeverAnImplicitConstructorAlias() {
        for (String declaration : new String[] {
                "pub fnc new(): Plain { return new Plain(); }",
                "pub static fnc new(): Plain { return new Plain(); }"
        }) {
            Ast.Program parsed = Parser.parse("define pub class Plain as " + declaration + " end");
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(parsed));
            assertTrue(failure.getMessage().contains("Newable"), failure.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define pub fnc bad(): int { val int new = 1; return 1; }"));
    }

    @Test
    void sealedNewableCannotBeSpoofedByUserDeclaredInterface() {
        Ast.Program parsed = Parser.parse("""
                define trait Newable as
                end
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.checkTypes(parsed));
        assertTrue(failure.getMessage().contains("reserved compiler-owned"), failure.getMessage());
    }

    @Test
    void newableFactoryHasStaticWitnessWithExactPublicReturnType() {
        Ast.Program program = Parser.parse("""
                define pub class Cart implements Newable[Cart] as
                  pub fnc new(): Cart { return new Cart(); }
                end
                define pub fnc build(): Cart { return Cart.new(); }
                """);
        Ast.ClassDecl cart = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertTrue(cart.methods().getFirst().isStatic(), "Newable new must lower to a type-level factory");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void newableFactoryRejectsIncorrectStaticWitnesses() {
        for (String member : new String[] {
                "private fnc new(): Cart { return new Cart(); }",
                "pub fnc new(): int { return 42; }",
                "pub fnc new(int ignored): Cart { return new Cart(); }",
                "pub async fnc new(): Cart { return new Cart(); }",
                "pub fnc new<Other>(): Cart { return new Cart(); }"
        }) {
            String source = "define pub class Cart implements Newable[Cart] as " + member + " end";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
        for (String source : new String[] {
                "define pub class Cart implements Newable as pub fnc new(): Cart { return new Cart(); } end",
                "define pub class Cart implements Newable[int] as pub fnc new(): Cart { return new Cart(); } end",
                "define pub class Cart implements Newable[Cart, int] as pub fnc new(): Cart { return new Cart(); } end",
                "define pub class Cart implements Newable[Cart] as end"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
    }

    @Test
    void actorCannotAcquireConstructorOrFactoryAuthorityByNamingMethodNew() {
        Ast.Program parsed = Parser.parse("""
                define actor Probe as
                  pub fnc new(): int {
                    return 1;
                  }
                end
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertTrue(failure.getMessage().contains("actor") && failure.getMessage().contains("new()"),
                failure.getMessage());
    }

    @Test
    void reservedNewIsStillUsableAsAnOrdinaryTraitSignatureName() {
        Ast.InterfaceDecl contract = assertInstanceOf(Ast.InterfaceDecl.class,
                onlyDeclaration("define trait FactoryContract as fnc new(): int; end"));
        assertEquals("new",
                assertInstanceOf(Ast.InterfaceFunctionDecl.class, contract.members().getFirst()).name());
    }

    @Test
    void executableScopesDoNotGainNestedDefineDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define pub fnc top(): int { define fnc nested(): int { return 1; } return 1; }"));
    }
}
