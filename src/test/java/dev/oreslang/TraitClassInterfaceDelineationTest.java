package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TraitClassInterfaceDelineationTest {
    @Test
    void traitStorageIsRejectedAtParseTime() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define trait Stateful as
                  private let int count = 0;
                  pub read() => int { return 1; }
                end
                """));
        assertTrue(error.getMessage().contains("storage-free"));
    }

    @Test
    void callableLocalTraitsAreRejectedButLocalInterfacesRemainLegal() {
        IllegalArgumentException trait = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc bad() => void {
                  trait Local {
                    pub run() => int { return 1; }
                  }
                  return;
                }
                """));
        assertTrue(trait.getMessage().contains("declaration-level"));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc good() => void {
                  interface Reader {
                    fnc read() => string;
                  }
                  return;
                }
                """)));
    }

    @Test
    void semanticLayerRejectsForgedStatefulTraitAst() {
        Ast.TraitDecl forged = new Ast.TraitDecl(
                "Forged",
                List.of(),
                List.of(),
                List.of(),
                List.of(new Ast.FieldDecl(
                        "state",
                        Ast.Visibility.PRIVATE,
                        Ast.BindingKind.LET,
                        new Ast.TypeRef("int", List.of(), false, false),
                        new Ast.LiteralExpr(1))),
                List.of());
        Ast.Program program = new Ast.Program(List.of(
                new Ast.ModuleDecl(Parser.ROOT_MODULE, List.of(forged))));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("storage-free"));
    }
}
