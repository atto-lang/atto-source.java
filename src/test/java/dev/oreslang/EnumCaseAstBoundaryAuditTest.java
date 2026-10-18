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
    void oversizedDirectAstListsFailBeforeTraversingUntrustedContents() {
        java.util.List<Ast.EnumCase> hugeEnum =
                new java.util.AbstractList<>() {
                    @Override public Ast.EnumCase get(int index) {
                        throw new AssertionError("unbounded enum list accessed before cap");
                    }
                    @Override public int size() { return 4097; }
                };
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumDecl("Foo", hugeEnum));
        java.util.List<String> hugePattern =
                new java.util.AbstractList<>() {
                    @Override public String get(int index) {
                        throw new AssertionError("unbounded pattern list accessed before cap");
                    }
                    @Override public int size() { return 4097; }
                };
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.PatternDecl("P", hugePattern));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.PatternDecl("P", List.of("Foo.A", "Foo.A")));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.PatternDecl("P", List.of()));
        assertDoesNotThrow(() ->
                new Ast.PatternDecl("P", List.of("Foo.A", "...Foo")));
    }

    @Test
    void directAstRejectsUnlexableEnumAndPatternIdentities() {
        for (String invalid : List.of("9Bad", "A.B", "Bad-case", "A B",
                "A\nB", "A\u200bB", "A\u202eB", "A\u00a0B",
                "A\uD800", "A\uDC00")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.EnumCase(invalid, null, 1), invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.EnumDecl(invalid,
                            List.of(new Ast.EnumCase("Good", null, 1))), invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.PatternDecl(invalid, List.of("Foo.A")), invalid);
        }
        for (String invalid : List.of("Unqualified", "...", "...A.B",
                "A.B.C", ".B", "A.", "A.3B", "A. B", "A.B\u200b")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.PatternDecl("P", List.of(invalid)), invalid);
        }
        assertDoesNotThrow(() -> new Ast.PatternDecl("P",
                List.of("Color.Red", "...Mode", "\u03A9.\u03A6")));
        assertDoesNotThrow(() -> new Ast.EnumDecl("\u03A9",
                List.of(new Ast.EnumCase("\u03A6", null, 1))));
        String longUnicode = "\u03A9".repeat(300);
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumCase(longUnicode, null, 1));
    }

    @Test
    void unrelatedLegacyProgramsStillPassOptimizerAndOwnership() {
        Ast.Program ordinary = Parser.parse("fnc main(): int { return 42; }");
        assertDoesNotThrow(() -> OwnershipChecker.check(ordinary));
        assertDoesNotThrow(() -> TreeShaker.shake(ordinary, BuildOptions.executable()));
    }
}
