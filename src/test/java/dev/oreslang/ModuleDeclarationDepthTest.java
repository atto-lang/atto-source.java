package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A module is the only named declaration container. Its type members are direct
 * children and cannot themselves contain a second declaration layer.
 */
final class ModuleDeclarationDepthTest {

    @Test
    void moduleMayDirectlyOwnClassesTraitsInterfacesAndTypes() {
        Ast.Program program = Parser.parse("""
                define module Models as
                  pub define class Vehicle as
                    pub id(): int { return 1; }
                  end

                  pub define trait Trackable as
                  end

                  pub define interface Identified as
                  end

                  pub define type VehicleId = int;
                end
                """);

        Ast.ModuleDecl models = program.modules().stream()
                .filter(module -> module.name().equals("Models"))
                .findFirst()
                .orElseThrow();

        assertEquals(4, models.declarations().size());
        assertInstanceOf(Ast.ClassDecl.class, models.declarations().get(0));
        Ast.InterfaceDecl trait = assertInstanceOf(
                Ast.InterfaceDecl.class, models.declarations().get(1));
        Ast.InterfaceDecl iface = assertInstanceOf(
                Ast.InterfaceDecl.class, models.declarations().get(2));
        assertEquals(Ast.InterfaceKind.TRAIT, trait.declarationKind());
        assertEquals(Ast.InterfaceKind.INTERFACE, iface.declarationKind());
        assertInstanceOf(Ast.TypeAliasDecl.class, models.declarations().get(3));
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void siblingModulesRemainLegal() {
        Ast.Program program = Parser.parse("""
                define module A as
                  pub type Id = int;
                end
                define module B as
                  pub define class Item as end
                end
                """);
        assertEquals(2, program.modules().size());
    }

    @Test
    void moduleInsideModuleIsRejectedEvenWithModifiers() {
        assertNestedModuleRejected("""
                define module Outer as
                  define module Inner as end
                end
                """);
        assertNestedModuleRejected("""
                define module Outer as
                  pub define module Inner as end
                end
                """);
        assertNestedModuleRejected("""
                define module Outer as
                  define pub module Inner as end
                end
                """);
    }

    @Test
    void typeDeclarationsCannotBeNestedInsideModuleMemberTypes() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module Outer as
                  define class Parent as
                    define class Child as end
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module Outer as
                  define class Parent as
                    define trait Child as end
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module Outer as
                  define trait Parent as
                    define interface Child as end
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module Outer as
                  define interface Parent as
                    define type Child = int;
                  end
                end
                """));
    }

    private static void assertNestedModuleRejected(String source) {
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains("nested modules"),
                failure::getMessage);
    }
}
