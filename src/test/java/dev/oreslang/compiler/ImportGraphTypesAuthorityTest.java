package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A plural type selector cannot convert a module contract into a value trait. */
final class ImportGraphTypesAuthorityTest {
    private static final Ast.Program LIBRARY = Parser.parse("""
            define module kinds as
              define pub trait TraitApi as
                fnc run() => int;
              end
              define pub interface InterfaceApi as
                fnc run() => int;
              end
              define pub contract ContractApi as
                fnc run() => int;
              end
              define trait HiddenTrait as
                fnc run() => int;
              end
            end
            """);

    private static void validate(String kind, String name) {
        Ast.Program importer = Parser.parse(
                "import " + kind + " " + name + " from \"kinds\";\n"
                        + "pub fnc main(): void { return; }");
        ImportGraph.validateLinkedImports(
                Map.of("kinds.atto", LIBRARY, "consumer.atto", importer));
    }

    @Test
    void typesSelectorAdmitsOnlyPublicNominalValuesAndNeverModuleContracts() {
        assertDoesNotThrow(() -> validate("types", "TraitApi"));
        assertDoesNotThrow(() -> validate("types", "InterfaceApi"));
        assertDoesNotThrow(() -> validate("trait", "TraitApi"));
        assertDoesNotThrow(() -> validate("interface", "InterfaceApi"));
        assertDoesNotThrow(() -> validate("contract", "ContractApi"));
        for (String[] invalid : new String[][] {
                {"types", "ContractApi"},
                {"types", "HiddenTrait"},
                {"types", "Nonexistent"},
                {"trait", "ContractApi"},
                {"interface", "TraitApi"},
                {"contract", "TraitApi"},
                {"trait", "InterfaceApi"}
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> validate(invalid[0], invalid[1]),
                    invalid[0] + " " + invalid[1]);
            assertTrue(error.getMessage().contains(
                    "does not match an exported declaration"), error.getMessage());
        }
    }

    @Test
    void parserRetainsExactThreeWayNominalKindAndDisallowsAuthorityMismatch() {
        var declared = LIBRARY.modules().getFirst().declarations().stream()
                .filter(Ast.InterfaceDecl.class::isInstance)
                .map(Ast.InterfaceDecl.class::cast).toList();
        assertEquals(Ast.InterfaceKind.TRAIT, declared.get(0).declarationKind());
        assertEquals(Ast.InterfaceKind.INTERFACE, declared.get(1).declarationKind());
        assertEquals(Ast.InterfaceKind.CONTRACT, declared.get(2).declarationKind());
        assertFalse(declared.get(0).moduleContract());
        assertFalse(declared.get(1).moduleContract());
        assertTrue(declared.get(2).moduleContract());
    }
}
