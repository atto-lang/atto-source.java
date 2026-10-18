package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No public compiler/ownership/optimizer entrypoint may launder a syntax-only
 * enum/pattern AST into a seemingly verified executable program.
 */
final class EnumCaseAstBoundaryAuditTest {
    @Test
    void directOwnershipEntrypointsRefuseUnlinkedEnumAndNamedPattern() {
        for (String source : List.of(
                "define enum Foo as A, B end",
                "define match pattern P as Foo.A end")) {
            Ast.Program program = Parser.parse(source);
            IllegalArgumentException first = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.check(program));
            assertTrue(first.getMessage().contains("syntax-only"), first.getMessage());
            IllegalArgumentException linked = assertThrows(IllegalArgumentException.class,
                    () -> OwnershipChecker.checkWithImportedClasses(program, java.util.Map.of()));
            assertTrue(linked.getMessage().contains("syntax-only"), linked.getMessage());
        }
    }

    @Test
    void directTreeShakerRefusesEnumAstBeforePruningDeadDeclarations() {
        Ast.Program program = Parser.parse("""
                define enum Foo as A, B end
                pub fnc main(): int { return 42; }
                """);
        for (BuildOptions options : List.of(
                BuildOptions.executable(), BuildOptions.library(java.util.Map.of()))) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> TreeShaker.shake(program, options));
            assertTrue(rejected.getMessage().contains("compiler-bound"), rejected.getMessage());
        }
    }

    @Test
    void forgedAstCannotBypassEnumNameTagAndCountChecks() {
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumCase("A", null, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumCase("", null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumDecl("", List.of(new Ast.EnumCase("A", null, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumDecl("Empty", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumDecl("Duplicate", List.of(
                        new Ast.EnumCase("A", null, 1),
                        new Ast.EnumCase("A", null, 2))));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumDecl("OutOfOrder", List.of(
                        new Ast.EnumCase("A", null, 2),
                        new Ast.EnumCase("B", null, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumDecl("RepeatedTag", List.of(
                        new Ast.EnumCase("A", null, 1),
                        new Ast.EnumCase("B", null, 1))));
        assertDoesNotThrow(() -> new Ast.EnumDecl("Good", List.of(
                new Ast.EnumCase("A", null, 1),
                new Ast.EnumCase("B", Ast.TypeRef.simple("int"), 3))));
    }

    @Test
    void unrelatedLegacyProgramsStillPassOptimizerAndOwnership() {
        Ast.Program ordinary = Parser.parse("fnc main(): int { return 42; }");
        assertDoesNotThrow(() -> OwnershipChecker.check(ordinary));
        assertDoesNotThrow(() -> TreeShaker.shake(ordinary, BuildOptions.executable()));
    }
}
