package dev.oreslang.compiler;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Linker regression: an imported trait is a nominal trait declaration,
 * not a similarly named interface, module contract, or structural shape.
 */
final class ImportGraphTraitKindTest {
    private static Ast.Program library() {
        return Parser.parse("""
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
    }

    private static void validate(String kind, String name) {
        Ast.Program importer = Parser.parse(
                "import " + kind + " " + name + " from \"kinds\";\n"
                + "pub fnc main(): void { return; }");
        ImportGraph.validateLinkedImports(
                Map.of("kinds.atto", library(), "consumer.atto", importer));
    }

    @Test
    void parsedTraitIdentitySurvivesPastParserAndLinksAsTrait() {
        Ast.Program library = library();
        Ast.InterfaceDecl trait = library.modules().get(0).declarations().stream()
                .filter(Ast.InterfaceDecl.class::isInstance)
                .map(Ast.InterfaceDecl.class::cast)
                .filter(x -> x.name().equals("TraitApi"))
                .findFirst().orElseThrow();
        assertTrue(trait.trait());
        assertFalse(trait.moduleContract());
        assertDoesNotThrow(() -> validate("trait", "TraitApi"));
        assertDoesNotThrow(() -> validate("interface", "InterfaceApi"));
        assertDoesNotThrow(() -> validate("contract", "ContractApi"));
    }

    @Test
    void wrongKindPrivateAndUnknownTraitImportsFailClosed() {
        for (String[] candidate : new String[][] {
                {"trait", "InterfaceApi"},
                {"trait", "ContractApi"},
                {"interface", "TraitApi"},
                {"contract", "TraitApi"},
                {"trait", "HiddenTrait"},
                {"trait", "NotDeclared"}
        }) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> validate(candidate[0], candidate[1]),
                    candidate[0] + " " + candidate[1]);
            assertTrue(failure.getMessage().contains("does not match an exported declaration"),
                    failure.getMessage());
        }
    }
}
