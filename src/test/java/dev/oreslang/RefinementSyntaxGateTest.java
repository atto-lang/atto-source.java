package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parse and normalization gates; does NOT assert executable refinement semantics.
 */
final class RefinementSyntaxGateTest {
    private static Ast.TypeAliasDecl alias(String source) {
        Ast.Program program = Parser.parse(source);
        return assertInstanceOf(Ast.TypeAliasDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    @Test void annotationsAndWithNormalizeToSameBounds() {
        Ast.TypeAliasDecl annotation = alias("@Length(eq=10) type Foo = string;");
        Ast.TypeAliasDecl keyword = alias("type Foo = string with length eq 10;");
        Ast.TypeAliasDecl symbolic = alias("type Foo = string with length == 10;");
        assertEquals(annotation.refinement().bounds(), keyword.refinement().bounds());
        assertEquals(keyword.refinement().bounds(), symbolic.refinement().bounds());
        assertEquals(List.of(new Ast.RefinementBound("length", "eq", 10)),
                annotation.refinement().bounds());
    }

    @Test void sizeRangeAndNotEmptyArePreserved() {
        Ast.TypeAliasDecl named = alias("@Size(min=1,max=32) type Username = string;");
        assertEquals(List.of(new Ast.RefinementBound("length", "gte", 1),
                             new Ast.RefinementBound("length", "lte", 32)),
                named.refinement().bounds());
        Ast.TypeAliasDecl nonEmpty = alias("@NotEmpty type NonEmpty = string;");
        assertEquals(List.of(new Ast.RefinementBound("length", "gt", 0)),
                nonEmpty.refinement().bounds());
    }

    @Test void scalarAnnotationsShareCanonicalRefinementRules() {
        Ast.TypeAliasDecl lengthAnnotations =
                alias("@MinLength(3) @MaxLength(32) type Username = string;");
        Ast.TypeAliasDecl lengthWith =
                alias("type Username = string with length gte 3 and length lte 32;");
        Ast.TypeAliasDecl lengthSymbols =
                alias("type Username = string with length >= 3 and length <= 32;");
        assertEquals(lengthWith.refinement().bounds(), lengthAnnotations.refinement().bounds());
        assertEquals(lengthSymbols.refinement().bounds(), lengthAnnotations.refinement().bounds());

        Ast.TypeAliasDecl numericAnnotations =
                alias("@Min(1) @Max(65535) type Port = int;");
        Ast.TypeAliasDecl numericRange =
                alias("@Range(min=1,max=65535) type Port = int;");
        Ast.TypeAliasDecl numericWith =
                alias("type Port = int with value gte 1 and value lte 65535;");
        assertEquals(numericWith.refinement().bounds(), numericAnnotations.refinement().bounds());
        assertEquals(numericRange.refinement().bounds(), numericAnnotations.refinement().bounds());

        Ast.TypeAliasDecl pct = alias("@Range(min=0,max=100) type Percentage = int;");
        assertEquals(List.of(new Ast.RefinementBound("value", "gte", 0),
                             new Ast.RefinementBound("value", "lte", 100)),
                pct.refinement().bounds());
        assertTrue(alias("@MinLength(1) define newtype Username(string);").refinement().nominal());
    }

    @Test void invalidScalarAnnotationsRejectInsteadOfWeakening() {
        String[] invalid = {
                "@MinLength(1) type Bad = int;",
                "@MaxLength(2) type Bad = int;",
                "@Min(1) type Bad = string;",
                "@Max(2) type Bad = string;",
                "@Range(min=1,max=4) type Bad = string;",
                "@MinLength() type Bad = string;",
                "@MaxLength(1, 2) type Bad = string;",
                "@Min(foo) type Bad = int;",
                "@Range(min=1,min=2) type Bad = int;",
                "@Range(foo=1) type Bad = int;",
                "@Range() type Bad = int;",
                "@Min(5) @Max(4) type Bad = int;",
                "@MinLength(10) @MaxLength(5) type Bad = string;"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test void rangeKeywordAndSymbolSpellingsNormalizeIdentically() {
        Ast.TypeAliasDecl keywords = alias(
                "type Port = int with value gte 1 and value lte 65535;");
        Ast.TypeAliasDecl symbols = alias(
                "type Port = int with value >= 1 and value <= 65535;");
        assertEquals(keywords.refinement().bounds(), symbols.refinement().bounds());
        assertEquals(List.of(new Ast.RefinementBound("value", "gte", 1),
                             new Ast.RefinementBound("value", "lte", 65535)), keywords.refinement().bounds());

        Ast.TypeAliasDecl pctKeywords = alias(
                "type Percentage = int with value gte 0 and value lte 100;");
        Ast.TypeAliasDecl pctSymbols = alias(
                "type Percentage = int with value >= 0 and value <= 100;");
        assertEquals(pctKeywords.refinement().bounds(), pctSymbols.refinement().bounds());
        assertEquals(List.of(new Ast.RefinementBound("value", "gte", 0),
                             new Ast.RefinementBound("value", "lte", 100)), pctKeywords.refinement().bounds());
    }

    @Test void newtypeRetainsNominalMarker() {
        Ast.TypeAliasDecl nominal = alias(
                "define newtype Username(string) with length gte 1 and length lte 32;");
        assertTrue(nominal.refinement().nominal());
        assertEquals(2, nominal.refinement().bounds().size());
        assertEquals("string", nominal.target().name());
        assertTrue(alias("@NotEmpty define newtype N(string);").refinement().nominal());
        assertTrue(alias("newtype N(string);").refinement().nominal());
    }

    @Test void whereIsRetainedButNotAdmitted() {
        Ast.TypeAliasDecl predicate = alias(
                "type NonBlank = string where it.trim().length > 0;");
        assertNotNull(predicate.refinement().whereExpression());
        assertTrue(predicate.refinement().whereExpression().contains("trim"));
    }

    @Test void rejectBadComparisonAndContradictions() {
        String[] invalid = {
                "type A = int with value ge 1;",
                "type A = int with value le 10;",
                "type A = int with value neq 10;",
                "type A = string with length gt 10 and length lt 5;",
                "type A = string with length eq -1;",
                "type A = int with length gt 0;",
                "type A = string with length gt foo();",
                "type A = string with length gt 0 or length eq 5;",
                "@Size(min=8,max=4) type A = string;",
                "@Unknown type A = string;"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test void compilerFailsClosedOnEveryParsedRefinement() {
        String[] source = {
                "@Size(min=1,max=32) type A = string;",
                "type B = string with length gt 0;",
                "define newtype C(string) with length gte 1;",
                "type D = string where it.length > 0;",
                "type Port = int with value gte 1 and value lte 65535;",
                "type Port = int with value >= 1 and value <= 65535;",
                "type Percentage = int with value gte 0 and value lte 100;",
                "type Percentage = int with value >= 0 and value <= 100;"
        };
        for (String s : source) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(s), s);
            assertTrue(ex.getMessage().contains("not yet executable"), ex::getMessage);
        }
    }


    @Test void unenforcedConstraintAnnotationsFailClosed() {
        String[] invalid = {
                "@NotEmpty pub fnc main(): void { return; }",
                "@MinLength(1) pub fnc main(): void { return; }",
                "@Max(10) pub fnc main(): void { return; }",
                "define class Person as @NotEmpty name: string; end",
                "pub fnc main(): void { @NotEmpty val string value = \"x\"; return; }"
        };
        for (String source : invalid) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(error.getMessage().contains("not yet executable"),
                    () -> "constraint annotation silently ignored or failed for wrong reason: " + error.getMessage());
        }
        assertNotNull(alias("@NotEmpty type N = string;").refinement());
        assertNotNull(alias("@Size(min=1) define newtype N(string);").refinement());
    }

    @Test void existingAliasesRemainUnchanged() {
        Ast.TypeAliasDecl ordinary = alias("type Normal = string;");
        assertNull(ordinary.refinement());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                "type Normal = string; pub fnc main(): void { return; }"));
    }
}
